package io.devnullapps.widgetleaf.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
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
import io.devnullapps.widgetleaf.ui.theme.DangerCoral
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme
import kotlin.math.roundToInt

/**
 * Reusable glassmorphic surface following the WidgetLeaf Design Bible:
 * - glassSurface background (Color(0xA6161B22))
 * - 18.dp rounded continuous squircle corners
 * - 1.dp outer glassBorder
 * - 0.5.dp top-left glassSpecular highlight bevel rim
 */
@Composable
fun GlassHudSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    backgroundColor: Color = WidgetLeafTheme.palette.glassSurface,
    borderColor: Color = WidgetLeafTheme.palette.glassBorder,
    specularColor: Color = WidgetLeafTheme.palette.glassSpecular,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(borderWidth, borderColor, shape)
            .drawWithContent {
                drawContent()
                val specularWidth = 0.5.dp.toPx()
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(specularColor, Color.Transparent),
                        startX = 0f,
                        endX = size.width * 0.5f
                    ),
                    start = Offset(0f, specularWidth / 2f),
                    end = Offset(size.width * 0.5f, specularWidth / 2f),
                    strokeWidth = specularWidth
                )
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(specularColor, Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.5f
                    ),
                    start = Offset(specularWidth / 2f, 0f),
                    end = Offset(specularWidth / 2f, size.height * 0.5f),
                    strokeWidth = specularWidth
                )
            }
            .padding(contentPadding),
        content = content
    )
}

@Composable
internal fun rememberSystemPointerIcon(context: Context, type: Int, fallback: PointerIcon = PointerIcon.Default): PointerIcon {
    return remember(context, type) {
        try {
            val sysIcon = android.view.PointerIcon.getSystemIcon(context, type)
            if (sysIcon != null) PointerIcon(sysIcon) else fallback
        } catch (_: Throwable) {
            fallback
        }
    }
}

/**
 * Frame rendered ONLY when a widget is in EDIT_MODE.
 * Draws:
 * - Subtle dark edit scrim and 2.dp leafPrimary (#10B981) border
 * - 4-edge leafBright (#34D399) drag handles with generous grab hitboxes (88x28dp / 28x88dp)
 *   and native resize pointer cursors (↕ / ↔)
 * - Centered floating glassmorphic action pill inside the widget bounds (so it never overlaps
 *   the top status bar or edge resize handles) with large hitboxes for Reconfigure and Remove
 */
