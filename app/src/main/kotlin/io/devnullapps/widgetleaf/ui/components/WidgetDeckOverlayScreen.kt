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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.domain.usecase.EdgeDirection
import io.devnullapps.widgetleaf.ui.theme.MotionSpec
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme
import io.devnullapps.widgetleaf.ui.viewmodel.DeckUiState
import io.devnullapps.widgetleaf.ui.viewmodel.DeckViewModel

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
    widgetContent: @Composable (PlacedWidget) -> Unit = { DefaultFramelessWidgetContent(it) }
) {
    val uiState by viewModel.uiState.collectAsState()

    WidgetDeckOverlayScreen(
        uiState = uiState,
        onDismissOverlay = onDismissOverlay,
        onSelectPage = viewModel::selectPage,
        onPreviousPage = viewModel::navigatePreviousPage,
        onNextPage = viewModel::navigateNextPage,
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
            val w = uiState.widgets.find { it.appWidgetId == id }
            if (w != null) {
                viewModel.moveOrResizeWidget(id, x, y, w.spanX, w.spanY)
            }
        },
        onResizeWidget = { id, x, y, sx, sy ->
            viewModel.moveOrResizeWidget(id, x, y, sx, sy)
        },
        onDragAcrossEdge = viewModel::dragWidgetAcrossEdge,
        onRemoveWidget = viewModel::removeWidget,
        onReconfigureWidget = { /* Handled by host activity/flow */ },
        onOpenContextMenu = viewModel::openContextMenu,
        onCloseContextMenu = viewModel::closeContextMenu,
        modifier = modifier,
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
    onRemoveWidget: (appWidgetId: Int) -> Unit = {},
    onReconfigureWidget: ((appWidgetId: Int) -> Unit)? = null,
    onOpenContextMenu: (Pair<Float, Float>) -> Unit = {},
    onCloseContextMenu: () -> Unit = {},
    modifier: Modifier = Modifier,
    widgetContent: @Composable (PlacedWidget) -> Unit = { DefaultFramelessWidgetContent(it) }
) {
    WidgetLeafTheme(useDynamicColor = uiState.settings.useDynamicColor) {
        val palette = WidgetLeafTheme.palette
        var lastContextMenuOpenUptimeMs by remember { mutableLongStateOf(-10_000L) }

        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("widget_deck_overlay_root")
        ) {
            // LAYER 1: Optical Frost Scrim & 2% Procedural Noise Grain
            val effectiveScrimAlpha = uiState.settings.scrimAlpha
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.scrimDark.copy(alpha = effectiveScrimAlpha))
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 12.dp.toPx()
                val dotRadius = 0.75.dp.toPx()
                val grainColor = Color.White.copy(alpha = 0.02f)
                var x = 0f
                while (x < size.width) {
                    var y = if (((x / step).toInt() % 2) == 0) 0f else step / 2f
                    while (y < size.height) {
                        drawCircle(color = grainColor, radius = dotRadius, center = Offset(x, y))
                        y += step
                    }
                    x += step
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
                if (targetState > initialState) {
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
                } else if (targetState < initialState) {
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
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            }

            AnimatedContent(
                targetState = uiState.activePageIndex,
                transitionSpec = pageTransitionSpec,
                label = "DeckPageTransition",
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val pageWidgets = uiState.widgets.filter { it.pageIndex == pageIndex }
                val showEmptyHint = uiState.showEmptyCanvasHint && pageIndex == 0

                WidgetGridPage(
                    widgets = pageWidgets,
                    gridProfile = uiState.gridProfile,
                    showEmptyCanvasHint = showEmptyHint,
                    editingWidgetId = uiState.editingWidgetId,
                    useCenteredBentoCanvas = uiState.settings.useCenteredBentoCanvas,
                    onEnterEditMode = onEnterEditMode,
                    onMoveWidget = onMoveWidget,
                    onResizeWidget = onResizeWidget,
                    onDragAcrossEdge = onDragAcrossEdge,
                    onRemoveWidget = onRemoveWidget,
                    onReconfigureWidget = onReconfigureWidget,
                    onEmptyCanvasClick = {
                        val sinceContextOpenMs = SystemClock.uptimeMillis() - lastContextMenuOpenUptimeMs
                        if (sinceContextOpenMs >= 450L) {
                            when {
                                uiState.contextMenuPosition != null -> onCloseContextMenu()
                                uiState.editingWidgetId != null -> onExitEditMode()
                                uiState.editingPageLabelIndex != null -> onCancelEditPageLabel()
                                !uiState.isWidgetPickerOpen && !uiState.isOverlaySettingsOpen -> onDismissOverlay()
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

            // LAYER 2 (Cont.): Bottom Pagination HUD Pill (hidden while modal sheets/dialogs are open)
            if (!uiState.isWidgetPickerOpen && !uiState.isOverlaySettingsOpen) {
                PageIndicatorHud(
                    pages = uiState.pages,
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
