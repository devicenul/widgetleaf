package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.devnullapps.widgetleaf.ui.WidgetDeckActivity

object ActiveDisplayLaunchHelper {

    const val ACTION_TOGGLE_DECK = "io.devnullapps.widgetleaf.ACTION_TOGGLE_DECK"
    const val ACTION_OPEN_WIDGET_PICKER = "io.devnullapps.widgetleaf.ACTION_OPEN_WIDGET_PICKER"
    const val ACTION_OPEN_OVERLAY_SETTINGS = "io.devnullapps.widgetleaf.ACTION_OPEN_OVERLAY_SETTINGS"

    const val EXTRA_TARGET_PAGE_INDEX = "io.devnullapps.widgetleaf.EXTRA_TARGET_PAGE_INDEX"

    const val DECK_ACTIVITY_CLASS_NAME = "io.devnullapps.widgetleaf.ui.WidgetDeckActivity"
    const val SHORTCUT_ACTIVITY_CLASS_NAME = "io.devnullapps.widgetleaf.ui.ToggleDeckShortcutActivity"

    /**
     * Resolves the ID of the display the user is currently interacting with from an
     * [AccessibilityService] using [AccessibilityService.getWindowsOnAllDisplays] (API 30+),
     * prioritizing focused non-overlay windows, input focus, and active application windows.
     */
    fun getActiveDisplayId(accessibilityService: AccessibilityService): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val windowsByDisplay = accessibilityService.windowsOnAllDisplays
                var firstFocusedOverlayDisplayId: Int? = null
                var firstActiveAppDisplayId: Int? = null
                var firstActiveAnyDisplayId: Int? = null

