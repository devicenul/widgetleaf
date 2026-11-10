package io.devnullapps.widgetleaf.ui.gesture

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Classifies MotionEvents to distinguish physical mouse wheels from trackpad / touchpad gestures
 * on Android Desktop (API 34/35 Googlebook / ChromeOS / tablet trackpads).
 *
 * Background & Heuristics:
 * 1. Android 14+ (API 34+) Classification:
 *    Touchpad two-finger scrolls arrive with `event.classification == CLASSIFICATION_TWO_FINGER_SWIPE` (value 3).
 *    Multi-finger gestures (3+ fingers) report `CLASSIFICATION_MULTI_FINGER_SWIPE` (value 4).
 * 2. Gesture Axes:
 *    Modern trackpads deliver relative scroll pixel distance via `AXIS_GESTURE_SCROLL_X_DISTANCE` (50)
 *    and `AXIS_GESTURE_SCROLL_Y_DISTANCE` (51). Non-zero values definitively identify touchpad gestures.
 * 3. Source & Tool Type:
 *    Trackpads report `InputDevice.SOURCE_TOUCHPAD`, or `SOURCE_MOUSE` / `SOURCE_CLASS_POINTER` with
 *    `TOOL_TYPE_FINGER` (indicating the OS translated finger gestures on a touchpad into pointer motion).
 * 4. Continuous Fractional Axes vs Discrete Mouse Steps:
 *    Traditional mechanical mouse wheels report discrete integer step values (typically exact 1.0 or -1.0)
 *    with `TOOL_TYPE_MOUSE`, `CLASSIFICATION_NONE`, and zero gesture axes. Trackpads without classification
 *    emit high-resolution fractional values (e.g. 0.12, 0.45).
 */
object TrackpadGestureClassifier {

    const val CLASSIFICATION_NONE = 0
    const val CLASSIFICATION_TWO_FINGER_SWIPE = 3
    const val CLASSIFICATION_MULTI_FINGER_SWIPE = 4

    const val AXIS_GESTURE_SCROLL_X_DISTANCE = 50
    const val AXIS_GESTURE_SCROLL_Y_DISTANCE = 51

    data class ScrollDelta(
        val dx: Float,
        val dy: Float
    )

    @androidx.annotation.VisibleForTesting
    internal var testClassificationOverride: Int? = null

    /**
     * Determines whether the given [MotionEvent] originates from a touchpad / trackpad gesture
     * rather than a physical mouse wheel or standard pointer click.
     */
    fun isTrackpadSwipe(event: MotionEvent): Boolean {
        // 1. Android 14+ Classification check
        val classification = testClassificationOverride ?: event.classification
        if (classification == CLASSIFICATION_TWO_FINGER_SWIPE ||
            classification == CLASSIFICATION_MULTI_FINGER_SWIPE
        ) {
            return true
        }

        // 2. Modern Gesture Scroll Axes (non-zero on API 34+ trackpads)
        val gestureX = event.getAxisValue(AXIS_GESTURE_SCROLL_X_DISTANCE)
        val gestureY = event.getAxisValue(AXIS_GESTURE_SCROLL_Y_DISTANCE)
        if (gestureX != 0f || gestureY != 0f) {
            return true
        }

        // 3. Explicit Touchpad Input Source
        if ((event.source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return true
        }

        // 4. Pointer Tool Type: Finger on a mouse/pointer device indicates touchpad
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        if (toolType == MotionEvent.TOOL_TYPE_FINGER &&
            (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_CLASS_POINTER))
        ) {
            return true
        }

        // Physical mouse pointer tool with no gesture indicators is a mouse wheel
        if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
            return false
        }

