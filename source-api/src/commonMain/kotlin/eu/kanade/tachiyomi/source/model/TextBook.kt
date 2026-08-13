package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable
import java.net.URI

object TextBookContract {
    const val CURRENT_VERSION = 2
}

@Serializable
@JvmInline
value class BookChapterId(val value: String) {
    init {
        require(value.isNotBlank()) { "Book chapter identity must not be blank" }
    }
}

private const val UNBOUND_BOOK_CHAPTER_ID = "\u0000"
private val UnboundBookChapterId = BookChapterId(UNBOUND_BOOK_CHAPTER_ID)

@Serializable
@JvmInline
value class BookFingerprint(val value: String) {
    init {
        require(value.isNotBlank()) { "Publication fingerprint must not be blank" }
    }
}

@Serializable
data class BookResource(
    val href: String,
    val mediaType: String,
) {
    init {
        require(href.isNotBlank()) { "Resource href must not be blank" }
        require(mediaType.isNotBlank()) { "Resource media type must not be blank" }
    }
}

@Serializable
data class BookSection(
    val id: String,
    val href: String,
    val title: String? = null,
    val resources: List<BookResource> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Section id must not be blank" }
        require(href.isNotBlank()) { "Section href must not be blank" }
    }
}

@Serializable
data class TextBookPublication(
    val contractVersion: Int = TextBookContract.CURRENT_VERSION,
    val fingerprint: BookFingerprint,
    val spine: List<BookSection>,
    val tableOfContents: List<BookSection>,
    val chapterId: BookChapterId = UnboundBookChapterId,
) {
    init {
        require(contractVersion > 0) { "Contract version must be positive" }
        require(spine.isNotEmpty()) { "Publication spine must not be empty" }
        require(spine.map(BookSection::id).distinct().size == spine.size) {
            "Publication spine section ids must be unique"
        }
        require(tableOfContents.all { toc -> spine.any { it.id == toc.id } }) {
            "Table of contents may only reference spine sections"
        }
    }

    fun requireSection(request: BookSectionRequest): BookSection {
        requireCurrentFingerprint(request.fingerprint)
        requireChapter(request.chapterId)
        return spine.firstOrNull { it.id == request.sectionId }
            ?: throw IllegalArgumentException("Unknown publication section: ${request.sectionId}")
    }

    fun requireResource(request: BookResourceRequest): BookResource {
        val section = requireSection(BookSectionRequest(request.fingerprint, request.chapterId, request.sectionId))
        val requestedUri = request.href.toResourceUri()
        val sectionUri = section.href.toAbsoluteUri("Section")

        require(requestedUri.isAbsolute) { "Resource href must be absolute" }
        require(requestedUri.sameOriginAs(sectionUri)) { "Resource origin does not match its section" }

        return section.resources.firstOrNull { resource ->
            resource.href.toAbsoluteUri("Allowlisted resource").normalize() == requestedUri.normalize()
        } ?: throw IllegalArgumentException("Resource is not allowlisted for section: ${request.sectionId}")
    }

    private fun requireCurrentFingerprint(requested: BookFingerprint) {
        check(requested == fingerprint) { "Publication fingerprint is stale" }
    }

    private fun requireChapter(requested: BookChapterId) {
        require(chapterId.value != UNBOUND_BOOK_CHAPTER_ID) {
            "Publication chapter identity is missing"
        }
        require(requested.value != UNBOUND_BOOK_CHAPTER_ID) {
            "Request chapter identity is missing"
        }
        require(requested == chapterId) {
            "Request chapter identity does not match the publication"
        }
    }
}

@Serializable
data class BookSectionRequest(
    val fingerprint: BookFingerprint,
    val chapterId: BookChapterId = UnboundBookChapterId,
    val sectionId: String,
) {
    constructor(fingerprint: BookFingerprint, sectionId: String) : this(
        fingerprint,
        UnboundBookChapterId,
        sectionId,
    )

    init {
        require(sectionId.isNotBlank()) { "Section id must not be blank" }
    }
}

@Serializable
data class BookResourceRequest(
    val fingerprint: BookFingerprint,
    val chapterId: BookChapterId = UnboundBookChapterId,
    val sectionId: String,
    val href: String,
) {
    constructor(fingerprint: BookFingerprint, sectionId: String, href: String) : this(
        fingerprint,
        UnboundBookChapterId,
        sectionId,
        href,
    )

    init {
        require(sectionId.isNotBlank()) { "Section id must not be blank" }
        require(href.isNotBlank()) { "Resource href must not be blank" }
    }
}

@Serializable
data class BookSectionContent(
    val sectionId: String,
    val href: String,
    val html: String,
)

@Serializable
data class BookResourceContent(
    val href: String,
    val mediaType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is BookResourceContent &&
            href == other.href &&
            mediaType == other.mediaType &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * href.hashCode() + mediaType.hashCode()) + bytes.contentHashCode()
}

private fun String.toResourceUri(): URI {
    require('\\' !in this) { "Resource href must not contain backslashes" }
    val uri = runCatching { URI(this) }
        .getOrElse { throw IllegalArgumentException("Invalid resource href", it) }
    require(uri.path.orEmpty().split('/').none { it == ".." }) {
        "Resource href must not traverse parent paths"
    }
    return uri
}

private fun String.toAbsoluteUri(label: String): URI {
    val uri = toResourceUri()
    require(uri.isAbsolute && uri.host != null) { "$label href must be an absolute URL" }
    return uri
}

private fun URI.sameOriginAs(other: URI): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}
