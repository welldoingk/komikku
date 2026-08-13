package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.source.model.BookFingerprint
import eu.kanade.tachiyomi.source.model.BookSection
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.TextBookPublication
import eu.kanade.tachiyomi.ui.reader.loader.EpubAssetPath
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubAssetLoaderControllerTest {

    @Test
    fun `svg book resource is served by local asset origin`() {
        assertTrue("image/svg+xml" in EpubAssetLoaderController.SAFE_MEDIA_TYPES)
    }

    @Test
    fun `section document response is composed utf8 html with no store`() {
        // Given: sanitized section content held by the loaded publication state.
        val section = BookSection("section-0", "https://books.example/chapter-0.xhtml")
        val publication = TextBookPublication(
            fingerprint = BookFingerprint("fixture-fingerprint"),
            spine = listOf(section),
            tableOfContents = listOf(section),
        )
        val request = EpubAssetPath.parseUrl(
            EpubAssetPath.sectionDocumentUrl(publication.fingerprint.value, section.id),
        ) as EpubAssetPath.SectionDocumentRequest
        val sections = listOf(
            BookSectionContent(section.id, section.href, "<article><p id=\"opening\">Safe prose</p></article>"),
        )

        // When: the controller resolves the strict request from typed publication state.
        val response = EpubAssetLoaderController.sectionDocumentContent(request, publication, sections)

        // Then: it returns a first-class no-store UTF-8 HTML document.
        assertEquals("text/html", response?.mediaType)
        assertEquals("UTF-8", response?.encoding)
        assertEquals(mapOf("Cache-Control" to "no-store"), response?.headers)
        val document = Jsoup.parse(response?.bytes?.toString(Charsets.UTF_8).orEmpty())
        assertEquals("Safe prose", document.body().text())
        assertEquals(
            "width=device-width, initial-scale=1",
            document.head().selectFirst("meta[name=viewport]")?.attr("content"),
        )
    }

    @Test
    fun `section document response rejects unknown or mismatched loader state`() {
        // Given: a request token that does not identify the publication's only section.
        val section = BookSection("section-0", "https://books.example/chapter-0.xhtml")
        val publication = TextBookPublication(
            fingerprint = BookFingerprint("fixture-fingerprint"),
            spine = listOf(section),
            tableOfContents = emptyList(),
        )
        val unknownRequest = EpubAssetPath.SectionDocumentRequest(
            publicationPath = "0".repeat(32),
            sectionPath = "1".repeat(32),
        )
        val mismatchedContent = listOf(
            BookSectionContent(section.id, "https://books.example/different.xhtml", "<p>Wrong document</p>"),
        )

        // When/Then: unknown path identity and mismatched section content both fail closed.
        assertNull(EpubAssetLoaderController.sectionDocumentContent(unknownRequest, publication, mismatchedContent))
        val knownRequest = EpubAssetPath.parseUrl(
            EpubAssetPath.sectionDocumentUrl(publication.fingerprint.value, section.id),
        ) as EpubAssetPath.SectionDocumentRequest
        assertNull(EpubAssetLoaderController.sectionDocumentContent(knownRequest, publication, mismatchedContent))
    }
}
