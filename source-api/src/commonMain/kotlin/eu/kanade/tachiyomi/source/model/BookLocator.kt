package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable

@Serializable
data class BookTextQuote(
    val exact: String,
    val prefix: String? = null,
    val suffix: String? = null,
) {
    init {
        require(exact.isNotBlank()) { "Quoted context must include exact text" }
    }
}

@Serializable
@ConsistentCopyVisibility
data class BookLocator private constructor(
    val href: String,
    val blockId: String? = null,
    val cfi: String? = null,
    val cssSelector: String? = null,
    val textQuote: BookTextQuote? = null,
    val progression: Double? = null,
    val totalProgression: Double? = null,
) {
    init {
        require(href.isNotBlank()) { "Locator href must not be blank" }
        require(blockId != null || cfi != null || cssSelector != null || textQuote != null) {
            "Locator requires a block ID, CFI, CSS selector, or quoted-context fallback"
        }
        require(progression == null || (progression.isFinite() && progression in 0.0..1.0)) {
            "Locator progression must be between zero and one"
        }
        require(totalProgression == null || (totalProgression.isFinite() && totalProgression in 0.0..1.0)) {
            "Locator total progression must be between zero and one"
        }
    }

    companion object {
        fun create(
            href: String,
            blockId: String? = null,
            cfi: String? = null,
            cssSelector: String? = null,
            textQuote: BookTextQuote? = null,
            progression: Double? = null,
            totalProgression: Double? = null,
        ) = BookLocator(
            href = href,
            blockId = blockId,
            cfi = cfi,
            cssSelector = cssSelector,
            textQuote = textQuote,
            progression = progression?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0),
            totalProgression = totalProgression?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0),
        )
    }
}
