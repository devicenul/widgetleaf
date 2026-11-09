package io.devnullapps.widgetleaf.ui.hostview

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

open class DeckAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    enum class HostInteractionMode {
        INTERACTIVE,
        EDIT_MODE
    }

    var interactionMode: HostInteractionMode = HostInteractionMode.INTERACTIVE
        set(value) {
            field = value
            if (value == HostInteractionMode.EDIT_MODE) {
                cancelLongPressTimer()
                isLongPressTriggered = false
            }
        }

    var onEnterEditModeListener: ((appWidgetId: Int, rawX: Float, rawY: Float) -> Unit)? = null
    var onHorizontalPageScrollListener: ((delta: Float) -> Unit)? = null
    var onErrorRemoveOrReloadListener: ((appWidgetId: Int) -> Unit)? = null
    var onWidgetClickedListener: ((appWidgetId: Int) -> Unit)? = null

    var lastInteractiveClickTimestampMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLongPressRunnable: Runnable? = null
    private var isLongPressTriggered: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downRawX: Float = 0f
    private var downRawY: Float = 0f
    private var downTimeMs: Long = 0L

    private val MotionEvent.isCtrlPressed: Boolean
        get() = (metaState and KeyEvent.META_CTRL_ON) != 0 || (metaState and KeyEvent.META_CTRL_MASK) != 0

    fun isSecondaryOrCtrlClick(event: MotionEvent): Boolean {
        val buttonState = event.buttonState
        val isSecondaryButton = (buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
                event.actionButton == MotionEvent.BUTTON_SECONDARY
        val isCtrlPrimary = (((buttonState and MotionEvent.BUTTON_PRIMARY) != 0 ||
                event.actionMasked == MotionEvent.ACTION_DOWN) && event.isCtrlPressed)
        val isTwoFingerDown = (event.pointerCount == 2 &&
                (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_DOWN))

        return isSecondaryButton || isCtrlPrimary || isTwoFingerDown
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isSecondaryOrCtrlClick(event)) {
            cancelLongPressTimer()
            cancelChildTouches(event)
            interactionMode = HostInteractionMode.EDIT_MODE
            onEnterEditModeListener?.invoke(appWidgetId, event.rawX, event.rawY)
            return true
        }

        if (interactionMode == HostInteractionMode.EDIT_MODE) {
            cancelLongPressTimer()
            return false
        }

        // INTERACTIVE mode handling
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isLongPressTriggered = false
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                downTimeMs = event.downTime
                scheduleLongPressTimer()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > slop * slop) {
                    cancelLongPressTimer()
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPressTimer()
                val wasLongPress = isLongPressTriggered
                isLongPressTriggered = false
                if (!wasLongPress) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy <= slop * slop) {
                        lastInteractiveClickTimestampMs = SystemClock.uptimeMillis()
                        onWidgetClickedListener?.invoke(appWidgetId)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                isLongPressTriggered = false
            }
        }

        if (isLongPressTriggered) {
            return true
        }

        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (interactionMode == HostInteractionMode.EDIT_MODE) {
            return true
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (interactionMode == HostInteractionMode.EDIT_MODE) {
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
        if (disallowIntercept) {
            cancelLongPressTimer()
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isSecondaryOrCtrlClick(event)) {
            cancelLongPressTimer()
            cancelChildTouches(event)
            interactionMode = HostInteractionMode.EDIT_MODE
            onEnterEditModeListener?.invoke(appWidgetId, event.rawX, event.rawY)
            return true
        }

        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)

            val direction = if (vScroll > 0f) -1 else 1
            val hasScrollableChild = canChildScrollVertically(this, direction)

            if (abs(vScroll) >= abs(hScroll) && vScroll != 0f && hasScrollableChild) {
                super.dispatchGenericMotionEvent(event)
                return true
            } else if (abs(hScroll) > abs(vScroll) || (vScroll != 0f && !hasScrollableChild)) {
                val delta = if (hScroll != 0f) hScroll else vScroll
                onHorizontalPageScrollListener?.invoke(delta)
                return true
            }
        }

        return super.dispatchGenericMotionEvent(event)
    }

    public override fun getErrorView(): View {
        val density = context.resources.displayMetrics.density

        val errorLayout = LinearLayout(context).apply {
            tag = "widget_error_view"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (16 * density).toInt()
            setPadding(padding, padding, padding, padding)

            val roundedBg = GradientDrawable().apply {
                setColor(0xA6161B22.toInt())
                cornerRadius = 16f * density
            }
            background = roundedBg
        }

        val titleView = TextView(context).apply {
            text = "Widget failed to load"
            setTextColor(0xFFF9FAFB.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val titleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (12 * density).toInt()
        }
        errorLayout.addView(titleView, titleParams)

        val reloadButton = Button(context).apply {
            text = "Remove / Reload Widget"
            setTextColor(0xFF10B981.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                onErrorRemoveOrReloadListener?.invoke(appWidgetId)
            }
        }
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        errorLayout.addView(reloadButton, buttonParams)

        return errorLayout
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelLongPressTimer()
    }

    private fun scheduleLongPressTimer() {
        cancelLongPressTimer()
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        val runnable = Runnable {
            if (interactionMode == HostInteractionMode.INTERACTIVE) {
                isLongPressTriggered = true
                cancelChildTouches(null)
                interactionMode = HostInteractionMode.EDIT_MODE
                onEnterEditModeListener?.invoke(appWidgetId, downRawX, downRawY)
            }
        }
        pendingLongPressRunnable = runnable
        mainHandler.postDelayed(runnable, timeout)
    }

    private fun cancelLongPressTimer() {
        pendingLongPressRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingLongPressRunnable = null
        }
    }

    private fun cancelChildTouches(event: MotionEvent?) {
        val now = SystemClock.uptimeMillis()
        val downTime = event?.downTime ?: if (downTimeMs > 0L) downTimeMs else now
        val eventTime = event?.eventTime ?: now
        val cancelEvent = MotionEvent.obtain(
            downTime,
            eventTime,
            MotionEvent.ACTION_CANCEL,
            0f,
            0f,
            0
        )
        try {
            super.dispatchTouchEvent(cancelEvent)
        } catch (ignored: Throwable) {
        } finally {
            cancelEvent.recycle()
        }
    }

    private fun canChildScrollVertically(view: View, direction: Int): Boolean {
        if (view.visibility != View.VISIBLE) {
            return false
        }
        if (view !== this) {
            if (view.canScrollVertically(direction)) {
                return true
            }
            if (view is AbsListView || view is ScrollView || view.javaClass.name.contains("StackView")) {
                return true
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (canChildScrollVertically(child, direction)) {
                    return true
                }
            }
        }
        return false
    }
}
