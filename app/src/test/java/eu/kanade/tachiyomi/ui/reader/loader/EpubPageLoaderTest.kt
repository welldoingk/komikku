package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import mihon.core.archive.EpubReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class EpubPageLoaderTest {

    @Test
    fun `legacy local EPUB keeps image order streams and recycle lifecycle`() = runTest {
        val reader = mockk<EpubReader>()
        every { reader.getImagesFromPages() } returns listOf("images/002.jpg", "images/001.jpg")
        every { reader.getInputStream("images/002.jpg") } returns ByteArrayInputStream(byteArrayOf(2))
        every { reader.getInputStream("images/001.jpg") } returns ByteArrayInputStream(byteArrayOf(1))
        every { reader.close() } returns Unit

        val loader = EpubPageLoader(reader)
        val pages = loader.getPages()

        assertFalse(loader.isRecycled)
        assertTrue(loader.isLocal)
        assertEquals(listOf(0, 1), pages.map(Page::index))
        assertEquals(listOf(Page.State.Ready, Page.State.Ready), pages.map(Page::status))
        assertArrayEquals(byteArrayOf(2), pages[0].stream!!.invoke().readBytes())
        assertArrayEquals(byteArrayOf(1), pages[1].stream!!.invoke().readBytes())

        loader.recycle()

        assertTrue(loader.isRecycled)
        verify(exactly = 1) { reader.close() }
    }
}
