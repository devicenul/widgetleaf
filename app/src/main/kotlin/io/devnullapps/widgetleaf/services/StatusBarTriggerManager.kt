package io.devnullapps.widgetleaf.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import io.devnullapps.widgetleaf.R

/**
 * Manages the ongoing silent status bar trigger icon (Top Bar Left).
 *
 * Uses the 24x24dp 1-bit monochrome leaf-matrix vector glyph ([R.drawable.ic_qs_tile_leaf])
 * to provide immediate access to the widget deck and shortcut actions.
 */
open class StatusBarTriggerManager(
    private val context: Context,
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
) {

    companion object {
        const val NOTIFICATION_ID = 2042
        const val CHANNEL_ID = "widgetleaf_top_bar_trigger"

        const val REQUEST_CODE_TOGGLE = 20421
        const val REQUEST_CODE_ADD_WIDGET = 20422
        const val REQUEST_CODE_SETTINGS = 20423
    }

    private var isExplicitlyShown = false

    /**
     * Synchronizes the status bar ongoing notification based on user configuration.
     */
    open fun syncStatusBarTrigger(enabled: Boolean) {
        if (enabled) {
            showTriggerNotification()
        } else {
            cancelTriggerNotification()
        }
    }

    /**
     * Posts the ongoing, silent notification trigger in the status bar.
     */
    open fun showTriggerNotification() {
        createChannelIfNeeded()

        val togglePendingIntent = ActiveDisplayLaunchHelper.createLaunchPendingIntent(
            context = context,
            action = ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK,
            requestCode = REQUEST_CODE_TOGGLE
        )

        val addWidgetPendingIntent = ActiveDisplayLaunchHelper.createLaunchPendingIntent(
            context = context,
            action = ActiveDisplayLaunchHelper.ACTION_OPEN_WIDGET_PICKER,
            requestCode = REQUEST_CODE_ADD_WIDGET
        )

        val settingsPendingIntent = ActiveDisplayLaunchHelper.createLaunchPendingIntent(
            context = context,
            action = ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS,
            requestCode = REQUEST_CODE_SETTINGS
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile_leaf)
            .setContentTitle(context.getString(R.string.notification_status_title))
            .setContentText(context.getString(R.string.notification_status_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(togglePendingIntent)
            .setShowWhen(false)
            .addAction(
                R.drawable.ic_taskbar_pin,
                context.getString(R.string.shortcut_add_widget),
                addWidgetPendingIntent
            )
            .addAction(
                R.drawable.ic_qs_tile_leaf,
                context.getString(R.string.notification_action_settings),
                settingsPendingIntent
            )
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            isExplicitlyShown = true
        } catch (_: SecurityException) {
            // Notification permission may not be granted on Android 13+
            isExplicitlyShown = false
        }
    }

    /**
     * Cancels the ongoing status bar notification trigger.
     */
    open fun cancelTriggerNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } finally {
            isExplicitlyShown = false
        }
    }

    /**
     * Returns true if the status bar trigger notification is currently active.
     */
    open fun isNotificationShown(): Boolean {
        val active = try {
            notificationManager.activeNotifications.any { it.id == NOTIFICATION_ID }
        } catch (_: Throwable) {
            false
        }
        return active || isExplicitlyShown
    }

    private fun createChannelIfNeeded() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_status_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_status_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
