package io.devnullapps.widgetleaf.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.domain.usecase.EdgeDirection
import io.devnullapps.widgetleaf.platform.DesktopGridProfileResolver
import io.devnullapps.widgetleaf.services.OverlayDiagnosticsLog
import io.devnullapps.widgetleaf.ui.theme.MotionSpec
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme
import io.devnullapps.widgetleaf.ui.viewmodel.DeckUiState
import io.devnullapps.widgetleaf.ui.viewmodel.DeckViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

data class OverlayViewportMetrics(
    val widthDp: Float,
    val heightDp: Float,
    val leftInsetDp: Float,
    val topInsetDp: Float,
    val rightInsetDp: Float,
    val bottomInsetDp: Float,
    val heightPx: Float,
    val bottomInsetPx: Float
)

/**
 * Full-screen root composable for the WidgetLeaf frameless multi-page overlay:
 * - Layer 1: Optical frost scrim (scrimDark with effectiveScrimAlpha + 2% procedural noise grain)
 * - Layer 2: Ambient radial leafGlow and bottom PageIndicatorHud
 * - Layer 3: Multi-page canvas with 280ms spring transition, click-to-dismiss, right-click context menu,
 *            and WidgetGridPage rendering frameless widgets
 * - Layer 4: DesktopContextMenu, WidgetPickerBottomSheet, and OverlaySettingsDialog
 */
@Composable
fun WidgetDeckOverlayScreen(
    viewModel: DeckViewModel,
    onDismissOverlay: () -> Unit,
    modifier: Modifier = Modifier,
    canWidgetScroll: (x: Float, y: Float, dirX: Int, dirY: Int) -> Boolean = { _, _, _, _ -> false },
    onCancelChildTouches: (x: Float, y: Float) -> Unit = { _, _ -> },
    widgetContent: @Composable (PlacedWidget) -> Unit = { DefaultFramelessWidgetContent(it) }
) {
    val uiState by viewModel.uiState.collectAsState()

    WidgetDeckOverlayScreen(
        uiState = uiState,
        onDismissOverlay = onDismissOverlay,
        onSelectPage = viewModel::selectPage,
        onPreviousPage = viewModel::navigatePreviousPage,
        onNextPage = viewModel::navigateNextPage,
        onSwipePrevious = viewModel::swipePreviousPage,
        onSwipeNext = viewModel::swipeNextPage,
        canWidgetScroll = canWidgetScroll,
        onCancelChildTouches = onCancelChildTouches,
        onOpenWidgetPicker = viewModel::openWidgetPicker,
        onCloseWidgetPicker = viewModel::closeWidgetPicker,
        onSearchQueryChange = viewModel::updateWidgetSearchQuery,
        onAddWidgetAutoPlace = viewModel::addWidgetAutoPlace,
        onDragOutCatalogItem = { item, cellX, cellY ->
            viewModel.dropCatalogWidgetAtCell(item, uiState.activePageIndex, cellX, cellY)
        },
        onOpenOverlaySettings = viewModel::openOverlaySettings,
        onCloseOverlaySettings = viewModel::closeOverlaySettings,
        onUpdateSettings = viewModel::updateSettings,
        onStartEditPageLabel = viewModel::startEditingPageLabel,
        onDraftChange = viewModel::updateEditingPageLabelDraft,
        onCommitPageLabel = viewModel::commitPageLabel,
        onCancelEditPageLabel = viewModel::cancelEditingPageLabel,
        onEnterEditMode = viewModel::enterWidgetEditMode,
        onExitEditMode = viewModel::exitWidgetEditMode,
        onMoveWidget = { id, x, y ->
            viewModel.moveWidget(id, x, y)
        },
        onResizeWidget = { id, x, y, sx, sy ->
            viewModel.resizeWidget(id, x, y, sx, sy)
        },
        onStartWidgetResize = viewModel::startWidgetResize,
        onWidgetResizeBoundsChanged = viewModel::onWidgetResizeBoundsChanged,
        onCommitWidgetResize = viewModel::commitWidgetResize,
        onCancelWidgetResize = viewModel::cancelWidgetResize,
        onStartWidgetDrag = viewModel::startWidgetDrag,
        onWidgetHoverCellChanged = viewModel::onWidgetHoverCellChanged,
        onCommitWidgetDrag = viewModel::commitWidgetDrag,
        onCancelWidgetDrag = viewModel::cancelWidgetDrag,
        onDragAcrossEdge = viewModel::dragWidgetAcrossEdge,
        onEdgeDwellTriggered = viewModel::onEdgeDwellTriggered,
        onRemoveWidget = viewModel::removeWidget,
        onReconfigureWidget = { /* Handled by host activity/flow */ },
        onOpenContextMenu = viewModel::openContextMenu,
        onCloseContextMenu = viewModel::closeContextMenu,
        onViewportMetricsChanged = { metrics ->
            if (metrics.widthDp > 10f && metrics.heightDp > 10f) {
                val newProfile = DesktopGridProfileResolver.resolve(
                    screenWidthDp = metrics.widthDp,
                    screenHeightDp = metrics.heightDp,
                    useCenteredBentoCanvas = uiState.settings.useCenteredBentoCanvas,
                    leftInsetDp = metrics.leftInsetDp,
                    topInsetDp = metrics.topInsetDp,
                    rightInsetDp = metrics.rightInsetDp,
                    bottomInsetDp = metrics.bottomInsetDp
                )
                if (newProfile != uiState.gridProfile) {
                    viewModel.updateGridProfile(newProfile)
                }
            }
        },
        modifier = modifier.fillMaxSize(),
        widgetContent = widgetContent
    )
}

