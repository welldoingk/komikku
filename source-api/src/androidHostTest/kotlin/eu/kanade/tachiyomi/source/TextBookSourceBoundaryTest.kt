package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.BookChapterId
import eu.kanade.tachiyomi.source.model.BookFingerprint
import eu.kanade.tachiyomi.source.model.BookLocator
import eu.kanade.tachiyomi.source.model.BookResource
import eu.kanade.tachiyomi.source.model.BookResourceContent
import eu.kanade.tachiyomi.source.model.BookResourceRequest
import eu.kanade.tachiyomi.source.model.BookSection
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.BookSectionRequest
import eu.kanade.tachiyomi.source.model.BookTextQuote
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.TextBookContract
import eu.kanade.tachiyomi.source.model.TextBookPublication
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextBookSourceBoundaryTest {

    @Test
    fun `constrained requests carry the exact selected chapter identity`() {
        val request = BookSectionRequest(
            fingerprint = BookFingerprint("sha256:current"),
            chapterId = BookChapterId("101"),
            sectionId = "chapter-1",
        )

        assertEquals("101", request.chapterId.value)
    }

    @Test
    fun `publication serialization keeps contract version and section ordering`() {
        val publication = publication()

        val restored = Json.decodeFromString<TextBookPublication>(
            Json.encodeToString(publication),
        )

        assertEquals(TextBookContract.CURRENT_VERSION, restored.contractVersion)
        assertEquals(BookChapterId("101"), restored.chapterId)
        assertEquals(listOf("chapter-1", "chapter-2"), restored.spine.map(BookSection::id))
        assertEquals(listOf("chapter-2", "chapter-1"), restored.tableOfContents.map(BookSection::id))
        assertEquals(publication, restored)
    }

    @Test
    fun `section and resource request response payloads round trip`() {
        val fingerprint = BookFingerprint("sha256:current")
        val chapterId = BookChapterId("101")
        val sectionRequest = BookSectionRequest(fingerprint, chapterId, "chapter-1")
        val sectionContent = BookSectionContent(
            sectionId = "chapter-1",
            href = "https://books.example/book/ch1.xhtml",
            html = "<p id=\"opening\">Call me Ishmael.</p>",
        )
        val resourceRequest = BookResourceRequest(
            fingerprint,
            chapterId,
            "chapter-1",
            "https://books.example/book/style.css",
        )
        val resourceContent = BookResourceContent(
            href = resourceRequest.href,
            mediaType = "text/css",
            bytes = "body {}".encodeToByteArray(),
        )

        assertEquals(sectionRequest, roundTrip(sectionRequest))
        assertEquals(sectionContent, roundTrip(sectionContent))
        assertEquals(resourceRequest, roundTrip(resourceRequest))
        assertEquals(resourceContent, roundTrip(resourceContent))
    }

    @Test
    fun `untrusted section text remains opaque contract data`() {
        val untrustedHtml = "<script>globalThis.exfiltrate()</script>"
        val content = BookSectionContent(
            sectionId = "chapter-1",
            href = "https://books.example/book/ch1.xhtml",
            html = untrustedHtml,
        )

        assertEquals(untrustedHtml, roundTrip(content).html)
    }

    @Test
    fun `locator factory clamps hints and locator round trips`() {
        val locator = BookLocator.create(
            href = "https://books.example/book/ch1.xhtml",
            cfi = "epubcfi(/6/2!/4/1:0)",
            cssSelector = "#opening",
            textQuote = BookTextQuote(exact = "Call me Ishmael", prefix = "", suffix = "."),
            progression = -0.2,
            totalProgression = 1.4,
        )

        assertEquals(0.0, locator.progression)
        assertEquals(1.0, locator.totalProgression)
        assertEquals(locator, Json.decodeFromString<BookLocator>(Json.encodeToString(locator)))
    }

    @Test
    fun `malformed locator is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BookLocator.create(href = "", cssSelector = "#opening")
        }
        assertFailsWith<IllegalArgumentException> {
            BookLocator.create(href = "chapter.xhtml")
        }
    }

    @Test
    fun `publication permits only known section and allowlisted same book resources`() {
        val publication = publication()
        val fingerprint = publication.fingerprint

        assertEquals(
            "chapter-1",
            publication.requireSection(BookSectionRequest(fingerprint, publication.chapterId, "chapter-1")).id,
        )
        assertEquals(
            "text/css",
            publication.requireResource(
                BookResourceRequest(
                    fingerprint,
                    publication.chapterId,
                    "chapter-1",
                    "https://books.example/book/style.css",
                ),
            ).mediaType,
        )
        assertEquals(
            "image/jpeg",
            publication.requireResource(
                BookResourceRequest(
                    fingerprint,
                    publication.chapterId,
                    "chapter-1",
                    "https://books.example/book/cover.jpg",
                ),
            ).mediaType,
        )

        assertFailsWith<IllegalArgumentException> {
            publication.requireResource(
                BookResourceRequest(
                    fingerprint,
                    publication.chapterId,
                    "chapter-1",
                    "https://foreign.example/book/style.css",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            publication.requireResource(
                BookResourceRequest(fingerprint, publication.chapterId, "chapter-1", "../secret.css"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            publication.requireSection(BookSectionRequest(fingerprint, publication.chapterId, "missing"))
        }
        assertFailsWith<IllegalStateException> {
            publication.requireSection(
                BookSectionRequest(BookFingerprint("stale"), publication.chapterId, "chapter-1"),
            )
        }
    }

    @Test
    fun `HttpSource can expose the versioned text book capability through composition`() {
        val concreteSource = CountingHttpTextBookSource()
        val source: HttpSource = concreteSource

        assertEquals(2, TextBookContract.CURRENT_VERSION)
        assertEquals(TextBookContract.CURRENT_VERSION, (source as TextBookSource).textBookContractVersion)
        assertEquals(0, concreteSource.textBookContent.networkHookCalls)
    }

    @Test
    fun `publication lookup receives the selected chapter identity`() = runTest {
        val concreteSource = CountingHttpTextBookSource()
        val source: HttpSource = concreteSource
        val content = (source as TextBookSource).textBookContent
        val manga = SManga.create().apply { url = "/Series/7" }
        val firstChapter = SChapter.create().apply { url = "/Chapter/101" }
        val secondChapter = SChapter.create().apply { url = "/Chapter/202" }

        val firstPublication = content.getTextBookPublication(manga, firstChapter)
        val secondPublication = content.getTextBookPublication(manga, secondChapter)

        assertEquals(
            listOf("/Chapter/101", "/Chapter/202"),
            concreteSource.textBookContent.requestedChapterUrls,
        )
        assertEquals(
            listOf("chapter:/Chapter/101", "chapter:/Chapter/202"),
            listOf(firstPublication, secondPublication).map { it.fingerprint.value },
        )
        assertEquals(
            listOf(BookChapterId("101"), BookChapterId("202")),
            listOf(firstPublication, secondPublication).map { it.chapterId },
        )
        assertEquals(
            listOf(
                "https://books.example/api/Book/101/book-info",
                "https://books.example/api/Book/202/book-info",
            ),
            listOf(firstPublication, secondPublication).map { it.spine.single().href },
        )
    }

    @Test
    fun `validated entrypoints are final and raw hooks remain protected`() {
        val methods = TextBookContent::class.java.declaredMethods.associateBy { it.name }

        assertEquals(true, Modifier.isFinal(methods.getValue("getTextBookSection").modifiers))
        assertEquals(true, Modifier.isFinal(methods.getValue("getTextBookResource").modifiers))
        assertEquals(true, Modifier.isProtected(methods.getValue("getTextBookSectionImpl").modifiers))
        assertEquals(true, Modifier.isProtected(methods.getValue("getTextBookResourceImpl").modifiers))
    }

    @Test
    fun `safe section boundary rejects unknown or stale requests before its implementation hook`() = runTest {
        val source = CountingHttpTextBookSource()
        val publication = publication()
        val content = source.textBookContent

        assertFailsWith<IllegalArgumentException> {
            content.getTextBookSection(
                publication,
                BookSectionRequest(publication.fingerprint, publication.chapterId, "missing"),
            )
        }
        assertFailsWith<IllegalStateException> {
            content.getTextBookSection(
                publication,
                BookSectionRequest(BookFingerprint("sha256:stale"), publication.chapterId, "chapter-1"),
            )
        }

        assertEquals(0, content.sectionHookCalls)
        assertEquals(0, content.networkHookCalls)
    }

    @Test
    fun `hostile resource and section inputs never reach raw hooks`() = runTest {
        val source = CountingHttpTextBookSource()
        val publication = publication()
        val content = source.textBookContent

        assertFailsWith<IllegalArgumentException> {
            content.getTextBookResource(
                publication,
                BookResourceRequest(
                    publication.fingerprint,
                    publication.chapterId,
                    "chapter-1",
                    "https://foreign.example/book/style.css",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            content.getTextBookResource(
                publication,
                BookResourceRequest(publication.fingerprint, publication.chapterId, "chapter-1", "../secret.css"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            content.getTextBookResource(
                publication,
                BookResourceRequest(
                    publication.fingerprint,
                    publication.chapterId,
                    "chapter-1",
                    "%2e%2e%2fsecret.css",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            content.getTextBookResource(
                publication,
                BookResourceRequest(
                    publication.fingerprint,
                    publication.chapterId,
                    "chapter-1",
                    "https://books.example/book/not-allowlisted.css",
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            content.getTextBookResource(
                publication,
                BookResourceRequest(
                    BookFingerprint("sha256:stale"),
                    publication.chapterId,
                    "chapter-1",
                    "https://books.example/book/style.css",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            content.getTextBookResource(
                publication,
                BookResourceRequest(
                    publication.fingerprint,
                    publication.chapterId,
                    "missing",
                    "https://books.example/book/style.css",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            content.getTextBookSection(
                publication,
                BookSectionRequest(
                    publication.fingerprint,
                    BookChapterId("foreign"),
                    "chapter-1",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            content.getTextBookSection(
                publication,
                BookSectionRequest(
                    publication.fingerprint,
                    BookChapterId(" "),
                    "chapter-1",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BookSectionRequest(publication.fingerprint, "")
        }
        assertFailsWith<IllegalArgumentException> {
            BookResourceRequest(publication.fingerprint, "chapter-1", "")
        }

        assertEquals(0, content.resourceHookCalls)
        assertEquals(0, content.networkHookCalls)
    }

    @Test
    fun `safe boundaries invoke hooks exactly once for allowlisted section css and image`() = runTest {
        val source = CountingHttpTextBookSource()
        val publication = publication()
        val content = source.textBookContent

        val section = content.getTextBookSection(
            publication,
            BookSectionRequest(publication.fingerprint, publication.chapterId, "chapter-1"),
        )
        val css = content.getTextBookResource(
            publication,
            BookResourceRequest(
                publication.fingerprint,
                publication.chapterId,
                "chapter-1",
                "https://books.example/book/style.css",
            ),
        )
        val image = content.getTextBookResource(
            publication,
            BookResourceRequest(
                publication.fingerprint,
                publication.chapterId,
                "chapter-1",
                "https://books.example/book/cover.jpg",
            ),
        )

        assertEquals("chapter-1", section.sectionId)
        assertEquals("text/css", css.mediaType)
        assertEquals("image/jpeg", image.mediaType)
        assertEquals(1, content.sectionHookCalls)
        assertEquals(2, content.resourceHookCalls)
        assertEquals(3, content.networkHookCalls)
        assertEquals(
            listOf(publication.chapterId, publication.chapterId, publication.chapterId),
            content.requestedChapterIds,
        )
    }

    private fun publication() = TextBookPublication(
        fingerprint = BookFingerprint("sha256:current"),
        chapterId = BookChapterId("101"),
        spine = listOf(
            BookSection(
                id = "chapter-1",
                href = "https://books.example/book/ch1.xhtml",
                title = "One",
                resources = listOf(
                    BookResource("https://books.example/book/style.css", "text/css"),
                    BookResource("https://books.example/book/cover.jpg", "image/jpeg"),
                ),
            ),
            BookSection(
                id = "chapter-2",
                href = "https://books.example/book/ch2.xhtml",
                title = "Two",
            ),
        ),
        tableOfContents = listOf(
            BookSection("chapter-2", "https://books.example/book/ch2.xhtml", "Two"),
            BookSection("chapter-1", "https://books.example/book/ch1.xhtml", "One"),
        ),
    )

    private inline fun <reified T> roundTrip(value: T): T =
        Json.decodeFromString(Json.encodeToString(value))

    private class CountingHttpTextBookSource : HttpSource(), TextBookSource {
        override val name = "book"
        override val lang = "en"
        override val baseUrl = "https://books.example"
        override val supportsLatest = false
        override val textBookContent = CountingTextBookContent()

        override suspend fun getPopularManga(page: Int) = error("Not used")

        override suspend fun getLatestUpdates(page: Int) = error("Not used")

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = error("Not used")

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = error("Not used")

        override suspend fun getPageList(chapter: SChapter) = error("Not used")
    }

    private class CountingTextBookContent : TextBookContent() {
        var sectionHookCalls = 0
        var resourceHookCalls = 0
        val requestedChapterUrls = mutableListOf<String>()
        val requestedChapterIds = mutableListOf<BookChapterId>()
        val networkHookCalls: Int
            get() = sectionHookCalls + resourceHookCalls

        override suspend fun getTextBookPublication(manga: SManga, chapter: SChapter): TextBookPublication {
            requestedChapterUrls += chapter.url
            val chapterId = chapter.url.substringAfterLast('/')
            return TextBookPublication(
                fingerprint = BookFingerprint("chapter:${chapter.url}"),
                chapterId = BookChapterId(chapterId),
                spine = listOf(
                    BookSection(
                        id = "chapter-$chapterId",
                        href = "https://books.example/api/Book/$chapterId/book-info",
                    ),
                ),
                tableOfContents = emptyList(),
            )
        }

        override suspend fun getTextBookSectionImpl(request: BookSectionRequest): BookSectionContent {
            sectionHookCalls += 1
            requestedChapterIds += request.chapterId
            return BookSectionContent(request.sectionId, "https://books.example/book/ch1.xhtml", "<p>One</p>")
        }

        override suspend fun getTextBookResourceImpl(request: BookResourceRequest): BookResourceContent {
            resourceHookCalls += 1
            requestedChapterIds += request.chapterId
            val mediaType = if (request.href.endsWith(".css")) "text/css" else "image/jpeg"
            return BookResourceContent(request.href, mediaType, byteArrayOf())
        }
    }
}
