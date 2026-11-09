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

    fun resolve(
        screenWidthDp: Float,
        screenHeightDp: Float,
        useCenteredBentoCanvas: Boolean = true
    ): DesktopGridProfile {
        val effectiveCanvasWidthDp = if (useCenteredBentoCanvas) {
            screenWidthDp.coerceAtMost(MAX_CANVAS_WIDTH_DP)
        } else {
            screenWidthDp
        }

        val usableWidthDp = (effectiveCanvasWidthDp - (2 * DEFAULT_MARGIN_DP)).coerceAtLeast(MIN_COLUMNS * 80f)
        val rawColumns = ((usableWidthDp + DEFAULT_GUTTER_DP) / (DEFAULT_CELL_WIDTH_DP + DEFAULT_GUTTER_DP)).toInt()
        val columns = rawColumns.coerceAtLeast(MIN_COLUMNS)
        val fittedCellWidthDp = ((usableWidthDp - (columns - 1) * DEFAULT_GUTTER_DP) / columns).coerceAtLeast(80f)

        val usableHeightDp = (screenHeightDp - DEFAULT_MARGIN_DP - VERTICAL_RESERVED_DP).coerceAtLeast(MIN_ROWS * 72f)
        val rawRows = ((usableHeightDp + DEFAULT_GUTTER_DP) / (DEFAULT_CELL_HEIGHT_DP + DEFAULT_GUTTER_DP)).toInt()
        val rows = rawRows.coerceAtLeast(MIN_ROWS)
        val fittedCellHeightDp = ((usableHeightDp - (rows - 1) * DEFAULT_GUTTER_DP) / rows).coerceAtLeast(72f)

        return DesktopGridProfile(
            columns = columns,
            rows = rows,
            cellWidthDp = fittedCellWidthDp,
            cellHeightDp = fittedCellHeightDp,
            gutterDp = DEFAULT_GUTTER_DP,
            marginDp = DEFAULT_MARGIN_DP,
            maxCanvasWidthDp = MAX_CANVAS_WIDTH_DP,
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
        return resolve(
            windowMetrics = metrics,
            density = density,
            useCenteredBentoCanvas = useCenteredBentoCanvas
        )
    }
}
