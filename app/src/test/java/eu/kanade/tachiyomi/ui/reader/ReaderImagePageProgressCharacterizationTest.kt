package eu.kanade.tachiyomi.ui.reader

import androidx.lifecycle.SavedStateHandle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.assertions.nondeterministic.eventually
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tachiyomi.core.common.storage.UniFileTempFileManager
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

class ReaderImagePageProgressCharacterizationTest {

    @Test
    fun `legacy image page selection synchronizes visible and persisted page progress`() = runBlocking {
        val chapter = legacyChapter()
        val pages = chapterPages(chapter)
        val viewModel = readerViewModel()

        viewModel.onPageSelected(pages[1], "2", false)

        eventually(2.seconds) {
            assertEquals(2, viewModel.state.value.currentPage)
            assertEquals(1, chapter.requestedPage)
            assertEquals(1, chapter.chapter.last_page_read)
        }
    }

    @Test
    fun `insert pages do not change legacy image page progress`() {
        val chapter = legacyChapter()
        val pages = chapterPages(chapter)
        val viewModel = readerViewModel()

        viewModel.onPageSelected(InsertPage(pages[1]), "1", false)

        assertEquals(-1, viewModel.state.value.currentPage)
        assertEquals(0, chapter.requestedPage)
        assertEquals(0, chapter.chapter.last_page_read)
    }

    private fun readerViewModel(): ReaderViewModel {
        val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
        every { downloadPreferences.autoDownloadWhileReading().get() } returns 0

        val getIncognitoState = mockk<GetIncognitoState>(relaxed = true)
        every { getIncognitoState.await(null) } returns false

        return ReaderViewModel(
            savedState = SavedStateHandle(),
            sourceManager = mockk<SourceManager>(relaxed = true),
            downloadManager = mockk<DownloadManager>(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            tempFileManager = mockk<UniFileTempFileManager>(relaxed = true),
            imageSaver = mockk<ImageSaver>(relaxed = true),
            readerPreferences = mockk<ReaderPreferences>(relaxed = true),
            basePreferences = mockk<BasePreferences>(relaxed = true),
            downloadPreferences = downloadPreferences,
            trackPreferences = mockk<TrackPreferences>(relaxed = true),
            trackChapter = mockk<TrackChapter>(relaxed = true),
            getManga = mockk<GetManga>(relaxed = true),
            getChaptersByMangaId = mockk<GetChaptersByMangaId>(relaxed = true),
            getNextChapters = mockk<GetNextChapters>(relaxed = true),
            upsertHistory = mockk<UpsertHistory>(relaxed = true),
            updateChapter = mockk<UpdateChapter>(relaxed = true),
            setMangaViewerFlags = mockk<SetMangaViewerFlags>(relaxed = true),
            getIncognitoState = getIncognitoState,
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true),
            syncPreferences = mockk<SyncPreferences>(relaxed = true),
            uiPreferences = mockk<UiPreferences>(relaxed = true),
            getFlatMetadataById = mockk<GetFlatMetadataById>(relaxed = true),
            getMergedMangaById = mockk<GetMergedMangaById>(relaxed = true),
            getMergedReferencesById = mockk<GetMergedReferencesById>(relaxed = true),
            getMergedChaptersByMangaId = mockk<GetMergedChaptersByMangaId>(relaxed = true),
        )
    }

    private fun legacyChapter(): ReaderChapter = ReaderChapter(
        ChapterImpl().apply {
            id = 101L
            manga_id = 202L
            url = "/legacy-images"
            name = "Legacy images"
        },
    )

    private fun chapterPages(chapter: ReaderChapter): List<ReaderPage> {
        val pages = listOf(
            ReaderPage(0, imageUrl = "https://images.example/0.jpg"),
            ReaderPage(1, imageUrl = "https://images.example/1.jpg"),
            ReaderPage(2, imageUrl = "https://images.example/2.jpg"),
        )
        pages.forEach { it.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(pages)
        pages.forEach { it.status = Page.State.Ready }
        return pages
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUpMainDispatcher() {
            Dispatchers.setMain(Dispatchers.Unconfined)
        }

        @AfterAll
        @JvmStatic
        fun resetMainDispatcher() {
            Dispatchers.resetMain()
        }
    }
}
