package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.util.AttributeSet
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Implementation of subsampling scale image view that ignores all touch events, because the
 * webtoon viewer handles all the gestures.
 */
class WebtoonSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attrs) {

    // KMK -->
    private val bitmapDrawFilter = PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG, 0)

    override fun onDraw(canvas: Canvas) {
        // Anti-aliased bitmap edges expose the background between adjacent webtoon pages.
        // Keep bitmap filtering enabled so scaling the image content remains smooth.
        val previousDrawFilter = canvas.drawFilter
        canvas.drawFilter = bitmapDrawFilter
        try {
            super.onDraw(canvas)
        } finally {
            canvas.drawFilter = previousDrawFilter
        }
    }
    // KMK <--

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }
}
