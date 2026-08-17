package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubBlockMappingTest {

    @Test
    fun `cumulative text proportions map boundaries to document ordered blocks`() {
        val blocks = listOf(
            EpubTextBlock("mk-b-0", 10),
            EpubTextBlock("mk-b-1", 30),
            EpubTextBlock("mk-b-2", 10),
        )

        assertEquals("mk-b-0", epubBlockIdAtProgression(blocks, 0.0))
        assertEquals("mk-b-0", epubBlockIdAtProgression(blocks, 0.199))
        assertEquals("mk-b-1", epubBlockIdAtProgression(blocks, 0.2))
        assertEquals("mk-b-1", epubBlockIdAtProgression(blocks, 0.799))
        assertEquals("mk-b-2", epubBlockIdAtProgression(blocks, 0.8))
        assertEquals("mk-b-2", epubBlockIdAtProgression(blocks, 1.0))
    }

    @Test
    fun `scroll progression is finite and clamped without a WebView`() {
        assertEquals(
            0.25,
            epubScrollProgression(scrollY = 250, verticalScrollRange = 1_250, viewportHeight = 250),
        )
        assertEquals(
            0.0,
            epubScrollProgression(scrollY = -10, verticalScrollRange = 1_250, viewportHeight = 250),
        )
        assertEquals(
            1.0,
            epubScrollProgression(scrollY = 2_000, verticalScrollRange = 1_250, viewportHeight = 250),
        )
    }

    @Test
    fun `scroll progression reaches one at the bottom of the scrollable distance`() {
        assertEquals(
            1.0,
            epubScrollProgression(scrollY = 800, verticalScrollRange = 1_000, viewportHeight = 200),
        )
    }

    @Test
    fun `scroll progression is zero when the document does not scroll`() {
        assertEquals(
            0.0,
            epubScrollProgression(scrollY = 10, verticalScrollRange = 1_000, viewportHeight = 1_000),
        )
    }

    @Test
    fun `scroll progression is zero when the viewport is taller than the content`() {
        assertEquals(
            0.0,
            epubScrollProgression(scrollY = 10, verticalScrollRange = 800, viewportHeight = 1_000),
        )
    }

    @Test
    fun `sanitized html yields block text weights in document order`() {
        val blocks = epubTextBlocks(
            "<div id=\"mk-b-0\"><span>Wrapper</span><p id=\"mk-b-1\">Paragraph text</p></div>" +
                "<figure id=\"mk-b-2\"><img alt=\"Cover\"></figure>",
        )

        assertEquals(listOf("mk-b-0", "mk-b-1", "mk-b-2"), blocks.map(EpubTextBlock::id))
        assertEquals(listOf(22, 14, 1), blocks.map(EpubTextBlock::textLength))
    }
}
