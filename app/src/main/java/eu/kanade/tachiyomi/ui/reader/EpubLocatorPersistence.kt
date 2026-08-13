package eu.kanade.tachiyomi.ui.reader

import android.content.SharedPreferences
import eu.kanade.tachiyomi.source.model.BookLocator
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.TextBookPublication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.URI
import kotlin.math.roundToInt

internal interface EpubLocatorStorage {
    fun read(key: String): String?

    fun write(key: String, value: String)
}

internal class SharedPreferencesEpubLocatorStorage(
    private val preferences: SharedPreferences,
) : EpubLocatorStorage {
    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

internal data class EpubRestoredLocation(
    val sectionIndex: Int,
    val fragment: String? = null,
)

/**
 * Persists text-book locations locally. The locator remains the source-api BookLocator; this
 * record adds only publication identity and reader-only section hints.
 */
internal class EpubLocatorPersistence(
    private val storage: EpubLocatorStorage,
) {

    fun persist(
        chapterId: Long,
        publication: TextBookPublication,
        locator: BookLocator,
        sectionIndex: Int,
        sectionCount: Int,
        incognito: Boolean,
    ) {
        if (incognito || sectionCount <= 0 || sectionIndex !in 0 until sectionCount) return

        storage.write(
            key(chapterId),
            json.encodeToString(
                StoredEpubLocator(
                    fingerprint = publication.fingerprint.value,
                    locator = locator,
                    sectionIndex = sectionIndex,
                    sectionCount = sectionCount,
                ),
            ),
        )
    }

    fun restore(
        chapterId: Long,
        publication: TextBookPublication,
        sections: List<BookSectionContent>,
        fallbackSectionIndex: Int,
    ): EpubRestoredLocation? {
        if (sections.isEmpty()) return null
        val fallback = EpubRestoredLocation(fallbackSectionIndex.takeIf { it in sections.indices } ?: 0)
        val record = storage.read(key(chapterId))
            ?.let { encoded -> runCatching { json.decodeFromString<StoredEpubLocator>(encoded) }.getOrNull() }
            ?: return fallback
        if (record.fingerprint != publication.fingerprint.value) return EpubRestoredLocation(0)

        val hrefMatch = findHref(sections, record.locator.href)
        findBlock(record.locator, hrefMatch?.first, sections)?.let { return it }
        hrefMatch?.let { (index, fragment) ->
            fragment?.takeIf(sections[index].html::containsAnchor)?.let {
                return EpubRestoredLocation(index, it)
            }
            findAnchor(record.locator, sections[index].html)?.let {
                return EpubRestoredLocation(index, it)
            }
        }
        findAnchor(record.locator)?.let { fragment ->
            sections.indexOfFirst { section -> section.html.containsAnchor(fragment) }
                .takeIf { it >= 0 }
                ?.let { return EpubRestoredLocation(it, fragment) }
        }
        findQuote(sections, record.locator)?.let { return EpubRestoredLocation(it) }
        hrefMatch?.first?.let { index ->
            val fragment = record.locator.progression
                ?.let { epubBlockIdAtProgression(epubTextBlocks(sections[index].html), it) }
                ?.let { "#$it" }
            return EpubRestoredLocation(index, fragment)
        }
        record.locator.totalProgression?.let {
            val index = (it.coerceIn(0.0, 1.0) * sections.lastIndex).roundToInt()
            return EpubRestoredLocation(index)
        }
        return EpubRestoredLocation(record.sectionIndex?.takeIf { it in sections.indices } ?: 0)
    }

    private fun key(chapterId: Long) = "chapter-$chapterId"

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class StoredEpubLocator(
    val fingerprint: String,
    val locator: BookLocator,
    val sectionIndex: Int? = null,
    val sectionCount: Int? = null,
)

private fun findHref(sections: List<BookSectionContent>, href: String): Pair<Int, String?>? {
    val locatorUri = href.toHierarchicalUriOrNull() ?: return null
    val locatorPath = locatorUri.withoutFragment().normalize().toString()
    val index = sections.indexOfFirst { section ->
        section.href.toHierarchicalUriOrNull()?.withoutFragment()?.normalize()?.toString() == locatorPath
    }
    return index.takeIf { it >= 0 }?.let { it to locatorUri.safeFragment() }
}

private fun findBlock(
    locator: BookLocator,
    hrefIndex: Int?,
    sections: List<BookSectionContent>,
): EpubRestoredLocation? {
    val blockId = locator.blockId?.takeIf(String::isSafeEpubAnchorId) ?: return null
    val sectionIndex = hrefIndex ?: return null
    return "#$blockId"
        .takeIf(sections[sectionIndex].html::containsAnchor)
        ?.let { EpubRestoredLocation(sectionIndex, it) }
}

private fun findAnchor(locator: BookLocator, html: String? = null): String? {
    val fragment = listOfNotNull(locator.cfi, locator.cssSelector)
        .firstNotNullOfOrNull(String::toSafeAnchorFragment)
        ?: return null
    return fragment.takeIf { html == null || html.containsAnchor(it) }
}

private fun findQuote(sections: List<BookSectionContent>, locator: BookLocator): Int? {
    val quote = locator.textQuote ?: return null
    val prefix = quote.prefix
    val suffix = quote.suffix
    val exactMatches = sections.mapIndexedNotNull { index, section ->
        val text = section.html.toVisibleText()
        val match = text.indexOf(quote.exact)
        match.takeIf { it >= 0 }?.let { index to (text to it) }
    }
    val contextual = exactMatches.firstOrNull { (_, match) ->
        val (text, offset) = match
        (prefix == null || text.substring(0, offset).endsWith(prefix)) &&
            (suffix == null || text.substring(offset + quote.exact.length).startsWith(suffix))
    }
    return contextual?.first ?: exactMatches.firstOrNull()?.first
}

private fun String.toHierarchicalUriOrNull(): URI? = runCatching { URI(this) }
    .getOrNull()
    ?.takeUnless(URI::isOpaque)

private fun URI.withoutFragment(): URI = URI(scheme, authority, path, query, null)

private fun URI.safeFragment(): String? = rawFragment
    ?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.:-]*")) }
    ?.let { "#$it" }

private fun String.toSafeAnchorFragment(): String? = removePrefix("#")
    .takeIf { this == "#$it" && it.matches(Regex("[A-Za-z][A-Za-z0-9_.:-]*")) }
    ?.let { "#$it" }

private fun String.containsAnchor(fragment: String): Boolean {
    val id = fragment.removePrefix("#")
    return Regex("""\bid\s*=\s*(["'])${Regex.escape(id)}\1""").containsMatchIn(this)
}

internal fun createEpubLocator(
    section: BookSectionContent,
    sectionIndex: Int,
    sectionCount: Int,
    blockId: String? = null,
    progression: Double = 0.0,
    restoredFragment: String? = null,
): BookLocator {
    val document = Jsoup.parseBodyFragment(section.html)
    val text = document.text().normalizeWhitespace()
    val safeBlockId = blockId
        ?.takeIf(String::isSafeEpubAnchorId)
        ?.takeIf { document.getElementById(it) != null }
    val blockText = safeBlockId
        ?.let(document::getElementById)
        ?.text()
        ?.normalizeWhitespace()
        .orEmpty()
    val exact = blockText.take(160).ifBlank { text.take(160) }.ifBlank { section.href.take(160) }
    val exactOffset = blockText.takeIf(String::isNotBlank)
        ?.let(text::indexOf)
        ?.takeIf { it >= 0 }
        ?: 0
    val prefix = text.take(exactOffset).takeLast(64).ifBlank { null }
    val suffix = text.drop(exactOffset + exact.length).take(64).ifBlank { null }
    val fragment = safeBlockId?.let { "#$it" }
        ?: restoredFragment?.takeIf(String::isSafeAnchorFragment)
        ?: document.select("[id]")
            .firstOrNull { it.text().isNotBlank() }
            ?.id()
            ?.takeIf { it.matches(ANCHOR_ID) }
            ?.let { "#$it" }
    val totalProgression = if (sectionCount <= 1) 0.0 else sectionIndex.toDouble() / (sectionCount - 1)
    return BookLocator.create(
        href = section.href.withFragment(fragment),
        blockId = safeBlockId,
        cssSelector = fragment ?: "body",
        textQuote = eu.kanade.tachiyomi.source.model.BookTextQuote(
            exact = exact,
            prefix = prefix,
            suffix = suffix,
        ),
        progression = progression,
        totalProgression = totalProgression,
    )
}

private val ANCHOR_ID = Regex("[A-Za-z][A-Za-z0-9_.:-]*")

private fun String.isSafeAnchorFragment(): Boolean = removePrefix("#").let { id ->
    this == "#$id" && id.matches(ANCHOR_ID)
}

private fun String.withFragment(fragment: String?): String = runCatching {
    val uri = URI(this)
    URI(uri.scheme, uri.authority, uri.path, uri.query, fragment?.removePrefix("#")).toString()
}.getOrDefault(substringBefore('#') + fragment.orEmpty())

private fun String.toVisibleText(): String = Jsoup.parseBodyFragment(this).text().normalizeWhitespace()

private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()
