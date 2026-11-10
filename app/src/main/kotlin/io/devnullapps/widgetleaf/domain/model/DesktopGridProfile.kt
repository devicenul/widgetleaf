package io.devnullapps.widgetleaf.domain.model

data class DesktopGridProfile(
    val columns: Int = 8,
    val rows: Int = 4,
    val cellWidthDp: Float = 140f,
    val cellHeightDp: Float = 140f,
    val gutterDp: Float = 16f,
    val marginDp: Float = 36f,
    val topMarginDp: Float = marginDp,
    val maxCanvasWidthDp: Float = 1440f,
    val availableWidthDp: Float = 1440f,
    val availableHeightDp: Float = 900f
) {
    fun widthDpForSpan(spanX: Int): Float {
        val clamped = spanX.coerceAtLeast(1)
        return clamped * cellWidthDp + (clamped - 1) * gutterDp
    }

    fun heightDpForSpan(spanY: Int): Float {
        val clamped = spanY.coerceAtLeast(1)
        return clamped * cellHeightDp + (clamped - 1) * gutterDp
    }
}
