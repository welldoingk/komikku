package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.TextBookContent
import eu.kanade.tachiyomi.source.TextBookSource
import eu.kanade.tachiyomi.source.model.BookChapterId
import eu.kanade.tachiyomi.source.model.BookFingerprint
import eu.kanade.tachiyomi.source.model.BookResourceContent
import eu.kanade.tachiyomi.source.model.BookResourceRequest
import eu.kanade.tachiyomi.source.model.BookSection
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.BookSectionRequest
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.TextBookPublication
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.manga.model.Manga

class ChapterLoaderBookSourceTest {

    @Test
    fun `chapter loader selects book loader without touching legacy download or page paths`() = runTest {
        val source = FakeTextBookSource()
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 1L
                manga_id = 2L
                url = "/chapter"
                name = "Book"
            },
        )
        val loader = ChapterLoader(
            context = mockk<Context>(),
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            manga = Manga.create().copy(source = source.id, url = "/book", ogTitle = "Fixture"),
            source = source,
        )

        loader.loadChapter(chapter)

        assertNotNull(chapter.bookLoader)
        assertNull(chapter.pageLoader)
        assertTrue(chapter.state is ReaderChapter.State.Loaded)
        assertEquals(2, chapter.bookLoader?.sectionCount)
        assertEquals(0, source.legacyPageCalls)
        assertEquals(listOf("/chapter"), source.content.requestedChapterUrls)
        confirmVerified(downloadManager, downloadProvider)
    }

    @Test
    fun `non text book source keeps downloaded image loader selection`() = runTest {
        val source = FakeLegacySource()
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 1L
                manga_id = 2L
                url = "/chapter"
                name = "Images"
            },
        )
        val manga = Manga.create().copy(source = source.id, url = "/images", ogTitle = "Fixture")
        every {
            downloadManager.isChapterDownloaded(
                chapterName = "Images",
                chapterScanlator = null,
                chapterUrl = "/chapter",
                mangaTitle = "Fixture",
                sourceId = source.id,
                skipCache = true,
            )
        } returns true
        every { downloadProvider.findChapterDir("Images", null, "/chapter", "Fixture", source) } returns null
        every { downloadManager.buildPageList(source, manga, any()) } returns listOf(
            Page(0, imageUrl = "https://images.example/page-0.jpg"),
        )
        val loader = ChapterLoader(
            context = mockk<Context>(),
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            manga = manga,
            source = source,
        )

        loader.loadChapter(chapter)

        assertNull(chapter.bookLoader)
        assertInstanceOf(DownloadPageLoader::class.java, chapter.pageLoader)
        assertEquals(listOf("https://images.example/page-0.jpg"), chapter.pages?.map(Page::imageUrl))
    }

    @Test
    fun `baseline image reader chapter retains ordered page identity and selected loader`() = runTest {
        val source = FakeLegacySource()
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 7L
                manga_id = 8L
                url = "/baseline-images"
                name = "Baseline images"
            },
        )
        val manga = Manga.create().copy(source = source.id, url = "/images", ogTitle = "Baseline")
        every {
            downloadManager.isChapterDownloaded(
                "Baseline images",
                null,
                "/baseline-images",
                "Baseline",
                source.id,
                true,
            )
        } returns true
        every {
            downloadProvider.findChapterDir("Baseline images", null, "/baseline-images", "Baseline", source)
        } returns null
        every { downloadManager.buildPageList(source, manga, any()) } returns listOf(
            Page(0, imageUrl = "https://images.example/0.jpg"),
            Page(1, imageUrl = "https://images.example/1.jpg"),
        )

        ChapterLoader(mockk(), downloadManager, downloadProvider, manga, source).loadChapter(chapter)

        assertInstanceOf(DownloadPageLoader::class.java, chapter.pageLoader)
        assertNull(chapter.bookLoader)
        assertEquals(listOf(0, 1), chapter.pages?.map(Page::index))
        assertEquals(
            listOf("https://images.example/0.jpg", "https://images.example/1.jpg"),
            chapter.pages?.map(Page::imageUrl),
        )
        assertTrue(chapter.pages.orEmpty().all { it.chapter === chapter })
    }

    @Test
    fun `premature chapter recycle keeps wait state after cancelling book load`() = runTest {
        val sectionStarted = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val source = FakeTextBookSource(
            FakeTextBookContent {
                sectionStarted.complete(Unit)
                neverComplete.await()
            },
        )
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 1L
                manga_id = 2L
                url = "/chapter"
                name = "Book"
            },
        )
        val loader = ChapterLoader(
            context = mockk<Context>(),
            downloadManager = mockk<DownloadManager>(),
            downloadProvider = mockk<DownloadProvider>(),
            manga = Manga.create().copy(source = source.id, url = "/book", ogTitle = "Fixture"),
            source = source,
        )
        chapter.ref()
        val loading = async { loader.loadChapter(chapter) }
        sectionStarted.await()

        chapter.unref()

        val exception = assertThrows<BookLoadCancellationException> { loading.await() }
        assertEquals(BookLoadError.Recycled, exception.reason)
        assertNull(chapter.bookLoader)
        assertTrue(chapter.state is ReaderChapter.State.Wait)
    }

    @Test
    fun `reader chapter recycles its selected book loader once at zero references`() = runTest {
        val source = FakeTextBookSource()
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 1L
                manga_id = 2L
                url = "/chapter"
                name = "Book"
            },
        )
        val loader = ChapterLoader(
            context = mockk<Context>(),
            downloadManager = mockk<DownloadManager>(),
            downloadProvider = mockk<DownloadProvider>(),
            manga = Manga.create().copy(source = source.id, url = "/book", ogTitle = "Fixture"),
            source = source,
        )
        chapter.ref()
        chapter.ref()
        loader.loadChapter(chapter)
        val selectedLoader = requireNotNull(chapter.bookLoader)

        chapter.unref()

        assertFalse(selectedLoader.isRecycled)
        assertSame(selectedLoader, chapter.bookLoader)

        chapter.unref()

        assertTrue(selectedLoader.isRecycled)
        assertNull(chapter.bookLoader)
        assertTrue(chapter.state is ReaderChapter.State.Wait)
    }

    private class FakeTextBookSource(
        val content: FakeTextBookContent = FakeTextBookContent(),
    ) : TextBookSource {
        override val id = 31L
        override val name = "fixture"
        override val supportsLatest = false
        var legacyPageCalls = 0
        override val textBookContent: TextBookContent
            get() = content

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            legacyPageCalls += 1
            return emptyList()
        }

        override suspend fun getPopularManga(page: Int): MangasPage = error("Not used")
        override suspend fun getLatestUpdates(page: Int): MangasPage = error("Not used")
        override suspend fun getSearchManga(
            page: Int,
            query: String,
            filters: FilterList,
        ): MangasPage = error("Not used")
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = error("Not used")
    }

    private class FakeLegacySource : eu.kanade.tachiyomi.source.Source {
        override val id = 32L
        override val name = "fixture-images"
        override val supportsLatest = false

        override suspend fun getPageList(chapter: SChapter): List<Page> = error("Downloaded path owns pages")
        override suspend fun getPopularManga(page: Int): MangasPage = error("Not used")
        override suspend fun getLatestUpdates(page: Int): MangasPage = error("Not used")
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            error("Not used")

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = error("Not used")
    }

    private class FakeTextBookContent(
        private val beforeSection: suspend () -> Unit = {},
    ) : TextBookContent() {
        val requestedChapterUrls = mutableListOf<String>()

        private val publication = TextBookPublication(
            fingerprint = BookFingerprint("sha256:chapter-loader"),
            chapterId = BookChapterId("chapter"),
            spine = listOf(
                BookSection("one", "https://books.example/one.xhtml"),
                BookSection("two", "https://books.example/two.xhtml"),
            ),
            tableOfContents = emptyList(),
        )

        override suspend fun getTextBookPublication(manga: SManga, chapter: SChapter): TextBookPublication {
            requestedChapterUrls += chapter.url
            return publication.copy(chapterId = BookChapterId(chapter.url.substringAfterLast('/')))
        }

        override suspend fun getTextBookSectionImpl(request: BookSectionRequest): BookSectionContent {
            beforeSection()
            return BookSectionContent(
                request.sectionId,
                "https://books.example/${request.sectionId}.xhtml",
                "<p>${request.sectionId}</p>",
            )
        }

        override suspend fun getTextBookResourceImpl(request: BookResourceRequest): BookResourceContent =
            error("Not used")
    }
}
