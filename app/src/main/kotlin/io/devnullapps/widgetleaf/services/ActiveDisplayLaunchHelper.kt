package io.devnullapps.widgetleaf.services

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Display
import android.view.WindowManager

object ActiveDisplayLaunchHelper {

    const val ACTION_TOGGLE_DECK = "io.devnullapps.widgetleaf.ACTION_TOGGLE_DECK"
    const val ACTION_OPEN_WIDGET_PICKER = "io.devnullapps.widgetleaf.ACTION_OPEN_WIDGET_PICKER"
    const val ACTION_OPEN_OVERLAY_SETTINGS = "io.devnullapps.widgetleaf.ACTION_OPEN_OVERLAY_SETTINGS"

    const val DECK_ACTIVITY_CLASS_NAME = "io.devnullapps.widgetleaf.ui.WidgetDeckActivity"

    /**
     * Resolves the ID of the currently active or focused display for multi-monitor setups.
     */
    fun getActiveDisplayId(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display?.displayId ?: Display.DEFAULT_DISPLAY
            } catch (_: Throwable) {
                Display.DEFAULT_DISPLAY
            }
        } else {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY
        }
    }

    /**
     * Builds an [Intent] targeting [DECK_ACTIVITY_CLASS_NAME] with [FLAG_ACTIVITY_NEW_TASK] and [FLAG_ACTIVITY_CLEAR_TASK].
     */
    fun createLaunchIntent(context: Context, action: String = ACTION_TOGGLE_DECK): Intent {
        return Intent(action).apply {
            component = ComponentName(context.packageName, DECK_ACTIVITY_CLASS_NAME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    /**
     * Builds [ActivityOptions] configured with [ActivityOptions.setLaunchDisplayId] for the active display.
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
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    ): PendingIntent {
        val intent = createLaunchIntent(context, action)
        val options = createLaunchOptions(context)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            flags,
            options.toBundle()
        )
    }

    /**
     * Launches the overlay Activity on the currently active display.
     */
    fun launchOverlay(context: Context, action: String = ACTION_TOGGLE_DECK) {
        val intent = createLaunchIntent(context, action)
        val options = createLaunchOptions(context)
        context.startActivity(intent, options.toBundle())
    }
}
