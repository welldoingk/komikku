package eu.kanade.tachiyomi.ui.reader.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.TextBookPublication
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.createEpubLocator
import eu.kanade.tachiyomi.ui.reader.epubBlockIdAtProgression
import eu.kanade.tachiyomi.ui.reader.epubScrollProgression
import eu.kanade.tachiyomi.ui.reader.epubTextBlocks
import eu.kanade.tachiyomi.ui.reader.loader.EpubAssetPath
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Fail-closed text-book viewer. The existing reader navigator treats each spine section as a page.
 */
class EpubTextViewer(
    private val activity: ReaderActivity,
) : Viewer {

    private val root = FrameLayout(activity)
    private val state = EpubTextViewerState()
    private var chapters: ViewerChapters? = null
    private var assetLoader: EpubAssetLoaderController? = null
    private var restoredFragment: String? = null
    private var pendingNavigation: PendingNavigation? = null
    private var currentNavigation: PendingNavigation? = null
    private var pendingScrollPersistence: Runnable? = null
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchTracking = false
    private lateinit var webView: LocatorWebView

    init {
        createWebView()
    }

    internal val tableOfContents: List<EpubTocEntry>
        get() = chapters?.currChapter?.bookLoader?.publication?.let(::epubTocEntries).orEmpty()

    override fun getView(): View = root

    override fun destroy() {
        persistCurrentLocation()
        root.removeView(webView)
        webView.destroy()
    }

    override fun setChapters(chapters: ViewerChapters) {
        persistCurrentLocation()
        this.chapters = chapters
        assetLoader = EpubAssetLoaderController(chapters.currChapter)
        val loader = chapters.currChapter.bookLoader ?: return
        val publication = loader.publication ?: return
        val sectionCount = loader.sectionCount
        val restored = activity.restoreEpubLocator(chapters.currChapter, publication, loader.sections)
        val requestedSection = (restored?.sectionIndex ?: chapters.currChapter.requestedPage)
            .coerceIn(0, sectionCount - 1)
        restoredFragment = restored?.fragment
        state.selectSection(requestedSection, sectionCount)
        renderCurrentSection(notifyReader = true)
    }

    override fun moveToPage(page: ReaderPage) {
        val currentChapter = chapters?.currChapter ?: return
        if (page.chapter !== currentChapter) return
        selectSection(page.index)
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> selectNextSection()
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> selectPreviousSection()
            KeyEvent.KEYCODE_MENU -> {
                activity.toggleMenu()
                true
            }
            else -> false
        }
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    internal fun selectTocSection(sectionId: String): Boolean {
        val loader = chapters?.currChapter?.bookLoader ?: return false
        val index = loader.publication?.spine?.indexOfFirst { it.id == sectionId } ?: return false
        return index >= 0 && selectSection(index)
    }

    private fun selectNextSection(): Boolean {
        val sectionCount = chapters?.currChapter?.bookLoader?.sectionCount ?: return false
        persistCurrentLocation()
        if (!state.moveNext(sectionCount)) return false
        restoredFragment = null
        renderCurrentSection(notifyReader = true)
        return true
    }

    private fun selectPreviousSection(): Boolean {
        val sectionCount = chapters?.currChapter?.bookLoader?.sectionCount ?: return false
        persistCurrentLocation()
        if (!state.movePrevious(sectionCount)) return false
        restoredFragment = null
        renderCurrentSection(notifyReader = true)
        return true
    }

    private fun selectSection(index: Int): Boolean {
        val sectionCount = chapters?.currChapter?.bookLoader?.sectionCount ?: return false
        persistCurrentLocation()
        if (!state.selectSection(index, sectionCount)) return false
        restoredFragment = null
        renderCurrentSection(notifyReader = true)
        return true
    }

    private fun renderCurrentSection(notifyReader: Boolean) {
        val chapter = chapters?.currChapter ?: return
        val loader = chapter.bookLoader ?: return
        val publication = loader.publication ?: return
        val section = loader.moveToSection(state.currentSectionIndex)
        val fragment = restoredFragment
        val sectionUrl = EpubAssetPath.sectionDocumentUrl(
            publication.fingerprint.value,
            section.sectionId,
            fragment,
        )
        currentNavigation = null
        pendingNavigation = PendingNavigation(
            chapter,
            publication,
            section,
            state.currentSectionIndex,
            loader.sectionCount,
            sectionUrl,
        )
        webView.loadUrl(sectionUrl)
        if (notifyReader) {
            chapter.pages?.getOrNull(state.currentSectionIndex)?.let(activity::onPageSelected)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createWebView() {
        webView = LocatorWebView(activity).apply {
            EpubWebViewPolicy.configure(settings)
            webViewClient = ReaderWebViewClient()
            setDownloadListener { _, _, _, _, _ -> }
            setOnScrollChangeListener { _, _, _, _, _ -> scheduleScrollPersistence() }
            setOnTouchListener { _, event ->
                handleTouchEvent(event)
                false
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(webView)
    }

    private fun scheduleScrollPersistence() {
        val navigation = currentNavigation ?: return
        pendingScrollPersistence?.let(root::removeCallbacks)
        val action = Runnable {
            pendingScrollPersistence = null
            persistNavigation(navigation, webView.currentProgression())
        }
        pendingScrollPersistence = action
        root.postDelayed(action, SCROLL_PERSIST_DEBOUNCE_MILLIS)
    }

    private fun persistCurrentLocation() {
        pendingScrollPersistence?.let(root::removeCallbacks)
        pendingScrollPersistence = null
        currentNavigation?.let { persistNavigation(it, webView.currentProgression()) }
    }

    private fun persistNavigation(navigation: PendingNavigation, progression: Double) {
        val blockId = epubBlockIdAtProgression(
            blocks = epubTextBlocks(navigation.section.html),
            progression = progression,
        )
        restoredFragment = blockId?.let { "#$it" }
        activity.persistEpubLocator(
            chapter = navigation.chapter,
            publication = navigation.publication,
            locator = createEpubLocator(
                section = navigation.section,
                sectionIndex = navigation.sectionIndex,
                sectionCount = navigation.sectionCount,
                blockId = blockId,
                progression = progression,
                restoredFragment = restoredFragment,
            ),
            sectionIndex = navigation.sectionIndex,
            sectionCount = navigation.sectionCount,
        )
    }

    private fun handleTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchTracking = true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchTracking) return
                touchTracking = false
                when (
                    EpubTouchNavigation.resolve(
                        downX = touchDownX,
                        downY = touchDownY,
                        upX = event.x,
                        upY = event.y,
                        viewportWidth = webView.width.toFloat(),
                        touchSlop = touchSlop,
                    )
                ) {
                    EpubTouchAction.Previous -> selectPreviousSection()
                    EpubTouchAction.Menu -> activity.toggleMenu()
                    EpubTouchAction.Next -> selectNextSection()
                    EpubTouchAction.None -> Unit
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> touchTracking = false
        }
    }

    private inner class ReaderWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !EpubAssetPath.isSectionDocumentUrl(request.url.toString())

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            !EpubAssetPath.isSectionDocumentUrl(url)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            assetLoader?.shouldInterceptRequest(request.url) ?: EpubAssetLoaderController.notFound()

        @Deprecated("Deprecated in Java")
        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse =
            assetLoader?.shouldInterceptRequest(android.net.Uri.parse(url))
                ?: EpubAssetLoaderController.notFound()

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            persistCurrentLocation()
            state.markRendererRecovery()
            root.removeView(view)
            view.destroy()
            createWebView()
            renderCurrentSection(notifyReader = false)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            pendingNavigation?.let { navigation ->
                if (url != navigation.baseUrl) return
                currentNavigation = navigation
                persistNavigation(navigation, webView.currentProgression())
            }
            pendingNavigation = null
        }
    }

    private data class PendingNavigation(
        val chapter: ReaderChapter,
        val publication: TextBookPublication,
        val section: BookSectionContent,
        val sectionIndex: Int,
        val sectionCount: Int,
        val baseUrl: String,
    )

    private class LocatorWebView(context: Context) : WebView(context) {
        fun currentProgression(): Double =
            epubScrollProgression(scrollY, computeVerticalScrollRange())
    }

    private companion object {
        const val SCROLL_PERSIST_DEBOUNCE_MILLIS = 400L
    }
}

internal data class EpubTocEntry(
    val sectionId: String,
    val title: String,
)

internal fun epubTocEntries(publication: TextBookPublication): List<EpubTocEntry> =
    publication.tableOfContents.map { section ->
        EpubTocEntry(
            sectionId = section.id,
            title = section.title?.takeIf(String::isNotBlank) ?: section.id,
        )
    }
