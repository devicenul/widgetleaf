package io.devnullapps.widgetleaf.ui.components

import android.view.PointerIcon as AndroidPointerIcon
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.usecase.EdgeDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun WidgetEditDragLayer(
    widget: PlacedWidget,
    gridProfile: DesktopGridProfile,
    isDragging: Boolean,
    activePageIndex: Int,
    onStartWidgetDrag: (appWidgetId: Int) -> Unit,
    onWidgetHoverCellChanged: (appWidgetId: Int, cellX: Int, cellY: Int) -> Unit,
    onCommitWidgetDrag: (appWidgetId: Int, cellX: Int, cellY: Int) -> Unit,
    onCancelWidgetDrag: () -> Unit,
    onEdgeDwellTriggered: (direction: EdgeDirection) -> Unit,
    onResizeWidget: (appWidgetId: Int, newCellX: Int, newCellY: Int, newSpanX: Int, newSpanY: Int) -> Unit,
    onRemoveWidget: (appWidgetId: Int) -> Unit,
    onReconfigureWidget: ((appWidgetId: Int) -> Unit)?,
    onStartWidgetResize: (appWidgetId: Int) -> Unit = {},
    onWidgetResizeBoundsChanged: (appWidgetId: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> Unit = { _, _, _, _, _ -> },
    onCommitWidgetResize: (appWidgetId: Int, cellX: Int, cellY: Int, spanX: Int, spanY: Int) -> Unit = { id, x, y, sx, sy -> onResizeWidget(id, x, y, sx, sy) },
    onCancelWidgetResize: () -> Unit = onCancelWidgetDrag,
    modifier: Modifier = Modifier,
    widgetContent: @Composable (PlacedWidget) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val currentWidgetState by rememberUpdatedState(widget)
    val currentGridProfile by rememberUpdatedState(gridProfile)
    val currentActivePageIndex by rememberUpdatedState(activePageIndex)
    val currentOnStartWidgetDrag by rememberUpdatedState(onStartWidgetDrag)
    val currentOnWidgetHoverCellChanged by rememberUpdatedState(onWidgetHoverCellChanged)
    val currentOnCommitWidgetDrag by rememberUpdatedState(onCommitWidgetDrag)
    val currentOnCancelWidgetDrag by rememberUpdatedState(onCancelWidgetDrag)
    val currentOnEdgeDwellTriggered by rememberUpdatedState(onEdgeDwellTriggered)
    val currentOnResizeWidget by rememberUpdatedState(onResizeWidget)
    val currentOnRemoveWidget by rememberUpdatedState(onRemoveWidget)
    val currentOnReconfigureWidget by rememberUpdatedState(onReconfigureWidget)
    val currentOnStartWidgetResize by rememberUpdatedState(onStartWidgetResize)
    val currentOnWidgetResizeBoundsChanged by rememberUpdatedState(onWidgetResizeBoundsChanged)
    val currentOnCommitWidgetResize by rememberUpdatedState(onCommitWidgetResize)
    val currentOnCancelWidgetResize by rememberUpdatedState(onCancelWidgetResize)

    var moveDragX by remember(widget.appWidgetId) { mutableFloatStateOf(0f) }
    var moveDragY by remember(widget.appWidgetId) { mutableFloatStateOf(0f) }
    var dragStartCell by remember(widget.appWidgetId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var lastHoverReportedCell by remember(widget.appWidgetId) { mutableStateOf<Pair<Int, Int>?>(null) }
    var activeEdgeZone by remember(widget.appWidgetId) { mutableStateOf<EdgeDirection?>(null) }
    var isLocalDragging by remember(widget.appWidgetId) { mutableStateOf(false) }
    var isLocalResizing by remember(widget.appWidgetId) { mutableStateOf(false) }
    var resizeStartBounds by remember(widget.appWidgetId) { mutableStateOf<io.devnullapps.widgetleaf.domain.model.GridBounds?>(null) }
    var dwellJob by remember(widget.appWidgetId) { mutableStateOf<Job?>(null) }
    var rootLeftPx by remember { mutableFloatStateOf(0f) }

    DisposableEffect(widget.appWidgetId) {
        onDispose {
            dwellJob?.cancel()
            dwellJob = null
        }
    }

    val effectiveIsDragging = isDragging || isLocalDragging

    val movePointerIcon = rememberSystemPointerIcon(
        context,
        AndroidPointerIcon.TYPE_ALL_SCROLL,
        PointerIcon.Hand
    )

    // INV-2 & INV-12: Base offset and span anchored at start throughout the gesture
    val baseCellX = if (isLocalResizing && resizeStartBounds != null) {
        resizeStartBounds!!.cellX
    } else if (effectiveIsDragging && dragStartCell != null) {
        dragStartCell!!.first
    } else {
        widget.cellX
    }

    val baseCellY = if (isLocalResizing && resizeStartBounds != null) {
        resizeStartBounds!!.cellY
    } else if (effectiveIsDragging && dragStartCell != null) {
        dragStartCell!!.second
    } else {
        widget.cellY
    }

    val baseSpanX = if (isLocalResizing && resizeStartBounds != null) {
        resizeStartBounds!!.spanX
    } else {
        widget.spanX
    }

    val baseSpanY = if (isLocalResizing && resizeStartBounds != null) {
        resizeStartBounds!!.spanY
    } else {
        widget.spanY
    }

    val widthDp = currentGridProfile.widthDpForSpan(baseSpanX).dp
    val heightDp = currentGridProfile.heightDpForSpan(baseSpanY).dp

    val targetLeftDp = (currentGridProfile.marginDp + baseCellX * (currentGridProfile.cellWidthDp + currentGridProfile.gutterDp)).dp
    val targetTopDp = (currentGridProfile.topMarginDp + baseCellY * (currentGridProfile.cellHeightDp + currentGridProfile.gutterDp)).dp

    val frameWidget = if (isLocalResizing && resizeStartBounds != null) {
        currentWidgetState.copy(
            cellX = resizeStartBounds!!.cellX,
            cellY = resizeStartBounds!!.cellY,
            spanX = resizeStartBounds!!.spanX,
            spanY = resizeStartBounds!!.spanY
        )
    } else {
        currentWidgetState
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootLeftPx = it.positionInRoot().x }
    ) {
        Box(
            modifier = Modifier
                .offset(x = targetLeftDp, y = targetTopDp)
                .size(width = widthDp, height = heightDp)
        ) {
            // Layer 1: Visual widget content (AndroidView) translated by live drag offset
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(moveDragX.roundToInt(), moveDragY.roundToInt()) }
            ) {
                widgetContent(frameWidget)
            }

            // Layer 2: Dedicated stationary move hit surface (INV-2, INV-11)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("placed_widget_${widget.appWidgetId}")
                    .pointerHoverIcon(movePointerIcon)
                    .pointerInput(widget.appWidgetId, true) { // INV-1: isEditing key true
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                dwellJob?.cancel()
                                dwellJob = null
                                val startX = currentWidgetState.cellX
                                val startY = currentWidgetState.cellY
                                dragStartCell = Pair(startX, startY)
                                lastHoverReportedCell = Pair(startX, startY)
                                moveDragX = 0f
                                moveDragY = 0f
                                activeEdgeZone = null
                                isLocalDragging = true
                                currentOnStartWidgetDrag(currentWidgetState.appWidgetId)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                moveDragX += dragAmount.x
                                moveDragY += dragAmount.y

                                val prof = currentGridProfile
                                val start = dragStartCell ?: Pair(currentWidgetState.cellX, currentWidgetState.cellY)
                                val stepXPx = with(density) { (prof.cellWidthDp + prof.gutterDp).dp.toPx() }
                                val stepYPx = with(density) { (prof.cellHeightDp + prof.gutterDp).dp.toPx() }
                                val cellDeltaX = (moveDragX / stepXPx).roundToInt()
                                val cellDeltaY = (moveDragY / stepYPx).roundToInt()
                                val hoverX = (start.first + cellDeltaX).coerceIn(0, (prof.columns - currentWidgetState.spanX).coerceAtLeast(0))
                                val hoverY = (start.second + cellDeltaY).coerceIn(0, (prof.rows - currentWidgetState.spanY).coerceAtLeast(0))
                                val currentHover = Pair(hoverX, hoverY)

                                if (currentHover != lastHoverReportedCell) {
                                    lastHoverReportedCell = currentHover
                                    currentOnWidgetHoverCellChanged(currentWidgetState.appWidgetId, hoverX, hoverY)
                                }

                                val wLeftPx = with(density) {
                                    (prof.marginDp + start.first * (prof.cellWidthDp + prof.gutterDp)).dp.toPx()
                                }
                                val wWidthPx = with(density) { prof.widthDpForSpan(currentWidgetState.spanX).dp.toPx() }
                                val startLeftPx = with(density) { targetLeftDp.toPx() }
                                val pointerInRootX = rootLeftPx + startLeftPx + change.position.x
                                val screenWidthPx = with(density) { prof.availableWidthDp.dp.toPx() }

                                val newZone = evaluateEdgeZone(
                                    moveDragX = moveDragX,
                                    wLeftPx = wLeftPx,
                                    wWidthPx = wWidthPx,
                                    pointerInRootX = pointerInRootX,
                                    screenWidthPx = screenWidthPx,
                                    gridProfile = prof,
                                    density = density
                                )

                                if (newZone != activeEdgeZone) {
                                    activeEdgeZone = newZone
                                    dwellJob?.cancel()
                                    dwellJob = if (newZone != null) {
                                        coroutineScope.launch {
                                            while (true) {
                                                delay(500L)
                                                val zone = activeEdgeZone ?: break
                                                if (zone == EdgeDirection.LEFT && currentActivePageIndex <= 0) {
                                                    break
                                                }
                                                currentOnEdgeDwellTriggered(zone)
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                }
                            },
                            onDragCancel = {
                                dwellJob?.cancel()
                                dwellJob = null
                                activeEdgeZone = null
                                isLocalDragging = false
                                currentOnCancelWidgetDrag()
                                dragStartCell = null
                                lastHoverReportedCell = null
                                moveDragX = 0f
                                moveDragY = 0f
                            },
                            onDragEnd = {
                                dwellJob?.cancel()
                                dwellJob = null
                                activeEdgeZone = null
                                isLocalDragging = false
                                val w = currentWidgetState
                                val prof = currentGridProfile
                                val start = dragStartCell ?: Pair(w.cellX, w.cellY)
                                val stepXPx = with(density) { (prof.cellWidthDp + prof.gutterDp).dp.toPx() }
                                val stepYPx = with(density) { (prof.cellHeightDp + prof.gutterDp).dp.toPx() }
                                val cellDeltaX = (moveDragX / stepXPx).roundToInt()
                                val cellDeltaY = (moveDragY / stepYPx).roundToInt()
                                val targetCellX = (start.first + cellDeltaX).coerceIn(0, (prof.columns - w.spanX).coerceAtLeast(0))
                                val targetCellY = (start.second + cellDeltaY).coerceIn(0, (prof.rows - w.spanY).coerceAtLeast(0))

                                dragStartCell = null
                                lastHoverReportedCell = null
                                moveDragX = 0f
                                moveDragY = 0f

                                currentOnCommitWidgetDrag(w.appWidgetId, targetCellX, targetCellY)
                            }
                        )
                    }
            ) {
                // Resize frame & action pill follow visual drag offset
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(moveDragX.roundToInt(), moveDragY.roundToInt()) }
                ) {
                    WidgetEditResizeFrame(
                        widget = frameWidget,
                        gridProfile = currentGridProfile,
                        isEditing = true,
                        onResizeWidget = { newX, newY, newSpanX, newSpanY ->
                            isLocalResizing = false
                            resizeStartBounds = null
                            currentOnCommitWidgetResize(currentWidgetState.appWidgetId, newX, newY, newSpanX, newSpanY)
                        },
                        onRemoveWidget = { id ->
                            currentOnRemoveWidget(id)
                        },
                        onReconfigureWidget = { id ->
                            currentOnReconfigureWidget?.invoke(id)
                        },
                        onStartResize = {
                            isLocalResizing = true
                            resizeStartBounds = io.devnullapps.widgetleaf.domain.model.GridBounds(
                                cellX = currentWidgetState.cellX,
                                cellY = currentWidgetState.cellY,
                                spanX = currentWidgetState.spanX,
                                spanY = currentWidgetState.spanY
                            )
                            currentOnStartWidgetResize(currentWidgetState.appWidgetId)
                        },
                        onResizeBoundsChange = { newX, newY, newSpanX, newSpanY ->
                            currentOnWidgetResizeBoundsChanged(currentWidgetState.appWidgetId, newX, newY, newSpanX, newSpanY)
                        },
                        onCancelResize = {
                            isLocalResizing = false
                            resizeStartBounds = null
                            currentOnCancelWidgetResize()
                        }
                    )
                }
            }
        }
    }
}

