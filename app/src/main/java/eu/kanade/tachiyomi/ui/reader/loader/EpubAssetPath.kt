package eu.kanade.tachiyomi.ui.reader.loader

import java.net.URI

/**
 * The only URL namespace that the text-book WebView can request.
 *
 * Publication and section identifiers are represented by deterministic hexadecimal path tokens;
 * source-controlled strings never become path syntax. Local requests are parsed strictly so a
 * malformed, traversing, or unknown path can only become a local 404.
 */
internal object EpubAssetPath {

    const val HOST = "appassets.androidplatform.net"
    const val PREFIX = "mihon-book"
    const val RESOURCE = "resource"
    const val SECTION = "section"

    sealed interface Request

    data class SectionDocumentRequest(
        val publicationPath: String,
        val sectionPath: String,
    ) : Request

    data class ResourceRequest(
        val publicationPath: String,
        val sectionPath: String,
        val resourceToken: String,
    ) : Request

    fun parse(path: String): ResourceRequest? {
        val match = RESOURCE_PATH.matchEntire(relativePath(path) ?: return null) ?: return null
        return ResourceRequest(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

    fun parseSectionDocument(path: String): SectionDocumentRequest? {
        val match = SECTION_DOCUMENT_PATH.matchEntire(relativePath(path) ?: return null) ?: return null
        return SectionDocumentRequest(match.groupValues[1], match.groupValues[2])
    }

    fun parseUrl(url: String): Request? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(HOST, ignoreCase = true) ||
            uri.rawUserInfo != null ||
            uri.port != -1 ||
            uri.rawQuery != null
        ) {
            return@runCatching null
        }
        val prefix = "/$PREFIX/"
        if (!uri.rawPath.startsWith(prefix)) return@runCatching null
        val path = uri.rawPath.removePrefix(prefix)
        val request = parseSectionDocument(path) ?: parse(path) ?: return@runCatching null
        val fragment = uri.rawFragment
        if (fragment != null && (request !is SectionDocumentRequest || !fragment.matches(SAFE_FRAGMENT))) {
            return@runCatching null
        }
        request
    }.getOrNull()

    fun isLocalUrl(url: String): Boolean = parseUrl(url) != null

    fun isSectionDocumentUrl(url: String): Boolean = parseUrl(url) is SectionDocumentRequest

    fun sectionDocumentUrl(fingerprint: String, sectionId: String, fragment: String? = null): String {
        val base =
            "https://$HOST/$PREFIX/${EpubHtmlSanitizer.publicationPath(fingerprint)}/$SECTION/" +
                "${EpubHtmlSanitizer.sectionPath(sectionId)}/"
        return base + fragment.orEmpty().takeIf { it.matches(SAFE_FRAGMENT_WITH_HASH) }.orEmpty()
    }

    private fun relativePath(path: String): String? {
        if (path.startsWith("//") || path.any { it == '?' || it == '#' || it == '\\' }) return null
        return path.removePrefix("/")
    }

    private val RESOURCE_PATH = Regex("([0-9a-f]{32})/$RESOURCE/([0-9a-f]{32})/([0-9a-f]{64})")
    private val SECTION_DOCUMENT_PATH = Regex("([0-9a-f]{32})/$SECTION/([0-9a-f]{32})/")
    private val SAFE_FRAGMENT = Regex("[A-Za-z][A-Za-z0-9_.:-]*")
    private val SAFE_FRAGMENT_WITH_HASH = Regex("#[A-Za-z][A-Za-z0-9_.:-]*")
}