@Composable
fun WidgetDeckOverlayScreen(
    uiState: DeckUiState,
    onDismissOverlay: () -> Unit,
    onSelectPage: (pageIndex: Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSwipePrevious: () -> Unit = onPreviousPage,
    onSwipeNext: () -> Unit = onNextPage,
    canWidgetScroll: (x: Float, y: Float, dirX: Int, dirY: Int) -> Boolean = { _, _, _, _ -> false },
    onCancelChildTouches: (x: Float, y: Float) -> Unit = { _, _ -> },
    onOpenWidgetPicker: () -> Unit,
    onCloseWidgetPicker: () -> Unit,
    onSearchQueryChange: (query: String) -> Unit,
    onAddWidgetAutoPlace: (WidgetCatalogItem) -> Unit,
    onDragOutCatalogItem: ((WidgetCatalogItem, Int, Int) -> Unit)? = null,
    onOpenOverlaySettings: () -> Unit,
    onCloseOverlaySettings: () -> Unit,
    onUpdateSettings: ((OverlaySettings) -> OverlaySettings) -> Unit,
    onStartEditPageLabel: (pageIndex: Int) -> Unit,
    onDraftChange: (draft: String) -> Unit = {},
    onCommitPageLabel: (pageIndex: Int, draft: String) -> Unit,
    onCancelEditPageLabel: () -> Unit = {},
    onEnterEditMode: (appWidgetId: Int) -> Unit = {},
    onExitEditMode: () -> Unit = {},
    onMoveWidget: (appWidgetId: Int, newCellX: Int, newCellY: Int) -> Unit = { _, _, _ -> },
    onResizeWidget: (appWidgetId: Int, newCellX: Int, newCellY: Int, newSpanX: Int, newSpanY: Int) -> Unit = { _, _, _, _, _ -> },
    onDragAcrossEdge: (appWidgetId: Int, direction: EdgeDirection, targetCellX: Int, targetCellY: Int) -> Unit = { _, _, _, _ -> },
    onStartWidgetDrag: (appWidgetId: Int) -> Unit = {},
    onWidgetHoverCellChanged: (appWidgetId: Int, cellX: Int, cellY: Int) -> Unit = { _, _, _ -> },
    onCommitWidgetDrag: (appWidgetId: Int, cellX: Int, cellY: Int) -> Unit = { id, x, y -> onMoveWidget(id, x, y) },
    onCancelWidgetDrag: () -> Unit = {},
    onStartWidgetResize: (appWidgetId: Int) -> Unit = {},
    onWidgetResizeBoundsChanged: (appWidgetId: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> Unit = { _, _, _, _, _ -> },
    onCommitWidgetResize: (appWidgetId: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> Unit = { id, x, y, sx, sy -> onResizeWidget(id, x, y, sx, sy) },
    onCancelWidgetResize: () -> Unit = onCancelWidgetDrag,
    onEdgeDwellTriggered: (EdgeDirection) -> Unit = {},
    onRemoveWidget: (appWidgetId: Int) -> Unit = {},
    onReconfigureWidget: ((appWidgetId: Int) -> Unit)? = null,
    onOpenContextMenu: (Pair<Float, Float>) -> Unit = {},
    onCloseContextMenu: () -> Unit = {},
    onReady: () -> Unit = {},
    effectiveScrimAlpha: Float = uiState.settings.scrimAlpha,
    onViewportMetricsChanged: ((OverlayViewportMetrics) -> Unit)? = null,
    modifier: Modifier = Modifier,
    widgetContent: @Composable (PlacedWidget) -> Unit = { DefaultFramelessWidgetContent(it) }
) {
    WidgetLeafTheme(useDynamicColor = uiState.settings.useDynamicColor) {
        val palette = WidgetLeafTheme.palette
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeSystemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val grainBrush = remember(density) { createNoiseGrainBrush(density) }
        val currentViewportCallback by rememberUpdatedState(onViewportMetricsChanged)

        var lastContextMenuOpenUptimeMs by remember { mutableLongStateOf(-10_000L) }
        var lastWidgetMutationUptimeMs by remember { mutableLongStateOf(-10_000L) }

        LaunchedEffect(uiState.isReady) {
            if (uiState.isReady && uiState.snapPageTransitions) {
                onReady()
            }
        }

        val isSwipeGesturesEnabled = uiState.editingWidgetId == null &&
            uiState.draggingWidgetId == null &&
            uiState.resizingWidgetId == null &&
            !uiState.isWidgetPickerOpen &&
            !uiState.isOverlaySettingsOpen &&
            uiState.contextMenuPosition == null

        BoxWithConstraints(
            modifier = modifier.fillMaxSize()
        ) {
            val widthDp = maxWidth.value
            val heightDp = maxHeight.value
            val leftInsetPx = safeSystemInsets.getLeft(density, layoutDirection)
            val topInsetPx = safeSystemInsets.getTop(density)
            val rightInsetPx = safeSystemInsets.getRight(density, layoutDirection)
            val bottomInsetPx = safeSystemInsets.getBottom(density)
            val leftInsetDp = with(density) { leftInsetPx.toDp().value }
            val topInsetDp = with(density) { topInsetPx.toDp().value }
            val rightInsetDp = with(density) { rightInsetPx.toDp().value }
            val bottomInsetDp = with(density) { bottomInsetPx.toDp().value }
            val containerHeightPx = constraints.maxHeight.toFloat().takeIf { it > 0f }
                ?: with(density) { maxHeight.toPx() }

            LaunchedEffect(
                widthDp,
                heightDp,
                leftInsetDp,
                topInsetDp,
                rightInsetDp,
                bottomInsetDp,
                containerHeightPx,
                bottomInsetPx,
                uiState.settings.useCenteredBentoCanvas
            ) {
                currentViewportCallback?.invoke(
                    OverlayViewportMetrics(
                        widthDp = widthDp,
                        heightDp = heightDp,
                        leftInsetDp = leftInsetDp,
                        topInsetDp = topInsetDp,
                        rightInsetDp = rightInsetDp,
                        bottomInsetDp = bottomInsetDp,
                        heightPx = containerHeightPx,
                        bottomInsetPx = bottomInsetPx.toFloat()
                    )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("widget_deck_overlay_root")
                    .overlaySwipeGestures(
                        enabled = isSwipeGesturesEnabled,
                        canWidgetScroll = canWidgetScroll,
                        onCancelChildTouches = onCancelChildTouches,
                        onSwipeNext = onSwipeNext,
                        onSwipePrevious = onSwipePrevious,
                        onDismiss = {
                            OverlayDiagnosticsLog.record("dismiss_trigger", "swipe_dismiss_touch")
                            onDismissOverlay()
                        }
                    )
            ) {
                // LAYER 1: Optical Frost Scrim & 2% Tiled Procedural Noise Grain
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.scrimDark.copy(alpha = effectiveScrimAlpha))
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (grainBrush != null) {
                        drawRect(brush = grainBrush)
                    }
                }

            // LAYER 2: Ambient Radial LeafGlow
            val glowColor = palette.leafGlow
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerOffset = Offset(size.width / 2f, size.height / 2f)
                val radius = size.maxDimension * 0.45f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                        center = centerOffset,
                        radius = radius
                    ),
                    radius = radius,
                    center = centerOffset
                )
            }

            // LAYER 3: Multi-Page Canvas with 280ms Spring Transition
            val pageTransitionSpec: AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
                DeckPageTransitionSpec.createTransition(
                    snapPageTransitions = uiState.snapPageTransitions,
                    initialState = initialState,
                    targetState = targetState
                )
            }

            if (uiState.isReady) {
                AnimatedContent(
                    targetState = uiState.activePageIndex,
                    transitionSpec = pageTransitionSpec,
                    label = "DeckPageTransition",
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    val pageWidgets = uiState.effectiveWidgets.filter { it.pageIndex == pageIndex }
                    val showEmptyHint = uiState.showEmptyCanvasHint && pageIndex == 0
                    val draggedWidget = uiState.effectiveWidgets.find { it.appWidgetId == uiState.draggingWidgetId }

                    WidgetGridPage(
                        widgets = pageWidgets,
                        gridProfile = uiState.gridProfile,
                        showEmptyCanvasHint = showEmptyHint,
                        editingWidgetId = uiState.editingWidgetId,
                        useCenteredBentoCanvas = uiState.settings.useCenteredBentoCanvas,
                        draggingWidgetId = uiState.draggingWidgetId,
                        draggedWidget = draggedWidget,
                        hoveredCell = uiState.hoveredCell,
                        onStartWidgetDrag = { id ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onStartWidgetDrag(id)
                        },
                        onWidgetHoverCellChanged = { id, x, y ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onWidgetHoverCellChanged(id, x, y)
                        },
                        onCommitWidgetDrag = { id, x, y ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onCommitWidgetDrag(id, x, y)
                        },
                        onCancelWidgetDrag = {
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onCancelWidgetDrag()
                        },
                        onEnterEditMode = { id ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onEnterEditMode(id)
                        },
                        onMoveWidget = { id, x, y ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onMoveWidget(id, x, y)
                        },
                        onResizeWidget = { id, x, y, sx, sy ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onResizeWidget(id, x, y, sx, sy)
                        },
                        onDragAcrossEdge = { id, dir, x, y ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onDragAcrossEdge(id, dir, x, y)
                        },
                        onRemoveWidget = { id ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onRemoveWidget(id)
                        },
                        onReconfigureWidget = onReconfigureWidget?.let { callback ->
                            { id ->
                                lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                                callback(id)
                            }
                        },
                        onEmptyCanvasClick = {
                            val now = SystemClock.uptimeMillis()
                            val sinceContextOpenMs = now - lastContextMenuOpenUptimeMs
                            val sinceWidgetMutationMs = now - lastWidgetMutationUptimeMs
                            if (sinceContextOpenMs >= 450L && sinceWidgetMutationMs >= 350L) {
                                when {
                                    uiState.contextMenuPosition != null -> onCloseContextMenu()
                                    uiState.editingWidgetId != null -> onExitEditMode()
                                    uiState.editingPageLabelIndex != null -> onCancelEditPageLabel()
                                    !uiState.isWidgetPickerOpen && !uiState.isOverlaySettingsOpen -> {
                                        OverlayDiagnosticsLog.record("dismiss_trigger", "empty_canvas_tap")
                                        onDismissOverlay()
                                    }
                                }
                            }
                        },
                        onOpenContextMenu = { pos ->
                            lastContextMenuOpenUptimeMs = SystemClock.uptimeMillis()
                            onOpenContextMenu(pos)
                        },
                        widgetContent = widgetContent
                    )
                }
            }

            // LAYER 3.5: Edit-Mode Drag & Resize Layer (Stationary overlay above AnimatedContent)
            val editingWidget = uiState.effectiveWidgets.find { it.appWidgetId == uiState.editingWidgetId }
            if (uiState.isReady && editingWidget != null && (editingWidget.pageIndex == uiState.activePageIndex || uiState.draggingWidgetId == editingWidget.appWidgetId || uiState.resizingWidgetId == editingWidget.appWidgetId)) {
                key(editingWidget.appWidgetId) {
                    WidgetEditDragLayer(
                        widget = editingWidget,
                        gridProfile = uiState.gridProfile,
                        isDragging = uiState.draggingWidgetId == editingWidget.appWidgetId,
                        activePageIndex = uiState.activePageIndex,
                        onStartWidgetDrag = { id ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onStartWidgetDrag(id)
                        },
                        onWidgetHoverCellChanged = { id, x, y ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onWidgetHoverCellChanged(id, x, y)
                        },
                        onCommitWidgetDrag = { id, x, y ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onCommitWidgetDrag(id, x, y)
                        },
                        onCancelWidgetDrag = {
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onCancelWidgetDrag()
                        },
                        onEdgeDwellTriggered = { dir ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onEdgeDwellTriggered(dir)
                        },
                        onResizeWidget = { id, x, y, sx, sy ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onResizeWidget(id, x, y, sx, sy)
                        },
                        onRemoveWidget = { id ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onRemoveWidget(id)
                        },
                        onReconfigureWidget = onReconfigureWidget?.let { callback ->
                            { id ->
                                lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                                callback(id)
                            }
                        },
                        onStartWidgetResize = { id ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onStartWidgetResize(id)
                        },
                        onWidgetResizeBoundsChanged = { id, x, y, sx, sy ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onWidgetResizeBoundsChanged(id, x, y, sx, sy)
                        },
                        onCommitWidgetResize = { id, x, y, sx, sy ->
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onCommitWidgetResize(id, x, y, sx, sy)
                        },
                        onCancelWidgetResize = {
                            lastWidgetMutationUptimeMs = SystemClock.uptimeMillis()
                            onCancelWidgetResize()
                        },
                        widgetContent = widgetContent
                    )
                }
            }

            // LAYER 2 (Cont.): Bottom Pagination HUD Pill (hidden while modal sheets/dialogs are open)
            if (uiState.isReady && !uiState.isWidgetPickerOpen && !uiState.isOverlaySettingsOpen) {
                PageIndicatorHud(
                    pages = uiState.effectivePages,
                    activePageIndex = uiState.activePageIndex,
                    editingPageLabelIndex = uiState.editingPageLabelIndex,
                    editingPageLabelDraft = uiState.editingPageLabelDraft,
                    onSelectPage = onSelectPage,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    onOpenWidgetPicker = onOpenWidgetPicker,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onStartEditPageLabel = onStartEditPageLabel,
                    onDraftChange = onDraftChange,
                    onCommitPageLabel = onCommitPageLabel,
                    onCancelEditPageLabel = onCancelEditPageLabel,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
                        .padding(bottom = 24.dp)
                )
            }

            // LAYER 4: Desktop Context Menu, Widget Picker & Overlay Settings Dialog
            DesktopContextMenu(
                position = uiState.contextMenuPosition,
                onOpenWidgetPicker = onOpenWidgetPicker,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onDismissRequest = onCloseContextMenu
            )

            WidgetPickerBottomSheet(
                isOpen = uiState.isWidgetPickerOpen,
                searchQuery = uiState.widgetSearchQuery,
                onSearchQueryChange = onSearchQueryChange,
                filteredCatalogByApp = uiState.filteredCatalogByApp,
                onAddWidgetAutoPlace = onAddWidgetAutoPlace,
                onDragOutCatalogItem = onDragOutCatalogItem,
                onDismissRequest = onCloseWidgetPicker
            )

            OverlaySettingsDialog(
                isOpen = uiState.isOverlaySettingsOpen,
                settings = uiState.settings,
                onUpdateSettings = onUpdateSettings,
                onDismissRequest = onCloseOverlaySettings
            )
            }
        }
    }
}

