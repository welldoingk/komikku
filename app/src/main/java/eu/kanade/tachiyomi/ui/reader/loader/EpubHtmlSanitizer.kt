package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.BookResource
import eu.kanade.tachiyomi.source.model.BookSection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.net.URI
import java.security.MessageDigest

object EpubHtmlSanitizer {

    internal val activeAtRulePattern = Regex(
        "@(import|font-face)\\b[^;{]*(?:;|\\{[^{}]*\\})",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val safeCssProperties = setOf(
        "color", "background-color", "font-family", "font-size", "font-style", "font-weight",
        "line-height", "letter-spacing", "text-align", "text-decoration", "text-indent",
        "text-transform", "white-space", "word-break", "overflow-wrap", "hyphens",
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "border", "border-width", "border-style", "border-color", "border-radius",
        "display", "max-width", "width", "height", "vertical-align", "list-style-type",
    )

    private val safeList = Safelist.relaxed()
        .addTags(
            "article", "section", "main", "header", "footer", "figure", "figcaption",
            "details", "summary", "mark", "time", "audio", "source", "style", "link",
        )
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addAttributes("audio", "src", "preload")
        .addAttributes("source", "src", "type")
        .addAttributes("a", "href", "title", "rel")
        .addAttributes("link", "rel", "href", "type", "media")
        .addProtocols("img", "src", "https")
        .addProtocols("audio", "src", "https")
        .addProtocols("source", "src", "https")
        .addProtocols("a", "href", "#")
        .addProtocols("link", "href", "https")
        // Anchors are the only durable in-section position primitive available while JavaScript
        // stays disabled, so ids survive cleaning. Values are narrowed to [SAFE_ID] before the
        // cleaner runs, which keeps this stricter than restoring arbitrary ids afterwards.
        .addAttributes(":all", "id")

    fun sanitize(html: String, fingerprint: String, section: BookSection): String {
        val document = Jsoup.parse(html, section.href)
        document.outputSettings().syntax(Document.OutputSettings.Syntax.html)

        document.select("script,iframe,object,embed,svg,math,canvas,template,noscript,video,track").remove()
        document.select("form").unwrap()
        document.select("input,button,select,option,textarea,label,fieldset,legend").remove()
        document.select("base,meta").remove()
        document.select("[style]").removeAttr("style")
        document.select("[id]").forEach { element ->
            if (!element.attr("id").isSafeAnchorId()) element.removeAttr("id")
        }
        document.allElements.forEach { element ->
            element.attributes().asList()
                .filter {
                    it.key.startsWith("on", ignoreCase = true) ||
                        it.key.equals("srcset", true) ||
                        it.key.equals("formaction", true)
                }
                .forEach { element.removeAttr(it.key) }
        }

        document.select("a").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (!href.startsWith("#") || href.length == 1 || href.any { it.code < 0x20 }) {
                anchor.removeAttr("href")
            } else {
                anchor.attr("href", href)
                anchor.attr("rel", "noreferrer noopener")
            }
        }

        document.select("link").forEach { link ->
            val relation = link.attr("rel").split(Regex("\\s+"))
                .map(String::lowercase)
            val absolute = if ("stylesheet" in relation) {
                resolveAllowedResource(
                    section = section,
                    requested = link.attr("href"),
                    mediaType = "text/css",
                )
            } else {
                null
            }
            if (absolute == null) {
                link.remove()
            } else {
                link.attr("rel", "stylesheet")
                link.attr("href", resourceUrl(fingerprint, section.id, absolute))
                link.removeAttr("integrity")
                link.removeAttr("crossorigin")
            }
        }

        document.select("img[src],audio[src],source[src]").forEach { media ->
            val expectedType = when (media.tagName()) {
                "img" -> "image/"
                else -> "audio/"
            }
            val absolute = resolveAllowedResource(
                section = section,
                requested = media.attr("src"),
                mediaType = expectedType,
            )
            if (absolute == null) {
                media.removeAttr("src")
            } else {
                media.attr("src", resourceUrl(fingerprint, section.id, absolute))
            }
            media.removeAttr("controls")
            media.removeAttr("autoplay")
        }

        document.select("style").forEach { style ->
            val sanitized = sanitizeCss(
                css = style.data().ifBlank { style.html() },
                fingerprint = fingerprint,
                section = section,
                sourceHref = section.href,
            )
            if (sanitized.isBlank()) style.remove() else style.text(sanitized)
        }

        // Stylesheet elements normally live in <head>, while the reader deliberately returns only
        // the cleaned body fragment. Move the already-rewritten local assets into the body before
        // cleaning so an allowlisted stylesheet is not silently discarded.
        document.head()?.children()?.toList().orEmpty()
            .filter { it.tagName() == "style" || it.tagName() == "link" }
            .forEach { document.body().appendChild(it) }

        val clean = Cleaner(safeList).clean(document)
        clean.head().select("link,style").toList().forEach(clean.body()::appendChild)
        injectBlockIds(clean.body())
        clean.body().select("link[href],img[src],audio[src],source[src]").forEach { element ->
            val attribute = if (element.tagName() == "link") "href" else "src"
            if (!isLocalAssetUrl(element.attr(attribute))) {
                element.removeAttr(attribute)
            }
        }
        clean.body().select("a[href]").forEach { anchor ->
            if (!anchor.attr("href").startsWith("#")) anchor.removeAttr("href")
        }
        return clean.body().html()
    }

