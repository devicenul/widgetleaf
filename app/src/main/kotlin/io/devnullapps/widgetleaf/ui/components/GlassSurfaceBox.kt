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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
    modifier: Modifier = Modifier
) {
    if (!isEditing) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val cellStepXPx = with(density) { (gridProfile.cellWidthDp + gridProfile.gutterDp).dp.toPx() }
    val cellStepYPx = with(density) { (gridProfile.cellHeightDp + gridProfile.gutterDp).dp.toPx() }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("widget_edit_frame_${widget.appWidgetId}")
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x400D1117))
            .border(2.dp, WidgetLeafTheme.palette.leafPrimary, RoundedCornerShape(16.dp))
    ) {
        // Centered floating action pill (100% inside widget hit bounds, never overlaps top bar or edge handles)
        GlassHudSurface(
            modifier = Modifier
                .align(Alignment.Center)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* Consume stray clicks on the pill frame */ }
                ),
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

        // Top drag handle (88x28dp grab zone with ↕ resize cursor)
        var topDragY by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 88.dp, height = 28.dp)
                .pointerHoverIcon(verticalResizeIcon)
                .testTag("resize_handle_top_${widget.appWidgetId}")
                .pointerInput(widget.appWidgetId, widget.cellY, widget.spanY) {
                    detectDragGestures(
                        onDragStart = { topDragY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            topDragY += dragAmount.y
                        },
                        onDragEnd = {
                            val cellDelta = (topDragY / cellStepYPx).roundToInt()
                            val newCellY = (widget.cellY + cellDelta).coerceIn(
                                0,
                                (widget.cellY + widget.spanY - widget.minSpanY).coerceAtLeast(0)
                            )
                            val newSpanY = (widget.spanY - (newCellY - widget.cellY)).coerceIn(
                                widget.minSpanY,
                                widget.maxSpanY
                            )
                            onResizeWidget(widget.cellX, newCellY, widget.spanX, newSpanY)
                            topDragY = 0f
                        }
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(width = 48.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(WidgetLeafTheme.palette.leafBright)
                    .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(4.dp))
            )
        }

        // Bottom drag handle (88x28dp grab zone with ↕ resize cursor)
        var bottomDragY by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(width = 88.dp, height = 28.dp)
                .pointerHoverIcon(verticalResizeIcon)
                .testTag("resize_handle_bottom_${widget.appWidgetId}")
                .pointerInput(widget.appWidgetId, widget.cellY, widget.spanY) {
                    detectDragGestures(
                        onDragStart = { bottomDragY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            bottomDragY += dragAmount.y
                        },
                        onDragEnd = {
                            val cellDelta = (bottomDragY / cellStepYPx).roundToInt()
                            val newSpanY = (widget.spanY + cellDelta).coerceIn(
                                widget.minSpanY,
                                widget.maxSpanY
                            )
                            onResizeWidget(widget.cellX, widget.cellY, widget.spanX, newSpanY)
                            bottomDragY = 0f
                        }
                    )
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 3.dp)
                    .size(width = 48.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(WidgetLeafTheme.palette.leafBright)
                    .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(4.dp))
            )
        }

        // Left drag handle (28x88dp grab zone with ↔ resize cursor)
        var leftDragX by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(width = 28.dp, height = 88.dp)
                .pointerHoverIcon(horizontalResizeIcon)
                .testTag("resize_handle_left_${widget.appWidgetId}")
                .pointerInput(widget.appWidgetId, widget.cellX, widget.spanX) {
                    detectDragGestures(
                        onDragStart = { leftDragX = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            leftDragX += dragAmount.x
                        },
                        onDragEnd = {
                            val cellDelta = (leftDragX / cellStepXPx).roundToInt()
                            val newCellX = (widget.cellX + cellDelta).coerceIn(
                                0,
                                (widget.cellX + widget.spanX - widget.minSpanX).coerceAtLeast(0)
                            )
                            val newSpanX = (widget.spanX - (newCellX - widget.cellX)).coerceIn(
                                widget.minSpanX,
                                widget.maxSpanX
                            )
                            onResizeWidget(newCellX, widget.cellY, newSpanX, widget.spanY)
                            leftDragX = 0f
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 3.dp)
                    .size(width = 8.dp, height = 48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(WidgetLeafTheme.palette.leafBright)
                    .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(4.dp))
            )
        }

        // Right drag handle (28x88dp grab zone with ↔ resize cursor)
        var rightDragX by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(width = 28.dp, height = 88.dp)
                .pointerHoverIcon(horizontalResizeIcon)
                .testTag("resize_handle_right_${widget.appWidgetId}")
                .pointerInput(widget.appWidgetId, widget.cellX, widget.spanX) {
                    detectDragGestures(
                        onDragStart = { rightDragX = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            rightDragX += dragAmount.x
                        },
                        onDragEnd = {
                            val cellDelta = (rightDragX / cellStepXPx).roundToInt()
                            val newSpanX = (widget.spanX + cellDelta).coerceIn(
                                widget.minSpanX,
                                widget.maxSpanX
                            )
                            onResizeWidget(widget.cellX, widget.cellY, newSpanX, widget.spanY)
                            rightDragX = 0f
                        }
                    )
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 3.dp)
                    .size(width = 8.dp, height = 48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(WidgetLeafTheme.palette.leafBright)
                    .border(0.5.dp, Color(0xAA0D1117), RoundedCornerShape(4.dp))
            )
        }
    }
}
