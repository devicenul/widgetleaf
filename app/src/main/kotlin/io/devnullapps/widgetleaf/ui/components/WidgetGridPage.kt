package io.devnullapps.widgetleaf.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
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
import androidx.compose.ui.unit.Dp
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
    draggingWidgetId: Int? = null,
    draggedWidget: PlacedWidget? = null,
    hoveredCell: Pair<Int, Int>? = null,
    onStartWidgetDrag: (appWidgetId: Int) -> Unit = {},
    onWidgetHoverCellChanged: (appWidgetId: Int, cellX: Int, cellY: Int) -> Unit = { _, _, _ -> },
    onCommitWidgetDrag: ((appWidgetId: Int, cellX: Int, cellY: Int) -> Unit)? = null,
    onCancelWidgetDrag: () -> Unit = {},
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

    fun findWidgetAtPosition(pos: Offset): PlacedWidget? {
        val profile = currentGridProfile
        val localX = pos.x - gridOffsetInRoot
        val localY = pos.y
        return currentWidgets.firstOrNull { w ->
            val leftPx = with(density) {
                (profile.marginDp + w.cellX * (profile.cellWidthDp + profile.gutterDp)).dp.toPx()
            }
            val topPx = with(density) {
                (profile.topMarginDp + w.cellY * (profile.cellHeightDp + profile.gutterDp)).dp.toPx()
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
                            val targetWidget = hitWidgetOnDown ?: findWidgetAtPosition(pos)
                            if (targetWidget != null) {
                                lastWidgetActionTimeMs = SystemClock.uptimeMillis()
                                currentOnEnterEditMode(targetWidget.appWidgetId)
                                // Do not mark contextMenuOrEditTriggered or consume events:
                                // allow the user to drag the widget in the same continuous gesture.
                            } else if (currentEditingWidgetId == null) {
                                contextMenuOrEditTriggered = true
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

        // Grid container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    gridOffsetInRoot = coords.positionInRoot().x
                }
        ) {
            val cellStepXPx = with(density) { (gridProfile.cellWidthDp + gridProfile.gutterDp).dp.toPx() }
            val cellStepYPx = with(density) { (gridProfile.cellHeightDp + gridProfile.gutterDp).dp.toPx() }

            // Subtle drop-target ghost outline at the hovered cell during widget drag
            if (draggingWidgetId != null && hoveredCell != null) {
                val dragged = draggedWidget ?: currentWidgets.find { it.appWidgetId == draggingWidgetId }
                if (dragged != null) {
                    val ghostLeftDp = (gridProfile.marginDp + hoveredCell.first * (gridProfile.cellWidthDp + gridProfile.gutterDp)).dp
                    val ghostTopDp = (gridProfile.topMarginDp + hoveredCell.second * (gridProfile.cellHeightDp + gridProfile.gutterDp)).dp
                    val ghostWidthDp = gridProfile.widthDpForSpan(dragged.spanX).dp
                    val ghostHeightDp = gridProfile.heightDpForSpan(dragged.spanY).dp

                    Box(
                        modifier = Modifier
                            .offset(x = ghostLeftDp, y = ghostTopDp)
                            .size(width = ghostWidthDp, height = ghostHeightDp)
                            .border(
                                width = 1.5.dp,
                                color = WidgetLeafTheme.palette.leafBright.copy(alpha = 0.50f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .background(
                                color = WidgetLeafTheme.palette.leafBright.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .testTag("drag_ghost_outline")
                    )
                }
            }

            widgets.forEach { widget ->
                if (widget.appWidgetId == editingWidgetId || widget.appWidgetId == draggingWidgetId) {
                    return@forEach
                }
                key(widget.appWidgetId) {
                    var isFirstComposition by remember(widget.appWidgetId) { mutableStateOf(true) }
                    SideEffect {
                        isFirstComposition = false
                    }

                    val widthDp = gridProfile.widthDpForSpan(widget.spanX).dp
                    val heightDp = gridProfile.heightDpForSpan(widget.spanY).dp

                    val targetLeftDp = (gridProfile.marginDp + widget.cellX * (gridProfile.cellWidthDp + gridProfile.gutterDp)).dp
                    val targetTopDp = (gridProfile.topMarginDp + widget.cellY * (gridProfile.cellHeightDp + gridProfile.gutterDp)).dp

                    // Non-dragged widgets animate position changes with spring physics
                    val animatedLeftDp by animateDpAsState(
                        targetValue = targetLeftDp,
                        animationSpec = if (!isFirstComposition) {
                            spring<Dp>(
                                dampingRatio = MotionSpec.PAGE_FLIP_SPRING_DAMPING,
                                stiffness = MotionSpec.PAGE_FLIP_SPRING_STIFFNESS
                            )
                        } else {
                            snap<Dp>()
                        },
                        label = "widget_x_${widget.appWidgetId}"
                    )
                    val animatedTopDp by animateDpAsState(
                        targetValue = targetTopDp,
                        animationSpec = if (!isFirstComposition) {
                            spring<Dp>(
                                dampingRatio = MotionSpec.PAGE_FLIP_SPRING_DAMPING,
                                stiffness = MotionSpec.PAGE_FLIP_SPRING_STIFFNESS
                            )
                        } else {
                            snap<Dp>()
                        },
                        label = "widget_y_${widget.appWidgetId}"
                    )

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

                    Box(
                        modifier = Modifier
                            .offset(x = animatedLeftDp, y = animatedTopDp)
                            .size(width = widthDp, height = heightDp)
                            .alpha(animatedAlpha.value)
                            .testTag("placed_widget_${widget.appWidgetId}")
                            .pointerInput(widget.appWidgetId, false) {
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
                    ) {
                        widgetContent(widget)
                    }
                }
            }
        }
    }
}