                for (i in 0 until windowsByDisplay.size()) {
                    val keyDisplayId = windowsByDisplay.keyAt(i)
                    val windows = windowsByDisplay.valueAt(i) ?: continue
                    for (w in windows) {
                        val winDisplayId = w.displayId.takeIf { it != Display.INVALID_DISPLAY } ?: keyDisplayId
                        if (w.isFocused) {
                            if (w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                                return winDisplayId
                            } else if (firstFocusedOverlayDisplayId == null) {
                                firstFocusedOverlayDisplayId = winDisplayId
                            }
                        }
                        if (w.isActive) {
                            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && firstActiveAppDisplayId == null) {
                                firstActiveAppDisplayId = winDisplayId
                            } else if (firstActiveAnyDisplayId == null) {
                                firstActiveAnyDisplayId = winDisplayId
                            }
                        }
                    }
                }
                if (firstFocusedOverlayDisplayId != null) return firstFocusedOverlayDisplayId
                val inputFocusDisplayId = runCatching {
                    accessibilityService.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.window?.displayId
                }.getOrNull()
                if (inputFocusDisplayId != null && inputFocusDisplayId != Display.INVALID_DISPLAY) {
                    return inputFocusDisplayId
                }
                if (firstActiveAppDisplayId != null) return firstActiveAppDisplayId
                if (firstActiveAnyDisplayId != null) return firstActiveAnyDisplayId
            } catch (_: Throwable) {
            }
        }

        return try {
            val windows = accessibilityService.windows.orEmpty()
            windows.firstOrNull { it.isFocused && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }?.displayId
                ?.takeIf { it != Display.INVALID_DISPLAY }
                ?: windows.firstOrNull { it.isFocused }?.displayId?.takeIf { it != Display.INVALID_DISPLAY }
                ?: windows.firstOrNull { it.isActive }?.displayId?.takeIf { it != Display.INVALID_DISPLAY }
                ?: Display.DEFAULT_DISPLAY
        } catch (_: Throwable) {
            Display.DEFAULT_DISPLAY
        }
    }

    /**
     * Resolves the ID of the currently active or focused display for multi-monitor setups.
     * For [AccessibilityService] contexts, queries [getActiveDisplayId(AccessibilityService)].
     * For visual contexts (Activity / DisplayContext / WindowContext), reads [Context.getDisplay].
     * For non-display service/application contexts, delegates to [WidgetLeafTopBarService.instance].
     */
    fun getActiveDisplayId(context: Context): Int {
        if (context is AccessibilityService) {
            return getActiveDisplayId(context)
        }
        val directDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.displayId
            } catch (_: Throwable) {
                null
            }
        } else {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.displayId
        }
        if (directDisplayId != null && directDisplayId != Display.INVALID_DISPLAY) {
            return directDisplayId
        }
        WidgetLeafTopBarService.instance?.let { service ->
            return getActiveDisplayId(service)
        }
        return Display.DEFAULT_DISPLAY
    }

    /**
     * Builds an [Intent] targeting [DECK_ACTIVITY_CLASS_NAME] with [FLAG_ACTIVITY_NEW_TASK] and [FLAG_ACTIVITY_CLEAR_TASK].
     */
    fun createLaunchIntent(
        context: Context,
        action: String = ACTION_TOGGLE_DECK,
        targetPageIndex: Int? = null
    ): Intent {
        return Intent(action).apply {
            component = ComponentName(context.packageName, DECK_ACTIVITY_CLASS_NAME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (targetPageIndex != null) {
                putExtra(EXTRA_TARGET_PAGE_INDEX, targetPageIndex)
            }
        }
    }

    /**
     * Builds [ActivityOptions] configured with [ActivityOptions.setLaunchDisplayId] for [displayId].
     */
    fun createLaunchOptions(
        context: Context,
        displayId: Int = getActiveDisplayId(context)
    ): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = displayId
        return options
    }

    /**
     * Builds a [PendingIntent] targeting the overlay activity with the specified action and display ID.
     */
    fun createLaunchPendingIntent(
        context: Context,
        action: String = ACTION_TOGGLE_DECK,
        requestCode: Int = action.hashCode(),
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        displayId: Int = getActiveDisplayId(context)
    ): PendingIntent {
        val intent = createLaunchIntent(context, action)
        val options = createLaunchOptions(context, displayId)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            flags,
            options.toBundle()
        )
    }

    /**
     * Builds a [PendingIntent] targeting [ToggleDeckShortcutActivity] without pinning a display at
     * creation time so ongoing notification triggers resolve the user's active display live on tap.
     */
    fun createShortcutPendingIntent(
        context: Context,
        action: String = ACTION_TOGGLE_DECK,
        requestCode: Int = action.hashCode(),
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    ): PendingIntent {
        val intent = Intent(action).apply {
            component = ComponentName(context.packageName, SHORTCUT_ACTIVITY_CLASS_NAME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            flags
        )
    }

    /**
     * Launches the overlay Activity on [displayId] (defaults to [getActiveDisplayId]).
     */
    fun launchOverlay(
        context: Context,
        action: String = ACTION_TOGGLE_DECK,
        targetPageIndex: Int? = null,
        displayId: Int = getActiveDisplayId(context)
    ) {
        val intent = createLaunchIntent(context, action, targetPageIndex)
        val options = createLaunchOptions(context, displayId)
        context.startActivity(intent, options.toBundle())
    }

    /**
     * Toggles the overlay off when already visible on [displayId] for [ACTION_TOGGLE_DECK], moves
     * it when currently open on a different display, or launches it on [displayId].
     */
    fun toggleOrLaunchOverlay(
        context: Context,
        displayId: Int,
        action: String = ACTION_TOGGLE_DECK,
        targetPageIndex: Int? = null
    ) {
        val active = WidgetDeckActivity.activeInstance
        if (active != null && !active.isFinishing && WidgetDeckActivity.isDeckVisible) {
            val activeDisplayId = getActiveDisplayId(active)
            if (action == ACTION_TOGGLE_DECK && activeDisplayId == displayId) {
                active.finish()
                return
            }
            if (activeDisplayId != displayId) {
                active.finish()
            }
        }
        launchOverlay(
            context = context,
            action = action,
            targetPageIndex = targetPageIndex,
            displayId = displayId
        )
    }
}
