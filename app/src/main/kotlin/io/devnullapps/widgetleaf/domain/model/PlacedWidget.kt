package io.devnullapps.widgetleaf.domain.model

data class PlacedWidget(
    val appWidgetId: Int,
    val providerPackage: String,
    val providerClass: String,
    val pageIndex: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val minSpanX: Int = 1,
    val minSpanY: Int = 1,
    val maxSpanX: Int = 12,
    val maxSpanY: Int = 8,
    val resizeMode: Int = RESIZE_BOTH,
    val isReconfigurable: Boolean = false,
    val label: String = ""
) {
    val bounds: GridBounds
        get() = GridBounds(cellX = cellX, cellY = cellY, spanX = spanX, spanY = spanY)

    companion object {
        const val RESIZE_NONE = 0
        const val RESIZE_HORIZONTAL = 1
        const val RESIZE_VERTICAL = 2
        const val RESIZE_BOTH = 3
    }
}