        // 5. Fractional axis heuristic for older or emulated touchpads delivering ACTION_SCROLL
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val hasFractionalV = vScroll != 0f && (abs(vScroll % 1.0f) > 0.001f)
            val hasFractionalH = hScroll != 0f && (abs(hScroll % 1.0f) > 0.001f)
            if (hasFractionalV || hasFractionalH) {
                return true
            }
        }

        return false
    }

    /**
     * Returns true if the event is a traditional mouse wheel scroll (which must never trigger page/dismiss).
     */
    fun isMouseWheel(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        return !isTrackpadSwipe(event)
    }

    /**
     * Extracts horizontal and vertical delta distances from a trackpad motion event.
     * Incorporates historical batched samples if present.
     */
    fun extractScrollDeltas(event: MotionEvent): ScrollDelta {
        var totalDx = 0f
        var totalDy = 0f

        val gestureX = event.getAxisValue(AXIS_GESTURE_SCROLL_X_DISTANCE)
        val gestureY = event.getAxisValue(AXIS_GESTURE_SCROLL_Y_DISTANCE)

        if (gestureX != 0f || gestureY != 0f) {
            val historySize = event.historySize
            for (h in 0 until historySize) {
                totalDx += event.getHistoricalAxisValue(AXIS_GESTURE_SCROLL_X_DISTANCE, h)
                totalDy += event.getHistoricalAxisValue(AXIS_GESTURE_SCROLL_Y_DISTANCE, h)
            }
            totalDx += gestureX
            totalDy += gestureY
        } else {
            // Fallback to AXIS_HSCROLL and AXIS_VSCROLL
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            totalDx = hScroll
            totalDy = vScroll
        }

        return ScrollDelta(dx = totalDx, dy = totalDy)
    }
}

/**
 * Accumulates continuous trackpad scroll events, enforcing thresholds, dominant axis locking,
 * gesture timeouts, cooldowns, and widget scroll-direction checks.
 */
class TrackpadSwipeAccumulator(
    var pageThresholdPx: Float = 48f,
    var dismissThresholdPx: Float = 72f,
    private val gestureTimeoutMs: Long = 300L,
    private val cooldownMs: Long = 400L
) {
    private var accumulatedDx = 0f
    private var accumulatedDy = 0f
    private var lastEventUptimeMs = 0L
    private var lastTriggerUptimeMs = 0L

    enum class SwipeResult {
        NONE,
        PAGE_NEXT,
        PAGE_PREVIOUS,
        DISMISS
    }

    fun onTrackpadEvent(
        dx: Float,
        dy: Float,
        uptimeMs: Long,
        canScrollX: (direction: Int) -> Boolean,
        canScrollY: (direction: Int) -> Boolean
    ): SwipeResult {
        if (uptimeMs - lastTriggerUptimeMs < cooldownMs) {
            return SwipeResult.NONE
        }
        if (uptimeMs - lastEventUptimeMs > gestureTimeoutMs) {
            accumulatedDx = 0f
            accumulatedDy = 0f
        }
        lastEventUptimeMs = uptimeMs
        accumulatedDx += dx
        accumulatedDy += dy

        val absDx = abs(accumulatedDx)
        val absDy = abs(accumulatedDy)

        // Dominant axis check (require 1.2x ratio to prevent diagonal false triggers)
        if (absDx > absDy * 1.2f) {
            val dirX = if (accumulatedDx < 0) 1 else -1
            if (canScrollX(dirX)) {
                return SwipeResult.NONE
            }
            if (accumulatedDx <= -pageThresholdPx) {
                lastTriggerUptimeMs = uptimeMs
                accumulatedDx = 0f
                accumulatedDy = 0f
                return SwipeResult.PAGE_NEXT
            } else if (accumulatedDx >= pageThresholdPx) {
                lastTriggerUptimeMs = uptimeMs
                accumulatedDx = 0f
                accumulatedDy = 0f
                return SwipeResult.PAGE_PREVIOUS
            }
        } else if (absDy > absDx * 1.2f) {
            val dirY = if (accumulatedDy < 0) 1 else -1
            if (canScrollY(dirY)) {
                return SwipeResult.NONE
            }
            if (absDy >= dismissThresholdPx) {
                lastTriggerUptimeMs = uptimeMs
                accumulatedDx = 0f
                accumulatedDy = 0f
                return SwipeResult.DISMISS
            }
        }

        return SwipeResult.NONE
    }

    fun reset() {
        accumulatedDx = 0f
        accumulatedDy = 0f
        lastEventUptimeMs = 0L
        lastTriggerUptimeMs = 0L
    }
}
