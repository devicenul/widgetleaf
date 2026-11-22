package io.devnullapps.widgetleaf.platform

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
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
    private const val MIN_GUTTER_DP = 6f
    private const val MIN_TOP_INSET_GAP_DP = 12f
    private const val COMPACT_TOP_INSET_GAP_DP = 8f
    private const val MIN_SCALED_CELL_DP = 16f

    fun resolve(
        screenWidthDp: Float,
        screenHeightDp: Float,
        useCenteredBentoCanvas: Boolean = true,
        leftInsetDp: Float = 0f,
        topInsetDp: Float = 0f,
        rightInsetDp: Float = 0f,
        bottomInsetDp: Float = 0f
    ): DesktopGridProfile {
        val safeLeftInset = leftInsetDp.coerceAtLeast(0f)
        val safeTopInset = topInsetDp.coerceAtLeast(0f)
        val safeRightInset = rightInsetDp.coerceAtLeast(0f)
        val safeBottomInset = bottomInsetDp.coerceAtLeast(0f)

        val windowWidthDp = screenWidthDp.coerceAtLeast(1f)
        val windowHeightDp = screenHeightDp.coerceAtLeast(1f)
        val safeAreaWidthDp = (windowWidthDp - safeLeftInset - safeRightInset).coerceAtLeast(1f)

        // Centered Bento Grid caps usable canvas width at MAX_CANVAS_WIDTH_DP (1440dp) and centers
        // within the safe horizontal area; when off, fills the full safe area width.
        val effectiveCanvasWidthDp = if (useCenteredBentoCanvas) {
            minOf(safeAreaWidthDp, MAX_CANVAS_WIDTH_DP)
        } else {
            safeAreaWidthDp
        }

        val nominalMinGridWidth = MIN_COLUMNS * MIN_CELL_WIDTH_DP + (MIN_COLUMNS - 1) * DEFAULT_GUTTER_DP // 368dp
        val nominalMinCanvasWidth = nominalMinGridWidth + 2f * DEFAULT_MARGIN_DP // 440dp
        val horizontalMarginBase = when {
            effectiveCanvasWidthDp >= nominalMinCanvasWidth -> DEFAULT_MARGIN_DP
            effectiveCanvasWidthDp >= nominalMinGridWidth + 16f ->
                ((effectiveCanvasWidthDp - nominalMinGridWidth) / 2f).coerceIn(8f, DEFAULT_MARGIN_DP)
            else -> (effectiveCanvasWidthDp * 0.025f).coerceIn(4f, 8f)
        }

        val safeBottomReserved = VERTICAL_RESERVED_DP + safeBottomInset
        val availableAboveHud = (windowHeightDp - safeTopInset - safeBottomReserved).coerceAtLeast(1f)
        val nominalMinGridHeight = MIN_ROWS * MIN_CELL_HEIGHT_DP + (MIN_ROWS - 1) * DEFAULT_GUTTER_DP // 336dp
        val baseTopGap = when {
            availableAboveHud >= nominalMinGridHeight + DEFAULT_MARGIN_DP -> DEFAULT_MARGIN_DP
            availableAboveHud >= nominalMinGridHeight + 8f ->
                (availableAboveHud - nominalMinGridHeight).coerceIn(12f, DEFAULT_MARGIN_DP)
            else -> (availableAboveHud * 0.04f).coerceIn(6f, 12f)
        }
        val topInsetGap = if (availableAboveHud >= nominalMinGridHeight + 8f) {
            MIN_TOP_INSET_GAP_DP
        } else {
            COMPACT_TOP_INSET_GAP_DP
        }
        val topMarginDp = if (safeTopInset > 0f) {
            maxOf(baseTopGap, safeTopInset + topInsetGap)
        } else {
            baseTopGap
        }

        val targetUsableWidth = (effectiveCanvasWidthDp - 2f * horizontalMarginBase).coerceAtLeast(1f)
        val targetUsableHeight = (windowHeightDp - topMarginDp - safeBottomReserved).coerceAtLeast(1f)

        val hRatio = (targetUsableWidth / nominalMinGridWidth).coerceIn(0f, 1f)
        val vRatio = (targetUsableHeight / nominalMinGridHeight).coerceIn(0f, 1f)
        val gutterDp = if (hRatio >= 1f && vRatio >= 1f) {
            DEFAULT_GUTTER_DP
        } else {
            val tightRatio = ((minOf(hRatio, vRatio) - 0.5f) / 0.5f).coerceIn(0f, 1f)
            (MIN_GUTTER_DP + (DEFAULT_GUTTER_DP - MIN_GUTTER_DP) * tightRatio)
                .coerceIn(MIN_GUTTER_DP, DEFAULT_GUTTER_DP)
        }

        // Compute columns & stretch cell width evenly across targetUsableWidth
        val rawColumns = ((targetUsableWidth + gutterDp) / (DEFAULT_CELL_WIDTH_DP + gutterDp)).toInt()
        val columns = rawColumns.coerceAtLeast(MIN_COLUMNS)
        val computedCellWidth = (targetUsableWidth - (columns - 1) * gutterDp) / columns
        val fittedCellWidthDp = if (targetUsableWidth >= nominalMinGridWidth) {
            computedCellWidth.coerceAtLeast(MIN_CELL_WIDTH_DP)
        } else {
            computedCellWidth.coerceAtLeast(MIN_SCALED_CELL_DP)
        }

        val totalGridWidth = columns * fittedCellWidthDp + (columns - 1) * gutterDp
        val symmetricSafeMarginDp = ((safeAreaWidthDp - totalGridWidth) / 2f).coerceAtLeast(0f)
        val marginDp = safeLeftInset + symmetricSafeMarginDp

        // Compute rows & fit cell height cleanly above bottomReserved
        val rawRows = ((targetUsableHeight + gutterDp) / (DEFAULT_CELL_HEIGHT_DP + gutterDp)).toInt()
        val rows = rawRows.coerceAtLeast(MIN_ROWS)
        val computedCellHeight = (targetUsableHeight - (rows - 1) * gutterDp) / rows
        val fittedCellHeightDp = if (targetUsableHeight >= nominalMinGridHeight) {
            computedCellHeight.coerceAtLeast(MIN_CELL_HEIGHT_DP)
        } else {
            computedCellHeight.coerceAtLeast(MIN_SCALED_CELL_DP)
        }

        return DesktopGridProfile(
            columns = columns,
            rows = rows,
            cellWidthDp = fittedCellWidthDp,
            cellHeightDp = fittedCellHeightDp,
            gutterDp = gutterDp,
            marginDp = marginDp,
            topMarginDp = topMarginDp,
            maxCanvasWidthDp = effectiveCanvasWidthDp,
            availableWidthDp = screenWidthDp,
            availableHeightDp = screenHeightDp,
            leftInsetDp = safeLeftInset,
            topInsetDp = safeTopInset,
            rightInsetDp = safeRightInset,
            bottomInsetDp = safeBottomInset
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
        val typeMask = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val insets = android.graphics.Insets.max(
            windowMetrics.windowInsets.getInsets(typeMask),
            windowMetrics.windowInsets.getInsetsIgnoringVisibility(typeMask)
        )
        return resolve(
            screenWidthDp = screenWidthDp,
            screenHeightDp = screenHeightDp,
            useCenteredBentoCanvas = useCenteredBentoCanvas,
            leftInsetDp = insets.left / safeDensity,
            topInsetDp = insets.top / safeDensity,
            rightInsetDp = insets.right / safeDensity,
            bottomInsetDp = insets.bottom / safeDensity
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
        if (boundsWidthDp > 0f && boundsHeightDp > 0f) {
            return resolve(
                windowMetrics = metrics,
                density = safeDensity,
                useCenteredBentoCanvas = useCenteredBentoCanvas
            )
        }
        val config = context.resources.configuration
        return resolve(
            screenWidthDp = config.screenWidthDp.toFloat(),
            screenHeightDp = config.screenHeightDp.toFloat(),
            useCenteredBentoCanvas = useCenteredBentoCanvas
        )
    }
}
