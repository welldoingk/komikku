package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.loader.EpubHtmlSanitizer
import org.jsoup.Jsoup

internal data class EpubTextBlock(
    val id: String,
    val textLength: Int,
)

internal fun epubTextBlocks(html: String): List<EpubTextBlock> =
    Jsoup.parseBodyFragment(html)
        .body()
        .select(EpubHtmlSanitizer.BLOCK_SELECTOR)
        .mapNotNull { element ->
            element.id().takeIf(String::isSafeEpubAnchorId)?.let { id ->
                EpubTextBlock(
                    id = id,
                    textLength = element.text().normalizeEpubWhitespace().length.coerceAtLeast(1),
                )
            }
        }

internal fun epubBlockIdAtProgression(
    blocks: List<EpubTextBlock>,
    progression: Double,
): String? {
    val eligible = blocks.filter { it.textLength > 0 && it.id.isSafeEpubAnchorId() }
    if (eligible.isEmpty()) return null

    val totalLength = eligible.sumOf { it.textLength.toLong() }
    val clampedProgression = progression.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    if (clampedProgression >= 1.0) return eligible.last().id

    val target = clampedProgression * totalLength
    var cumulativeLength = 0L
    eligible.forEach { block ->
        cumulativeLength += block.textLength
        if (target < cumulativeLength) return block.id
    }
    return eligible.last().id
}

internal fun epubScrollProgression(
    scrollY: Int,
    verticalScrollRange: Int,
    viewportHeight: Int,
): Double {
    val scrollableRange = verticalScrollRange - viewportHeight
    if (scrollableRange <= 0) return 0.0
    return (scrollY.toDouble() / scrollableRange).coerceIn(0.0, 1.0)
}

internal fun String.isSafeEpubAnchorId(): Boolean = matches(EPUB_ANCHOR_ID)

internal fun String.normalizeEpubWhitespace(): String = replace(Regex("\\s+"), " ").trim()

private val EPUB_ANCHOR_ID = Regex("[A-Za-z][A-Za-z0-9_.:-]*")
