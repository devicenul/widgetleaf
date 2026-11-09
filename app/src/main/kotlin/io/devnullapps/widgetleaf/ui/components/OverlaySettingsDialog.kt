package io.devnullapps.widgetleaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme

@Composable
fun OverlaySettingsDialog(
    isOpen: Boolean,
    settings: OverlaySettings,
    onUpdateSettings: ((OverlaySettings) -> OverlaySettings) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Backdrop for outside click dismissal
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WidgetLeafTheme.palette.scrimDark.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
        )

        GlassHudSurface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.9f)
                .heightIn(max = 680.dp)
                .testTag("overlay_settings_dialog")
                .padding(24.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Overlay Settings",
                        color = WidgetLeafTheme.palette.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(
                            text = "✕",
                            color = WidgetLeafTheme.palette.textSecondary,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section: Startup & Navigation
                SettingsSectionHeader("Startup & Navigation")

                SettingToggleRow(
                    title = "Always Open on Page 1",
                    subtitle = if (settings.openOnPageOne) "Starts on Page 1" else "Resumes on Last Viewed Page",
                    checked = settings.openOnPageOne,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(openOnPageOne = checked) }
                    },
                    testTag = "setting_open_on_page_one"
                )

                SettingToggleRow(
                    title = "Launch on Startup",
                    subtitle = "Automatically show widget overlay after boot",
                    checked = settings.launchOnStartup,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(launchOnStartup = checked) }
                    },
                    testTag = "setting_launch_on_startup"
                )

                HorizontalDivider(
                    color = WidgetLeafTheme.palette.glassBorder,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Section: Taskbar Integration
                SettingsSectionHeader("Taskbar Integration")

                SettingToggleRow(
                    title = "Pin to Taskbar (Left)",
                    subtitle = "Dock launcher pill in persistent left taskbar",
                    checked = settings.pinTaskbarLeft,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(pinTaskbarLeft = checked) }
                    },
                    testTag = "setting_pin_taskbar_left"
                )

                SettingToggleRow(
                    title = "Show in Running Taskbar (Right)",
                    subtitle = "Show WidgetLeaf pill among active running taskbar items",
                    checked = settings.showInRunningTaskbarRight,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(showInRunningTaskbarRight = checked) }
                    },
                    testTag = "setting_running_taskbar_right"
                )

                HorizontalDivider(
                    color = WidgetLeafTheme.palette.glassBorder,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Section: Top Bar & Quick Settings
                SettingsSectionHeader("Top Bar & Quick Settings")

                SettingToggleRow(
                    title = "Top Bar Left (Status Icon)",
                    subtitle = "Quick-access icon on the top status bar",
                    checked = settings.pinTopBarLeftStatusIcon,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(pinTopBarLeftStatusIcon = checked) }
                    },
                    testTag = "setting_top_bar_left"
                )

                SettingToggleRow(
                    title = "Top Bar Right (QS Tile)",
                    subtitle = "Enable Android Quick Settings tile toggle",
                    checked = settings.pinTopBarRightQsTile,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(pinTopBarRightQsTile = checked) }
                    },
                    testTag = "setting_top_bar_right"
                )

                HorizontalDivider(
                    color = WidgetLeafTheme.palette.glassBorder,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Section: Visuals & Optical Physics
                SettingsSectionHeader("Visuals & Optical Physics")

                // Blur slider
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Background Blur Radius",
                            color = WidgetLeafTheme.palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${settings.blurRadiusDp} dp",
                            color = WidgetLeafTheme.palette.leafBright,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = settings.blurRadiusDp.toFloat(),
                        onValueChange = { value ->
                            onUpdateSettings { it.copy(blurRadiusDp = value.toInt()) }
                        },
                        valueRange = 0f..36f,
                        colors = SliderDefaults.colors(
                            thumbColor = WidgetLeafTheme.palette.leafBright,
                            activeTrackColor = WidgetLeafTheme.palette.leafPrimary,
                            inactiveTrackColor = WidgetLeafTheme.palette.glassBorder
                        ),
                        modifier = Modifier.testTag("setting_blur_slider")
                    )
                }

                // Scrim opacity slider
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Frost Scrim Opacity",
                            color = WidgetLeafTheme.palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(settings.scrimAlpha * 100).toInt()}%",
                            color = WidgetLeafTheme.palette.leafBright,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = settings.scrimAlpha,
                        onValueChange = { value ->
                            onUpdateSettings { it.copy(scrimAlpha = value) }
                        },
                        valueRange = 0.40f..0.95f,
                        colors = SliderDefaults.colors(
                            thumbColor = WidgetLeafTheme.palette.leafBright,
                            activeTrackColor = WidgetLeafTheme.palette.leafPrimary,
                            inactiveTrackColor = WidgetLeafTheme.palette.glassBorder
                        ),
                        modifier = Modifier.testTag("setting_scrim_slider")
                    )
                }

                SettingToggleRow(
                    title = "Material You Dynamic Color",
                    subtitle = "Harmonize emerald palette with Android system wallpaper colors",
                    checked = settings.useDynamicColor,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(useDynamicColor = checked) }
                    },
                    testTag = "setting_dynamic_color"
                )

                SettingToggleRow(
                    title = "Centered Bento Grid",
                    subtitle = "Constrain desktop grid max-width to 1440dp centered",
                    checked = settings.useCenteredBentoCanvas,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(useCenteredBentoCanvas = checked) }
                    },
                    testTag = "setting_centered_bento_canvas"
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = WidgetLeafTheme.palette.leafBright,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = WidgetLeafTheme.palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = WidgetLeafTheme.palette.textSecondary,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = WidgetLeafTheme.palette.leafPrimary,
                uncheckedThumbColor = WidgetLeafTheme.palette.textSecondary,
                uncheckedTrackColor = WidgetLeafTheme.palette.glassSurface
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
