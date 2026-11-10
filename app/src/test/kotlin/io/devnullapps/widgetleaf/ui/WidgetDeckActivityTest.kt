package io.devnullapps.widgetleaf.ui

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.service.quicksettings.TileService
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper
import io.devnullapps.widgetleaf.ui.gesture.TrackpadGestureClassifier
import io.devnullapps.widgetleaf.ui.viewmodel.DeckUiState
import io.devnullapps.widgetleaf.ui.viewmodel.DeckViewModel
import io.devnullapps.widgetleaf.ui.viewmodel.PendingWidgetAction
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w1440dp-h1080dp")
class WidgetDeckActivityTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadowAppWidgetManager = Shadows.shadowOf(AppWidgetManager.getInstance(context))
        shadowAppWidgetManager.setAllowedToBindAppWidgets(true)
        shadowAppWidgetManager.setValidWidgetProviderComponentName(true)
    }

    @org.junit.After
    fun tearDown() {
        WidgetDeckActivity.activeInstance = null
        WidgetDeckActivity.isDeckVisible = false
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun tearDownController(controller: org.robolectric.android.controller.ActivityController<WidgetDeckActivity>) {
        try {
            val activity = controller.get()
            activity.setContentView(android.view.View(activity))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } catch (_: Throwable) {
        }
        try {
            controller.pause().stop().destroy()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } catch (_: Throwable) {
        }
        WidgetDeckActivity.activeInstance = null
        WidgetDeckActivity.isDeckVisible = false
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun launchWithOpenWidgetPickerIntent_opensWidgetPickerState() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java).apply {
            action = ActiveDisplayLaunchHelper.ACTION_OPEN_WIDGET_PICKER
        }

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertThat(activity.viewModel.uiState.value.isWidgetPickerOpen).isTrue()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun launchWithQsTilePreferencesIntent_opensOverlaySettingsState() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java).apply {
            action = TileService.ACTION_QS_TILE_PREFERENCES
        }

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertThat(activity.viewModel.uiState.value.isOverlaySettingsOpen).isTrue()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun keyboardShortcuts_plusOpensPicker_andEscapeClosesPickerThenDismissesActivity() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertThat(activity.viewModel.uiState.value.isWidgetPickerOpen).isFalse()

            // Press '+' to open Widget Picker
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PLUS))
            assertThat(activity.viewModel.uiState.value.isWidgetPickerOpen).isTrue()

            // First Escape closes Widget Picker without finishing Activity
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            assertThat(activity.viewModel.uiState.value.isWidgetPickerOpen).isFalse()
            assertThat(activity.isFinishing).isFalse()

            // Second Escape finishes Activity
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            assertThat(activity.isFinishing).isTrue()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun keyboardNavigation_arrowsNumberKeysAndDeleteSelectedWidget() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            // Add two widgets across pages
            val item = WidgetCatalogItem(
                providerPackage = "io.devnullapps.test",
                providerClass = "io.devnullapps.test.TestWidget",
                appName = "TestApp",
                widgetLabel = "Clock",
                defaultSpanX = 8,
                defaultSpanY = 8,
                resizeMode = PlacedWidget.RESIZE_BOTH
            )
            activity.viewModel.addWidgetAutoPlace(item)
            activity.viewModel.addWidgetAutoPlace(item)

            assertThat(activity.viewModel.uiState.value.pages.size).isEqualTo(2)
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(1)

            // Key '1' jumps to Page 1 (index 0)
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)

            // Left arrow from Page 1 loops circularly to last page (index 1)
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(1)

            // Right arrow from last page loops circularly to Page 1 (index 0)
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)

            // Select widget in Edit Mode and press Delete
            val firstWidgetId = activity.viewModel.uiState.value.widgets.first().appWidgetId
            activity.viewModel.enterWidgetEditMode(firstWidgetId)
            assertThat(activity.viewModel.uiState.value.editingWidgetId).isEqualTo(firstWidgetId)

            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
            assertThat(activity.viewModel.uiState.value.editingWidgetId).isNull()
            assertThat(activity.viewModel.uiState.value.pages.size).isEqualTo(1)
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun keyboardShortcuts_ctrlN_numpadAdd_shiftEquals_andPageUpDown() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            // Ctrl+N opens picker
            val ctrlN = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N, 0, KeyEvent.META_CTRL_ON)
            activity.dispatchKeyEvent(ctrlN)
            assertThat(activity.viewModel.uiState.value.isWidgetPickerOpen).isTrue()
            activity.viewModel.closeWidgetPicker()

            // Numpad Add opens picker
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ADD))
            assertThat(activity.viewModel.uiState.value.isWidgetPickerOpen).isTrue()
            activity.viewModel.closeWidgetPicker()

            // Shift + Equals opens picker
            val shiftEquals = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_EQUALS, 0, KeyEvent.META_SHIFT_ON)
            activity.dispatchKeyEvent(shiftEquals)
            assertThat(activity.viewModel.uiState.value.isWidgetPickerOpen).isTrue()
            activity.viewModel.closeWidgetPicker()

            // Page Up / Down navigation
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN))
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun newIntentToggleDeck_whileResumed_dismissesActivity() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val toggleIntent = Intent(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
            val method = WidgetDeckActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            method.isAccessible = true
            method.invoke(activity, toggleIntent)
            assertThat(activity.isFinishing).isTrue()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun onUserLeaveHint_dismissesActivityWhenAllowed() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val method = WidgetDeckActivity::class.java.getDeclaredMethod("onUserLeaveHint")
            method.isAccessible = true
            method.invoke(activity)
            assertThat(activity.isFinishing).isTrue()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun twoFingerTrackpadSecondaryClick_opensContextMenuAndDoesNotDismissActivity() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertThat(activity.viewModel.uiState.value.contextMenuPosition).isNull()

            val now = android.os.SystemClock.uptimeMillis()
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 })
            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 320f; y = 240f })
            val buttonPress = android.view.MotionEvent.obtain(
                now,
                now,
                android.view.MotionEvent.ACTION_BUTTON_PRESS,
                1,
                props,
                coords,
                0,
                android.view.MotionEvent.BUTTON_SECONDARY,
                1f,
                1f,
                0,
                0,
                android.view.InputDevice.SOURCE_MOUSE,
                0
            )

            val consumed = activity.dispatchGenericMotionEvent(buttonPress)
            buttonPress.recycle()

            assertThat(consumed).isTrue()
            assertThat(activity.viewModel.uiState.value.contextMenuPosition).isEqualTo(Pair(320f, 240f))
            assertThat(activity.isFinishing).isFalse()

            // Even if the OS synthesizes a spurious KEYCODE_BACK right after secondary click,
            // it must be absorbed within the 600ms debounce window without closing the menu or activity.
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            assertThat(activity.viewModel.uiState.value.contextMenuPosition).isEqualTo(Pair(320f, 240f))
            assertThat(activity.isFinishing).isFalse()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun twoFingerTrackpadSecondaryClick_onPlacedWidget_entersWidgetEditModeWithoutContextMenu() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val item = WidgetCatalogItem(
                providerPackage = "io.devnullapps.test",
                providerClass = "io.devnullapps.test.TestWidget",
                appName = "TestApp",
                widgetLabel = "Clock",
                defaultSpanX = 4,
                defaultSpanY = 2,
                resizeMode = PlacedWidget.RESIZE_BOTH
            )
            activity.viewModel.addWidgetAutoPlace(item)
            val placed = activity.viewModel.uiState.value.widgets.first()

            // Secondary click inside the top-left cell (cellX=0, cellY=0) where the widget is placed
            val now = android.os.SystemClock.uptimeMillis()
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 })
            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 80f; y = 80f })
            val buttonPress = android.view.MotionEvent.obtain(
                now,
                now,
                android.view.MotionEvent.ACTION_BUTTON_PRESS,
                1,
                props,
                coords,
                0,
                android.view.MotionEvent.BUTTON_SECONDARY,
                1f,
                1f,
                0,
                0,
                android.view.InputDevice.SOURCE_MOUSE,
                0
            )

            val consumed = activity.dispatchGenericMotionEvent(buttonPress)
            buttonPress.recycle()

            assertThat(consumed).isTrue()
            assertThat(activity.viewModel.uiState.value.editingWidgetId).isEqualTo(placed.appWidgetId)
            assertThat(activity.viewModel.uiState.value.contextMenuPosition).isNull()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun pressingNonWidgetLeafKeyOnDeckCanvas_dismissesOverlay() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val keysToTest = listOf(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_EQUALS
        )

        for (keyCode in keysToTest) {
            val intent = Intent(context, WidgetDeckActivity::class.java)
            val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
            try {
                val activity = controller.get()
                assertThat(activity.isFinishing).isFalse()

                // Fast forward past initial 300ms creation debounce window
                android.os.SystemClock.sleep(350L)

                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                assertThat(activity.isFinishing).isTrue()
            } finally {
                tearDownController(controller)
            }
        }
    }

    private fun createTrackpadMotionEvent(
        x: Float = 500f,
        y: Float = 500f,
        dx: Float = 0f,
        dy: Float = 0f,
        uptimeMs: Long = android.os.SystemClock.uptimeMillis()
    ): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(TrackpadGestureClassifier.AXIS_GESTURE_SCROLL_X_DISTANCE, dx)
                setAxisValue(TrackpadGestureClassifier.AXIS_GESTURE_SCROLL_Y_DISTANCE, dy)
                setAxisValue(MotionEvent.AXIS_HSCROLL, dx)
                setAxisValue(MotionEvent.AXIS_VSCROLL, dy)
            }
        )
        return MotionEvent.obtain(
            uptimeMs,
            uptimeMs,
            MotionEvent.ACTION_SCROLL,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_TOUCHPAD,
            0
        )
    }

    private fun createMouseWheelMotionEvent(
        x: Float = 500f,
        y: Float = 500f,
        vScroll: Float = 1.0f,
        hScroll: Float = 0.0f
    ): MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            }
        )
        return MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_SCROLL,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
    }

    @Test
    fun trackpadTwoFingerSwipe_changesPage_andEnforcesBounds() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val item = WidgetCatalogItem(
                providerPackage = "io.devnullapps.test",
                providerClass = "io.devnullapps.test.TestWidget",
                appName = "TestApp",
                widgetLabel = "Clock",
                defaultSpanX = 8,
                defaultSpanY = 8,
                resizeMode = PlacedWidget.RESIZE_BOTH
            )
            activity.viewModel.addWidgetAutoPlace(item)
            activity.viewModel.addWidgetAutoPlace(item)
            assertThat(activity.viewModel.uiState.value.pages.size).isEqualTo(2)

            // Jump to page 0
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)

            var t = 1000L
            // Trackpad swipe right (positive dx) on Page 0: bounds check, stays on Page 0
            val swipeRightFromZero = createTrackpadMotionEvent(dx = 200f, dy = 0f, uptimeMs = t)
            activity.dispatchGenericMotionEvent(swipeRightFromZero)
            swipeRightFromZero.recycle()
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)

            t += 500L
            // Trackpad swipe left (negative dx): advances to Page 1
            val swipeLeft = createTrackpadMotionEvent(dx = -200f, dy = 0f, uptimeMs = t)
            activity.dispatchGenericMotionEvent(swipeLeft)
            swipeLeft.recycle()
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(1)

            t += 500L
            // Trackpad swipe left on last page: bounds check, stays on Page 1 (no wrap or new pages)
            val swipeLeftFromLast = createTrackpadMotionEvent(dx = -200f, dy = 0f, uptimeMs = t)
            activity.dispatchGenericMotionEvent(swipeLeftFromLast)
            swipeLeftFromLast.recycle()
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(1)
            assertThat(activity.viewModel.uiState.value.pages.size).isEqualTo(2)

            t += 500L
            // Trackpad swipe right on Page 1: navigates back to Page 0
            val swipeRight = createTrackpadMotionEvent(dx = 200f, dy = 0f, uptimeMs = t)
            activity.dispatchGenericMotionEvent(swipeRight)
            swipeRight.recycle()
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun trackpadTwoFingerSwipe_verticalDismissOnBackground() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertThat(activity.isFinishing).isFalse()

            // Vertical swipe down on background dismisses pane
            val swipeDown = createTrackpadMotionEvent(dx = 0f, dy = 200f, uptimeMs = 1000L)
            activity.dispatchGenericMotionEvent(swipeDown)
            swipeDown.recycle()
            assertThat(activity.isFinishing).isTrue()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun trackpadTwoFingerSwipe_disabledInEditMode() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val item = WidgetCatalogItem(
                providerPackage = "io.devnullapps.test",
                providerClass = "io.devnullapps.test.TestWidget",
                appName = "TestApp",
                widgetLabel = "Clock",
                defaultSpanX = 4,
                defaultSpanY = 2,
                resizeMode = PlacedWidget.RESIZE_BOTH
            )
            activity.viewModel.addWidgetAutoPlace(item)
            val placed = activity.viewModel.uiState.value.widgets.first()

            // Enter edit mode
            activity.viewModel.enterWidgetEditMode(placed.appWidgetId)
            assertThat(activity.viewModel.uiState.value.editingWidgetId).isEqualTo(placed.appWidgetId)

            // Swipes must NOT change page or dismiss when in edit mode
            val swipeDown = createTrackpadMotionEvent(dx = 0f, dy = 200f, uptimeMs = 1000L)
            activity.dispatchGenericMotionEvent(swipeDown)
            swipeDown.recycle()
            assertThat(activity.isFinishing).isFalse()

            val swipeLeft = createTrackpadMotionEvent(dx = -200f, dy = 0f, uptimeMs = 1500L)
            activity.dispatchGenericMotionEvent(swipeLeft)
            swipeLeft.recycle()
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun regularMouseWheel_doesNotPageOrDismiss() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            // Add two widgets across pages
            val item = WidgetCatalogItem(
                providerPackage = "io.devnullapps.test",
                providerClass = "io.devnullapps.test.TestWidget",
                appName = "TestApp",
                widgetLabel = "Clock",
                defaultSpanX = 8,
                defaultSpanY = 8,
                resizeMode = PlacedWidget.RESIZE_BOTH
            )
            activity.viewModel.addWidgetAutoPlace(item)
            activity.viewModel.addWidgetAutoPlace(item)
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)

            // Mouse wheel vertical scroll down: must NOT dismiss and must NOT page
            val wheelV = createMouseWheelMotionEvent(vScroll = 1.0f)
            activity.dispatchGenericMotionEvent(wheelV)
            wheelV.recycle()
            assertThat(activity.isFinishing).isFalse()
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)

            // Mouse wheel horizontal scroll: must NOT page
            val wheelH = createMouseWheelMotionEvent(vScroll = 0f, hScroll = -1.0f)
            activity.dispatchGenericMotionEvent(wheelH)
            wheelH.recycle()
            assertThat(activity.viewModel.uiState.value.activePageIndex).isEqualTo(0)
            assertThat(activity.isFinishing).isFalse()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun hotkeyToggle_dismissesOverlayWhenOpen_andDebouncesKeyRepeat() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)

        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertThat(activity.isFinishing).isFalse()

            // Key repeat for hotkey (repeatCount = 1) must be ignored and not dismiss as an "other key"
            val repeatHotkey = KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_W,
                1,
                KeyEvent.META_ALT_ON
            )
            activity.dispatchKeyEvent(repeatHotkey)
            assertThat(activity.isFinishing).isFalse()

            // Clean hotkey press (Alt + W) dismisses the active overlay
            val hotkeyPress = KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_W,
                0,
                KeyEvent.META_ALT_ON
            )
            activity.dispatchKeyEvent(hotkeyPress)
            assertThat(activity.isFinishing).isTrue()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun toggleDeckShortcutActivity_dismissesRunningOverlay() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val deckIntent = Intent(context, WidgetDeckActivity::class.java)

        val deckController = Robolectric.buildActivity(WidgetDeckActivity::class.java, deckIntent).setup()
        try {
            val activity = deckController.get()
            assertThat(activity.isFinishing).isFalse()
            assertThat(WidgetDeckActivity.activeInstance).isSameInstanceAs(activity)
            assertThat(WidgetDeckActivity.isDeckVisible).isTrue()

            // Trigger shortcut activity while deck is open
            val shortcutController = Robolectric.buildActivity(ToggleDeckShortcutActivity::class.java).setup()
            try {
                assertThat(activity.isFinishing).isTrue()
            } finally {
                shortcutController.pause().stop().destroy()
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
        } finally {
            tearDownController(deckController)
        }
    }

    @Test
    fun configureLaunchOptions_setsLaunchDisplayId() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)
        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val options = activity.createConfigureLaunchOptions()
            assertThat(options.launchDisplayId).isEqualTo(ActiveDisplayLaunchHelper.getActiveDisplayId(activity))
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun reassertOverlayPresentation_setsFullMatchParentLayoutAndScreenFlags() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)
        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            activity.reassertOverlayPresentation()

            val lp = activity.window.attributes
            assertThat(lp.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
            assertThat(lp.height).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
            val flags = lp.flags
            assertThat((flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0).isTrue()
            assertThat((flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0).isTrue()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun configureResult_resultOk_placesWidgetAndPreservesOverlayWithoutFinishing() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)
        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val candidate = PlacedWidget(
                appWidgetId = 77,
                providerPackage = "com.config.ok",
                providerClass = "ConfigWidget",
                pageIndex = 0,
                cellX = 0,
                cellY = 0,
                spanX = 2,
                spanY = 2
            )
            activity.viewModel.markLaunchingInternalFlow(true)
            val shadowVm = activity.viewModel
            val requestAction = PendingWidgetAction.RequestConfigure(
                appWidgetId = 77,
                candidateWidget = candidate
            )
            val uiStateField = DeckViewModel::class.java.getDeclaredField("_uiState")
            uiStateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val mutableState = uiStateField.get(shadowVm) as kotlinx.coroutines.flow.MutableStateFlow<DeckUiState>
            mutableState.value = mutableState.value.copy(
                isLaunchingInternalFlow = true,
                pendingWidgetAction = requestAction
            )

            // Deliver RESULT_OK via onActivityResult
            val resultIntent = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 77)
            }
            val onActivityResultMethod = WidgetDeckActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java
            )
            onActivityResultMethod.isAccessible = true
            onActivityResultMethod.invoke(activity, 4042, Activity.RESULT_OK, resultIntent)

            // Must NOT finish and relaunch
            assertThat(activity.isFinishing).isFalse()

            // Overlay layout and flags must be preserved
            val lp = activity.window.attributes
            assertThat(lp.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
            assertThat(lp.height).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
            assertThat((lp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0).isTrue()

            // Internal flow cleared and widget placed
            assertThat(shadowVm.uiState.value.isLaunchingInternalFlow).isFalse()
            assertThat(shadowVm.uiState.value.pendingWidgetAction).isNull()
            val placed = shadowVm.uiState.value.widgets.find { it.appWidgetId == 77 }
            assertThat(placed).isNotNull()
        } finally {
            tearDownController(controller)
        }
    }

    @Test
    fun configureResult_resultCanceled_preservesOverlayWithoutFinishing() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetDeckActivity::class.java)
        val controller = Robolectric.buildActivity(WidgetDeckActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val candidate = PlacedWidget(
                appWidgetId = 88,
                providerPackage = "com.config.cancel",
                providerClass = "CancelWidget",
                pageIndex = 0,
                cellX = 0,
                cellY = 0,
                spanX = 2,
                spanY = 2
            )
            val shadowVm = activity.viewModel
            val uiStateField = DeckViewModel::class.java.getDeclaredField("_uiState")
            uiStateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val mutableState = uiStateField.get(shadowVm) as kotlinx.coroutines.flow.MutableStateFlow<DeckUiState>
            mutableState.value = mutableState.value.copy(
                isLaunchingInternalFlow = true,
                pendingWidgetAction = PendingWidgetAction.RequestConfigure(88, candidate)
            )

            // Deliver RESULT_CANCELED
            val onActivityResultMethod = WidgetDeckActivity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java
            )
            onActivityResultMethod.isAccessible = true
            onActivityResultMethod.invoke(activity, 4042, Activity.RESULT_CANCELED, null)

            // Must NOT finish
            assertThat(activity.isFinishing).isFalse()

            // Overlay flags preserved
            val lp = activity.window.attributes
            assertThat((lp.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0).isTrue()

            // State cleaned up and widget NOT placed
            assertThat(shadowVm.uiState.value.isLaunchingInternalFlow).isFalse()
            assertThat(shadowVm.uiState.value.pendingWidgetAction).isNull()
            val placed = shadowVm.uiState.value.widgets.find { it.appWidgetId == 88 }
            assertThat(placed).isNull()
        } finally {
            tearDownController(controller)
        }
    }
}
