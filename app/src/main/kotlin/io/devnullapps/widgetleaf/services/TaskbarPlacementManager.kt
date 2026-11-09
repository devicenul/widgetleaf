package io.devnullapps.widgetleaf.services

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.devnullapps.widgetleaf.R

/**
 * Manages Taskbar placement for WidgetLeaf in desktop environments:
 * - Left side of Taskbar (Pinned shelf shortcut via [ShortcutManager.requestPinShortcut])
 * - Right side of Taskbar (Running Apps section via [ActivityManager.AppTask.setExcludeFromRecents])
 * - Dynamic shortcuts for right-click / Ctrl-click access to Add Widget and Overlay Settings.
 */
open class TaskbarPlacementManager(
    private val shortcutManagerProvider: ((Context) -> ShortcutManager?)? = null
) {

    companion object {
        const val PIN_SHORTCUT_ID = "taskbar_pin_widgetleaf"
        const val SHORTCUT_ADD_WIDGET = "shortcut_add_widget"
        const val SHORTCUT_OVERLAY_SETTINGS = "shortcut_overlay_settings"
    }

    private fun getShortcutManager(context: Context): ShortcutManager? {
        return shortcutManagerProvider?.invoke(context)
            ?: context.getSystemService(ShortcutManager::class.java)
    }

    /**
     * Requests pinning the WidgetLeaf toggle shortcut to the left/center dock of the taskbar.
     */
    open fun requestPinToTaskbarLeft(context: Context): Boolean {
        val shortcutManager = getShortcutManager(context) ?: return false
        if (!shortcutManager.isRequestPinShortcutSupported) {
            return false
        }

        val pinShortcutInfo = ShortcutInfo.Builder(context, PIN_SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.app_name))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_taskbar_pin))
            .setIntent(
                ActiveDisplayLaunchHelper.createLaunchIntent(
                    context,
                    ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
                )
            )
            .build()

        return try {
            shortcutManager.requestPinShortcut(pinShortcutInfo, null)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Synchronizes whether the overlay task appears in the "Running Apps" section on the right side
     * of the taskbar divider, and updates dynamic shortcuts for right-click desktop actions.
     */
    open fun syncRunningTaskbarPresence(context: Context, keepInRunningTaskbarRight: Boolean) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        try {
            activityManager?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(!keepInRunningTaskbarRight)
            }
        } catch (_: Throwable) {
            // Ignored if app tasks cannot be accessed from this context
        }

        syncDynamicShortcuts(context)
    }

    /**
     * Updates dynamic shortcuts for right-click / Ctrl-click / two-finger click access
     * on the taskbar icon (Add Widget and Overlay Settings).
     */
    open fun syncDynamicShortcuts(context: Context): Boolean {
        val shortcutManager = getShortcutManager(context) ?: return false

        val addWidgetShortcut = ShortcutInfo.Builder(context, SHORTCUT_ADD_WIDGET)
            .setShortLabel(context.getString(R.string.shortcut_add_widget))
            .setLongLabel(context.getString(R.string.shortcut_add_widget_desc))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_taskbar_pin))
            .setIntent(
                ActiveDisplayLaunchHelper.createLaunchIntent(
                    context,
                    ActiveDisplayLaunchHelper.ACTION_OPEN_WIDGET_PICKER
                )
            )
            .build()

        val settingsShortcut = ShortcutInfo.Builder(context, SHORTCUT_OVERLAY_SETTINGS)
            .setShortLabel(context.getString(R.string.shortcut_overlay_settings))
            .setLongLabel(context.getString(R.string.shortcut_overlay_settings_desc))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_qs_tile_leaf))
            .setIntent(
                ActiveDisplayLaunchHelper.createLaunchIntent(
                    context,
                    ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS
                )
            )
            .build()

        return try {
            shortcutManager.dynamicShortcuts = listOf(addWidgetShortcut, settingsShortcut)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
