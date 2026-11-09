package io.devnullapps.widgetleaf.ui.components

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme
import kotlin.math.roundToInt

@Composable
fun DesktopContextMenu(
    position: Pair<Float, Float>?,
    onOpenWidgetPicker: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (position == null) return

    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }

    val menuWidthPx = with(density) { 220.dp.toPx() }
    val menuHeightPx = with(density) { 120.dp.toPx() }

    // Clamp coordinates to stay on screen
    val clampedX = position.first.coerceIn(16f, (screenWidthPx - menuWidthPx - 16f).coerceAtLeast(16f))
    val clampedY = position.second.coerceIn(16f, (screenHeightPx - menuHeightPx - 16f).coerceAtLeast(16f))

    val openedAtUptimeMs = remember(position) { SystemClock.uptimeMillis() }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrim backdrop for outside click dismissal (ignores trailing release of the opening gesture)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(position) {
                    awaitEachGesture {
                        var sawFreshPrimaryDown = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val elapsedMs = SystemClock.uptimeMillis() - openedAtUptimeMs
                            val anyPressed = event.changes.any { it.pressed }
                            val anyPrevPressed = event.changes.any { it.previousPressed }
                            val isSecondary = event.buttons.isSecondaryPressed || event.changes.count { it.pressed } >= 2

                            event.changes.forEach { it.consume() }

                            if (elapsedMs < 250L || isSecondary) {
                                if (!anyPressed) break
                                continue
                            }

                            if (anyPressed && !anyPrevPressed) {
                                sawFreshPrimaryDown = true
                            } else if (!anyPressed && anyPrevPressed) {
                                if (sawFreshPrimaryDown) {
                                    onDismissRequest()
                                }
                                break
                            }
                        }
                    }
                }
        )

        GlassHudSurface(
            modifier = Modifier
                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                .width(220.dp)
                .testTag("desktop_context_menu"),
            shape = RoundedCornerShape(16.dp),
            backgroundColor = Color(0xF0161B22),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Widgets option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("context_menu_widgets")
                        .clickable {
                            onOpenWidgetPicker()
                            onDismissRequest()
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "❖",
                        color = WidgetLeafTheme.palette.leafBright,
                        fontSize = 16.sp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Widgets",
                        color = WidgetLeafTheme.palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                HorizontalDivider(
                    color = WidgetLeafTheme.palette.glassBorder,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                )

                // Overlay Settings option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("context_menu_settings")
                        .clickable {
                            onOpenOverlaySettings()
                            onDismissRequest()
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚙",
                        color = WidgetLeafTheme.palette.textSecondary,
                        fontSize = 16.sp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Overlay Settings",
                        color = WidgetLeafTheme.palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
