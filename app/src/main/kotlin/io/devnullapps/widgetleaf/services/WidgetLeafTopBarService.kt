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
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Multi-display desktop top-bar overlay & global hotkey service for Googlebook / Android Desktop.
 *
 * Maintains an independent `TYPE_ACCESSIBILITY_OVERLAY` leaf pill per logical display via
 * [DisplayManager.DisplayListener] and per-display `createDisplayContext(display).createWindowContext(...)`,
 * coexisting with BentoBar and avoiding display cutouts, while hiding the floating leaf on any display
 * that has no top status bar (e.g. bottom-taskbar-only shells). Intercepts the global hotkey and routes
 * launches to the active display.
 */
class WidgetLeafTopBarService : AccessibilityService() {

    internal data class DisplayTopBarEntry(
        val displayId: Int,
        val windowContext: Context,
        val windowManager: WindowManager,
        val stripContainer: FrameLayout,
        val hoverPillBg: GradientDrawable,
        val iconView: ImageView,
        val layoutParams: WindowManager.LayoutParams,
        val density: Float,
        var isWindowAttached: Boolean = false
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private var displayManager: DisplayManager? = null

    internal var currentSettings: OverlaySettings = OverlaySettings()
    internal val displayWindows = mutableMapOf<Int, DisplayTopBarEntry>()

    internal val stripContainer: FrameLayout?
        get() = displayWindows[Display.DEFAULT_DISPLAY]?.stripContainer

    internal val layoutParams: WindowManager.LayoutParams?
        get() = displayWindows[Display.DEFAULT_DISPLAY]?.layoutParams

    private var lastHotkeyToggleUptimeMs: Long = -10_000L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            syncDisplays()
            displayWindows[displayId]?.let { refreshTopBarPlacementForDisplay(it) }
            scheduleSettlingRescans()
        }

        override fun onDisplayRemoved(displayId: Int) {
            removeDisplayEntry(displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            val display = displayManager?.getDisplay(displayId)
            if (display == null) {
                removeDisplayEntry(displayId)
                return
            }
            val existing = displayWindows[displayId]
            val probeDensity = createPerDisplayWindowContext(display).first.resources.displayMetrics.density
            if (existing == null || existing.density != probeDensity) {
                ensureDisplayWindowAttached(display)
            }
            displayWindows[displayId]?.let { refreshTopBarPlacementForDisplay(it) }
            scheduleSettlingRescans()
        }
    }

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
        keyguardManager = getSystemService(KeyguardManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)?.also { dm ->
            dm.registerDisplayListener(displayListener, mainHandler)
        }

        syncDisplays()

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

