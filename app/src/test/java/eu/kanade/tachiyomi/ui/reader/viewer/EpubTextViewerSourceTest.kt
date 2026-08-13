package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class EpubTextViewerSourceTest {

    @Test
    fun `viewer navigates the strict local section document instead of a data main frame`() {
        // Given: the production EPUB viewer source.
        val source = projectRoot().resolve(VIEWER_SOURCE).toFile().readText()

        // When/Then: navigation is a strict local URL load and no data-main-frame API remains.
        assertTrue(source.contains("EpubAssetPath.sectionDocumentUrl("))
        assertTrue(source.contains("webView.loadUrl("))
        assertFalse(source.contains("loadDataWithBaseURL"))
    }

    @Test
    fun `viewer observes scroll and debounces block locator persistence`() {
        val source = projectRoot().resolve(VIEWER_SOURCE).toFile().readText()

        assertTrue(source.contains("setOnScrollChangeListener"))
        assertTrue(source.contains("computeVerticalScrollRange()"))
        assertTrue(source.contains("postDelayed"))
        assertTrue(source.contains("SCROLL_PERSIST_DEBOUNCE_MILLIS"))
        assertTrue(source.contains("epubBlockIdAtProgression"))
        assertTrue(source.contains("blockId = blockId"))
        assertTrue(source.contains("progression = progression"))
    }

    private fun projectRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(candidate.resolve(VIEWER_SOURCE))) {
            candidate = candidate.parent ?: error("Unable to locate EpubTextViewer.kt")
        }
        return candidate
    }

    private companion object {
        const val VIEWER_SOURCE = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/EpubTextViewer.kt"
    }
}