@Composable
fun WidgetEditResizeFrame(
    widget: PlacedWidget,
    gridProfile: DesktopGridProfile,
    isEditing: Boolean,
    onResizeWidget: (newCellX: Int, newCellY: Int, newSpanX: Int, newSpanY: Int) -> Unit,
    onRemoveWidget: (appWidgetId: Int) -> Unit,
    onReconfigureWidget: ((appWidgetId: Int) -> Unit)? = null,
    onResizePreviewChange: (leftDeltaPx: Float, topDeltaPx: Float, rightDeltaPx: Float, bottomDeltaPx: Float) -> Unit = { _, _, _, _ -> },
    onStartResize: () -> Unit = {},
    onResizeBoundsChange: (newCellX: Int, newCellY: Int, newSpanX: Int, newSpanY: Int) -> Unit = { _, _, _, _ -> },
    onCancelResize: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!isEditing) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val cellStepXPx = with(density) { (gridProfile.cellWidthDp + gridProfile.gutterDp).dp.toPx() }
    val cellStepYPx = with(density) { (gridProfile.cellHeightDp + gridProfile.gutterDp).dp.toPx() }

    val currentWidget by rememberUpdatedState(widget)
    val currentGridProfile by rememberUpdatedState(gridProfile)
    val currentStepXPx by rememberUpdatedState(cellStepXPx)
    val currentStepYPx by rememberUpdatedState(cellStepYPx)
    val currentOnResizeWidget by rememberUpdatedState(onResizeWidget)
    val currentOnResizePreviewChange by rememberUpdatedState(onResizePreviewChange)
    val currentOnStartResize by rememberUpdatedState(onStartResize)
    val currentOnResizeBoundsChange by rememberUpdatedState(onResizeBoundsChange)
    val currentOnCancelResize by rememberUpdatedState(onCancelResize)

    val verticalResizeIcon = rememberSystemPointerIcon(
        context,
        android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW,
        PointerIcon.Hand
    )
    val horizontalResizeIcon = rememberSystemPointerIcon(
        context,
        android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,
        PointerIcon.Hand
    )
    val diagonalTlBrIcon = rememberSystemPointerIcon(
        context,
        android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW,
        PointerIcon.Hand
    )
    val diagonalTrBlIcon = rememberSystemPointerIcon(
        context,
        android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW,
        PointerIcon.Hand
    )

    var topDragY by remember { mutableFloatStateOf(0f) }
    var bottomDragY by remember { mutableFloatStateOf(0f) }
    var leftDragX by remember { mutableFloatStateOf(0f) }
    var rightDragX by remember { mutableFloatStateOf(0f) }
    val leafBrightColor = WidgetLeafTheme.palette.leafBright

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("widget_edit_frame_${widget.appWidgetId}")
            .drawWithContent {
                drawContent()
                if (topDragY != 0f || bottomDragY != 0f || leftDragX != 0f || rightDragX != 0f) {
                    val minSizePx = 48.dp.toPx()
                    val previewLeft = leftDragX.coerceAtMost(size.width - minSizePx)
                    val previewTop = topDragY.coerceAtMost(size.height - minSizePx)
                    val previewWidth = (size.width + rightDragX - previewLeft).coerceAtLeast(minSizePx)
                    val previewHeight = (size.height + bottomDragY - previewTop).coerceAtLeast(minSizePx)
                    drawRoundRect(
                        color = leafBrightColor.copy(alpha = 0.20f),
                        topLeft = Offset(previewLeft, previewTop),
                        size = Size(previewWidth, previewHeight),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                    )
                    drawRoundRect(
                        color = leafBrightColor,
                        topLeft = Offset(previewLeft, previewTop),
                        size = Size(previewWidth, previewHeight),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            }
            .background(Color(0x400D1117), RoundedCornerShape(16.dp))
            .border(2.dp, WidgetLeafTheme.palette.leafPrimary, RoundedCornerShape(16.dp))
    ) {
        // Centered floating action pill (100% inside widget hit bounds, never overlaps top bar or edge handles)
        GlassHudSurface(
            modifier = Modifier.align(Alignment.Center),
            shape = RoundedCornerShape(22.dp),
            backgroundColor = Color(0xF2161B22),
            borderColor = WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.7f),
            borderWidth = 1.dp,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (widget.isReconfigurable) {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.22f))
                            .border(
                                0.5.dp,
                                WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.6f),
                                RoundedCornerShape(14.dp)
                            )
                            .pointerHoverIcon(PointerIcon.Hand)
                            .testTag("widget_reconfigure_${widget.appWidgetId}")
                            .clickable {
                                onReconfigureWidget?.invoke(widget.appWidgetId)
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Reconfigure",
                            color = WidgetLeafTheme.palette.leafBright,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(DangerCoral.copy(alpha = 0.20f))
                        .border(
                            0.5.dp,
                            DangerCoral.copy(alpha = 0.65f),
                            RoundedCornerShape(14.dp)
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("widget_remove_${widget.appWidgetId}")
                        .clickable {
                            onRemoveWidget(widget.appWidgetId)
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Remove",
                        color = DangerCoral,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        val canResizeHorizontal = (widget.resizeMode and PlacedWidget.RESIZE_HORIZONTAL) != 0
        val canResizeVertical = (widget.resizeMode and PlacedWidget.RESIZE_VERTICAL) != 0

        val maxAvailableX = if (widget.maxSpanX in 1 until gridProfile.columns) widget.maxSpanX else gridProfile.columns
        val maxAvailableY = if (widget.maxSpanY in 1 until gridProfile.rows) widget.maxSpanY else gridProfile.rows
        val minAllowedX = widget.minSpanX.coerceIn(1, maxAvailableX)
        val minAllowedY = widget.minSpanY.coerceIn(1, maxAvailableY)

        // Top drag handle (centered handle grab zone with ↕ resize cursor)
        if (canResizeVertical) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(64.dp)
                    .height(20.dp)
                    .pointerHoverIcon(verticalResizeIcon)
                    .testTag("resize_handle_top_${widget.appWidgetId}")
                    .pointerInput(widget.appWidgetId) {
                        detectDragGestures(
                            onDragStart = {
                                topDragY = 0f
                                currentOnStartResize()
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxUpwardDragPx = minOf(maxAvailableY - currentWidget.spanY, currentWidget.cellY) * currentStepYPx
                                val maxDownwardDragPx = (currentWidget.spanY - minAllowedY) * currentStepYPx
                                topDragY = (topDragY + dragAmount.y).coerceIn(-maxUpwardDragPx, maxDownwardDragPx)
                                currentOnResizePreviewChange(0f, topDragY, 0f, 0f)

                                val w = currentWidget
                                val cellDelta = (topDragY / currentStepYPx).roundToInt()
                                val maxSpanForCurrentTop = w.cellY + w.spanY
                                val maxAllowedSpanY = minOf(maxAvailableY, maxSpanForCurrentTop)
                                val newSpanY = (w.spanY - cellDelta).coerceIn(minAllowedY, maxAllowedSpanY)
                                val newCellY = (w.cellY + w.spanY) - newSpanY
                                currentOnResizeBoundsChange(w.cellX, newCellY, w.spanX, newSpanY)
                            },
                            onDragCancel = {
                                topDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnCancelResize()
                            },
                            onDragEnd = {
                                val w = currentWidget
                                val cellDelta = (topDragY / currentStepYPx).roundToInt()
                                val maxSpanForCurrentTop = w.cellY + w.spanY
                                val maxAllowedSpanY = minOf(maxAvailableY, maxSpanForCurrentTop)
                                val newSpanY = (w.spanY - cellDelta).coerceIn(minAllowedY, maxAllowedSpanY)
                                val newCellY = (w.cellY + w.spanY) - newSpanY
                                topDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnResizeWidget(w.cellX, newCellY, w.spanX, newSpanY)
                            }
                        )
                    },
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, topDragY.roundToInt()) }
                        .padding(top = 3.dp)
                        .size(width = 56.dp, height = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(WidgetLeafTheme.palette.leafBright)
                        .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(4.dp))
                )
            }

            // Bottom drag handle (centered handle grab zone with ↕ resize cursor)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(64.dp)
                    .height(20.dp)
                    .pointerHoverIcon(verticalResizeIcon)
                    .testTag("resize_handle_bottom_${widget.appWidgetId}")
                    .pointerInput(widget.appWidgetId) {
                        detectDragGestures(
                            onDragStart = {
                                bottomDragY = 0f
                                currentOnStartResize()
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxUpwardDragPx = (currentWidget.spanY - minAllowedY) * currentStepYPx
                                val maxDownwardDragPx = (minOf(maxAvailableY, currentGridProfile.rows - currentWidget.cellY) - currentWidget.spanY) * currentStepYPx
                                bottomDragY = (bottomDragY + dragAmount.y).coerceIn(-maxUpwardDragPx, maxDownwardDragPx)
                                currentOnResizePreviewChange(0f, 0f, 0f, bottomDragY)

                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDelta = (bottomDragY / currentStepYPx).roundToInt()
                                val maxAvailableSpanY = minOf(maxAvailableY, prof.rows - w.cellY)
                                val newSpanY = (w.spanY + cellDelta).coerceIn(minAllowedY, maxAvailableSpanY)
                                currentOnResizeBoundsChange(w.cellX, w.cellY, w.spanX, newSpanY)
                            },
                            onDragCancel = {
                                bottomDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnCancelResize()
                            },
                            onDragEnd = {
                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDelta = (bottomDragY / currentStepYPx).roundToInt()
                                val maxAvailableSpanY = minOf(maxAvailableY, prof.rows - w.cellY)
                                val newSpanY = (w.spanY + cellDelta).coerceIn(minAllowedY, maxAvailableSpanY)
                                bottomDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnResizeWidget(w.cellX, w.cellY, w.spanX, newSpanY)
                            }
                        )
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, bottomDragY.roundToInt()) }
                        .padding(bottom = 3.dp)
                        .size(width = 56.dp, height = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(WidgetLeafTheme.palette.leafBright)
                        .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(4.dp))
                )
            }
        }

        // Left drag handle (centered handle grab zone with ↔ resize cursor)
        if (canResizeHorizontal) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(20.dp)
                    .height(64.dp)
                    .pointerHoverIcon(horizontalResizeIcon)
                    .testTag("resize_handle_left_${widget.appWidgetId}")
                    .pointerInput(widget.appWidgetId) {
                        detectDragGestures(
                            onDragStart = {
                                leftDragX = 0f
                                currentOnStartResize()
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxLeftwardDragPx = minOf(maxAvailableX - currentWidget.spanX, currentWidget.cellX) * currentStepXPx
                                val maxRightwardDragPx = (currentWidget.spanX - minAllowedX) * currentStepXPx
                                leftDragX = (leftDragX + dragAmount.x).coerceIn(-maxLeftwardDragPx, maxRightwardDragPx)
                                currentOnResizePreviewChange(leftDragX, 0f, 0f, 0f)

                                val w = currentWidget
                                val cellDelta = (leftDragX / currentStepXPx).roundToInt()
                                val maxSpanForCurrentLeft = w.cellX + w.spanX
                                val maxAllowedSpanX = minOf(maxAvailableX, maxSpanForCurrentLeft)
                                val newSpanX = (w.spanX - cellDelta).coerceIn(minAllowedX, maxAllowedSpanX)
                                val newCellX = (w.cellX + w.spanX) - newSpanX
                                currentOnResizeBoundsChange(newCellX, w.cellY, newSpanX, w.spanY)
                            },
                            onDragCancel = {
                                leftDragX = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnCancelResize()
                            },
                            onDragEnd = {
                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDelta = (leftDragX / currentStepXPx).roundToInt()
                                val maxSpanForCurrentLeft = w.cellX + w.spanX
                                val maxAllowedSpanX = minOf(maxAvailableX, maxSpanForCurrentLeft)
                                val newSpanX = (w.spanX - cellDelta).coerceIn(minAllowedX, maxAllowedSpanX)
                                val newCellX = (w.cellX + w.spanX) - newSpanX
                                leftDragX = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnResizeWidget(newCellX, w.cellY, newSpanX, w.spanY)
                            }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftDragX.roundToInt(), 0) }
                        .padding(start = 3.dp)
                        .size(width = 8.dp, height = 56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(WidgetLeafTheme.palette.leafBright)
                        .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(4.dp))
                )
            }

            // Right drag handle (centered handle grab zone with ↔ resize cursor)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(20.dp)
                    .height(64.dp)
                    .pointerHoverIcon(horizontalResizeIcon)
                    .testTag("resize_handle_right_${widget.appWidgetId}")
                    .pointerInput(widget.appWidgetId) {
                        detectDragGestures(
                            onDragStart = {
                                rightDragX = 0f
                                currentOnStartResize()
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxLeftwardDragPx = (currentWidget.spanX - minAllowedX) * currentStepXPx
                                val maxRightwardDragPx = (minOf(maxAvailableX, currentGridProfile.columns - currentWidget.cellX) - currentWidget.spanX) * currentStepXPx
                                rightDragX = (rightDragX + dragAmount.x).coerceIn(-maxLeftwardDragPx, maxRightwardDragPx)
                                currentOnResizePreviewChange(0f, 0f, rightDragX, 0f)

                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDelta = (rightDragX / currentStepXPx).roundToInt()
                                val maxAvailableSpanX = minOf(maxAvailableX, prof.columns - w.cellX)
                                val newSpanX = (w.spanX + cellDelta).coerceIn(minAllowedX, maxAvailableSpanX)
                                currentOnResizeBoundsChange(w.cellX, w.cellY, newSpanX, w.spanY)
                            },
                            onDragCancel = {
                                rightDragX = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnCancelResize()
                            },
                            onDragEnd = {
                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDelta = (rightDragX / currentStepXPx).roundToInt()
                                val maxAvailableSpanX = minOf(maxAvailableX, prof.columns - w.cellX)
                                val newSpanX = (w.spanX + cellDelta).coerceIn(minAllowedX, maxAvailableSpanX)
                                rightDragX = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnResizeWidget(w.cellX, w.cellY, newSpanX, w.spanY)
                            }
                        )
                    },
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(rightDragX.roundToInt(), 0) }
                        .padding(end = 3.dp)
                        .size(width = 8.dp, height = 56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(WidgetLeafTheme.palette.leafBright)
                        .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(4.dp))
                )
            }
        }

        // Four corner drag handles ONLY for RESIZE_BOTH (diagonal cursors, anchor opposite corner)
        val canResizeBoth = (widget.resizeMode and PlacedWidget.RESIZE_BOTH) == PlacedWidget.RESIZE_BOTH
        if (canResizeBoth) {
            // Top-Left corner handle (\ cursor, anchors bottom-right)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(28.dp)
                    .pointerHoverIcon(diagonalTlBrIcon)
                    .testTag("resize_handle_top_left_${widget.appWidgetId}")
                    .pointerInput(widget.appWidgetId) {
                        detectDragGestures(
                            onDragStart = {
                                leftDragX = 0f
                                topDragY = 0f
                                currentOnStartResize()
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxLeftwardDragPx = minOf(maxAvailableX - currentWidget.spanX, currentWidget.cellX) * currentStepXPx
                                val maxRightwardDragPx = (currentWidget.spanX - minAllowedX) * currentStepXPx
                                leftDragX = (leftDragX + dragAmount.x).coerceIn(-maxLeftwardDragPx, maxRightwardDragPx)

                                val maxUpwardDragPx = minOf(maxAvailableY - currentWidget.spanY, currentWidget.cellY) * currentStepYPx
                                val maxDownwardDragPx = (currentWidget.spanY - minAllowedY) * currentStepYPx
                                topDragY = (topDragY + dragAmount.y).coerceIn(-maxUpwardDragPx, maxDownwardDragPx)

                                currentOnResizePreviewChange(leftDragX, topDragY, 0f, 0f)

                                val w = currentWidget
                                val cellDeltaX = (leftDragX / currentStepXPx).roundToInt()
                                val cellDeltaY = (topDragY / currentStepYPx).roundToInt()
                                val maxAllowedSpanX = minOf(maxAvailableX, w.cellX + w.spanX)
                                val maxAllowedSpanY = minOf(maxAvailableY, w.cellY + w.spanY)
                                val newSpanX = (w.spanX - cellDeltaX).coerceIn(minAllowedX, maxAllowedSpanX)
                                val newSpanY = (w.spanY - cellDeltaY).coerceIn(minAllowedY, maxAllowedSpanY)
                                val newCellX = (w.cellX + w.spanX) - newSpanX
                                val newCellY = (w.cellY + w.spanY) - newSpanY
                                currentOnResizeBoundsChange(newCellX, newCellY, newSpanX, newSpanY)
                            },
                            onDragCancel = {
                                leftDragX = 0f
                                topDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnCancelResize()
                            },
                            onDragEnd = {
                                val w = currentWidget
                                val cellDeltaX = (leftDragX / currentStepXPx).roundToInt()
                                val cellDeltaY = (topDragY / currentStepYPx).roundToInt()
                                val maxAllowedSpanX = minOf(maxAvailableX, w.cellX + w.spanX)
                                val maxAllowedSpanY = minOf(maxAvailableY, w.cellY + w.spanY)
                                val newSpanX = (w.spanX - cellDeltaX).coerceIn(minAllowedX, maxAllowedSpanX)
                                val newSpanY = (w.spanY - cellDeltaY).coerceIn(minAllowedY, maxAllowedSpanY)
                                val newCellX = (w.cellX + w.spanX) - newSpanX
                                val newCellY = (w.cellY + w.spanY) - newSpanY
                                leftDragX = 0f
                                topDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnResizeWidget(newCellX, newCellY, newSpanX, newSpanY)
                            }
                        )
                    },
                contentAlignment = Alignment.TopStart
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftDragX.roundToInt(), topDragY.roundToInt()) }
                        .padding(start = 3.dp, top = 3.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(leafBrightColor)
                        .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(2.dp))
                )
            }

            // Top-Right corner handle (/ cursor, anchors bottom-left)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .pointerHoverIcon(diagonalTrBlIcon)
                    .testTag("resize_handle_top_right_${widget.appWidgetId}")
                    .pointerInput(widget.appWidgetId) {
                        detectDragGestures(
                            onDragStart = {
                                rightDragX = 0f
                                topDragY = 0f
                                currentOnStartResize()
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxLeftwardDragPx = (currentWidget.spanX - minAllowedX) * currentStepXPx
                                val maxRightwardDragPx = (minOf(maxAvailableX, currentGridProfile.columns - currentWidget.cellX) - currentWidget.spanX) * currentStepXPx
                                rightDragX = (rightDragX + dragAmount.x).coerceIn(-maxLeftwardDragPx, maxRightwardDragPx)

                                val maxUpwardDragPx = minOf(maxAvailableY - currentWidget.spanY, currentWidget.cellY) * currentStepYPx
                                val maxDownwardDragPx = (currentWidget.spanY - minAllowedY) * currentStepYPx
                                topDragY = (topDragY + dragAmount.y).coerceIn(-maxUpwardDragPx, maxDownwardDragPx)

                                currentOnResizePreviewChange(0f, topDragY, rightDragX, 0f)

                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDeltaX = (rightDragX / currentStepXPx).roundToInt()
                                val cellDeltaY = (topDragY / currentStepYPx).roundToInt()
                                val maxAvailableSpanX = minOf(maxAvailableX, prof.columns - w.cellX)
                                val maxAllowedSpanY = minOf(maxAvailableY, w.cellY + w.spanY)
                                val newSpanX = (w.spanX + cellDeltaX).coerceIn(minAllowedX, maxAvailableSpanX)
                                val newSpanY = (w.spanY - cellDeltaY).coerceIn(minAllowedY, maxAllowedSpanY)
                                val newCellX = w.cellX
                                val newCellY = (w.cellY + w.spanY) - newSpanY
                                currentOnResizeBoundsChange(newCellX, newCellY, newSpanX, newSpanY)
                            },
                            onDragCancel = {
                                rightDragX = 0f
                                topDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnCancelResize()
                            },
                            onDragEnd = {
                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDeltaX = (rightDragX / currentStepXPx).roundToInt()
                                val cellDeltaY = (topDragY / currentStepYPx).roundToInt()
                                val maxAvailableSpanX = minOf(maxAvailableX, prof.columns - w.cellX)
                                val maxAllowedSpanY = minOf(maxAvailableY, w.cellY + w.spanY)
                                val newSpanX = (w.spanX + cellDeltaX).coerceIn(minAllowedX, maxAvailableSpanX)
                                val newSpanY = (w.spanY - cellDeltaY).coerceIn(minAllowedY, maxAllowedSpanY)
                                val newCellX = w.cellX
                                val newCellY = (w.cellY + w.spanY) - newSpanY
                                rightDragX = 0f
                                topDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnResizeWidget(newCellX, newCellY, newSpanX, newSpanY)
                            }
                        )
                    },
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(rightDragX.roundToInt(), topDragY.roundToInt()) }
                        .padding(end = 3.dp, top = 3.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(leafBrightColor)
                        .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(2.dp))
                )
            }

            // Bottom-Left corner handle (/ cursor, anchors top-right)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(28.dp)
                    .pointerHoverIcon(diagonalTrBlIcon)
                    .testTag("resize_handle_bottom_left_${widget.appWidgetId}")
                    .pointerInput(widget.appWidgetId) {
                        detectDragGestures(
                            onDragStart = {
                                leftDragX = 0f
                                bottomDragY = 0f
                                currentOnStartResize()
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxLeftwardDragPx = minOf(maxAvailableX - currentWidget.spanX, currentWidget.cellX) * currentStepXPx
                                val maxRightwardDragPx = (currentWidget.spanX - minAllowedX) * currentStepXPx
                                leftDragX = (leftDragX + dragAmount.x).coerceIn(-maxLeftwardDragPx, maxRightwardDragPx)

                                val maxUpwardDragPx = (currentWidget.spanY - minAllowedY) * currentStepYPx
                                val maxDownwardDragPx = (minOf(maxAvailableY, currentGridProfile.rows - currentWidget.cellY) - currentWidget.spanY) * currentStepYPx
                                bottomDragY = (bottomDragY + dragAmount.y).coerceIn(-maxUpwardDragPx, maxDownwardDragPx)

                                currentOnResizePreviewChange(leftDragX, 0f, 0f, bottomDragY)

                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDeltaX = (leftDragX / currentStepXPx).roundToInt()
                                val cellDeltaY = (bottomDragY / currentStepYPx).roundToInt()
                                val maxAllowedSpanX = minOf(maxAvailableX, w.cellX + w.spanX)
                                val maxAvailableSpanY = minOf(maxAvailableY, prof.rows - w.cellY)
                                val newSpanX = (w.spanX - cellDeltaX).coerceIn(minAllowedX, maxAllowedSpanX)
                                val newSpanY = (w.spanY + cellDeltaY).coerceIn(minAllowedY, maxAvailableSpanY)
                                val newCellX = (w.cellX + w.spanX) - newSpanX
                                val newCellY = w.cellY
                                currentOnResizeBoundsChange(newCellX, newCellY, newSpanX, newSpanY)
                            },
                            onDragCancel = {
                                leftDragX = 0f
                                bottomDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnCancelResize()
                            },
                            onDragEnd = {
                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDeltaX = (leftDragX / currentStepXPx).roundToInt()
                                val cellDeltaY = (bottomDragY / currentStepYPx).roundToInt()
                                val maxAllowedSpanX = minOf(maxAvailableX, w.cellX + w.spanX)
                                val maxAvailableSpanY = minOf(maxAvailableY, prof.rows - w.cellY)
                                val newSpanX = (w.spanX - cellDeltaX).coerceIn(minAllowedX, maxAllowedSpanX)
                                val newSpanY = (w.spanY + cellDeltaY).coerceIn(minAllowedY, maxAvailableSpanY)
                                val newCellX = (w.cellX + w.spanX) - newSpanX
                                val newCellY = w.cellY
                                leftDragX = 0f
                                bottomDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnResizeWidget(newCellX, newCellY, newSpanX, newSpanY)
                            }
                        )
                    },
                contentAlignment = Alignment.BottomStart
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(leftDragX.roundToInt(), bottomDragY.roundToInt()) }
                        .padding(start = 3.dp, bottom = 3.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(leafBrightColor)
                        .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(2.dp))
                )
            }

            // Bottom-Right corner handle (\ cursor, anchors top-left)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .pointerHoverIcon(diagonalTlBrIcon)
                    .testTag("resize_handle_bottom_right_${widget.appWidgetId}")
                    .pointerInput(widget.appWidgetId) {
                        detectDragGestures(
                            onDragStart = {
                                rightDragX = 0f
                                bottomDragY = 0f
                                currentOnStartResize()
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxLeftwardDragPx = (currentWidget.spanX - minAllowedX) * currentStepXPx
                                val maxRightwardDragPx = (minOf(maxAvailableX, currentGridProfile.columns - currentWidget.cellX) - currentWidget.spanX) * currentStepXPx
                                rightDragX = (rightDragX + dragAmount.x).coerceIn(-maxLeftwardDragPx, maxRightwardDragPx)

                                val maxUpwardDragPx = (currentWidget.spanY - minAllowedY) * currentStepYPx
                                val maxDownwardDragPx = (minOf(maxAvailableY, currentGridProfile.rows - currentWidget.cellY) - currentWidget.spanY) * currentStepYPx
                                bottomDragY = (bottomDragY + dragAmount.y).coerceIn(-maxUpwardDragPx, maxDownwardDragPx)

                                currentOnResizePreviewChange(0f, 0f, rightDragX, bottomDragY)

                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDeltaX = (rightDragX / currentStepXPx).roundToInt()
                                val cellDeltaY = (bottomDragY / currentStepYPx).roundToInt()
                                val maxAvailableSpanX = minOf(maxAvailableX, prof.columns - w.cellX)
                                val maxAvailableSpanY = minOf(maxAvailableY, prof.rows - w.cellY)
                                val newSpanX = (w.spanX + cellDeltaX).coerceIn(minAllowedX, maxAvailableSpanX)
                                val newSpanY = (w.spanY + cellDeltaY).coerceIn(minAllowedY, maxAvailableSpanY)
                                val newCellX = w.cellX
                                val newCellY = w.cellY
                                currentOnResizeBoundsChange(newCellX, newCellY, newSpanX, newSpanY)
                            },
                            onDragCancel = {
                                rightDragX = 0f
                                bottomDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnCancelResize()
                            },
                            onDragEnd = {
                                val w = currentWidget
                                val prof = currentGridProfile
                                val cellDeltaX = (rightDragX / currentStepXPx).roundToInt()
                                val cellDeltaY = (bottomDragY / currentStepYPx).roundToInt()
                                val maxAvailableSpanX = minOf(maxAvailableX, prof.columns - w.cellX)
                                val maxAvailableSpanY = minOf(maxAvailableY, prof.rows - w.cellY)
                                val newSpanX = (w.spanX + cellDeltaX).coerceIn(minAllowedX, maxAvailableSpanX)
                                val newSpanY = (w.spanY + cellDeltaY).coerceIn(minAllowedY, maxAvailableSpanY)
                                val newCellX = w.cellX
                                val newCellY = w.cellY
                                rightDragX = 0f
                                bottomDragY = 0f
                                currentOnResizePreviewChange(0f, 0f, 0f, 0f)
                                currentOnResizeWidget(newCellX, newCellY, newSpanX, newSpanY)
                            }
                        )
                    },
                contentAlignment = Alignment.BottomEnd
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(rightDragX.roundToInt(), bottomDragY.roundToInt()) }
                        .padding(end = 3.dp, bottom = 3.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(leafBrightColor)
                        .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}
