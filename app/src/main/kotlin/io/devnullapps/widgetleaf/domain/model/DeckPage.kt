package io.devnullapps.widgetleaf.domain.model

data class DeckPage(
    val pageIndex: Int,
    val customLabel: String? = null
) {
    val defaultNumberLabel: String
        get() = (pageIndex + 1).toString()

    val displayLabel: String
        get() = customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: defaultNumberLabel
}
