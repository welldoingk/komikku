package eu.kanade.tachiyomi.ui.reader.setting

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import java.nio.file.Files
import java.nio.file.Path

class ReadingModeImageViewerCharacterizationTest {

    @Test
    fun `non text book chapter retains downloaded image loader and pager viewer path`() = runTest {
        val source = LegacyImageSource()
        val downloadManager = mockk<DownloadManager>()
        val downloadProvider = mockk<DownloadProvider>()
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = 41L
                manga_id = 42L
                url = "/legacy-chapter"
                name = "Legacy images"
            },
        )
        val manga = Manga.create().copy(source = source.id, ogTitle = "Legacy manga")
        val sourcePages = listOf(
            Page(0, imageUrl = "https://images.example/first.jpg"),
            Page(1, imageUrl = "https://images.example/second.jpg"),
        )
        every {
            downloadManager.isChapterDownloaded(
                "Legacy images",
                null,
                "/legacy-chapter",
                "Legacy manga",
                source.id,
                true,
            )
        } returns true
        every {
            downloadProvider.findChapterDir("Legacy images", null, "/legacy-chapter", "Legacy manga", source)
        } returns null
        every { downloadManager.buildPageList(source, manga, any()) } returns sourcePages

        ChapterLoader(
            context = mockk<Context>(),
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            manga = manga,
            source = source,
        ).loadChapter(chapter)

        assertInstanceOf(DownloadPageLoader::class.java, chapter.pageLoader)
        assertNull(chapter.bookLoader)
        assertEquals(sourcePages.map(Page::imageUrl), chapter.pages?.map(Page::imageUrl))
        chapter.pages.orEmpty().forEach { assertSame(chapter, it.chapter) }

        val readingModeSource = projectRoot().resolve(READING_MODE_SOURCE).toFile().readText()
        assertTrue(readingModeSource.contains("LEFT_TO_RIGHT -> L2RPagerViewer("))
        assertTrue(readingModeSource.contains("RIGHT_TO_LEFT -> R2LPagerViewer("))
        assertTrue(readingModeSource.contains("VERTICAL -> VerticalPagerViewer("))
        assertTrue(readingModeSource.contains("WEBTOON -> WebtoonViewer("))
        assertTrue(readingModeSource.contains("CONTINUOUS_VERTICAL -> WebtoonViewer("))
    }

    private fun projectRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(candidate.resolve(READING_MODE_SOURCE))) {
            candidate = candidate.parent ?: error("Unable to locate $READING_MODE_SOURCE")
        }
        return candidate
    }

    private class LegacyImageSource : Source {
        override val id = 43L
        override val name = "legacy-images"
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

    private companion object {
        const val READING_MODE_SOURCE =
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReadingMode.kt"
    }
}
