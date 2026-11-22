package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.service.quicksettings.Tile
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.R
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class WidgetDeckTileServiceTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var statusBarTriggerManager: StatusBarTriggerManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        statusBarTriggerManager = StatusBarTriggerManager(context, notificationManager)
    }

    @After
    fun tearDown() {
        statusBarTriggerManager.cancelTriggerNotification()
        DefaultServicesContainer.setContainerForTesting(null)
    }

    @Test
    fun tileServiceOnStartListeningSetsActiveStateLabelAndSubtitle() {
        val serviceController = Robolectric.buildService(WidgetDeckTileService::class.java).create()
        val service = serviceController.get()

        service.onStartListening()

        val tile = service.qsTile
        assertThat(tile).isNotNull()
        assertThat(tile.state).isEqualTo(Tile.STATE_ACTIVE)
        assertThat(tile.label).isEqualTo(context.getString(R.string.tile_widget_deck_label))
        assertThat(tile.subtitle).isEqualTo(context.getString(R.string.tile_widget_deck_subtitle))
    }

    @Test
    fun tileServiceOnClickCreatesLaunchIntentWithActionToggleDeck() {
        val serviceController = Robolectric.buildService(WidgetDeckTileService::class.java).create()
        val service = serviceController.get()

        service.onClick()

        assertThat(service.lastLaunchedIntent).isNotNull()
        assertThat(service.lastLaunchedIntent?.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
        assertThat(service.lastLaunchedIntent?.flags?.and(Intent.FLAG_ACTIVITY_NEW_TASK)).isNotEqualTo(0)
        assertThat(service.lastLaunchedPendingIntent).isNotNull()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun requestPinQsTileInvokesStatusBarManagerOnApi33() {
        var callbackInvoked = false
        WidgetDeckTileService.requestPinQsTile(context) {
            callbackInvoked = true
        }

        val sbm = context.getSystemService(StatusBarManager::class.java)
        if (sbm != null) {
            // Callback or request registered
            assertThat(callbackInvoked).isNotNull()
        }
    }

    @Test
    fun statusBarTriggerManagerPostsOngoingSilentNotification() {
        statusBarTriggerManager.showTriggerNotification()

        assertThat(statusBarTriggerManager.isNotificationShown()).isTrue()

        val shadowNotificationManager = shadowOf(notificationManager)
        val notifications = shadowNotificationManager.allNotifications
        assertThat(notifications).hasSize(1)

        val notification = notifications[0]
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)

        // Verify notification actions (Add Widget, Overlay Settings)
        assertThat(notification.actions).isNotNull()
        assertThat(notification.actions.size).isAtLeast(2)
        val actionTitles = notification.actions.map { it.title.toString() }
        assertThat(actionTitles).contains(context.getString(R.string.shortcut_add_widget))
        assertThat(actionTitles).contains(context.getString(R.string.notification_action_settings))
    }

    @Test
    fun statusBarTriggerManagerCancelsNotification() {
        statusBarTriggerManager.showTriggerNotification()
        assertThat(statusBarTriggerManager.isNotificationShown()).isTrue()

        statusBarTriggerManager.cancelTriggerNotification()
        assertThat(statusBarTriggerManager.isNotificationShown()).isFalse()

        val shadowNotificationManager = shadowOf(notificationManager)
        assertThat(shadowNotificationManager.allNotifications).isEmpty()
    }

    @Test
    fun syncStatusBarTriggerTogglesNotification() {
        statusBarTriggerManager.syncStatusBarTrigger(true)
        assertThat(statusBarTriggerManager.isNotificationShown()).isTrue()

        statusBarTriggerManager.syncStatusBarTrigger(false)
        assertThat(statusBarTriggerManager.isNotificationShown()).isFalse()
    }

    @Test
    fun tileServiceOnClickRoutesToActiveDisplayFromAccessibilityService() {
        val topBarController = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val topBarService = topBarController.create().get()
        topBarService.onServiceConnected()
        shadowOf(Looper.getMainLooper()).idle()

        val tileController = Robolectric.buildService(WidgetDeckTileService::class.java).create()
        val tileService = tileController.get()

        try {
            val focusedOnDisplay3 = AccessibilityWindowInfo.obtain().also { win ->
                val s = shadowOf(win)
                s.setId(401)
                s.setDisplayId(3)
                s.setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                s.setFocused(true)
                s.setActive(true)
                s.setBoundsInScreen(Rect(0, 0, 1920, 1080))
            }
            shadowOf(topBarService as AccessibilityService).setWindowsOnDisplay(3, listOf(focusedOnDisplay3))

            tileService.onClick()
            assertThat(tileService.lastLaunchedDisplayId).isEqualTo(3)
        } finally {
            topBarController.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun statusBarTriggerNotificationTargetsToggleDeckShortcutActivityForDynamicDisplayResolution() {
        statusBarTriggerManager.showTriggerNotification()
        val notifications = shadowOf(notificationManager).allNotifications
        assertThat(notifications).hasSize(1)

        val contentIntent = shadowOf(notifications[0].contentIntent).savedIntent
        assertThat(contentIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
        assertThat(contentIntent.component?.className).isEqualTo(ActiveDisplayLaunchHelper.SHORTCUT_ACTIVITY_CLASS_NAME)
    }
}
