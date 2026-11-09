package io.devnullapps.widgetleaf.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onEmptyCanvasClick() },
                    onLongPress = { pos -> onOpenContextMenu(Pair(pos.x, pos.y)) }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press &&
                            (event.buttons.isSecondaryPressed || event.keyboardModifiers.isCtrlPressed)
                        ) {
                            val pos = event.changes.firstOrNull()?.position ?: Offset.Zero
                            onOpenContextMenu(Pair(pos.x, pos.y))
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

                var moveDragX by remember { mutableFloatStateOf(0f) }
                var moveDragY by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .size(width = widthDp, height = heightDp)
                        .alpha(animatedAlpha.value)
                        .testTag("placed_widget_${widget.appWidgetId}")
                        .then(
                            if (isEditing) {
                                Modifier.pointerInput(widget.appWidgetId) {
                                    detectDragGestures(
                                        onDragStart = {
                                            moveDragX = 0f
                                            moveDragY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            moveDragX += dragAmount.x
                                            moveDragY += dragAmount.y
                                        },
                                        onDragEnd = {
                                            val cellDeltaX = (moveDragX / cellStepXPx).roundToInt()
                                            val cellDeltaY = (moveDragY / cellStepYPx).roundToInt()
                                            val targetCellX = (widget.cellX + cellDeltaX).coerceIn(0, gridProfile.columns - widget.spanX)
                                            val targetCellY = (widget.cellY + cellDeltaY).coerceIn(0, gridProfile.rows - widget.spanY)

                                            // Check edge drag thresholds (dragged past screen edges)
                                            val currentLeftPx = with(density) { leftDp.toPx() } + moveDragX
                                            val currentRightPx = currentLeftPx + with(density) { widthDp.toPx() }
                                            val screenWidthPx = with(density) { gridProfile.availableWidthDp.dp.toPx() }
                                            val edgeThresholdPx = with(density) { 48.dp.toPx() }

                                            if (currentLeftPx < edgeThresholdPx) {
                                                val landingX = (gridProfile.columns - widget.spanX).coerceAtLeast(0)
                                                onDragAcrossEdge(widget.appWidgetId, EdgeDirection.LEFT, landingX, targetCellY)
                                            } else if (currentRightPx > screenWidthPx - edgeThresholdPx) {
                                                onDragAcrossEdge(widget.appWidgetId, EdgeDirection.RIGHT, 0, targetCellY)
                                            } else {
                                                onMoveWidget(widget.appWidgetId, targetCellX, targetCellY)
                                            }

                                            moveDragX = 0f
                                            moveDragY = 0f
                                        }
                                    )
                                }
                            } else {
                                Modifier
                                    .combinedClickable(
                                        onClick = { /* Interactive widget click handled by host view */ },
                                        onLongClick = { onEnterEditMode(widget.appWidgetId) }
                                    )
                                    .pointerInput(widget.appWidgetId) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                                    onEnterEditMode(widget.appWidgetId)
                                                }
                                            }
                                        }
                                    }
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
                            onResizeWidget(widget.appWidgetId, newX, newY, newSpanX, newSpanY)
                        },
                        onRemoveWidget = onRemoveWidget,
                        onReconfigureWidget = onReconfigureWidget
                    )
                }
            }
        }
    }
}
