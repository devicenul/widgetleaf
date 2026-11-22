package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.TimeUnit

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

    private fun setTopStatusBarWindow(
        service: WidgetLeafTopBarService,
        displayId: Int,
        screenWidthPx: Int = 1440,
        barHeightPx: Int = 41,
        extraWindows: List<AccessibilityWindowInfo> = emptyList()
    ) {
        val barWindow = AccessibilityWindowInfo.obtain().also { win ->
            val s = Shadows.shadowOf(win)
            s.setId(500 + displayId)
            s.setDisplayId(displayId)
            s.setType(AccessibilityWindowInfo.TYPE_SYSTEM)
            s.setBoundsInScreen(Rect(0, 0, screenWidthPx, barHeightPx))
        }
        Shadows.shadowOf(service as AccessibilityService)
            .setWindowsOnDisplay(displayId, listOf(barWindow) + extraWindows)
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
        setTopStatusBarWindow(service, Display.DEFAULT_DISPLAY, screenWidthPx = 1440)
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

    @Test
    fun missingTopStatusBar_hidesLeafWithoutFallbackPill() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()
        service.onServiceConnected()
        service.updateStripVisibilityAndPosition()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        try {
            val topBarView = service.stripContainer
            assertThat(topBarView).isNotNull()
            // Neither a top status bar window nor statusBars().top inset exists -> must stay hidden
            assertThat(topBarView?.visibility).isEqualTo(View.INVISIBLE)

            // Even with a bottom taskbar TYPE_SYSTEM window, there is no top status bar -> stay hidden
            val bottomTaskbar = AccessibilityWindowInfo.obtain().also { win ->
                val s = Shadows.shadowOf(win)
                s.setId(888)
                s.setDisplayId(Display.DEFAULT_DISPLAY)
                s.setType(AccessibilityWindowInfo.TYPE_SYSTEM)
                s.setBoundsInScreen(Rect(0, 1000, 1440, 1080))
            }
            Shadows.shadowOf(service as AccessibilityService)
                .setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(bottomTaskbar))
            service.refreshTopBarPlacement()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertThat(topBarView?.visibility).isEqualTo(View.INVISIBLE)
        } finally {
            controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun multiDisplay_addAndRemoveDisplay_managesPerDisplayWindowsAndLaunchesOnTargetDisplay() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()
        setTopStatusBarWindow(service, Display.DEFAULT_DISPLAY, screenWidthPx = 1440)
        service.onServiceConnected()
        service.updateStripVisibilityAndPosition()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        var secondaryDisplayId = Display.INVALID_DISPLAY
        try {
            secondaryDisplayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
            assertThat(secondaryDisplayId).isNotEqualTo(Display.DEFAULT_DISPLAY)
            assertThat(secondaryDisplayId).isNotEqualTo(Display.INVALID_DISPLAY)

            setTopStatusBarWindow(service, secondaryDisplayId, screenWidthPx = 1920, barHeightPx = 44)
            // Settling rescan (+80ms) picks up the newly-appeared SystemUI status bar on the new display
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

            assertThat(service.displayWindows).containsKey(Display.DEFAULT_DISPLAY)
            assertThat(service.displayWindows).containsKey(secondaryDisplayId)

            val defaultEntry = service.displayWindows.getValue(Display.DEFAULT_DISPLAY)
            val secondaryEntry = service.displayWindows.getValue(secondaryDisplayId)
            assertThat(defaultEntry.stripContainer).isNotSameInstanceAs(secondaryEntry.stripContainer)
            assertThat(defaultEntry.layoutParams.title.toString())
                .isEqualTo("${StatusBarSpaceScanner.WIDGET_LEAF_WINDOW_TITLE}:${Display.DEFAULT_DISPLAY}")
            assertThat(secondaryEntry.layoutParams.title.toString())
                .isEqualTo("${StatusBarSpaceScanner.WIDGET_LEAF_WINDOW_TITLE}:$secondaryDisplayId")
            assertThat(secondaryEntry.stripContainer.visibility).isEqualTo(View.VISIBLE)
            assertThat(secondaryEntry.layoutParams.gravity).isEqualTo(Gravity.TOP or Gravity.LEFT)

            // Switching TopBarPosition to RIGHT repositions secondary display's leaf too
            service.currentSettings = OverlaySettings(topBarPosition = TopBarPosition.RIGHT)
            service.refreshTopBarPlacement()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertThat(secondaryEntry.layoutParams.gravity).isEqualTo(Gravity.TOP or Gravity.RIGHT)

            // Clicking the leaf on the secondary display launches WidgetDeckActivity on that display
            val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            shadowApp.clearNextStartedActivities()
            secondaryEntry.stripContainer.performClick()

            val launched = shadowApp.nextStartedActivityForResult
            assertThat(launched).isNotNull()
            assertThat(launched.intent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
            assertThat(launched.options?.getInt("android.activity.launchDisplayId", Display.INVALID_DISPLAY))
                .isEqualTo(secondaryDisplayId)

            // Display removed -> entry cleaned up
            ShadowDisplayManager.removeDisplay(secondaryDisplayId)
            secondaryDisplayId = Display.INVALID_DISPLAY
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertThat(service.displayWindows).containsKey(Display.DEFAULT_DISPLAY)
            assertThat(service.displayWindows.size).isEqualTo(1)
        } finally {
            if (secondaryDisplayId != Display.INVALID_DISPLAY) {
                runCatching { ShadowDisplayManager.removeDisplay(secondaryDisplayId) }
            }
            controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun hotkeyOnKeyEvent_routesToFocusedDisplayViaWindowsOnAllDisplays() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()
        service.onServiceConnected()
        service.updateStripVisibilityAndPosition()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        try {
            val focusedOnSecondary = AccessibilityWindowInfo.obtain().also { win ->
                val s = Shadows.shadowOf(win)
                s.setId(901)
                s.setDisplayId(7)
                s.setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                s.setFocused(true)
                s.setActive(true)
                s.setBoundsInScreen(Rect(0, 0, 1920, 1080))
            }
            val unfocusedOnDefault = AccessibilityWindowInfo.obtain().also { win ->
                val s = Shadows.shadowOf(win)
                s.setId(902)
                s.setDisplayId(Display.DEFAULT_DISPLAY)
                s.setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                s.setFocused(false)
                s.setActive(false)
                s.setBoundsInScreen(Rect(0, 0, 1440, 1080))
            }
            val shadowService = Shadows.shadowOf(service as AccessibilityService)
            shadowService.setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(unfocusedOnDefault))
            shadowService.setWindowsOnDisplay(7, listOf(focusedOnSecondary))

            val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            shadowApp.clearNextStartedActivities()

            val altW = KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_W,
                0,
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            )
            assertThat(service.onKeyEvent(altW)).isTrue()

            val launched = shadowApp.nextStartedActivityForResult
            assertThat(launched).isNotNull()
            assertThat(launched.intent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
            assertThat(launched.options?.getInt("android.activity.launchDisplayId", Display.INVALID_DISPLAY))
                .isEqualTo(7)
        } finally {
            controller.destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