    /**
     * Mirrors the anchor grammar used by locator persistence. Kept as character checks instead of a
     * [Regex] so this file's regex surface stays fixed for the Android ICU guard test.
     */
    private fun String.isSafeAnchorId(): Boolean =
        isNotEmpty() &&
            (first() in 'A'..'Z' || first() in 'a'..'z') &&
            all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.:-" }

    private fun injectBlockIds(body: Element) {
        val originalIds = body.select("[id]").map(Element::id)
        var prefixIndex = 0
        var prefix = BLOCK_ID_PREFIX
        while (originalIds.any { it.startsWith(prefix) }) {
            prefixIndex++
            prefix = "mk-b$prefixIndex-"
        }
        body.select(BLOCK_SELECTOR).forEachIndexed { index, element ->
            if (element.id().isBlank()) {
                element.attr("id", "$prefix$index")
            }
        }
    }

    fun sanitizeCss(css: String): String {
        return sanitizeCssInternal(css, null, null, null)
    }

    /**
     * Sanitizes a fetched or embedded stylesheet and rewrites only allowlisted image URLs to the
     * same local asset namespace used by sanitized HTML. The one-argument overload intentionally
     * rejects every URL because it has no source-resource allowlist to consult.
     */
    internal fun sanitizeCss(
        css: String,
        fingerprint: String,
        section: BookSection,
        sourceHref: String,
    ): String = sanitizeCssInternal(css, fingerprint, section, sourceHref)

