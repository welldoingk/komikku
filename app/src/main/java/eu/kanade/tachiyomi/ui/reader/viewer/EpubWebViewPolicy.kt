package eu.kanade.tachiyomi.ui.reader.viewer

import android.webkit.WebSettings
import eu.kanade.tachiyomi.ui.reader.loader.EpubAssetPath
import java.net.URI

/**
 * Navigation policy shared by the WebView client and host-side security tests.
 */
internal object EpubWebViewPolicy {

    fun configure(settings: WebSettings) {
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.blockNetworkLoads = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
    }

    fun allowsLocalNavigation(url: String): Boolean = localUri(url) != null

    fun resourceRequest(url: String): EpubAssetPath.ResourceRequest? {
        val uri = localUri(url) ?: return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        return EpubAssetPath.parse(uri.path.removePrefix("/${EpubAssetPath.PREFIX}/"))
    }

    private fun localUri(url: String): URI? = runCatching {
        URI(url).takeIf { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(EpubAssetPath.HOST, ignoreCase = true) &&
                uri.path.startsWith("/${EpubAssetPath.PREFIX}/")
        }
    }.getOrNull()
}

internal class EpubTextViewerState {

    var currentSectionIndex: Int = 0
        private set

    var rendererRecoveryCount: Int = 0
        private set

    fun selectSection(index: Int, sectionCount: Int): Boolean {
        if (index !in 0 until sectionCount) return false
        currentSectionIndex = index
        return true
    }

    fun moveNext(sectionCount: Int): Boolean = selectSection(currentSectionIndex + 1, sectionCount)

    fun movePrevious(sectionCount: Int): Boolean = selectSection(currentSectionIndex - 1, sectionCount)

    fun markRendererRecovery() {
        rendererRecoveryCount++
    }
}
