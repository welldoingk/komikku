package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.TextBookSource
import eu.kanade.tachiyomi.source.model.BookResourceContent
import eu.kanade.tachiyomi.source.model.BookResourceRequest
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.BookSectionRequest
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.TextBookPublication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.manga.model.Manga
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads and owns the ordered sections for a text-book chapter.
 */
class BookLoader(
    private val source: TextBookSource,
    private val manga: Manga,
    private val chapter: SChapter,
) {

    private val loadMutex = Mutex()
    private val recycled = AtomicBoolean(false)
    private val activeLoadJob = AtomicReference<Job?>(null)

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val stateFlow = mutableState.asStateFlow()

    val state: State
        get() = mutableState.value

    val isRecycled: Boolean
        get() = recycled.get()

    val sectionCount: Int
        get() = (state as? State.Loaded)?.sections?.size ?: 0

    val currentSectionIndex: Int?
        get() = (state as? State.Loaded)?.currentSectionIndex

    val currentSection: BookSectionContent?
        get() = (state as? State.Loaded)?.currentSection

    val publication: TextBookPublication?
        get() = (state as? State.Loaded)?.publication

    val sections: List<BookSectionContent>
        get() = (state as? State.Loaded)?.sections.orEmpty()

    suspend fun load(): List<BookSectionContent> = loadMutex.withLock {
        (state as? State.Loaded)?.let { return@withLock it.sections }
        throwIfRecycled()

        mutableState.value = State.Loading
        val loadJob = currentCoroutineContext()[Job]
        activeLoadJob.set(loadJob)
        try {
            throwIfRecycled()
            val content = source.textBookContent
            val publication = content.getTextBookPublication(manga.toSManga(), chapter)
            if (publication.spine.isEmpty()) {
                throw BookLoadException(BookLoadError.EmptyPublication)
            }

            val sections = publication.spine.map { expected ->
                throwIfRecycled()
                val sectionContent = content.getTextBookSection(
                    publication,
                    BookSectionRequest(
                        fingerprint = publication.fingerprint,
                        sectionId = expected.id,
                        chapterId = publication.chapterId,
                    ),
                )
                if (sectionContent.sectionId != expected.id || sectionContent.href != expected.href) {
                    throw BookLoadException(
                        BookLoadError.InvalidSection(
                            expectedSectionId = expected.id,
                            actualSectionId = sectionContent.sectionId,
                            expectedHref = expected.href,
                            actualHref = sectionContent.href,
                        ),
                    )
                }
                sectionContent.copy(
                    html = EpubHtmlSanitizer.sanitize(
                        html = sectionContent.html,
                        fingerprint = publication.fingerprint.value,
                        section = expected,
                    ),
                )
            }
            throwIfRecycled()

            mutableState.value = State.Loaded(
                publication = publication,
                sections = sections,
                currentSectionIndex = 0,
            )
            sections
        } catch (error: CancellationException) {
            if (isRecycled) {
                mutableState.value = State.Error(BookLoadError.Recycled)
                throw BookLoadCancellationException(BookLoadError.Recycled)
            }
            mutableState.value = State.Idle
            throw error
        } catch (error: BookLoadException) {
            mutableState.value = State.Error(error.reason)
            throw error
        } catch (error: Exception) {
            val reason = BookLoadError.SourceContract(error)
            mutableState.value = State.Error(reason)
            throw BookLoadException(reason)
        } finally {
            activeLoadJob.compareAndSet(loadJob, null)
        }
    }

    fun moveToSection(index: Int): BookSectionContent {
        val loaded = state as? State.Loaded
            ?: throw BookLoadException(BookLoadError.NotLoaded)
        val section = loaded.sections.getOrNull(index)
            ?: throw BookLoadException(BookLoadError.InvalidSectionIndex(index, loaded.sections.size))
        mutableState.value = loaded.copy(currentSectionIndex = index)
        return section
    }

    suspend fun loadResource(sectionId: String, href: String): BookResourceContent {
        val loaded = state as? State.Loaded ?: throw BookLoadException(BookLoadError.NotLoaded)
        val request = BookResourceRequest(
            fingerprint = loaded.publication.fingerprint,
            chapterId = loaded.publication.chapterId,
            sectionId = sectionId,
            href = href,
        )
        val expected = try {
            loaded.publication.requireResource(request)
        } catch (error: IllegalArgumentException) {
            throw BookLoadException(
                BookLoadError.InvalidResource(sectionId, href, error.message ?: "resource is not allowlisted"),
            )
        }

        val content = try {
            source.textBookContent.getTextBookResource(loaded.publication, request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw BookLoadException(BookLoadError.SourceContract(error))
        }

        val sameHref = runCatching {
            URI(content.href).normalize() == URI(expected.href).normalize()
        }.getOrDefault(false)
        val sameMediaType = content.mediaType.substringBefore(';').trim()
            .equals(expected.mediaType.substringBefore(';').trim(), ignoreCase = true)
        if (!sameHref || !sameMediaType || content.bytes.isEmpty()) {
            throw BookLoadException(
                BookLoadError.InvalidResource(sectionId, href, "source returned malformed resource content"),
            )
        }
        return content
    }

    fun recycle() {
        if (!recycled.compareAndSet(false, true)) return

        mutableState.value = State.Error(BookLoadError.Recycled)
        activeLoadJob.getAndSet(null)?.cancel(BookLoadCancellationException(BookLoadError.Recycled))
    }

    private fun throwIfRecycled() {
        if (isRecycled) {
            throw BookLoadCancellationException(BookLoadError.Recycled)
        }
    }

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Loaded(
            val publication: TextBookPublication,
            val sections: List<BookSectionContent>,
            val currentSectionIndex: Int,
        ) : State {
            val currentSection: BookSectionContent
                get() = sections[currentSectionIndex]
        }
        data class Error(val reason: BookLoadError) : State
    }
}

sealed interface BookLoadError {
    data class SourceContract(val cause: Throwable) : BookLoadError
    data object EmptyPublication : BookLoadError
    data class InvalidSection(
        val expectedSectionId: String,
        val actualSectionId: String,
        val expectedHref: String,
        val actualHref: String,
    ) : BookLoadError
    data class InvalidSectionIndex(val index: Int, val sectionCount: Int) : BookLoadError
    data class InvalidResource(
        val sectionId: String,
        val href: String,
        val detail: String,
    ) : BookLoadError
    data object NotLoaded : BookLoadError
    data object Recycled : BookLoadError
}

class BookLoadException(
    val reason: BookLoadError,
) : Exception(reason.message(), (reason as? BookLoadError.SourceContract)?.cause)

class BookLoadCancellationException(
    val reason: BookLoadError.Recycled,
) : CancellationException(reason.message())

private fun BookLoadError.message(): String = when (this) {
    is BookLoadError.SourceContract -> "Text-book source contract failed"
    BookLoadError.EmptyPublication -> "Text-book publication has no sections"
    is BookLoadError.InvalidSection -> "Text-book source returned a section that does not match the spine"
    is BookLoadError.InvalidSectionIndex -> "Text-book section index $index is outside 0 until $sectionCount"
    is BookLoadError.InvalidResource -> "Text-book resource is unavailable"
    BookLoadError.NotLoaded -> "Text-book publication is not loaded"
    BookLoadError.Recycled -> "Text-book loader was recycled"
}