            val targetDisplayId = ActiveDisplayLaunchHelper.getActiveDisplayId(this)
            launchOrToggleOverlayOnDisplay(
                displayId = targetDisplayId,
                action = ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
            )
            return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncDisplays()
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
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        detachAllWindows()
        serviceScope.cancel()
    }

    private fun scheduleSettlingRescans() {
        mainHandler.removeCallbacks(rescanRunnable)
        mainHandler.postDelayed(rescanRunnable, 80L)
        mainHandler.postDelayed(rescanRunnable, 400L)
    }

    internal fun updateStripVisibilityAndPosition() {
        syncDisplays()
        refreshTopBarPlacement()
    }

    internal fun syncDisplays() {
        val dm = displayManager ?: getSystemService(DisplayManager::class.java).also { displayManager = it }
        val displays = try {
            dm?.getDisplays()?.filter {
                it.displayId != Display.INVALID_DISPLAY &&
                    (it.flags and Display.FLAG_PRIVATE) == 0
            }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        val activeIds = displays.map { it.displayId }.toSet()
        val staleIds = displayWindows.keys.filter { it !in activeIds }
        for (staleId in staleIds) {
            removeDisplayEntry(staleId)
        }

        if (displays.isEmpty() && !displayWindows.containsKey(Display.DEFAULT_DISPLAY)) {
            val fallbackWm = getSystemService(WindowManager::class.java) ?: return
            attachEntryForContext(
                displayId = Display.DEFAULT_DISPLAY,
                windowContext = this,
                perDisplayWm = fallbackWm
            )
            return
        }

        for (display in displays) {
            ensureDisplayWindowAttached(display)
        }
    }

    private fun createPerDisplayWindowContext(display: Display): Pair<Context, WindowManager> {
        val displayContext = try {
            createDisplayContext(display)
        } catch (_: Throwable) {
            this
        }
        val windowContext = try {
            displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                null
            )
        } catch (_: Throwable) {
            displayContext
        }
        val perDisplayWm = windowContext.getSystemService(WindowManager::class.java)
            ?: displayContext.getSystemService(WindowManager::class.java)
            ?: getSystemService(WindowManager::class.java)
        return Pair(windowContext, perDisplayWm)
    }

    private fun ensureDisplayWindowAttached(display: Display) {
        val displayId = display.displayId
        val (windowContext, perDisplayWm) = createPerDisplayWindowContext(display)
        val density = windowContext.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val existing = displayWindows[displayId]
        if (existing != null && existing.isWindowAttached && existing.density == density) {
            return
        }
        if (existing != null) {
            removeDisplayEntry(displayId)
        }
        attachEntryForContext(displayId, windowContext, perDisplayWm)
    }

    private fun attachEntryForContext(
        displayId: Int,
        windowContext: Context,
        perDisplayWm: WindowManager
    ) {
        val density = windowContext.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val slotWidthPx = (28 * density).toInt()
        val glyphSizePx = (16 * density).toInt()
        val hoverHeightPx = (22 * density).toInt()

        val hoverBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 11f * density
            setColor(Color.TRANSPARENT)
        }

        val leafGlyph = ImageView(windowContext).apply {
            setImageResource(R.drawable.ic_qs_tile_leaf)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val hoverFrame = FrameLayout(windowContext).apply {
            background = hoverBg
            addView(
                leafGlyph,
                FrameLayout.LayoutParams(glyphSizePx, glyphSizePx, Gravity.CENTER)
            )
        }

        val rootContainer = FrameLayout(windowContext).apply {
            contentDescription = getString(R.string.app_name)
            visibility = View.INVISIBLE
            addView(
                hoverFrame,
                FrameLayout.LayoutParams(slotWidthPx, hoverHeightPx, Gravity.CENTER)
            )
            setOnClickListener {
                launchOrToggleOverlayOnDisplay(
                    displayId = displayId,
                    action = ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
                )
            }
            setOnLongClickListener {
                launchOrToggleOverlayOnDisplay(
                    displayId = displayId,
                    action = ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS
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
                    launchOrToggleOverlayOnDisplay(
                        displayId = displayId,
                        action = ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS
                    )
                    true
                } else {
                    false
                }
            }
        }

        val insetTop = try {
            val wi = perDisplayWm.currentWindowMetrics.windowInsets
            maxOf(
                wi.getInsets(WindowInsets.Type.statusBars()).top,
                wi.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            )
        } catch (_: Throwable) {
            0
        }
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
            title = "${StatusBarSpaceScanner.WIDGET_LEAF_WINDOW_TITLE}:$displayId"
        }

        val entry = DisplayTopBarEntry(
            displayId = displayId,
            windowContext = windowContext,
            windowManager = perDisplayWm,
            stripContainer = rootContainer,
            hoverPillBg = hoverBg,
            iconView = leafGlyph,
            layoutParams = params,
            density = density,
            isWindowAttached = false
        )

        try {
            perDisplayWm.addView(rootContainer, params)
            entry.isWindowAttached = true
            displayWindows[displayId] = entry
        } catch (_: Throwable) {
            entry.isWindowAttached = false
        }
    }

    internal fun launchOrToggleOverlayOnDisplay(displayId: Int, action: String) {
        ActiveDisplayLaunchHelper.toggleOrLaunchOverlay(
            context = this,
            displayId = displayId,
            action = action
        )
    }

    private fun updateIconTintForConfiguration() {
        // Desktop top bar uses crisp white status bar glyphs
        for (entry in displayWindows.values) {
            entry.iconView.setColorFilter(Color.WHITE)
        }
    }

    internal fun refreshTopBarPlacement() {
        for (entry in displayWindows.values.toList()) {
            refreshTopBarPlacementForDisplay(entry)
        }
    }

    internal fun refreshTopBarPlacementForDisplay(entry: DisplayTopBarEntry) {
        val container = entry.stripContainer
        val params = entry.layoutParams
        if (!entry.isWindowAttached) {
            container.visibility = View.INVISIBLE
            return
        }

        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
            container.visibility = View.INVISIBLE
            return
        }

        val density = entry.density
        val gapPx = (6 * density).toInt()
        val itemWidthPx = (28 * density).toInt()

        val scanResult = StatusBarSpaceScanner.scan(
            service = this,
            windowManager = entry.windowManager,
            displayId = entry.displayId,
            density = density
        )

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
                gapPx = gapPx,
                cutoutRects = scanResult.cutoutRects,
                density = density
            )

            if (!placement.hasRoom) {
                container.visibility = View.INVISIBLE
                return
            }

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
                    entry.windowManager.updateViewLayout(container, params)
                } catch (_: Throwable) {
                    // Ignored if view detached concurrently
                }
            }
        } else {
            // No top status bar window found via Accessibility: check live visible statusBars inset.
            // On bottom-taskbar-only shells (e.g., DeX / ChromeOS shelf) or immersive fullscreen,
            // visibleInsetTop == 0 -> HIDE the leaf (no floating fallback pill).
            val metrics = entry.windowManager.currentWindowMetrics
            val screenW = metrics.bounds.width()
            val visibleInsetTop = metrics.windowInsets
                .getInsets(WindowInsets.Type.statusBars())
                .top
            if (visibleInsetTop <= 0 || screenW <= 0) {
                container.visibility = View.INVISIBLE
                return
            }

            val cutoutRects = metrics.windowInsets.displayCutout?.boundingRects.orEmpty()
            val barRect = Rect(0, 0, screenW, visibleInsetTop)
            val fallbackFree = Rect(
                (screenW * 0.12f).toInt(),
                0,
                (screenW * 0.82f).toInt(),
                visibleInsetTop
            )
            val placement = StatusBarSpaceScanner.computeCoexistingPlacement(
                barBounds = barRect,
                freeSpace = fallbackFree,
                otherTopBarOverlays = emptyList(),
                position = currentSettings.topBarPosition,
                itemWidthPx = itemWidthPx,
                screenWidth = screenW,
                gapPx = gapPx,
                cutoutRects = cutoutRects,
                density = density
            )
            if (!placement.hasRoom) {
                container.visibility = View.INVISIBLE
                return
            }
            container.visibility = View.VISIBLE
            params.gravity = placement.gravity
            params.x = placement.x
            params.y = placement.y
            params.width = placement.width
            params.height = placement.height
            try {
                entry.windowManager.updateViewLayout(container, params)
            } catch (_: Throwable) {
                // Ignored
            }
        }
    }

    private fun removeDisplayEntry(displayId: Int) {
        val entry = displayWindows.remove(displayId) ?: return
        if (entry.isWindowAttached) {
            try {
                entry.windowManager.removeViewImmediate(entry.stripContainer)
            } catch (_: Throwable) {
                // Ignored
            }
            entry.isWindowAttached = false
        }
    }

    private fun detachAllWindows() {
        for (displayId in displayWindows.keys.toList()) {
            removeDisplayEntry(displayId)
        }
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
