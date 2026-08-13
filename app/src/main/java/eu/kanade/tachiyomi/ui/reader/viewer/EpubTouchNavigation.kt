package eu.kanade.tachiyomi.ui.reader.viewer

import kotlin.math.abs

internal enum class EpubTouchAction {
    Previous,
    Menu,
    Next,
    None,
}

internal object EpubTouchNavigation {

    fun resolve(
        downX: Float,
        downY: Float,
        upX: Float,
        upY: Float,
        viewportWidth: Float,
        touchSlop: Float,
    ): EpubTouchAction {
        if (viewportWidth <= 0f) return EpubTouchAction.None

        val deltaX = upX - downX
        val deltaY = upY - downY
        if (abs(deltaX) > touchSlop && abs(deltaX) > abs(deltaY)) {
            return if (deltaX < 0f) EpubTouchAction.Next else EpubTouchAction.Previous
        }
        if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) return EpubTouchAction.None

        return when {
            upX < viewportWidth / 3f -> EpubTouchAction.Previous
            upX > viewportWidth * 2f / 3f -> EpubTouchAction.Next
            else -> EpubTouchAction.Menu
        }
    }
}
