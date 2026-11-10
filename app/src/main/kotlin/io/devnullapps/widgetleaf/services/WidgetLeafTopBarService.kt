package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import io.devnullapps.widgetleaf.R
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.ui.WidgetDeckActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Lightweight desktop top-bar overlay & global hotkey service for Googlebook / Android Desktop.
 *
 * Draws a small, native status-bar-style leaf icon inside the desktop status bar's
 * `DesktopStatusBarSpacer` region on either the Left or Right, coexisting side-by-side with
 * BentoBar (`com.meridian11.books.bentobar`) without overlap, and intercepts the user's global
 * hotkey to toggle the widget pane from any app.
 */
class WidgetLeafTopBarService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager

    internal var currentSettings: OverlaySettings = OverlaySettings()
    internal var stripContainer: FrameLayout? = null
    private var hoverPillBg: GradientDrawable? = null
    private var iconView: ImageView? = null
    internal var layoutParams: WindowManager.LayoutParams? = null
    private var isWindowAttached = false
    private var lastHotkeyToggleUptimeMs: Long = -10_000L

    private val rescanRunnable = Runnable {
        refreshTopBarPlacement()
    }

    private val periodicPollRunnable = object : Runnable {
        override fun run() {
            if (powerManager.isInteractive) {
                refreshTopBarPlacement()
            }
            mainHandler.postDelayed(this, 2500L)
        }
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)

        serviceScope.launch {
            DefaultServicesContainer.getPreferencesDataStore(applicationContext)
                .settingsFlow
                .collectLatest { settings ->
                    currentSettings = settings
                    DefaultServicesContainer.getTaskbarPlacementManager(applicationContext)
                        .syncRunningTaskbarPresence(applicationContext)
                    updateStripVisibilityAndPosition()
                }
        }

        mainHandler.removeCallbacks(periodicPollRunnable)
        mainHandler.postDelayed(periodicPollRunnable, 2000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleSettlingRescans()
        }
    }

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        if (currentSettings.matchesKeyEvent(event)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastHotkeyToggleUptimeMs < 350L) {
                return true
            }
            lastHotkeyToggleUptimeMs = now

            val active = WidgetDeckActivity.activeInstance
            if (active != null && !active.isFinishing && WidgetDeckActivity.isDeckVisible) {
                active.finish()
            } else {
                ActiveDisplayLaunchHelper.launchOverlay(
                    this,
                    ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
                )
            }
            return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateIconTintForConfiguration()
        scheduleSettlingRescans()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) {
            instance = null
        }
        mainHandler.removeCallbacksAndMessages(null)
        detachWindowIfNeeded()
        serviceScope.cancel()
    }

    private fun scheduleSettlingRescans() {
        mainHandler.removeCallbacks(rescanRunnable)
        mainHandler.postDelayed(rescanRunnable, 80L)
        mainHandler.postDelayed(rescanRunnable, 400L)
    }

    internal fun updateStripVisibilityAndPosition() {
        ensureWindowAttached()
        refreshTopBarPlacement()
    }

    private fun ensureWindowAttached() {
        if (isWindowAttached && stripContainer != null) return

        val density = resources.displayMetrics.density
        val slotWidthPx = (28 * density).toInt()
        val glyphSizePx = (16 * density).toInt()
        val hoverHeightPx = (22 * density).toInt()

        val hoverBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 11f * density
            setColor(Color.TRANSPARENT)
        }
        hoverPillBg = hoverBg

        val leafGlyph = ImageView(this).apply {
            setImageResource(R.drawable.ic_qs_tile_leaf)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        iconView = leafGlyph
        updateIconTintForConfiguration()

        val hoverFrame = FrameLayout(this).apply {
            background = hoverBg
            addView(
                leafGlyph,
                FrameLayout.LayoutParams(glyphSizePx, glyphSizePx, Gravity.CENTER)
            )
        }

        val rootContainer = FrameLayout(this).apply {
            contentDescription = getString(R.string.app_name)
            addView(
                hoverFrame,
                FrameLayout.LayoutParams(slotWidthPx, hoverHeightPx, Gravity.CENTER)
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
            setOnHoverListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> {
                        hoverBg.setColor(Color.argb(42, 255, 255, 255))
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        hoverBg.setColor(Color.TRANSPARENT)
                    }
                }
                false
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

        val insetTop = windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            .top
        val initialHeightPx = if (insetTop > 0) insetTop else (36 * density).toInt()

        val params = WindowManager.LayoutParams(
            slotWidthPx,
            initialHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (174 * density).toInt()
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = StatusBarSpaceScanner.WIDGET_LEAF_WINDOW_TITLE
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

    private fun updateIconTintForConfiguration() {
        // Desktop top bar uses crisp white status bar glyphs
        iconView?.setColorFilter(Color.WHITE)
    }

    internal fun refreshTopBarPlacement() {
        val container = stripContainer ?: return
        val params = layoutParams ?: return
        if (!isWindowAttached) {
            container.visibility = View.INVISIBLE
            return
        }

        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
            container.visibility = View.INVISIBLE
            return
        }

        val density = resources.displayMetrics.density
        val gapPx = (6 * density).toInt()
        val itemWidthPx = (28 * density).toInt()

        val scanResult = StatusBarSpaceScanner.scan(this, windowManager)
        if (scanResult != null) {
            if (scanResult.shouldHideForFullscreenOrSystemPanel) {
                container.visibility = View.INVISIBLE
                return
            }

            val placement = StatusBarSpaceScanner.computeCoexistingPlacement(
                barBounds = scanResult.barBounds,
                freeSpace = scanResult.freeSpace,
                otherTopBarOverlays = scanResult.otherTopBarOverlays,
                position = currentSettings.topBarPosition,
                itemWidthPx = itemWidthPx,
                screenWidth = scanResult.screenWidth,
                gapPx = gapPx
            )

            container.visibility = View.VISIBLE
            if (params.gravity != placement.gravity ||
                params.x != placement.x ||
                params.y != placement.y ||
                params.width != placement.width ||
                params.height != placement.height
            ) {
                params.gravity = placement.gravity
                params.x = placement.x
                params.y = placement.y
                params.width = placement.width
                params.height = placement.height
                try {
                    windowManager.updateViewLayout(container, params)
                } catch (_: Throwable) {
                    // Ignored if view detached concurrently
                }
            }
        } else {
            val metrics = windowManager.currentWindowMetrics
            val screenW = metrics.bounds.width()
            val insetTop = metrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                .top
            val barH = if (insetTop > 0) insetTop else (36 * density).toInt()
            val fallbackFree = Rect(
                (screenW * 0.12f).toInt(),
                0,
                (screenW * 0.82f).toInt(),
                barH
            )
            val placement = StatusBarSpaceScanner.computeCoexistingPlacement(
                barBounds = Rect(0, 0, screenW, barH),
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
            params.width = placement.width
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
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
                // Ignored
            }
        }
        stripContainer = null
        hoverPillBg = null
        iconView = null
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
            val am = context.getSystemService(AccessibilityManager::class.java)
            val enabledInManager = runCatching {
                am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any {
                        val s = it.resolveInfo.serviceInfo
                        s.packageName == expectedComponent.packageName &&
                            s.name == expectedComponent.className
                    } == true
            }.getOrDefault(false)
            if (enabledInManager) return true

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
                val cn = ComponentName(context, WidgetLeafTopBarService::class.java).flattenToString()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(":settings:fragment_args_key", cn)
                    putExtra(
                        ":settings:show_fragment_args",
                        Bundle().apply { putString(":settings:fragment_args_key", cn) }
                    )
                }
                context.startActivity(intent)
            } catch (_: Throwable) {
                // Ignored in headless test environments
            }
        }
    }
}
