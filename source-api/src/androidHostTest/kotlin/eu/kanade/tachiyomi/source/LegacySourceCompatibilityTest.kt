package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LegacySourceCompatibilityTest {

    @Test
    fun `existing image source keeps returning ordinary page list`() = runTest {
        val chapter = SChapter.create().apply { url = "/chapter/1" }
        val source: Source = LegacyImageSource

        val pages = source.getPageList(chapter)

        assertFalse(source is TextBookSource)
        assertEquals(1, pages.size)
        assertEquals("https://example.test/page-1.jpg", pages.single().imageUrl)
        assertEquals("", pages.single().url)
    }

    private object LegacyImageSource : Source {
        override val id = 1L
        override val name = "legacy"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = SMangaUpdate(manga, chapters)

        override suspend fun getPageList(chapter: SChapter): List<Page> =
            listOf(Page(index = 0, imageUrl = "https://example.test/page-1.jpg"))
    }
}
