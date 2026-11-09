package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import io.devnullapps.widgetleaf.R
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.TopBarPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Lightweight desktop top-bar overlay & global hotkey service for Googlebook / Android Desktop.
 *
 * Draws a clickable WidgetLeaf icon inside the status bar's `DesktopStatusBarSpacer` region on
 * either the Left or Right, coexisting side-by-side with BentoBar (`com.meridian11.books.bentobar`)
 * without overlap, and intercepts the user's global hotkey to toggle the widget pane from any app.
 */
class WidgetLeafTopBarService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var keyguardManager: KeyguardManager

    private var currentSettings: OverlaySettings = OverlaySettings()
    private var stripContainer: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isWindowAttached = false

    private val rescanRunnable = Runnable {
        refreshTopBarPlacement()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)

        serviceScope.launch {
            DefaultServicesContainer.getPreferencesDataStore(applicationContext)
                .settingsFlow
                .collectLatest { settings ->
                    currentSettings = settings
                    DefaultServicesContainer.getTaskbarPlacementManager(applicationContext)
                        .syncRunningTaskbarPresence(
                            applicationContext,
                            settings.effectiveShowInRunningTaskbar
                        )
                    updateStripVisibilityAndPosition()
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleRescan()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (currentSettings.matchesKeyEvent(event)) {
            ActiveDisplayLaunchHelper.launchOverlay(
                this,
                ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
            )
            return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleRescan()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacks(rescanRunnable)
        detachWindowIfNeeded()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun scheduleRescan() {
        mainHandler.removeCallbacks(rescanRunnable)
        mainHandler.postDelayed(rescanRunnable, 80L)
    }

    private fun updateStripVisibilityAndPosition() {
        if (!currentSettings.pinToTopBar) {
            detachWindowIfNeeded()
            return
        }
        ensureWindowAttached()
        refreshTopBarPlacement()
    }

    private fun ensureWindowAttached() {
        if (isWindowAttached && stripContainer != null) return

        val density = resources.displayMetrics.density
        val buttonSizePx = (28 * density).toInt()
        val iconSizePx = (18 * density).toInt()
        val horizontalPadPx = (4 * density).toInt()

        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(Color.argb(46, 16, 185, 129)) // subtle emerald glass tint
            setStroke((1 * density).toInt().coerceAtLeast(1), Color.argb(96, 52, 211, 153))
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_qs_tile_leaf)
            setColorFilter(Color.parseColor("#34D399"))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val buttonFrame = FrameLayout(this).apply {
            background = pillBg
            contentDescription = getString(R.string.app_name)
            addView(
                iconView,
                FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER)
            )
            setOnClickListener {
                ActiveDisplayLaunchHelper.launchOverlay(
                    this@WidgetLeafTopBarService,
                    ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
                )
            }
            setOnLongClickListener {
                ActiveDisplayLaunchHelper.launchOverlay(
                    this@WidgetLeafTopBarService,
                    ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS
                )
                true
            }
            setOnGenericMotionListener { _, event ->
                val isSecondary = (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
                    (event.actionButton == MotionEvent.BUTTON_SECONDARY)
                if (isSecondary && event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
                    ActiveDisplayLaunchHelper.launchOverlay(
                        this@WidgetLeafTopBarService,
                        ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS
                    )
                    true
                } else {
                    false
                }
            }
        }

        val rootContainer = FrameLayout(this).apply {
            setPadding(horizontalPadPx, 0, horizontalPadPx, 0)
            addView(
                buttonFrame,
                FrameLayout.LayoutParams(buttonSizePx, buttonSizePx, Gravity.CENTER)
            )
        }

        val defaultBarHeightPx = (36 * density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            defaultBarHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (176 * density).toInt()
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "WidgetLeafTopBar"
        }

        try {
            windowManager.addView(rootContainer, params)
            stripContainer = rootContainer
            layoutParams = params
            isWindowAttached = true
        } catch (_: Throwable) {
            isWindowAttached = false
        }
    }

    private fun refreshTopBarPlacement() {
        val container = stripContainer ?: return
        val params = layoutParams ?: return
        if (!isWindowAttached || !currentSettings.pinToTopBar) {
            container.visibility = View.GONE
            return
        }

        if (keyguardManager.isKeyguardLocked) {
            container.visibility = View.GONE
            return
        }

        val density = resources.displayMetrics.density
        val gapPx = (6 * density).toInt()
        val itemWidthPx = (36 * density).toInt()

        val scanResult = StatusBarSpaceScanner.scan(this)
        if (scanResult != null) {
            if (scanResult.shouldHideForFullscreenOrSystemPanel) {
                container.visibility = View.GONE
                return
            }

            val placement = StatusBarSpaceScanner.computeCoexistingPlacement(
                barBounds = scanResult.barBounds,
                freeSpace = scanResult.freeSpace,
                otherTopBarOverlays = emptyList(),
                position = currentSettings.topBarPosition,
                itemWidthPx = itemWidthPx,
                screenWidth = scanResult.screenWidth,
                gapPx = gapPx
            )

            container.visibility = View.VISIBLE
            if (params.gravity != placement.gravity ||
                params.x != placement.x ||
                params.y != placement.y ||
                params.height != placement.height
            ) {
                params.gravity = placement.gravity
                params.x = placement.x
                params.y = placement.y
                params.height = placement.height
                try {
                    windowManager.updateViewLayout(container, params)
                } catch (_: Throwable) {
                    // Ignored if view detached concurrently
                }
            }
        } else {
            // Fallback placement when status bar accessibility node bounds are not yet reported
            val screenW = resources.displayMetrics.widthPixels
            val fallbackFree = Rect(
                (screenW * 0.12f).toInt(),
                0,
                (screenW * 0.78f).toInt(),
                (36 * density).toInt()
            )
            val placement = StatusBarSpaceScanner.computeCoexistingPlacement(
                barBounds = Rect(0, 0, screenW, (36 * density).toInt()),
                freeSpace = fallbackFree,
                otherTopBarOverlays = emptyList(),
                position = currentSettings.topBarPosition,
                itemWidthPx = itemWidthPx,
                screenWidth = screenW,
                gapPx = gapPx
            )
            container.visibility = View.VISIBLE
            params.gravity = placement.gravity
            params.x = placement.x
            params.y = placement.y
            params.height = placement.height
            try {
                windowManager.updateViewLayout(container, params)
            } catch (_: Throwable) {
                // Ignored
            }
        }
    }

    private fun detachWindowIfNeeded() {
        val view = stripContainer
        if (isWindowAttached && view != null) {
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
                // Ignored
            }
        }
        stripContainer = null
        layoutParams = null
        isWindowAttached = false
    }

    companion object {
        @Volatile
        var instance: WidgetLeafTopBarService? = null
            private set

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expectedComponent = ComponentName(context, WidgetLeafTopBarService::class.java)
            val enabledServices = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (_: Throwable) {
                null
            } ?: return false

            return enabledServices.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it == expectedComponent }
        }

        fun openAccessibilitySettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Throwable) {
                // Ignored in headless test environments
            }
        }
    }
}
