package io.devnullapps.widgetleaf.domain.model

import android.view.KeyEvent

enum class TopBarPosition {
    LEFT,
    RIGHT
}

data class OverlaySettings(
    val blurRadiusDp: Int = 36,
    val scrimAlpha: Float = 0.72f,
    val useDynamicColor: Boolean = false,
    val openOnPageOne: Boolean = false,
    val lastViewedPageIndex: Int = 0,
    val launchOnStartup: Boolean = false,
    val pinTaskbarLeft: Boolean = false,
    val showInRunningTaskbarRight: Boolean = true,
    val pinTopBarLeftStatusIcon: Boolean = true,
    val pinTopBarRightQsTile: Boolean = false,
    val pinToTopBar: Boolean = pinTopBarLeftStatusIcon,
    val topBarPosition: TopBarPosition = TopBarPosition.LEFT,
    val hotkeyKeyCode: Int = KeyEvent.KEYCODE_W,
    val hotkeyCtrl: Boolean = false,
    val hotkeyAlt: Boolean = true,
    val hotkeyShift: Boolean = false,
    val hotkeyMeta: Boolean = false,
    val useCenteredBentoCanvas: Boolean = true
) {
    /**
     * When WidgetLeaf is pinned to the top bar, hide the running taskbar icon to avoid duplicates.
     */
    val effectiveShowInRunningTaskbar: Boolean
        get() = showInRunningTaskbarRight && !pinToTopBar

    fun formatHotkeyLabel(): String {
        if (hotkeyKeyCode <= 0) return "None"
        val parts = mutableListOf<String>()
        if (hotkeyCtrl) parts.add("Ctrl")
        if (hotkeyAlt) parts.add("Alt")
        if (hotkeyShift) parts.add("Shift")
        if (hotkeyMeta) parts.add("Action")
        parts.add(formatKeyCodeName(hotkeyKeyCode))
        return parts.joinToString(" + ")
    }

    fun matchesKeyEvent(event: KeyEvent): Boolean {
        if (hotkeyKeyCode <= 0) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (event.keyCode != hotkeyKeyCode) return false
        return event.isCtrlPressed == hotkeyCtrl &&
            event.isAltPressed == hotkeyAlt &&
            event.isShiftPressed == hotkeyShift &&
            event.isMetaPressed == hotkeyMeta
    }

    companion object {
        fun isModifierKeyCode(keyCode: Int): Boolean {
            return keyCode in setOf(
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_CTRL_RIGHT,
                KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_ALT_RIGHT,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT,
                KeyEvent.KEYCODE_META_LEFT,
                KeyEvent.KEYCODE_META_RIGHT,
                KeyEvent.KEYCODE_CAPS_LOCK,
                KeyEvent.KEYCODE_FUNCTION
            )
        }

        fun formatKeyCodeName(keyCode: Int): String {
            return when (keyCode) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                    ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                    ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                    "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
                KeyEvent.KEYCODE_SPACE -> "Space"
                KeyEvent.KEYCODE_GRAVE -> "`"
                KeyEvent.KEYCODE_SLASH -> "/"
                KeyEvent.KEYCODE_BACKSLASH -> "\\"
                KeyEvent.KEYCODE_SEMICOLON -> ";"
                KeyEvent.KEYCODE_APOSTROPHE -> "'"
                KeyEvent.KEYCODE_COMMA -> ","
                KeyEvent.KEYCODE_PERIOD -> "."
                KeyEvent.KEYCODE_MINUS -> "-"
                KeyEvent.KEYCODE_EQUALS -> "="
                KeyEvent.KEYCODE_LEFT_BRACKET -> "["
                KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
                KeyEvent.KEYCODE_TAB -> "Tab"
                else -> KeyEvent.keyCodeToString(keyCode)
                    .removePrefix("KEYCODE_")
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
            }
        }
    }
}
