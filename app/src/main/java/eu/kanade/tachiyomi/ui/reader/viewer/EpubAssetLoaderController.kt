package eu.kanade.tachiyomi.ui.reader.viewer

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.TextBookPublication
import eu.kanade.tachiyomi.ui.reader.loader.BookLoader
import eu.kanade.tachiyomi.ui.reader.loader.EpubAssetPath
import eu.kanade.tachiyomi.ui.reader.loader.EpubHtmlDocumentComposer
import eu.kanade.tachiyomi.ui.reader.loader.EpubHtmlSanitizer
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * Serves only loaded section documents and source-allowlisted resources from the app asset origin.
 */
internal class EpubAssetLoaderController(
    private val chapter: ReaderChapter,
) {

    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(EpubAssetPath.HOST)
        .setHttpAllowed(false)
        .addPathHandler("/${EpubAssetPath.PREFIX}/", LocalPathHandler())
        .build()

    fun shouldInterceptRequest(uri: Uri): WebResourceResponse {
        if (EpubAssetPath.parseUrl(uri.toString()) == null) return notFound()
        return assetLoader.shouldInterceptRequest(uri) ?: notFound()
    }

    private inner class LocalPathHandler : WebViewAssetLoader.PathHandler {

        override fun handle(path: String): WebResourceResponse {
            val loader = chapter.bookLoader ?: return notFound()
            val publication = loader.publication ?: return notFound()
            EpubAssetPath.parseSectionDocument(path)?.let { request ->
                val content = sectionDocumentContent(request, publication, loader.sections) ?: return notFound()
                return successful(content)
            }
            val request = EpubAssetPath.parse(path) ?: return notFound()
            if (request.publicationPath != EpubHtmlSanitizer.publicationPath(publication.fingerprint.value)) {
                return notFound()
            }
            val section = publication.spine.firstOrNull {
                EpubHtmlSanitizer.sectionPath(it.id) == request.sectionPath
            } ?: return notFound()
            val resource = section.resources.firstOrNull {
                EpubHtmlSanitizer.resourceToken(it.href) == request.resourceToken
            } ?: return notFound()
            val mediaType = resource.mediaType.substringBefore(';').trim().lowercase()
            if (mediaType !in SAFE_MEDIA_TYPES) return notFound()

            val content = runCatching {
                runBlocking { loader.loadResource(section.id, resource.href) }
            }.getOrNull() ?: return notFound()
            val bytes = if (mediaType == "text/css") {
                EpubHtmlSanitizer.sanitizeCss(
                    css = content.bytes.toString(Charsets.UTF_8),
                    fingerprint = publication.fingerprint.value,
                    section = section,
                    sourceHref = resource.href,
                ).toByteArray(Charsets.UTF_8)
            } else {
                content.bytes
            }
            return successful(mediaType, "UTF-8", bytes)
        }
    }

    internal companion object {
        val SAFE_MEDIA_TYPES = setOf(
            "text/css",
            "image/avif",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/svg+xml",
            "image/webp",
            "audio/mpeg",
            "audio/mp4",
            "audio/ogg",
            "audio/wav",
            "audio/webm",
        )

        internal fun sectionDocumentContent(
            request: EpubAssetPath.SectionDocumentRequest,
            publication: TextBookPublication,
            sections: List<BookSectionContent>,
        ): EpubAssetContent? {
            if (request.publicationPath != EpubHtmlSanitizer.publicationPath(publication.fingerprint.value)) {
                return null
            }
            val section = publication.spine.firstOrNull {
                EpubHtmlSanitizer.sectionPath(it.id) == request.sectionPath
            } ?: return null
            val content = sections.firstOrNull { it.sectionId == section.id && it.href == section.href } ?: return null
            return EpubAssetContent(
                mediaType = "text/html",
                encoding = "UTF-8",
                headers = NO_STORE_HEADERS,
                bytes = EpubHtmlDocumentComposer.compose(content.html).toByteArray(Charsets.UTF_8),
            )
        }

        internal fun successful(mediaType: String, encoding: String?, bytes: ByteArray): WebResourceResponse =
            successful(EpubAssetContent(mediaType, encoding, NO_STORE_HEADERS, bytes))

        private fun successful(content: EpubAssetContent): WebResourceResponse =
            WebResourceResponse(
                content.mediaType,
                content.encoding,
                200,
                "OK",
                content.headers,
                ByteArrayInputStream(content.bytes),
            )

        internal fun notFound(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )

        private val NO_STORE_HEADERS = mapOf("Cache-Control" to "no-store")
    }
}

internal data class EpubAssetContent(
    val mediaType: String,
    val encoding: String?,
    val headers: Map<String, String>,
    val bytes: ByteArray,
)
