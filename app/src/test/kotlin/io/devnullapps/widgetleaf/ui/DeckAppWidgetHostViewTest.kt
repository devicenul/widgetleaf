package io.devnullapps.widgetleaf.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.ui.hostview.DeckAppWidgetHostView
import io.devnullapps.widgetleaf.ui.hostview.DeckAppWidgetHostView.HostInteractionMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DeckAppWidgetHostViewTest {

    private lateinit var context: Context
    private lateinit var hostView: DeckAppWidgetHostView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        hostView = DeckAppWidgetHostView(context)
        hostView.setAppWidget(42, null)
    }

    @Test
    fun defaultInteractionModeIsInteractive() {
        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.INTERACTIVE)
        assertThat(hostView.appWidgetId).isEqualTo(42)
    }

    @Test
    fun paddingIsZeroByDefault() {
        assertThat(hostView.paddingLeft).isEqualTo(0)
        assertThat(hostView.paddingTop).isEqualTo(0)
        assertThat(hostView.paddingRight).isEqualTo(0)
        assertThat(hostView.paddingBottom).isEqualTo(0)
        assertThat(hostView.paddingStart).isEqualTo(0)
        assertThat(hostView.paddingEnd).isEqualTo(0)
    }

    @Test
    fun setPadding_forcesPaddingToZero() {
        hostView.setPadding(16, 24, 32, 40)
        assertThat(hostView.paddingLeft).isEqualTo(0)
        assertThat(hostView.paddingTop).isEqualTo(0)
        assertThat(hostView.paddingRight).isEqualTo(0)
        assertThat(hostView.paddingBottom).isEqualTo(0)
        assertThat(hostView.paddingStart).isEqualTo(0)
        assertThat(hostView.paddingEnd).isEqualTo(0)
    }

    @Test
    fun setPaddingRelative_forcesPaddingToZero() {
        hostView.setPaddingRelative(16, 24, 32, 40)
        assertThat(hostView.paddingLeft).isEqualTo(0)
        assertThat(hostView.paddingTop).isEqualTo(0)
        assertThat(hostView.paddingRight).isEqualTo(0)
        assertThat(hostView.paddingBottom).isEqualTo(0)
        assertThat(hostView.paddingStart).isEqualTo(0)
        assertThat(hostView.paddingEnd).isEqualTo(0)
    }

    @Test
    fun setAppWidget_preservesZeroPadding() {
        hostView.setAppWidget(99, null)
        assertThat(hostView.paddingLeft).isEqualTo(0)
        assertThat(hostView.paddingTop).isEqualTo(0)
        assertThat(hostView.paddingRight).isEqualTo(0)
        assertThat(hostView.paddingBottom).isEqualTo(0)
        assertThat(hostView.paddingStart).isEqualTo(0)
        assertThat(hostView.paddingEnd).isEqualTo(0)
    }

    @Test
    fun secondaryButtonRightClickEntersEditModeAndFiresListener() {
        var enteredId = -1
        var rawX = 0f
        var rawY = 0f
        hostView.onEnterEditModeListener = { id, x, y ->
            enteredId = id
            rawX = x
            rawY = y
        }

        val event = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            x = 120f,
            y = 180f,
            buttonState = MotionEvent.BUTTON_SECONDARY
        )

        val consumed = hostView.dispatchTouchEvent(event)
        event.recycle()

        assertThat(consumed).isTrue()
        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.EDIT_MODE)
        assertThat(enteredId).isEqualTo(42)
        assertThat(rawX).isEqualTo(120f)
        assertThat(rawY).isEqualTo(180f)
    }

    @Test
    fun ctrlLeftClickEntersEditModeAndFiresListener() {
        var enteredId = -1
        hostView.onEnterEditModeListener = { id, _, _ -> enteredId = id }

        val event = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            x = 50f,
            y = 60f,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            metaState = KeyEvent.META_CTRL_ON
        )

        val consumed = hostView.dispatchTouchEvent(event)
        event.recycle()

        assertThat(consumed).isTrue()
        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.EDIT_MODE)
        assertThat(enteredId).isEqualTo(42)
    }

    @Test
    fun twoFingerPointerDownEntersEditModeAndFiresListener() {
        var enteredId = -1
        hostView.onEnterEditModeListener = { id, _, _ -> enteredId = id }

        val event = createTwoPointerDownEvent(100f, 100f, 150f, 150f)
        val consumed = hostView.dispatchTouchEvent(event)
        event.recycle()

        assertThat(consumed).isTrue()
        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.EDIT_MODE)
        assertThat(enteredId).isEqualTo(42)
    }

    @Test
    fun longPressInInteractiveModeEntersEditMode() {
        var enteredId = -1
        var capturedX = -1f
        var capturedY = -1f
        hostView.onEnterEditModeListener = { id, x, y ->
            enteredId = id
            capturedX = x
            capturedY = y
        }

        val downEvent = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            x = 75f,
            y = 85f
        )
        hostView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.INTERACTIVE)

        // Advance looper past long press timeout
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        ShadowLooper.idleMainLooper(timeout + 50, TimeUnit.MILLISECONDS)

        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.EDIT_MODE)
        assertThat(enteredId).isEqualTo(42)
        assertThat(capturedX).isEqualTo(75f)
        assertThat(capturedY).isEqualTo(85f)
    }

    @Test
    fun longPressCanceledWhenMovingBeyondTouchSlop() {
        var enteredId = -1
        hostView.onEnterEditModeListener = { id, _, _ -> enteredId = id }

        val downEvent = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            x = 75f,
            y = 85f
        )
        hostView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val moveEvent = createMotionEvent(
            action = MotionEvent.ACTION_MOVE,
            x = 75f + slop + 20f,
            y = 85f
        )
        hostView.dispatchTouchEvent(moveEvent)
        moveEvent.recycle()

        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        ShadowLooper.idleMainLooper(timeout + 50, TimeUnit.MILLISECONDS)

        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.INTERACTIVE)
        assertThat(enteredId).isEqualTo(-1)
    }

    @Test
    fun disallowInterceptCancelsPendingLongPress() {
        var enteredId = -1
        hostView.onEnterEditModeListener = { id, _, _ -> enteredId = id }

        val downEvent = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            x = 50f,
            y = 50f
        )
        hostView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        hostView.requestDisallowInterceptTouchEvent(true)

        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        ShadowLooper.idleMainLooper(timeout + 50, TimeUnit.MILLISECONDS)

        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.INTERACTIVE)
        assertThat(enteredId).isEqualTo(-1)
    }

    @Test
    fun editModeInterceptsTouchEvent() {
        hostView.interactionMode = HostInteractionMode.EDIT_MODE

        val downEvent = createMotionEvent(
            action = MotionEvent.ACTION_DOWN,
            x = 100f,
            y = 100f
        )
        val intercepted = hostView.onInterceptTouchEvent(downEvent)
        downEvent.recycle()

        assertThat(intercepted).isTrue()
    }

    @Test
    fun interactiveModePassesClicksAndInvokesClickListener() {
        var clickedId = -1
        hostView.onWidgetClickedListener = { id -> clickedId = id }

        val downEvent = createMotionEvent(action = MotionEvent.ACTION_DOWN, x = 40f, y = 40f)
        val upEvent = createMotionEvent(action = MotionEvent.ACTION_UP, x = 40f, y = 40f)

        hostView.dispatchTouchEvent(downEvent)
        hostView.dispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()

        assertThat(clickedId).isEqualTo(42)
        assertThat(hostView.lastInteractiveClickTimestampMs).isGreaterThan(0L)
    }

    @Test
    fun genericMotionSecondaryClickEntersEditMode() {
        var enteredId = -1
        hostView.onEnterEditModeListener = { id, _, _ -> enteredId = id }

        val event = createMotionEvent(
            action = MotionEvent.ACTION_SCROLL,
            x = 50f,
            y = 50f,
            buttonState = MotionEvent.BUTTON_SECONDARY
        )

        val consumed = hostView.dispatchGenericMotionEvent(event)
        event.recycle()

        assertThat(consumed).isTrue()
        assertThat(hostView.interactionMode).isEqualTo(HostInteractionMode.EDIT_MODE)
        assertThat(enteredId).isEqualTo(42)
    }

    @Test
    fun genericMotionScrollDelegatesToScrollableChild() {
        var horizontalScrollDelta: Float? = null
        hostView.onHorizontalPageScrollListener = { delta -> horizontalScrollDelta = delta }

        val listView = ListView(context)
        hostView.addView(listView)

        val scrollEvent = createScrollEvent(vScroll = 2.0f, hScroll = 0f)
        val handled = hostView.dispatchGenericMotionEvent(scrollEvent)
        scrollEvent.recycle()

        assertThat(handled).isTrue()
        assertThat(horizontalScrollDelta).isNull()
    }

    @Test
    fun genericMotionScrollRoutesToHorizontalPagerWhenNoScrollableChild() {
        var horizontalScrollDelta: Float? = null
        hostView.onHorizontalPageScrollListener = { delta -> horizontalScrollDelta = delta }

        // HostView with only a static TextView child
        val textView = TextView(context).apply { text = "Static widget" }
        hostView.addView(textView)

        val scrollEvent = createScrollEvent(vScroll = 3.5f, hScroll = 0f)
        val handled = hostView.dispatchGenericMotionEvent(scrollEvent)
        scrollEvent.recycle()

        assertThat(handled).isTrue()
        assertThat(horizontalScrollDelta).isEqualTo(3.5f)
    }

    @Test
    fun genericMotionHorizontalScrollRoutesToPageScrollListener() {
        var horizontalScrollDelta: Float? = null
        hostView.onHorizontalPageScrollListener = { delta -> horizontalScrollDelta = delta }

        val scrollEvent = createScrollEvent(vScroll = 0f, hScroll = -4.0f)
        val handled = hostView.dispatchGenericMotionEvent(scrollEvent)
        scrollEvent.recycle()

        assertThat(handled).isTrue()
        assertThat(horizontalScrollDelta).isEqualTo(-4.0f)
    }

    @Test
    fun getErrorViewReturnsConfiguredErrorCardAndFiresListenerOnButtonClick() {
        var errorWidgetId = -1
        hostView.onErrorRemoveOrReloadListener = { id -> errorWidgetId = id }

        val errorView = hostView.getErrorView()
        assertThat(errorView).isInstanceOf(LinearLayout::class.java)
        assertThat(errorView.tag).isEqualTo("widget_error_view")

        val layout = errorView as LinearLayout
        var titleFound = false
        var button: Button? = null

        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            if (child is TextView && child !is Button) {
                if (child.text == "Widget failed to load") {
                    titleFound = true
                    assertThat(child.currentTextColor).isEqualTo(0xFFF9FAFB.toInt())
                }
            } else if (child is Button) {
                if (child.text == "Remove / Reload Widget") {
                    button = child
                    assertThat(child.currentTextColor).isEqualTo(0xFF10B981.toInt())
                }
            }
        }

        assertThat(titleFound).isTrue()
        assertThat(button).isNotNull()

        button!!.performClick()
        assertThat(errorWidgetId).isEqualTo(42)
    }

    // Helper functions for creating MotionEvents in Robolectric
    private fun createMotionEvent(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        metaState: Int = 0
    ): MotionEvent {
        val pp = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val pc = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
        })
        return MotionEvent.obtain(
            100L,
            100L,
            action,
            1,
            pp,
            pc,
            metaState,
            buttonState,
            1.0f,
            1.0f,
            0,
            0,
            android.view.InputDevice.SOURCE_MOUSE,
            0
        )
    }

    private fun createTwoPointerDownEvent(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): MotionEvent {
        val pp = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val pc = arrayOf(
            MotionEvent.PointerCoords().apply { x = x1; y = y1 },
            MotionEvent.PointerCoords().apply { x = x2; y = y2 }
        )
        return MotionEvent.obtain(
            100L,
            100L,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            pp,
            pc,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }

    private fun createScrollEvent(vScroll: Float, hScroll: Float): MotionEvent {
        val pp = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val pc = arrayOf(MotionEvent.PointerCoords().apply {
            x = 50f
            y = 50f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        })
        return MotionEvent.obtain(
            100L,
            100L,
            MotionEvent.ACTION_SCROLL,
            1,
            pp,
            pc,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            android.view.InputDevice.SOURCE_MOUSE,
            0
        )
    }
}
