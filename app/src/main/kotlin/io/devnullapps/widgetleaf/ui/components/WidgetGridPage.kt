package io.devnullapps.widgetleaf.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.usecase.EdgeDirection
import io.devnullapps.widgetleaf.ui.theme.MotionSpec
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun DefaultFramelessWidgetContent(
    widget: PlacedWidget,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(WidgetLeafTheme.palette.glassSurfaceHover),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = widget.label.ifEmpty { "Widget ${widget.appWidgetId}" },
            color = WidgetLeafTheme.palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetGridPage(
    widgets: List<PlacedWidget>,
    gridProfile: DesktopGridProfile,
    showEmptyCanvasHint: Boolean,
    editingWidgetId: Int?,
    useCenteredBentoCanvas: Boolean = true,
    onEnterEditMode: (appWidgetId: Int) -> Unit = {},
    onMoveWidget: (appWidgetId: Int, newCellX: Int, newCellY: Int) -> Unit = { _, _, _ -> },
    onResizeWidget: (appWidgetId: Int, newCellX: Int, newCellY: Int, newSpanX: Int, newSpanY: Int) -> Unit = { _, _, _, _, _ -> },
    onDragAcrossEdge: (appWidgetId: Int, direction: EdgeDirection, targetCellX: Int, targetCellY: Int) -> Unit = { _, _, _, _ -> },
    onRemoveWidget: (appWidgetId: Int) -> Unit = {},
    onReconfigureWidget: ((appWidgetId: Int) -> Unit)? = null,
    onEmptyCanvasClick: () -> Unit = {},
    onOpenContextMenu: (Pair<Float, Float>) -> Unit = {},
    modifier: Modifier = Modifier,
    widgetContent: @Composable (PlacedWidget) -> Unit = { DefaultFramelessWidgetContent(it) }
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var lastContextMenuOpenTimeMs by remember { mutableLongStateOf(-10_000L) }
    var lastWidgetActionTimeMs by remember { mutableLongStateOf(-10_000L) }
    var gridOffsetInRoot by remember { mutableFloatStateOf(0f) }

    val currentWidgets by rememberUpdatedState(widgets)
    val currentEditingWidgetId by rememberUpdatedState(editingWidgetId)
    val currentGridProfile by rememberUpdatedState(gridProfile)
    val currentOnEnterEditMode by rememberUpdatedState(onEnterEditMode)
    val currentOnEmptyCanvasClick by rememberUpdatedState(onEmptyCanvasClick)
    val currentOnOpenContextMenu by rememberUpdatedState(onOpenContextMenu)

    val movePointerIcon = rememberSystemPointerIcon(
        context,
        android.view.PointerIcon.TYPE_ALL_SCROLL,
        PointerIcon.Hand
    )

    fun findWidgetAtPosition(pos: Offset): PlacedWidget? {
        val profile = currentGridProfile
        val localX = pos.x - gridOffsetInRoot
        val localY = pos.y
        return currentWidgets.firstOrNull { w ->
            val leftPx = with(density) {
                (profile.marginDp + w.cellX * (profile.cellWidthDp + profile.gutterDp)).dp.toPx()
            }
            val topPx = with(density) {
                (profile.marginDp + w.cellY * (profile.cellHeightDp + profile.gutterDp)).dp.toPx()
            }
            val widthPx = with(density) { profile.widthDpForSpan(w.spanX).dp.toPx() }
            val heightPx = with(density) { profile.heightDpForSpan(w.spanY).dp.toPx() }
            val padPx = if (currentEditingWidgetId == w.appWidgetId) {
                with(density) { 16.dp.toPx() }
            } else {
                0f
            }
            localX >= (leftPx - padPx) &&
                localX <= (leftPx + widthPx + padPx) &&
                localY >= (topPx - padPx) &&
                localY <= (topPx + heightPx + padPx)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    var firstDownPos: Offset? = null
                    var hitWidgetOnDown: PlacedWidget? = null
                    var isSecondaryOrTwoFinger = false
                    var contextMenuOrEditTriggered = false
                    var consumedByChild = false
                    var movedBeyondSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                    var downTimeMs = 0L

                    while (true) {
                        val timeoutRemaining = if (
                            firstDownPos != null &&
                            !isSecondaryOrTwoFinger &&
                            !contextMenuOrEditTriggered &&
                            !consumedByChild &&
                            !movedBeyondSlop
                        ) {
                            (longPressTimeoutMs - (SystemClock.uptimeMillis() - downTimeMs)).coerceAtLeast(1L)
                        } else {
                            null
                        }

                        val event = if (timeoutRemaining != null) {
                            withTimeoutOrNull(timeoutRemaining) {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                        } else {
                            awaitPointerEvent(PointerEventPass.Main)
                        }

                        if (event == null) {
                            // Long-press timeout reached while finger still down
                            val pos = firstDownPos ?: Offset.Zero
                            contextMenuOrEditTriggered = true
                            val targetWidget = hitWidgetOnDown ?: findWidgetAtPosition(pos)
                            if (targetWidget != null) {
                                lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                                currentOnEnterEditMode(targetWidget.appWidgetId)
                            } else if (currentEditingWidgetId == null) {
                                lastContextMenuOpenTimeMs = SystemClock.uptimeMillis()
                                currentOnOpenContextMenu(Pair(pos.x, pos.y))
                            }
                            continue
                        }

                        if (event.changes.any { it.isConsumed }) {
                            consumedByChild = true
                        }

                        val anyPressed = event.changes.any { it.pressed }
                        val anyPreviouslyPressed = event.changes.any { it.previousPressed }
                        if (firstDownPos == null && !anyPressed && !event.buttons.isSecondaryPressed) {
                            continue
                        }

                        if (firstDownPos == null) {
                            val downPos = event.changes.firstOrNull()?.position ?: Offset.Zero
                            firstDownPos = downPos
                            downTimeMs = SystemClock.uptimeMillis()
                            hitWidgetOnDown = findWidgetAtPosition(downPos)
                        }

                        if (consumedByChild) {
                            if (!anyPressed) {
                                break
                            }
                            continue
                        }

                        val pressedCount = event.changes.count { it.pressed }
                        if (event.buttons.isSecondaryPressed ||
                            event.keyboardModifiers.isCtrlPressed ||
                            pressedCount >= 2
                        ) {
                            isSecondaryOrTwoFinger = true
                            if (!contextMenuOrEditTriggered) {
                                val pos = firstDownPos ?: event.changes.firstOrNull()?.position ?: Offset.Zero
                                contextMenuOrEditTriggered = true
                                val targetWidget = hitWidgetOnDown ?: findWidgetAtPosition(pos)
                                if (targetWidget != null) {
                                    lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                                    currentOnEnterEditMode(targetWidget.appWidgetId)
                                } else if (currentEditingWidgetId == null) {
                                    lastContextMenuOpenTimeMs = SystemClock.uptimeMillis()
                                    currentOnOpenContextMenu(Pair(pos.x, pos.y))
                                }
                            }
                        }

                        if (isSecondaryOrTwoFinger || contextMenuOrEditTriggered) {
                            event.changes.forEach { it.consume() }
                            if (!anyPressed) {
                                break
                            }
                            continue
                        }

                        val currentPos = event.changes.firstOrNull()?.position ?: firstDownPos
                        if ((currentPos - firstDownPos).getDistance() > touchSlop) {
                            movedBeyondSlop = true
                        }

                        if (!anyPressed && anyPreviouslyPressed) {
                            event.changes.forEach { it.consume() }
                            val now = SystemClock.uptimeMillis()
                            val sinceContextMs = now - lastContextMenuOpenTimeMs
                            val sinceWidgetActionMs = now - lastWidgetActionTimeMs
                            if (!movedBeyondSlop &&
                                !consumedByChild &&
                                hitWidgetOnDown == null &&
                                sinceContextMs > 400L &&
                                sinceWidgetActionMs > 350L
                            ) {
                                currentOnEmptyCanvasClick()
                            }
                            break
                        }
                    }
                }
            }
    ) {
        // Empty canvas hint when 0 widgets on page 1
        if (showEmptyCanvasHint) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Right-click or press + to add a widget",
                    color = WidgetLeafTheme.palette.textSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.testTag("empty_canvas_hint")
                )
            }
        }

        // Grid container, centered if useCenteredBentoCanvas == true
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (useCenteredBentoCanvas) {
                        Modifier
                            .widthIn(max = gridProfile.maxCanvasWidthDp.dp)
                            .align(Alignment.Center)
                    } else {
                        Modifier
                    }
                )
                .onGloballyPositioned { coords ->
                    gridOffsetInRoot = coords.positionInRoot().x
                }
        ) {
            val cellStepXPx = with(density) { (gridProfile.cellWidthDp + gridProfile.gutterDp).dp.toPx() }
            val cellStepYPx = with(density) { (gridProfile.cellHeightDp + gridProfile.gutterDp).dp.toPx() }

            widgets.forEach { widget ->
                val isEditing = editingWidgetId == widget.appWidgetId

                val widthDp = gridProfile.widthDpForSpan(widget.spanX).dp
                val heightDp = gridProfile.heightDpForSpan(widget.spanY).dp
                val leftDp = (gridProfile.marginDp + widget.cellX * (gridProfile.cellWidthDp + gridProfile.gutterDp)).dp
                val topDp = (gridProfile.marginDp + widget.cellY * (gridProfile.cellHeightDp + gridProfile.gutterDp)).dp

                // Staggered column entry cascade animation
                val animatedAlpha = remember(widget.appWidgetId) { Animatable(1f) }
                LaunchedEffect(widget.appWidgetId) {
                    delay(widget.cellX * MotionSpec.COLUMN_STAGGER_DELAY_MS.toLong())
                    animatedAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = MotionSpec.OVERLAY_ENTRY_DURATION_MS,
                            easing = MotionSpec.OverlayEntryEasing
                        )
                    )
                }

                var moveDragX by remember(widget.appWidgetId, widget.cellX, widget.cellY) {
                    mutableFloatStateOf(0f)
                }
                var moveDragY by remember(widget.appWidgetId, widget.cellX, widget.cellY) {
                    mutableFloatStateOf(0f)
                }
                var resizeLeftPx by remember(widget.appWidgetId) { mutableFloatStateOf(0f) }
                var resizeTopPx by remember(widget.appWidgetId) { mutableFloatStateOf(0f) }
                var resizeRightPx by remember(widget.appWidgetId) { mutableFloatStateOf(0f) }
                var resizeBottomPx by remember(widget.appWidgetId) { mutableFloatStateOf(0f) }

                val resizeLeftDp = with(density) { resizeLeftPx.toDp() }
                val resizeTopDp = with(density) { resizeTopPx.toDp() }
                val resizeRightDp = with(density) { resizeRightPx.toDp() }
                val resizeBottomDp = with(density) { resizeBottomPx.toDp() }

                val effectiveWidthDp = if (isEditing) {
                    (widthDp + resizeRightDp - resizeLeftDp).coerceAtLeast(64.dp)
                } else {
                    widthDp
                }
                val effectiveHeightDp = if (isEditing) {
                    (heightDp + resizeBottomDp - resizeTopDp).coerceAtLeast(64.dp)
                } else {
                    heightDp
                }

                val currentWidgetState by rememberUpdatedState(widget)

                Box(
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .offset {
                            if (isEditing) {
                                IntOffset(
                                    (moveDragX + resizeLeftPx).roundToInt(),
                                    (moveDragY + resizeTopPx).roundToInt()
                                )
                            } else {
                                IntOffset.Zero
                            }
                        }
                        .size(width = effectiveWidthDp, height = effectiveHeightDp)
                        .alpha(animatedAlpha.value)
                        .testTag("placed_widget_${widget.appWidgetId}")
                        .then(
                            if (isEditing) {
                                Modifier
                                    .pointerHoverIcon(movePointerIcon)
                                    .pointerInput(widget.appWidgetId) {
                                        detectDragGestures(
                                            onDragStart = {
                                                lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                                                moveDragX = 0f
                                                moveDragY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                                                moveDragX += dragAmount.x
                                                moveDragY += dragAmount.y
                                            },
                                            onDragCancel = {
                                                moveDragX = 0f
                                                moveDragY = 0f
                                            },
                                            onDragEnd = {
                                                lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                                                val w = currentWidgetState
                                                val prof = currentGridProfile
                                                val stepXPx = with(density) { (prof.cellWidthDp + prof.gutterDp).dp.toPx() }
                                                val stepYPx = with(density) { (prof.cellHeightDp + prof.gutterDp).dp.toPx() }
                                                val cellDeltaX = (moveDragX / stepXPx).roundToInt()
                                                val cellDeltaY = (moveDragY / stepYPx).roundToInt()
                                                val targetCellX = (w.cellX + cellDeltaX).coerceIn(0, (prof.columns - w.spanX).coerceAtLeast(0))
                                                val targetCellY = (w.cellY + cellDeltaY).coerceIn(0, (prof.rows - w.spanY).coerceAtLeast(0))

                                                // Check edge drag thresholds (dragged past screen edges)
                                                val wLeftPx = with(density) {
                                                    (prof.marginDp + w.cellX * (prof.cellWidthDp + prof.gutterDp)).dp.toPx()
                                                }
                                                val wWidthPx = with(density) { prof.widthDpForSpan(w.spanX).dp.toPx() }
                                                val currentLeftPx = wLeftPx + moveDragX
                                                val currentRightPx = currentLeftPx + wWidthPx
                                                val screenWidthPx = with(density) { prof.availableWidthDp.dp.toPx() }
                                                val edgeThresholdPx = with(density) { 48.dp.toPx() }

                                                if (currentLeftPx < edgeThresholdPx) {
                                                    val landingX = (prof.columns - w.spanX).coerceAtLeast(0)
                                                    onDragAcrossEdge(w.appWidgetId, EdgeDirection.LEFT, landingX, targetCellY)
                                                } else if (currentRightPx > screenWidthPx - edgeThresholdPx) {
                                                    onDragAcrossEdge(w.appWidgetId, EdgeDirection.RIGHT, 0, targetCellY)
                                                } else {
                                                    onMoveWidget(w.appWidgetId, targetCellX, targetCellY)
                                                }

                                                moveDragX = 0f
                                                moveDragY = 0f
                                            }
                                        )
                                    }
                            } else {
                                Modifier
                                    .pointerInput(widget.appWidgetId) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                val pressedCount = event.changes.count { it.pressed }
                                                if (event.type == PointerEventType.Press &&
                                                    (event.buttons.isSecondaryPressed ||
                                                        event.keyboardModifiers.isCtrlPressed ||
                                                        pressedCount >= 2)
                                                ) {
                                                    event.changes.forEach { it.consume() }
                                                    lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                                                    onEnterEditMode(widget.appWidgetId)
                                                    do {
                                                        val next = awaitPointerEvent(PointerEventPass.Initial)
                                                        next.changes.forEach { it.consume() }
                                                    } while (next.changes.any { it.pressed })
                                                }
                                            }
                                        }
                                    }
                                    .combinedClickable(
                                        onClick = { /* Interactive widget click handled by host view */ },
                                        onLongClick = {
                                            lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                                            onEnterEditMode(widget.appWidgetId)
                                        }
                                    )
                            }
                        )
                ) {
                    // Frameless widget rendering
                    widgetContent(widget)

                    // Edit mode overlay with 4-edge handles and action pill
                    WidgetEditResizeFrame(
                        widget = widget,
                        gridProfile = gridProfile,
                        isEditing = isEditing,
                        onResizeWidget = { newX, newY, newSpanX, newSpanY ->
                            lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                            onResizeWidget(widget.appWidgetId, newX, newY, newSpanX, newSpanY)
                        },
                        onRemoveWidget = { id ->
                            lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                            onRemoveWidget(id)
                        },
                        onReconfigureWidget = { id ->
                            lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                            onReconfigureWidget?.invoke(id)
                        },
                        onResizePreviewChange = { lPx, tPx, rPx, bPx ->
                            lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                            resizeLeftPx = lPx
                            resizeTopPx = tPx
                            resizeRightPx = rPx
                            resizeBottomPx = bPx
                        }
                    )
                }
            }
        }
    }
}
