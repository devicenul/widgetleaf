package io.devnullapps.widgetleaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
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
            },
        content = content
    )
}

/**
 * Frame rendered ONLY when a widget is in EDIT_MODE.
 * Draws:
 * - 1.5.dp leafPrimary (#10B981) border
 * - 4-edge leafBright (#34D399) drag handles that snap to grid cells
 * - Compact floating glassmorphic action pill with Reconfigure and Remove actions
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

    val density = LocalDensity.current
    val cellStepXPx = with(density) { (gridProfile.cellWidthDp + gridProfile.gutterDp).dp.toPx() }
    val cellStepYPx = with(density) { (gridProfile.cellHeightDp + gridProfile.gutterDp).dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("widget_edit_frame_${widget.appWidgetId}")
            .border(1.5.dp, WidgetLeafTheme.palette.leafPrimary, RoundedCornerShape(16.dp))
    ) {
        // Floating action pill
        GlassHudSurface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, -with(density) { 42.dp.roundToPx() }) }
                .height(34.dp)
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(17.dp)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (widget.isReconfigurable) {
                    TextButton(
                        onClick = { onReconfigureWidget?.invoke(widget.appWidgetId) },
                        modifier = Modifier.testTag("widget_reconfigure_${widget.appWidgetId}")
                    ) {
                        Text(
                            text = "Reconfigure",
                            color = WidgetLeafTheme.palette.leafBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                TextButton(
                    onClick = { onRemoveWidget(widget.appWidgetId) },
                    modifier = Modifier.testTag("widget_remove_${widget.appWidgetId}")
                ) {
                    Text(
                        text = "Remove",
                        color = DangerCoral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Top drag handle
        var topDragY by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 36.dp, height = 8.dp)
                .offset(y = (-4).dp)
                .testTag("resize_handle_top_${widget.appWidgetId}")
                .clip(RoundedCornerShape(4.dp))
                .background(WidgetLeafTheme.palette.leafBright)
                .pointerInput(widget.appWidgetId) {
                    detectDragGestures(
                        onDragStart = { topDragY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            topDragY += dragAmount.y
                        },
                        onDragEnd = {
                            val cellDelta = (topDragY / cellStepYPx).roundToInt()
                            val newCellY = (widget.cellY + cellDelta).coerceIn(0, widget.cellY + widget.spanY - widget.minSpanY)
                            val newSpanY = (widget.spanY - (newCellY - widget.cellY)).coerceIn(widget.minSpanY, widget.maxSpanY)
                            onResizeWidget(widget.cellX, newCellY, widget.spanX, newSpanY)
                            topDragY = 0f
                        }
                    )
                }
        )

        // Bottom drag handle
        var bottomDragY by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(width = 36.dp, height = 8.dp)
                .offset(y = 4.dp)
                .testTag("resize_handle_bottom_${widget.appWidgetId}")
                .clip(RoundedCornerShape(4.dp))
                .background(WidgetLeafTheme.palette.leafBright)
                .pointerInput(widget.appWidgetId) {
                    detectDragGestures(
                        onDragStart = { bottomDragY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            bottomDragY += dragAmount.y
                        },
                        onDragEnd = {
                            val cellDelta = (bottomDragY / cellStepYPx).roundToInt()
                            val newSpanY = (widget.spanY + cellDelta).coerceIn(widget.minSpanY, widget.maxSpanY)
                            onResizeWidget(widget.cellX, widget.cellY, widget.spanX, newSpanY)
                            bottomDragY = 0f
                        }
                    )
                }
        )

        // Left drag handle
        var leftDragX by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(width = 8.dp, height = 36.dp)
                .offset(x = (-4).dp)
                .testTag("resize_handle_left_${widget.appWidgetId}")
                .clip(RoundedCornerShape(4.dp))
                .background(WidgetLeafTheme.palette.leafBright)
                .pointerInput(widget.appWidgetId) {
                    detectDragGestures(
                        onDragStart = { leftDragX = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            leftDragX += dragAmount.x
                        },
                        onDragEnd = {
                            val cellDelta = (leftDragX / cellStepXPx).roundToInt()
                            val newCellX = (widget.cellX + cellDelta).coerceIn(0, widget.cellX + widget.spanX - widget.minSpanX)
                            val newSpanX = (widget.spanX - (newCellX - widget.cellX)).coerceIn(widget.minSpanX, widget.maxSpanX)
                            onResizeWidget(newCellX, widget.cellY, newSpanX, widget.spanY)
                            leftDragX = 0f
                        }
                    )
                }
        )

        // Right drag handle
        var rightDragX by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(width = 8.dp, height = 36.dp)
                .offset(x = 4.dp)
                .testTag("resize_handle_right_${widget.appWidgetId}")
                .clip(RoundedCornerShape(4.dp))
                .background(WidgetLeafTheme.palette.leafBright)
                .pointerInput(widget.appWidgetId) {
                    detectDragGestures(
                        onDragStart = { rightDragX = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            rightDragX += dragAmount.x
                        },
                        onDragEnd = {
                            val cellDelta = (rightDragX / cellStepXPx).roundToInt()
                            val newSpanX = (widget.spanX + cellDelta).coerceIn(widget.minSpanX, widget.maxSpanX)
                            onResizeWidget(widget.cellX, widget.cellY, newSpanX, widget.spanY)
                            rightDragX = 0f
                        }
                    )
                }
        )
    }
}
