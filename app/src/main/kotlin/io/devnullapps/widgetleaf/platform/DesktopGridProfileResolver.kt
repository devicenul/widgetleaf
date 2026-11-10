package io.devnullapps.widgetleaf.platform

import android.content.Context
import android.view.WindowMetrics
import android.view.WindowManager
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile

object DesktopGridProfileResolver {

    const val DEFAULT_CELL_WIDTH_DP = 140f
    const val DEFAULT_CELL_HEIGHT_DP = 100f
    const val DEFAULT_GUTTER_DP = 16f
    const val DEFAULT_MARGIN_DP = 36f
    const val MAX_CANVAS_WIDTH_DP = 1440f
    const val VERTICAL_RESERVED_DP = 84f

    const val MIN_COLUMNS = 4
    const val MIN_ROWS = 4
    const val MIN_CELL_WIDTH_DP = 80f
    const val MIN_CELL_HEIGHT_DP = 72f

    fun resolve(
        screenWidthDp: Float,
        screenHeightDp: Float,
        useCenteredBentoCanvas: Boolean = true
    ): DesktopGridProfile {
        val availableWidth = screenWidthDp.coerceAtLeast(MIN_COLUMNS * MIN_CELL_WIDTH_DP)
        val availableHeight = screenHeightDp.coerceAtLeast(MIN_ROWS * MIN_CELL_HEIGHT_DP)

        // Target usable width with base margins
        val targetUsableWidth = (availableWidth - (2 * DEFAULT_MARGIN_DP))
            .coerceAtLeast(MIN_COLUMNS * MIN_CELL_WIDTH_DP + (MIN_COLUMNS - 1) * DEFAULT_GUTTER_DP)

        // Compute number of columns that fit nominal cell width + gutter
        val rawColumns = ((targetUsableWidth + DEFAULT_GUTTER_DP) / (DEFAULT_CELL_WIDTH_DP + DEFAULT_GUTTER_DP)).toInt()
        val columns = rawColumns.coerceAtLeast(MIN_COLUMNS)

        // Stretch cell width to span the usable width evenly
        val computedCellWidth = (targetUsableWidth - (columns - 1) * DEFAULT_GUTTER_DP) / columns
        val fittedCellWidthDp = computedCellWidth.coerceAtLeast(MIN_CELL_WIDTH_DP)

        // Calculate total grid width from fitted cells and gutters
        val totalGridWidth = columns * fittedCellWidthDp + (columns - 1) * DEFAULT_GUTTER_DP

        // Symmetric horizontal margins: left margin == right margin == (availableWidth - totalGridWidth) / 2
        val symmetricMarginDp = ((availableWidth - totalGridWidth) / 2f).coerceAtLeast(0f)

        // Vertical sizing
        val usableHeightDp = (availableHeight - DEFAULT_MARGIN_DP - VERTICAL_RESERVED_DP)
            .coerceAtLeast(MIN_ROWS * MIN_CELL_HEIGHT_DP)
        val rawRows = ((usableHeightDp + DEFAULT_GUTTER_DP) / (DEFAULT_CELL_HEIGHT_DP + DEFAULT_GUTTER_DP)).toInt()
        val rows = rawRows.coerceAtLeast(MIN_ROWS)
        val fittedCellHeightDp = ((usableHeightDp - (rows - 1) * DEFAULT_GUTTER_DP) / rows)
            .coerceAtLeast(MIN_CELL_HEIGHT_DP)

        return DesktopGridProfile(
            columns = columns,
            rows = rows,
            cellWidthDp = fittedCellWidthDp,
            cellHeightDp = fittedCellHeightDp,
            gutterDp = DEFAULT_GUTTER_DP,
            marginDp = symmetricMarginDp,
            topMarginDp = DEFAULT_MARGIN_DP,
            maxCanvasWidthDp = availableWidth,
            availableWidthDp = screenWidthDp,
            availableHeightDp = screenHeightDp
        )
    }

    fun resolve(
        windowMetrics: WindowMetrics,
        density: Float,
        useCenteredBentoCanvas: Boolean = true
    ): DesktopGridProfile {
        val safeDensity = if (density > 0f) density else 1f
        val screenWidthDp = windowMetrics.bounds.width() / safeDensity
        val screenHeightDp = windowMetrics.bounds.height() / safeDensity
        return resolve(
            screenWidthDp = screenWidthDp,
            screenHeightDp = screenHeightDp,
            useCenteredBentoCanvas = useCenteredBentoCanvas
        )
    }

    fun resolve(
        context: Context,
        useCenteredBentoCanvas: Boolean = true
    ): DesktopGridProfile {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = windowManager.currentWindowMetrics
        val density = context.resources.displayMetrics.density
        val safeDensity = if (density > 0f) density else 1f
        val boundsWidthDp = metrics.bounds.width() / safeDensity
        val boundsHeightDp = metrics.bounds.height() / safeDensity
        val config = context.resources.configuration
        val widthDp = if (boundsWidthDp > 0f) boundsWidthDp else config.screenWidthDp.toFloat()
        val heightDp = if (boundsHeightDp > 0f) boundsHeightDp else config.screenHeightDp.toFloat()
        return resolve(
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            useCenteredBentoCanvas = useCenteredBentoCanvas
        )
    }
}