internal fun createNoiseGrainBrush(density: Density): ShaderBrush? {
    val stepPx = with(density) { 12.dp.toPx() }.roundToInt().coerceAtLeast(4)
    val dotRadiusPx = with(density) { 0.75.dp.toPx() }.coerceAtLeast(0.75f)
    val tileSize = stepPx * 2
    val bitmap = try {
        android.graphics.Bitmap.createBitmap(
            tileSize,
            tileSize,
            android.graphics.Bitmap.Config.ARGB_8888
        )
    } catch (_: Throwable) {
        null
    } ?: return null

    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(5, 255, 255, 255)
        style = android.graphics.Paint.Style.FILL
    }
    val halfStep = stepPx / 2f
    for (x in listOf(0f, tileSize.toFloat())) {
        for (y in listOf(0f, stepPx.toFloat(), tileSize.toFloat())) {
            canvas.drawCircle(x, y, dotRadiusPx, paint)
        }
    }
    for (y in listOf(halfStep, stepPx + halfStep)) {
        canvas.drawCircle(stepPx.toFloat(), y, dotRadiusPx, paint)
    }
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private enum class SwipeDirection {
    HORIZONTAL,
    VERTICAL
}

/**
 * Intercepts 1-finger touch gestures at the overlay root in [PointerEventPass.Initial].
 *
 * Mechanics:
 * - If [enabled] is false (e.g. widget edit mode or open dialogs), touch streams pass through unaltered.
 * - Filters for [PointerType.Touch] (single-finger touchscreen swipe).
 * - Dominant-axis locking: requires 1.2x ratio between X/Y deltas to prevent diagonal jitter.
 * - Widget scrollability awareness: queries [canWidgetScroll]. If a child widget can scroll in the gesture
 *   direction, the swipe is not claimed, allowing widget lists/carousels to scroll normally.
 * - Once claimed, consumes pointer events in Initial pass and invokes [onCancelChildTouches] on the target widget.
 * - Distance & velocity thresholds:
 *   - Swipe Left (negative dx) -> [onSwipeNext]
 *   - Swipe Right (positive dx) -> [onSwipePrevious]
 *   - Swipe Up or Down (vertical dy) -> [onDismiss]
 */