internal fun evaluateEdgeZone(
    moveDragX: Float,
    wLeftPx: Float,
    wWidthPx: Float,
    pointerInRootX: Float,
    screenWidthPx: Float,
    gridProfile: DesktopGridProfile,
    density: Density
): EdgeDirection? {
    val minHorizontalDragPx = with(density) { 32.dp.toPx() }
    val edgeOvershootPx = with(density) { 20.dp.toPx() }
    val screenEdgeZonePx = with(density) { 56.dp.toPx() }

    val gridLeftPx = with(density) { gridProfile.marginDp.dp.toPx() }
    val gridRightPx = with(density) {
        (gridProfile.marginDp + gridProfile.widthDpForSpan(gridProfile.columns)).dp.toPx()
    }
    val currentLeftPx = wLeftPx + moveDragX
    val currentRightPx = currentLeftPx + wWidthPx

    val isLeft = moveDragX < -minHorizontalDragPx &&
        (currentLeftPx < gridLeftPx - edgeOvershootPx || pointerInRootX < screenEdgeZonePx)
    val isRight = moveDragX > minHorizontalDragPx &&
        (currentRightPx > gridRightPx + edgeOvershootPx || pointerInRootX > screenWidthPx - screenEdgeZonePx)

    return when {
        isLeft -> EdgeDirection.LEFT
        isRight -> EdgeDirection.RIGHT
        else -> null
    }
}
