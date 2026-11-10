package io.devnullapps.widgetleaf.ui.gesture

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrackpadGestureClassifierTest {

    @After
    fun tearDown() {
        TrackpadGestureClassifier.testClassificationOverride = null
    }

    private fun obtainMotionEvent(
        action: Int,
        x: Float = 100f,
        y: Float = 100f,
        source: Int = InputDevice.SOURCE_MOUSE,
        toolType: Int = MotionEvent.TOOL_TYPE_MOUSE,
        axisValues: Map<Int, Float> = emptyMap()
    ): MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                this.toolType = toolType
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                axisValues.forEach { (axis, value) ->
                    setAxisValue(axis, value)
                }
            }
        )
        return MotionEvent.obtain(
            now,
            now,
            action,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            source,
            0
        )
    }

    @Test
    fun isTrackpadSwipe_returnsTrueForTwoFingerSwipeClassification() {
        TrackpadGestureClassifier.testClassificationOverride =
            TrackpadGestureClassifier.CLASSIFICATION_TWO_FINGER_SWIPE
        val event = obtainMotionEvent(action = MotionEvent.ACTION_SCROLL)
        assertThat(TrackpadGestureClassifier.isTrackpadSwipe(event)).isTrue()
        assertThat(TrackpadGestureClassifier.isMouseWheel(event)).isFalse()
        event.recycle()
    }

    @Test
    fun isTrackpadSwipe_returnsTrueForMultiFingerSwipeClassification() {
        TrackpadGestureClassifier.testClassificationOverride =
            TrackpadGestureClassifier.CLASSIFICATION_MULTI_FINGER_SWIPE
        val event = obtainMotionEvent(action = MotionEvent.ACTION_SCROLL)
        assertThat(TrackpadGestureClassifier.isTrackpadSwipe(event)).isTrue()
        assertThat(TrackpadGestureClassifier.isMouseWheel(event)).isFalse()
        event.recycle()
    }

    @Test
    fun isTrackpadSwipe_returnsTrueForGestureScrollAxes() {
        val event = obtainMotionEvent(
            action = MotionEvent.ACTION_MOVE,
            axisValues = mapOf(
                TrackpadGestureClassifier.AXIS_GESTURE_SCROLL_X_DISTANCE to -25.5f
            )
        )
        assertThat(TrackpadGestureClassifier.isTrackpadSwipe(event)).isTrue()
        assertThat(TrackpadGestureClassifier.isMouseWheel(event)).isFalse()
        event.recycle()

        val eventY = obtainMotionEvent(
            action = MotionEvent.ACTION_MOVE,
            axisValues = mapOf(
                TrackpadGestureClassifier.AXIS_GESTURE_SCROLL_Y_DISTANCE to 42.0f
            )
        )
        assertThat(TrackpadGestureClassifier.isTrackpadSwipe(eventY)).isTrue()
        eventY.recycle()
    }

    @Test
    fun isTrackpadSwipe_returnsTrueForSourceTouchpad() {
        val event = obtainMotionEvent(
            action = MotionEvent.ACTION_SCROLL,
            source = InputDevice.SOURCE_TOUCHPAD
        )
        assertThat(TrackpadGestureClassifier.isTrackpadSwipe(event)).isTrue()
        assertThat(TrackpadGestureClassifier.isMouseWheel(event)).isFalse()
        event.recycle()
    }

    @Test
    fun isTrackpadSwipe_returnsTrueForToolTypeFingerOnMouseSource() {
        val event = obtainMotionEvent(
            action = MotionEvent.ACTION_SCROLL,
            source = InputDevice.SOURCE_MOUSE,
            toolType = MotionEvent.TOOL_TYPE_FINGER
        )
        assertThat(TrackpadGestureClassifier.isTrackpadSwipe(event)).isTrue()
        assertThat(TrackpadGestureClassifier.isMouseWheel(event)).isFalse()
        event.recycle()
    }

    @Test
    fun isTrackpadSwipe_returnsFalseForStandardMouseWheel() {
        // Discrete mouse wheel click (vScroll = 1.0f or -1.0f, toolType = mouse)
        val event = obtainMotionEvent(
            action = MotionEvent.ACTION_SCROLL,
            source = InputDevice.SOURCE_MOUSE,
            toolType = MotionEvent.TOOL_TYPE_MOUSE,
            axisValues = mapOf(MotionEvent.AXIS_VSCROLL to 1.0f)
        )
        assertThat(TrackpadGestureClassifier.isTrackpadSwipe(event)).isFalse()
        assertThat(TrackpadGestureClassifier.isMouseWheel(event)).isTrue()
        event.recycle()
    }

    @Test
    fun isTrackpadSwipe_returnsTrueForFractionalScrollDeltasOnNonMouseTool() {
        // Fractional values (e.g. 0.35) from a touchpad that wasn't tagged as toolType mouse
        val event = obtainMotionEvent(
            action = MotionEvent.ACTION_SCROLL,
            source = InputDevice.SOURCE_CLASS_POINTER,
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN,
            axisValues = mapOf(MotionEvent.AXIS_VSCROLL to 0.35f)
        )
        assertThat(TrackpadGestureClassifier.isTrackpadSwipe(event)).isTrue()
        event.recycle()
    }

    @Test
    fun extractScrollDeltas_prefersGestureAxesAndReadsCorrectDeltas() {
        val event = obtainMotionEvent(
            action = MotionEvent.ACTION_MOVE,
            axisValues = mapOf(
                TrackpadGestureClassifier.AXIS_GESTURE_SCROLL_X_DISTANCE to -18.5f,
                TrackpadGestureClassifier.AXIS_GESTURE_SCROLL_Y_DISTANCE to 32.0f,
                MotionEvent.AXIS_HSCROLL to 1f,
                MotionEvent.AXIS_VSCROLL to 2f
            )
        )
        val deltas = TrackpadGestureClassifier.extractScrollDeltas(event)
        assertThat(deltas.dx).isEqualTo(-18.5f)
        assertThat(deltas.dy).isEqualTo(32.0f)
        event.recycle()
    }

    @Test
    fun extractScrollDeltas_fallsBackToHScrollAndVScrollWhenGestureAxesZero() {
        val event = obtainMotionEvent(
            action = MotionEvent.ACTION_SCROLL,
            axisValues = mapOf(
                MotionEvent.AXIS_HSCROLL to -3.5f,
                MotionEvent.AXIS_VSCROLL to 7.2f
            )
        )
        val deltas = TrackpadGestureClassifier.extractScrollDeltas(event)
        assertThat(deltas.dx).isEqualTo(-3.5f)
        assertThat(deltas.dy).isEqualTo(7.2f)
        event.recycle()
    }

    @Test
    fun trackpadSwipeAccumulator_pageNextAndPreviousWithDominantHorizontal() {
        val accumulator = TrackpadSwipeAccumulator(
            pageThresholdPx = 40f,
            dismissThresholdPx = 60f,
            cooldownMs = 200L
        )

        // Swipe Left (dx = -25, then dx = -20) -> Next Page
        var result = accumulator.onTrackpadEvent(
            dx = -25f,
            dy = 2f,
            uptimeMs = 1000L,
            canScrollX = { false },
            canScrollY = { false }
        )
        assertThat(result).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.NONE)

        result = accumulator.onTrackpadEvent(
            dx = -20f,
            dy = 1f,
            uptimeMs = 1050L,
            canScrollX = { false },
            canScrollY = { false }
        )
        assertThat(result).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.PAGE_NEXT)

        // Events during cooldown return NONE
        result = accumulator.onTrackpadEvent(
            dx = 50f,
            dy = 0f,
            uptimeMs = 1100L,
            canScrollX = { false },
            canScrollY = { false }
        )
        assertThat(result).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.NONE)

        // After cooldown, Swipe Right (dx = 45f) -> Previous Page
        result = accumulator.onTrackpadEvent(
            dx = 45f,
            dy = 0f,
            uptimeMs = 1350L,
            canScrollX = { false },
            canScrollY = { false }
        )
        assertThat(result).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.PAGE_PREVIOUS)
    }

    @Test
    fun trackpadSwipeAccumulator_dismissWithDominantVertical() {
        val accumulator = TrackpadSwipeAccumulator(
            pageThresholdPx = 40f,
            dismissThresholdPx = 60f,
            cooldownMs = 200L
        )

        // Vertical swipe down (dy = 70f) -> Dismiss
        val result = accumulator.onTrackpadEvent(
            dx = 5f,
            dy = 70f,
            uptimeMs = 1000L,
            canScrollX = { false },
            canScrollY = { false }
        )
        assertThat(result).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.DISMISS)
    }

    @Test
    fun trackpadSwipeAccumulator_suppressedWhenWidgetCanScroll() {
        val accumulator = TrackpadSwipeAccumulator(
            pageThresholdPx = 40f,
            dismissThresholdPx = 60f,
            cooldownMs = 200L
        )

        // Horizontal swipe over widget that can scroll horizontally
        val resultH = accumulator.onTrackpadEvent(
            dx = -80f,
            dy = 0f,
            uptimeMs = 1000L,
            canScrollX = { true },
            canScrollY = { false }
        )
        assertThat(resultH).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.NONE)

        // Vertical swipe over widget that can scroll vertically
        val resultV = accumulator.onTrackpadEvent(
            dx = 0f,
            dy = 90f,
            uptimeMs = 1050L,
            canScrollX = { false },
            canScrollY = { true }
        )
        assertThat(resultV).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.NONE)
    }

    @Test
    fun trackpadSwipeAccumulator_resetsOnTimeoutAndExplicitReset() {
        val accumulator = TrackpadSwipeAccumulator(
            pageThresholdPx = 40f,
            dismissThresholdPx = 60f,
            gestureTimeoutMs = 300L
        )

        accumulator.onTrackpadEvent(
            dx = -25f,
            dy = 0f,
            uptimeMs = 1000L,
            canScrollX = { false },
            canScrollY = { false }
        )

        // Event arrived 500ms later (> 300ms timeout) -> accumulated dx reset to 0 before adding 10
        val resultAfterTimeout = accumulator.onTrackpadEvent(
            dx = -10f,
            dy = 0f,
            uptimeMs = 1500L,
            canScrollX = { false },
            canScrollY = { false }
        )
        assertThat(resultAfterTimeout).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.NONE)

        accumulator.reset()
        val resultAfterReset = accumulator.onTrackpadEvent(
            dx = -15f,
            dy = 0f,
            uptimeMs = 1550L,
            canScrollX = { false },
            canScrollY = { false }
        )
        assertThat(resultAfterReset).isEqualTo(TrackpadSwipeAccumulator.SwipeResult.NONE)
    }
}