internal fun Modifier.overlaySwipeGestures(
    enabled: Boolean,
    canWidgetScroll: (x: Float, y: Float, dirX: Int, dirY: Int) -> Boolean,
    onCancelChildTouches: (x: Float, y: Float) -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    onDismiss: () -> Unit
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput

    val touchSlop = viewConfiguration.touchSlop
    val density = this

    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
        if (down.type != PointerType.Touch) {
            return@awaitEachGesture
        }

        val downPos = down.position
        var currentPos = downPos
        var gestureClaimed = false
        var gestureDirection: SwipeDirection? = null
        var actionTriggered = false
        val velocityTracker = VelocityTracker()
        velocityTracker.addPosition(down.uptimeMillis, downPos)

        val pageThresholdPx = with(density) { 56.dp.toPx() }
        val dismissThresholdPx = with(density) { 80.dp.toPx() }

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            if (!change.pressed) {
                currentPos = change.position
                velocityTracker.addPosition(change.uptimeMillis, currentPos)
                break
            }

            currentPos = change.position
            velocityTracker.addPosition(change.uptimeMillis, currentPos)

            val totalDx = currentPos.x - downPos.x
            val totalDy = currentPos.y - downPos.y
            val distSq = totalDx * totalDx + totalDy * totalDy

            if (!gestureClaimed && !actionTriggered) {
                if (distSq > touchSlop * touchSlop) {
                    val absDx = abs(totalDx)
                    val absDy = abs(totalDy)

                    if (absDx > absDy * 1.2f) {
                        val dirX = if (totalDx < 0) 1 else -1
                        val widgetCanScroll = canWidgetScroll(downPos.x, downPos.y, dirX, 0)
                        if (!widgetCanScroll) {
                            gestureClaimed = true
                            gestureDirection = SwipeDirection.HORIZONTAL
                            onCancelChildTouches(downPos.x, downPos.y)
                        }
                    } else if (absDy > absDx * 1.2f) {
                        val dirY = if (totalDy < 0) 1 else -1
                        val widgetCanScroll = canWidgetScroll(downPos.x, downPos.y, 0, dirY)
                        if (!widgetCanScroll) {
                            gestureClaimed = true
                            gestureDirection = SwipeDirection.VERTICAL
                            onCancelChildTouches(downPos.x, downPos.y)
                        }
                    }
                }
            }

            if (gestureClaimed) {
                change.consume()

                // Trigger immediately if dragged past the full threshold during continuous touch
                if (!actionTriggered) {
                    if (gestureDirection == SwipeDirection.HORIZONTAL) {
                        if (totalDx <= -pageThresholdPx) {
                            actionTriggered = true
                            onSwipeNext()
                        } else if (totalDx >= pageThresholdPx) {
                            actionTriggered = true
                            onSwipePrevious()
                        }
                    } else if (gestureDirection == SwipeDirection.VERTICAL) {
                        if (abs(totalDy) >= dismissThresholdPx) {
                            actionTriggered = true
                            onDismiss()
                        }
                    }
                }
            }
        }

        // On pointer release (UP): evaluate velocity flick if claimed and not yet fired
        if (gestureClaimed && !actionTriggered) {
            val velocity = velocityTracker.calculateVelocity()
            val totalDx = currentPos.x - downPos.x
            val totalDy = currentPos.y - downPos.y
            val minFlickDist = touchSlop * 2f

            if (gestureDirection == SwipeDirection.HORIZONTAL) {
                if ((totalDx < -minFlickDist && velocity.x < -600f) || totalDx <= -pageThresholdPx) {
                    onSwipeNext()
                } else if ((totalDx > minFlickDist && velocity.x > 600f) || totalDx >= pageThresholdPx) {
                    onSwipePrevious()
                }
            } else if (gestureDirection == SwipeDirection.VERTICAL) {
                if ((abs(totalDy) > minFlickDist && abs(velocity.y) > 600f) ||
                    abs(totalDy) >= dismissThresholdPx
                ) {
                    onDismiss()
                }
            }
        }
    }
}

