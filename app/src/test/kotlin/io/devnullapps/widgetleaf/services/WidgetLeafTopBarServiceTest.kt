package io.devnullapps.widgetleaf.services

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.TopBarPosition
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w1440dp-h1080dp")
class WidgetLeafTopBarServiceTest {

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var shadowPowerManager: ShadowPowerManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        powerManager = context.getSystemService(PowerManager::class.java)
        shadowPowerManager = Shadows.shadowOf(powerManager)
        shadowPowerManager.setIsInteractive(true)
    }

    @After
    fun tearDown() {
        DefaultServicesContainer.setContainerForTesting(null)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun serviceLifecycle_connectsAttachesWindowAndCleansUpOnDestroy() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()

        service.onServiceConnected()
        service.updateStripVisibilityAndPosition()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertThat(WidgetLeafTopBarService.instance).isSameInstanceAs(service)
        assertThat(WidgetLeafTopBarService.isServiceEnabled(context)).isTrue()
        assertThat(service.stripContainer).isNotNull()

        // Configuration change
        service.onConfigurationChanged(Configuration())
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Windows changed accessibility event
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        service.onAccessibilityEvent(event)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        service.onInterrupt()

        controller.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertThat(WidgetLeafTopBarService.instance).isNull()
        assertThat(service.stripContainer).isNull()
    }

    @Test
    fun onKeyEvent_matchingHotkeyLaunchesOverlayAndConsumesEvent() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()
        service.onServiceConnected()
        service.updateStripVisibilityAndPosition()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        try {
            // Default hotkey is Alt + W
            val altW = KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_W,
                0,
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
            val consumed = service.onKeyEvent(altW)
            assertThat(consumed).isTrue()

            val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            val nextIntent = shadowApp.nextStartedActivity
            assertThat(nextIntent).isNotNull()
            assertThat(nextIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)

            // Non-matching key event returns false
            val plainA = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
            assertThat(service.onKeyEvent(plainA)).isFalse()
        } finally {
            controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun topBarView_clickAndLongClickAndHover_triggerExpectedActions() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()
        service.onServiceConnected()
        service.updateStripVisibilityAndPosition()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        try {
            val topBarView = service.stripContainer
            assertThat(topBarView).isNotNull()
            requireNotNull(topBarView)

            val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            shadowApp.clearNextStartedActivities()

            // Click -> ACTION_TOGGLE_DECK
            topBarView.performClick()
            val clickIntent = shadowApp.nextStartedActivity
            assertThat(clickIntent).isNotNull()
            assertThat(clickIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)

            // Long click -> ACTION_OPEN_OVERLAY_SETTINGS
            topBarView.performLongClick()
            val longClickIntent = shadowApp.nextStartedActivity
            assertThat(longClickIntent).isNotNull()
            assertThat(longClickIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS)

            // Hover enter and exit
            val hoverEnter = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 10f, 10f, 0)
            topBarView.dispatchGenericMotionEvent(hoverEnter)
            hoverEnter.recycle()

            val hoverExit = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_EXIT, 10f, 10f, 0)
            topBarView.dispatchGenericMotionEvent(hoverExit)
            hoverExit.recycle()

            // Secondary button click -> ACTION_OPEN_OVERLAY_SETTINGS
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f })
            val secondaryPress = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_BUTTON_PRESS,
                1,
                props,
                coords,
                0,
                MotionEvent.BUTTON_SECONDARY,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0
            )
            topBarView.dispatchGenericMotionEvent(secondaryPress)
            secondaryPress.recycle()

            val secondaryIntent = shadowApp.nextStartedActivity
            assertThat(secondaryIntent).isNotNull()
            assertThat(secondaryIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS)
        } finally {
            controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun placementUpdates_onTopBarPositionSettingsChange_andHidesWhenScreenOffOrLocked() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()
        service.onServiceConnected()
        service.currentSettings = OverlaySettings(topBarPosition = TopBarPosition.LEFT)
        service.updateStripVisibilityAndPosition()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        try {
            val topBarView = service.stripContainer
            assertThat(topBarView).isNotNull()
            requireNotNull(topBarView)

            assertThat(topBarView.visibility).isEqualTo(View.VISIBLE)
            val initialParams = service.layoutParams
            assertThat(initialParams).isNotNull()
            assertThat(initialParams?.gravity).isEqualTo(Gravity.TOP or Gravity.LEFT)

            // Switch to RIGHT
            service.currentSettings = OverlaySettings(topBarPosition = TopBarPosition.RIGHT)
            service.refreshTopBarPlacement()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val rightParams = service.layoutParams
            assertThat(rightParams?.gravity).isEqualTo(Gravity.TOP or Gravity.RIGHT)

            // Non-interactive power state hides container
            shadowPowerManager.setIsInteractive(false)
            service.refreshTopBarPlacement()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertThat(topBarView.visibility).isEqualTo(View.INVISIBLE)

            shadowPowerManager.setIsInteractive(true)
            service.refreshTopBarPlacement()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertThat(topBarView.visibility).isEqualTo(View.VISIBLE)

            // Keyguard locked hides container
            val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            val shadowKeyguard = Shadows.shadowOf(keyguardManager)
            shadowKeyguard.setKeyguardLocked(true)
            service.refreshTopBarPlacement()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertThat(topBarView.visibility).isEqualTo(View.INVISIBLE)
        } finally {
            controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun isServiceEnabled_and_openAccessibilitySettings_executeCorrectly() {
        // Set secure settings string for accessibility services
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/${WidgetLeafTopBarService::class.java.name}"
        )
        assertThat(WidgetLeafTopBarService.isServiceEnabled(context)).isTrue()

        // Open accessibility settings
        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
        shadowApp.clearNextStartedActivities()
        WidgetLeafTopBarService.openAccessibilitySettings(context)
        val settingsIntent = shadowApp.nextStartedActivity
        assertThat(settingsIntent).isNotNull()
        assertThat(settingsIntent.action).isEqualTo(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
