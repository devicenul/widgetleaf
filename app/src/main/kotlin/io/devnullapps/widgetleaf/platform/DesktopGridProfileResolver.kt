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
    private const val MIN_INSET_GAP_DP = 4f
    private const val MIN_SCALED_CELL_DP = 16f

    fun resolve(
        screenWidthDp: Float,
        screenHeightDp: Float,
        useCenteredBentoCanvas: Boolean = false,
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
        // within the safe horizontal area; when off (default), fills the full safe area width.
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
        val edgeInsetGap = if (effectiveCanvasWidthDp >= nominalMinCanvasWidth) {
            MIN_INSET_GAP_DP
        } else {
            2f
        }
        val leftMarginMin = if (safeLeftInset > 0f) {
            maxOf(horizontalMarginBase, safeLeftInset + edgeInsetGap)
        } else {
            horizontalMarginBase
        }
        val rightMarginMin = if (safeRightInset > 0f) {
            maxOf(horizontalMarginBase, safeRightInset + edgeInsetGap)
        } else {
            horizontalMarginBase
        }

        // Bottom HUD clearance: PageIndicatorHud applies navigationBars ∪ displayCutout + 24dp bottom
        // padding + 52dp pill + 8dp clearance = VERTICAL_RESERVED_DP (84dp) + safeBottomInset.
        // Nominal vertical reserve respects bottom inset once (max(84, inset + gap)) so typical bars
        // preserve v0.0.4 row counts by flexing cell height smoothly (down to MIN_CELL_HEIGHT_DP).
        val hudClearanceBottomReserved = VERTICAL_RESERVED_DP + safeBottomInset
        val nominalBottomReserved = if (safeBottomInset > 0f) {
            maxOf(VERTICAL_RESERVED_DP, safeBottomInset + MIN_INSET_GAP_DP)
        } else {
            VERTICAL_RESERVED_DP
        }

        val availableAboveHud = (windowHeightDp - safeTopInset - hudClearanceBottomReserved).coerceAtLeast(1f)
        val nominalMinGridHeight = MIN_ROWS * MIN_CELL_HEIGHT_DP + (MIN_ROWS - 1) * DEFAULT_GUTTER_DP // 336dp
        val baseTopGap = when {
            availableAboveHud >= nominalMinGridHeight + DEFAULT_MARGIN_DP -> DEFAULT_MARGIN_DP
            availableAboveHud >= nominalMinGridHeight + 8f ->
                (availableAboveHud - nominalMinGridHeight).coerceIn(12f, DEFAULT_MARGIN_DP)
            else -> (availableAboveHud * 0.04f).coerceIn(6f, 12f)
        }
        val topInsetGap = if (availableAboveHud >= nominalMinGridHeight + 8f) {
            MIN_INSET_GAP_DP
        } else {
            2f
        }
        val topMarginDp = if (safeTopInset > 0f) {
            maxOf(baseTopGap, safeTopInset + topInsetGap)
        } else {
            baseTopGap
        }

        val maxSafeUsableWidth = (windowWidthDp - leftMarginMin - rightMarginMin).coerceAtLeast(1f)
        val maxCenteredUsableWidth = (MAX_CANVAS_WIDTH_DP - 2f * horizontalMarginBase).coerceAtLeast(1f)
        val targetUsableWidth = if (useCenteredBentoCanvas) {
            minOf(maxSafeUsableWidth, maxCenteredUsableWidth)
        } else {
            maxSafeUsableWidth
        }

        val actualUsableHeight = (windowHeightDp - topMarginDp - hudClearanceBottomReserved).coerceAtLeast(1f)
        val nominalUsableHeight = (windowHeightDp - topMarginDp - nominalBottomReserved).coerceAtLeast(1f)

        val hRatio = (targetUsableWidth / nominalMinGridWidth).coerceIn(0f, 1f)
        val vRatio = (actualUsableHeight / nominalMinGridHeight).coerceIn(0f, 1f)
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
        val extraHorizontalSlack = (windowWidthDp - leftMarginMin - rightMarginMin - totalGridWidth).coerceAtLeast(0f)
        val marginDp = leftMarginMin + extraHorizontalSlack / 2f

        // Compute rows using v0.0.4 inset-once nominal bounds, flexing cell height down (not below
        // MIN_CELL_HEIGHT_DP) before ever dropping a row to clear the bottom HUD without overlap.
        val nominalRawRows = ((nominalUsableHeight + gutterDp) / (DEFAULT_CELL_HEIGHT_DP + gutterDp))
            .toInt()
            .coerceAtLeast(MIN_ROWS)
        val rows: Int
        val fittedCellHeightDp: Float
        if (actualUsableHeight >= nominalMinGridHeight) {
            val candidateHeight = (actualUsableHeight - (nominalRawRows - 1) * gutterDp) / nominalRawRows
            if (candidateHeight >= MIN_CELL_HEIGHT_DP) {
                rows = nominalRawRows
                fittedCellHeightDp = candidateHeight
            } else {
                val maxFittingRows = ((actualUsableHeight + gutterDp) / (MIN_CELL_HEIGHT_DP + gutterDp))
                    .toInt()
                    .coerceAtLeast(MIN_ROWS)
                rows = minOf(nominalRawRows, maxFittingRows).coerceAtLeast(MIN_ROWS)
                fittedCellHeightDp = ((actualUsableHeight - (rows - 1) * gutterDp) / rows)
                    .coerceAtLeast(MIN_CELL_HEIGHT_DP)
            }
        } else {
            rows = MIN_ROWS
            fittedCellHeightDp = ((actualUsableHeight - (rows - 1) * gutterDp) / rows)
                .coerceAtLeast(MIN_SCALED_CELL_DP)
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
        useCenteredBentoCanvas: Boolean = false
    ): DesktopGridProfile {
        val safeDensity = if (density > 0f) density else 1f
        val screenWidthDp = windowMetrics.bounds.width() / safeDensity
        val screenHeightDp = windowMetrics.bounds.height() / safeDensity
        val typeMask = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val insets = windowMetrics.windowInsets.getInsets(typeMask)
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
        useCenteredBentoCanvas: Boolean = false
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