internal object DeckPageTransitionSpec {
    fun createTransition(
        snapPageTransitions: Boolean,
        initialState: Int,
        targetState: Int
    ): ContentTransform {
        return if (snapPageTransitions || targetState == initialState) {
            EnterTransition.None togetherWith ExitTransition.None
        } else if (targetState > initialState) {
            slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = MotionSpec.PAGE_FLIP_SPRING_DAMPING,
                    stiffness = MotionSpec.PAGE_FLIP_SPRING_STIFFNESS
                )
            ) { it } togetherWith slideOutHorizontally(
                animationSpec = spring(
                    dampingRatio = MotionSpec.PAGE_FLIP_SPRING_DAMPING,
                    stiffness = MotionSpec.PAGE_FLIP_SPRING_STIFFNESS
                )
            ) { -it }
        } else {
            slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = MotionSpec.PAGE_FLIP_SPRING_DAMPING,
                    stiffness = MotionSpec.PAGE_FLIP_SPRING_STIFFNESS
                )
            ) { -it } togetherWith slideOutHorizontally(
                animationSpec = spring(
                    dampingRatio = MotionSpec.PAGE_FLIP_SPRING_DAMPING,
                    stiffness = MotionSpec.PAGE_FLIP_SPRING_STIFFNESS
                )
            ) { it }
        }
    }
}
