package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.BookResourceContent
import eu.kanade.tachiyomi.source.model.BookResourceRequest
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.BookSectionRequest
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.TextBookContract
import eu.kanade.tachiyomi.source.model.TextBookPublication

/**
 * Optional source capability for ordered text-book content.
 *
 * Implementations keep their existing source superclass (for example [online.HttpSource]) and
 * expose a composed [TextBookContent] delegate. The delegate owns the non-overridable validation
 * boundary before any implementation hook can perform I/O.
 */
interface TextBookSource : Source {

    val textBookContractVersion: Int
        get() = TextBookContract.CURRENT_VERSION

    val textBookContent: TextBookContent
}

abstract class TextBookContent {

    abstract suspend fun getTextBookPublication(manga: SManga, chapter: SChapter): TextBookPublication

    suspend fun getTextBookSection(
        publication: TextBookPublication,
        request: BookSectionRequest,
    ): BookSectionContent {
        publication.requireSection(request)
        return getTextBookSectionImpl(request)
    }

    suspend fun getTextBookResource(
        publication: TextBookPublication,
        request: BookResourceRequest,
    ): BookResourceContent {
        publication.requireResource(request)
        return getTextBookResourceImpl(request)
    }

    protected abstract suspend fun getTextBookSectionImpl(request: BookSectionRequest): BookSectionContent

    protected abstract suspend fun getTextBookResourceImpl(request: BookResourceRequest): BookResourceContent
}
