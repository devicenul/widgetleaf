package io.devnullapps.widgetleaf.domain.usecase

import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import kotlin.math.ceil

data class WidgetSpanResult(
    val defaultSpanX: Int,
    val defaultSpanY: Int,
    val minSpanX: Int,
    val minSpanY: Int,
    val maxSpanX: Int,
    val maxSpanY: Int
)

class WidgetSpanCalculator {

    fun calculateSpans(
        targetCellWidth: Int,
        targetCellHeight: Int,
        minWidthDp: Float,
        minHeightDp: Float,
        minResizeWidthDp: Float,
        minResizeHeightDp: Float,
        maxResizeWidthDp: Float,
        maxResizeHeightDp: Float,
        resizeMode: Int,
        profile: DesktopGridProfile
    ): WidgetSpanResult {
        val defaultSpanX = if (targetCellWidth > 0) {
            targetCellWidth.coerceIn(1, profile.columns)
        } else {
            dpToSpan(minWidthDp, profile.cellWidthDp, profile.gutterDp).coerceIn(1, profile.columns)
        }

        val defaultSpanY = if (targetCellHeight > 0) {
            targetCellHeight.coerceIn(1, profile.rows)
        } else {
            dpToSpan(minHeightDp, profile.cellHeightDp, profile.gutterDp).coerceIn(1, profile.rows)
        }

        val canResizeX = (resizeMode and PlacedWidget.RESIZE_HORIZONTAL) != 0
        val canResizeY = (resizeMode and PlacedWidget.RESIZE_VERTICAL) != 0

        val (minSpanX, maxSpanX) = if (!canResizeX) {
            Pair(defaultSpanX, defaultSpanX)
        } else {
            val minX = when {
                minResizeWidthDp > 0f -> dpToSpan(minResizeWidthDp, profile.cellWidthDp, profile.gutterDp)
                minWidthDp > 0f -> dpToSpan(minWidthDp, profile.cellWidthDp, profile.gutterDp)
                else -> 1
            }.coerceIn(1, defaultSpanX)

            val maxX = if (maxResizeWidthDp > 0f) {
                dpToSpan(maxResizeWidthDp, profile.cellWidthDp, profile.gutterDp).coerceIn(defaultSpanX, profile.columns)
            } else {
                profile.columns
            }
            Pair(minX, maxX)
        }

        val (minSpanY, maxSpanY) = if (!canResizeY) {
            Pair(defaultSpanY, defaultSpanY)
        } else {
            val minY = when {
                minResizeHeightDp > 0f -> dpToSpan(minResizeHeightDp, profile.cellHeightDp, profile.gutterDp)
                minHeightDp > 0f -> dpToSpan(minHeightDp, profile.cellHeightDp, profile.gutterDp)
                else -> 1
            }.coerceIn(1, defaultSpanY)

            val maxY = if (maxResizeHeightDp > 0f) {
                dpToSpan(maxResizeHeightDp, profile.cellHeightDp, profile.gutterDp).coerceIn(defaultSpanY, profile.rows)
            } else {
                profile.rows
            }
            Pair(minY, maxY)
        }

        return WidgetSpanResult(
            defaultSpanX = defaultSpanX,
            defaultSpanY = defaultSpanY,
            minSpanX = minSpanX,
            minSpanY = minSpanY,
            maxSpanX = maxSpanX,
            maxSpanY = maxSpanY
        )
    }

    private fun dpToSpan(dp: Float, cellSizeDp: Float, gutterDp: Float): Int {
        if (dp <= 0f) return 1
        val cellAndGutter = cellSizeDp + gutterDp
        if (cellAndGutter <= 0f) return 1
        return ceil((dp + gutterDp - 0.05f) / cellAndGutter).toInt().coerceAtLeast(1)
    }

    companion object {
        private val defaultCalculator = WidgetSpanCalculator()

        fun calculateSpans(
            targetCellWidth: Int,
            targetCellHeight: Int,
            minWidthDp: Float,
            minHeightDp: Float,
            minResizeWidthDp: Float,
            minResizeHeightDp: Float,
            maxResizeWidthDp: Float,
            maxResizeHeightDp: Float,
            resizeMode: Int,
            profile: DesktopGridProfile
        ): WidgetSpanResult = defaultCalculator.calculateSpans(
            targetCellWidth = targetCellWidth,
            targetCellHeight = targetCellHeight,
            minWidthDp = minWidthDp,
            minHeightDp = minHeightDp,
            minResizeWidthDp = minResizeWidthDp,
            minResizeHeightDp = minResizeHeightDp,
            maxResizeWidthDp = maxResizeWidthDp,
            maxResizeHeightDp = maxResizeHeightDp,
            resizeMode = resizeMode,
            profile = profile
        )
    }
}
