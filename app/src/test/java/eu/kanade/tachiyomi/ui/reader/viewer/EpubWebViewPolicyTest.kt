package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.source.model.BookFingerprint
import eu.kanade.tachiyomi.source.model.BookSection
import eu.kanade.tachiyomi.source.model.TextBookPublication
import eu.kanade.tachiyomi.ui.reader.loader.EpubAssetPath
import eu.kanade.tachiyomi.ui.reader.loader.EpubHtmlSanitizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubWebViewPolicyTest {

    @Test
    fun `section document url uses deterministic tokens and accepts only a safe fragment`() {
        // Given: stable publication and section identities.
        val expectedBase =
            "https://appassets.androidplatform.net/mihon-book/7cc082593adf00fe141410aaeb24974d/" +
                "section/fd88a97dd36bd775f019f140f34d3029/"

        // When: local section document URLs are generated with safe and hostile fragments.
        val baseUrl = EpubAssetPath.sectionDocumentUrl("fixture-fingerprint", "section-0")
        val safeUrl = EpubAssetPath.sectionDocumentUrl("fixture-fingerprint", "section-0", "#opening")
        val hostileUrl = EpubAssetPath.sectionDocumentUrl("fixture-fingerprint", "section-0", "#x?steal=1")

        // Then: tokens are deterministic, safe anchors survive, and hostile text never enters the URL.
        assertEquals(expectedBase, baseUrl)
        assertEquals("$expectedBase#opening", safeUrl)
        assertEquals(expectedBase, hostileUrl)
        assertEquals(
            EpubAssetPath.SectionDocumentRequest(
                publicationPath = "7cc082593adf00fe141410aaeb24974d",
                sectionPath = "fd88a97dd36bd775f019f140f34d3029",
            ),
            EpubAssetPath.parseUrl(safeUrl),
        )
    }

    @Test
    fun `section document parser rejects malformed query traversal and extra segments`() {
        // Given: one canonical local section document URL.
        val canonical = EpubAssetPath.sectionDocumentUrl("fixture-fingerprint", "section-0")
        val publicationToken = "7cc082593adf00fe141410aaeb24974d"
        val sectionToken = "fd88a97dd36bd775f019f140f34d3029"

        // When: hostile URL shapes cross the local boundary.
        val hostile = listOf(
            canonical.replace("https://", "http://"),
            canonical.replace(EpubAssetPath.HOST, "attacker.invalid"),
            canonical.replace(EpubAssetPath.HOST, "user@${EpubAssetPath.HOST}"),
            canonical.replace(EpubAssetPath.HOST, "${EpubAssetPath.HOST}:443"),
            "$canonical?reload=1",
            "${canonical}extra",
            "https://${EpubAssetPath.HOST}/${EpubAssetPath.PREFIX}/$publicationToken/unknown/$sectionToken/",
            "https://${EpubAssetPath.HOST}/${EpubAssetPath.PREFIX}/$publicationToken/section/../",
            "https://${EpubAssetPath.HOST}/${EpubAssetPath.PREFIX}/$publicationToken/section/%2e%2e/",
            "https://${EpubAssetPath.HOST}/${EpubAssetPath.PREFIX}/short/section/$sectionToken/",
            canonical.dropLast(1),
            "$canonical#x%2fescape",
        )

        // Then: every non-canonical or hostile URL fails closed.
        hostile.forEach { assertNull(EpubAssetPath.parseUrl(it)) }
    }

    @Test
    fun `toc entries expose source titles and stable section ids in order`() {
        val publication = TextBookPublication(
            fingerprint = BookFingerprint("fixture-fingerprint"),
            spine = listOf(
                BookSection("section-0", "chapter-0.xhtml", title = "First Light"),
                BookSection("section-1", "chapter-1.xhtml"),
            ),
            tableOfContents = listOf(
                BookSection("section-0", "chapter-0.xhtml", title = "First Light"),
                BookSection("section-1", "chapter-1.xhtml"),
            ),
        )

        assertEquals(
            listOf(
                EpubTocEntry("section-0", "First Light"),
                EpubTocEntry("section-1", "section-1"),
            ),
            epubTocEntries(publication),
        )
    }

    @Test
    fun `only the local asset origin is navigable and resource paths are parsed`() {
        val localResource = EpubHtmlSanitizer.resourceUrl(
            fingerprint = "fixture-fingerprint",
            sectionId = "section-0",
            href = "https://books.example/images/cover.jpg",
        )

        assertTrue(EpubWebViewPolicy.allowsLocalNavigation(localResource))
        assertFalse(EpubWebViewPolicy.allowsLocalNavigation("https://books.example/chapter.xhtml"))
        assertFalse(EpubWebViewPolicy.allowsLocalNavigation("http://appassets.androidplatform.net/mihon-book/book/"))
        assertFalse(EpubWebViewPolicy.allowsLocalNavigation("file:///data/local/tmp/book.xhtml"))
        assertFalse(EpubWebViewPolicy.allowsLocalNavigation("javascript:alert(1)"))

        val request = EpubWebViewPolicy.resourceRequest(localResource)

        assertEquals(EpubHtmlSanitizer.resourceToken("https://books.example/images/cover.jpg"), request?.resourceToken)
        assertNull(EpubWebViewPolicy.resourceRequest("https://attacker.invalid/mihon-book/book/resource/section/token"))
        assertNull(
            EpubWebViewPolicy.resourceRequest("https://appassets.androidplatform.net/mihon-book/../../etc/passwd"),
        )
    }

    @Test
    fun `section state supports next previous and renderer recovery`() {
        val state = EpubTextViewerState()

        assertTrue(state.moveNext(sectionCount = 2))
        assertEquals(1, state.currentSectionIndex)
        assertFalse(state.moveNext(sectionCount = 2))
        assertEquals(1, state.currentSectionIndex)
        assertTrue(state.movePrevious(sectionCount = 2))
        assertEquals(0, state.currentSectionIndex)

        state.markRendererRecovery()

        assertEquals(1, state.rendererRecoveryCount)
    }
}
