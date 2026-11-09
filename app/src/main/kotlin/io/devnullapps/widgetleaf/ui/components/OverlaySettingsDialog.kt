package io.devnullapps.widgetleaf.ui.components

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.devnullapps.widgetleaf.BuildConfig
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.TopBarPosition
import io.devnullapps.widgetleaf.services.WidgetLeafTopBarService
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme
import kotlinx.coroutines.delay

@Composable
fun OverlaySettingsDialog(
    isOpen: Boolean,
    settings: OverlaySettings,
    onUpdateSettings: ((OverlaySettings) -> OverlaySettings) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val scrollState = rememberScrollState()
    var isRecordingHotkey by remember { mutableStateOf(false) }
    val hotkeyFocusRequester = remember { FocusRequester() }
    var isTopBarServiceActive by remember(settings.pinToTopBar, isOpen, windowInfo.isWindowFocused) {
        mutableStateOf(WidgetLeafTopBarService.isServiceEnabled(context))
    }

    LaunchedEffect(isOpen, settings.pinToTopBar) {
        while (isOpen && settings.pinToTopBar && !isTopBarServiceActive) {
            delay(1000L)
            isTopBarServiceActive = WidgetLeafTopBarService.isServiceEnabled(context)
        }
    }

    LaunchedEffect(isRecordingHotkey) {
        if (isRecordingHotkey) {
            try {
                hotkeyFocusRequester.requestFocus()
            } catch (_: Throwable) {
                // Ignored if focus tree not attached yet
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Backdrop for outside click dismissal
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WidgetLeafTheme.palette.scrimDark.copy(alpha = 0.68f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
        )

        GlassHudSurface(
            modifier = Modifier
                .widthIn(max = 580.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 700.dp)
                .padding(24.dp)
                .testTag("overlay_settings_dialog"),
            shape = RoundedCornerShape(22.dp),
            backgroundColor = Color(0xEB161B22),
            contentPadding = PaddingValues(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Pinned Header with Version Badge and Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Overlay Settings",
                            color = WidgetLeafTheme.palette.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.18f))
                                .border(
                                    0.5.dp,
                                    WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.55f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                color = WidgetLeafTheme.palette.leafBright,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("overlay_settings_version_badge")
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(WidgetLeafTheme.palette.glassSurfaceHover)
                            .clickable(onClick = onDismissRequest),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = WidgetLeafTheme.palette.textSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                HorizontalDivider(
                    color = WidgetLeafTheme.palette.glassBorder,
                    thickness = 0.5.dp
                )

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(top = 12.dp)
                ) {
                    // Section: Startup & Navigation
                    SettingsSectionHeader("Startup & Navigation")

                    SettingToggleRow(
                        title = "Always Open on Page 1",
                        subtitle = if (settings.openOnPageOne) {
                            "Starts on Page 1"
                        } else {
                            "Resumes on Last Viewed Page"
                        },
                        checked = settings.openOnPageOne,
                        onCheckedChange = { checked ->
                            onUpdateSettings { it.copy(openOnPageOne = checked) }
                        },
                        testTag = "setting_open_on_page_one"
                    )

                    SettingToggleRow(
                        title = "Launch on Startup",
                        subtitle = "Start WidgetLeaf quietly in the background on boot so it is ready",
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

                    // Section: Keyboard Shortcut (Hotkey)
                    SettingsSectionHeader("Keyboard Shortcut (Hotkey)")

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .focusRequester(hotkeyFocusRequester)
                            .focusable()
                            .onKeyEvent { keyEvent ->
                                if (!isRecordingHotkey) return@onKeyEvent false
                                val native = keyEvent.nativeKeyEvent
                                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent true
                                if (native.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                                    isRecordingHotkey = false
                                    return@onKeyEvent true
                                }
                                if (OverlaySettings.isModifierKeyCode(native.keyCode)) {
                                    return@onKeyEvent true
                                }
                                onUpdateSettings {
                                    it.copy(
                                        hotkeyKeyCode = native.keyCode,
                                        hotkeyCtrl = native.isCtrlPressed,
                                        hotkeyAlt = native.isAltPressed,
                                        hotkeyShift = native.isShiftPressed,
                                        hotkeyMeta = native.isMetaPressed
                                    )
                                }
                                isRecordingHotkey = false
                                true
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Open Widget View Hotkey",
                                    color = WidgetLeafTheme.palette.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isRecordingHotkey) {
                                        "Press any key combination (Esc to cancel)..."
                                    } else {
                                        "Global shortcut to toggle the widget pane"
                                    },
                                    color = if (isRecordingHotkey) {
                                        WidgetLeafTheme.palette.leafBright
                                    } else {
                                        WidgetLeafTheme.palette.textSecondary
                                    },
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isRecordingHotkey) {
                                            WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.28f)
                                        } else {
                                            WidgetLeafTheme.palette.glassSurfaceHover
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        if (isRecordingHotkey) {
                                            WidgetLeafTheme.palette.leafPrimary
                                        } else {
                                            WidgetLeafTheme.palette.glassBorder
                                        },
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isRecordingHotkey) {
                                        "Listening..."
                                    } else {
                                        settings.formatHotkeyLabel()
                                    },
                                    color = WidgetLeafTheme.palette.leafBright,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("current_hotkey_badge")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .testTag("record_hotkey_button")
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.22f))
                                    .border(
                                        0.5.dp,
                                        WidgetLeafTheme.palette.leafPrimary,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        isRecordingHotkey = !isRecordingHotkey
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isRecordingHotkey) "Cancel Recording" else "Record Hotkey",
                                    color = WidgetLeafTheme.palette.leafBright,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .testTag("open_system_shortcuts_button")
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(WidgetLeafTheme.palette.glassSurfaceHover)
                                    .border(
                                        0.5.dp,
                                        WidgetLeafTheme.palette.glassBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        try {
                                            context.startActivity(
                                                Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        } catch (_: Throwable) {
                                            try {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_SETTINGS).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                )
                                            } catch (_: Throwable) {
                                                // Ignored
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "System Keyboard Shortcuts",
                                    color = WidgetLeafTheme.palette.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = WidgetLeafTheme.palette.glassBorder,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    // Section: Top Bar Integration
                    SettingsSectionHeader("Top Bar Integration")

                    SettingToggleRow(
                        title = "Pin to Top Bar",
                        subtitle = "Show clickable WidgetLeaf icon in the desktop top status bar",
                        checked = settings.pinToTopBar,
                        onCheckedChange = { checked ->
                            onUpdateSettings {
                                it.copy(
                                    pinToTopBar = checked,
                                    pinTopBarLeftStatusIcon = checked
                                )
                            }
                        },
                        testTag = "setting_top_bar_left"
                    )

                    if (settings.pinToTopBar) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Top Bar Position",
                                    color = WidgetLeafTheme.palette.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Compatible with BentoBar on either side",
                                    color = WidgetLeafTheme.palette.textSecondary,
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(WidgetLeafTheme.palette.glassSurfaceHover)
                                    .border(
                                        0.5.dp,
                                        WidgetLeafTheme.palette.glassBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val isLeft = settings.topBarPosition == TopBarPosition.LEFT
                                Box(
                                    modifier = Modifier
                                        .testTag("setting_top_bar_position_left")
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isLeft) {
                                                WidgetLeafTheme.palette.leafPrimary
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .clickable {
                                            onUpdateSettings {
                                                it.copy(topBarPosition = TopBarPosition.LEFT)
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Left",
                                        color = if (isLeft) Color.Black else WidgetLeafTheme.palette.textSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                val isRight = settings.topBarPosition == TopBarPosition.RIGHT
                                Box(
                                    modifier = Modifier
                                        .testTag("setting_top_bar_position_right")
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isRight) {
                                                WidgetLeafTheme.palette.leafPrimary
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .clickable {
                                            onUpdateSettings {
                                                it.copy(topBarPosition = TopBarPosition.RIGHT)
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Right",
                                        color = if (isRight) Color.Black else WidgetLeafTheme.palette.textSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (!isTopBarServiceActive) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.14f))
                                    .border(
                                        0.5.dp,
                                        WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Enable Top Bar Service",
                                        color = WidgetLeafTheme.palette.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Turn on 'Use WidgetLeaf Top Bar' in Accessibility Settings (keep 'WidgetLeaf Top Bar shortcut' off so no floating side button appears).",
                                        color = WidgetLeafTheme.palette.textSecondary,
                                        fontSize = 11.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Box(
                                    modifier = Modifier
                                        .testTag("enable_top_bar_service_button")
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(WidgetLeafTheme.palette.leafPrimary)
                                        .clickable {
                                            WidgetLeafTopBarService.openAccessibilitySettings(context)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "Enable",
                                        color = Color.Black,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    SettingToggleRow(
                        title = "Quick Settings Tile",
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
                        subtitle = if (settings.pinToTopBar) {
                            "Automatically hidden while Pinned to Top Bar is enabled"
                        } else {
                            "Show WidgetLeaf pill among active running taskbar items"
                        },
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
