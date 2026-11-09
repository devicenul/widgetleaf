package io.devnullapps.widgetleaf.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.service.quicksettings.TileService
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper
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
            controller.pause().stop().destroy()
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
            controller.pause().stop().destroy()
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
            controller.pause().stop().destroy()
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
                defaultSpanY = 4,
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
            controller.pause().stop().destroy()
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
            controller.pause().stop().destroy()
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
            controller.pause().stop().destroy()
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
            controller.pause().stop().destroy()
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
            controller.pause().stop().destroy()
        }
    }
}
