package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubTouchNavigationTest {

    @Test
    fun `tap zones and horizontal swipes navigate while vertical reading scroll is preserved`() {
        val slop = 12f
        val width = 900f

        assertEquals(EpubTouchAction.Previous, resolve(80f, 400f, 80f, 400f, width, slop))
        assertEquals(EpubTouchAction.Menu, resolve(450f, 400f, 450f, 400f, width, slop))
        assertEquals(EpubTouchAction.Next, resolve(820f, 400f, 820f, 400f, width, slop))
        assertEquals(EpubTouchAction.Next, resolve(700f, 400f, 200f, 410f, width, slop))
        assertEquals(EpubTouchAction.Previous, resolve(200f, 400f, 700f, 390f, width, slop))
        assertEquals(EpubTouchAction.None, resolve(450f, 200f, 460f, 700f, width, slop))
    }

    private fun resolve(
        downX: Float,
        downY: Float,
        upX: Float,
        upY: Float,
        width: Float,
        touchSlop: Float,
    ) = EpubTouchNavigation.resolve(
        downX = downX,
        downY = downY,
        upX = upX,
        upY = upY,
        viewportWidth = width,
        touchSlop = touchSlop,
    )
}
