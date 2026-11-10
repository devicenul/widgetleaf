package io.devnullapps.widgetleaf.services

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.devnullapps.widgetleaf.R

/**
 * Manages Taskbar presence and launcher shortcuts for WidgetLeaf in desktop environments:
 * - Always excludes overlay tasks from recents/running taskbar so it doesn't duplicate the top-bar entry point.
 * - Dynamic shortcuts for right-click / Ctrl-click access to Add Widget and Overlay Settings.
 */
open class TaskbarPlacementManager(
    private val shortcutManagerProvider: ((Context) -> ShortcutManager?)? = null
) {

    companion object {
        const val SHORTCUT_ADD_WIDGET = "shortcut_add_widget"
        const val SHORTCUT_OVERLAY_SETTINGS = "shortcut_overlay_settings"
    }

    private fun getShortcutManager(context: Context): ShortcutManager? {
        return shortcutManagerProvider?.invoke(context)
            ?: context.getSystemService(ShortcutManager::class.java)
    }

    /**
     * Ensures the overlay task is excluded from the "Running Apps" section of the taskbar
     * (as WidgetLeaf lives in the top bar) and updates dynamic shortcuts for right-click desktop actions.
     */
    open fun syncRunningTaskbarPresence(context: Context, keepInRunningTaskbarRight: Boolean = false) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        try {
            activityManager?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(true)
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