    private fun sanitizeCssInternal(
        css: String,
        fingerprint: String?,
        section: BookSection?,
        sourceHref: String?,
    ): String {
        val withoutComments = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val withoutActiveAtRules = withoutComments.replace(
            activeAtRulePattern,
            "",
        )
        return Regex("([^{}]+)\\{([^{}]*)\\}").findAll(withoutActiveAtRules).mapNotNull { rule ->
            val selector = rule.groupValues[1].trim()
            if (
                selector.isBlank() ||
                selector.startsWith("@") ||
                selector.contains("<") ||
                selector.contains(">") ||
                selector.contains("url(", true)
            ) {
                return@mapNotNull null
            }
            val declarations = rule.groupValues[2].split(';').mapNotNull { declaration ->
                val parts = declaration.split(':', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val property = parts[0].trim().lowercase()
                val value = parts[1].trim()
                if (property !in safeCssProperties) return@mapNotNull null
                val rewrittenValue = rewriteCssValue(
                    value = value,
                    fingerprint = fingerprint,
                    section = section,
                    sourceHref = sourceHref,
                ) ?: return@mapNotNull null
                if (!safeCssValue(rewrittenValue, allowLocalAssetUrls = fingerprint != null)) {
                    return@mapNotNull null
                }
                "$property:$rewrittenValue"
            }
            if (declarations.isEmpty()) null else "$selector{${declarations.joinToString(";")}}"
        }.joinToString("\n")
    }

    fun resourceToken(href: String): String = MessageDigest.getInstance("SHA-256")
        .digest(href.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    internal fun publicationPath(fingerprint: String): String = pathToken(fingerprint)

    internal fun sectionPath(sectionId: String): String = pathToken(sectionId)

    internal fun resourceUrl(fingerprint: String, sectionId: String, href: String): String =
        "https://${EpubAssetPath.HOST}/${EpubAssetPath.PREFIX}/${publicationPath(fingerprint)}/" +
            "${EpubAssetPath.RESOURCE}/${sectionPath(sectionId)}/${resourceToken(href)}"

    internal fun sectionBaseUrl(fingerprint: String, sectionId: String): String =
        "https://${EpubAssetPath.HOST}/${EpubAssetPath.PREFIX}/${publicationPath(fingerprint)}/section/" +
            "${sectionPath(sectionId)}/"

    private fun pathToken(value: String): String = resourceToken(value).take(32)

    private fun resolveAllowedResource(section: BookSection, requested: String): String? {
        return resolveAllowedResource(section, requested, null)
    }

    private fun resolveAllowedResource(
        section: BookSection,
        requested: String,
        mediaType: String?,
        baseHref: String = section.href,
    ): String? {
        if (requested.isBlank() || requested.contains('\\') || requested.any { it.code < 0x20 }) return null
        val resolved = runCatching { URI(baseHref).resolve(requested).normalize() }.getOrNull() ?: return null
        if (resolved.scheme?.lowercase() !in setOf("http", "https")) return null
        return section.resources.firstOrNull { resource ->
            val sameHref = runCatching { URI(resource.href).normalize() == resolved }.getOrDefault(false)
            val hasExpectedType = mediaType == null || resource.mediaType.startsWith(mediaType, ignoreCase = true)
            sameHref && hasExpectedType
        }?.href
    }

    private fun rewriteCssValue(
        value: String,
        fingerprint: String?,
        section: BookSection?,
        sourceHref: String?,
    ): String? {
        if (!value.contains("url(", true)) return value
        if (fingerprint == null || section == null || sourceHref == null) return null

        val urlPattern = Regex("url\\(\\s*(['\\\"]?)(.*?)\\1\\s*\\)", RegexOption.IGNORE_CASE)
        var rewritten = value
        urlPattern.findAll(value).toList().asReversed().forEach { match ->
            val requested = match.groupValues[2].trim()
            val resource = resolveAllowedResource(
                section = section,
                requested = requested,
                mediaType = "image/",
                baseHref = sourceHref,
            ) ?: return null
            val replacement = "url(\"${resourceUrl(fingerprint, section.id, resource)}\")"
            rewritten = rewritten.replaceRange(match.range, replacement)
        }
        return if (rewritten.contains("url(", true)) rewritten else rewritten
    }

    private fun safeCssValue(value: String, allowLocalAssetUrls: Boolean): Boolean {
        val lower = value.lowercase()
        val localAssetOrigin = LOCAL_ASSET_URL.lowercase()
        val withoutLocalAssetOrigin = lower.replace(localAssetOrigin, "")
        return value.length <= 256 &&
            (!lower.contains("url(") || (allowLocalAssetUrls && localAssetOrigin in lower)) &&
            !lower.contains("expression") && !lower.contains("javascript:") &&
            !lower.contains("data:") && !lower.contains("file:") && !lower.contains("content:") &&
            !lower.contains("@") && !withoutLocalAssetOrigin.contains("://") &&
            !lower.contains("behavior:") && !lower.contains("-moz-binding") &&
            value.none { it == '<' || it == '>' || it == '\\' || (it.code < 0x20 && it !in "\t\n\r") }
    }

    private fun isLocalAssetUrl(value: String): Boolean =
        value.startsWith(LOCAL_ASSET_URL, ignoreCase = true) &&
            EpubAssetPath.parse(value.substringAfter("/${EpubAssetPath.PREFIX}/", "")).let { it != null }

    private const val LOCAL_ASSET_URL = "https://appassets.androidplatform.net/"
    private const val BLOCK_ID_PREFIX = "mk-b-"
    internal const val BLOCK_SELECTOR =
        "address,article,aside,blockquote,dd,div,dl,dt,figcaption,figure,footer," +
            "h1,h2,h3,h4,h5,h6,header,hr,li,main,nav,ol,p,pre,section," +
            "table,tbody,td,tfoot,th,thead,tr,ul"
}
