package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.devnullapps.widgetleaf.domain.model.TopBarPosition

/**
 * Scans SystemUI's desktop status bar window (`StatusBar`) for its empty `DesktopStatusBarSpacer`
 * region (matching BentoBar's `StatusBarScan`) and accounts for other top-bar overlay windows
 * (such as BentoBar `com.meridian11.books.bentobar`) so WidgetLeaf and BentoBar coexist side-by-side.
 */
object StatusBarSpaceScanner {

    const val WIDGET_LEAF_WINDOW_TITLE = "WidgetLeafTopBar"

    data class TopBarScanResult(
        val windowId: Int,
        val barBounds: Rect,
        val freeSpace: Rect,
        val otherTopBarOverlays: List<Rect>,
        val screenWidth: Int,
        val screenHeight: Int,
        val shouldHideForFullscreenOrSystemPanel: Boolean
    )

    data class WindowPlacement(
        val gravity: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    /**
     * Pure placement solver that subtracts any other top-bar accessibility overlays (e.g. BentoBar)
     * from [freeSpace] and calculates the non-overlapping window coordinates for [position].
     */
    fun computeCoexistingPlacement(
        barBounds: Rect,
        freeSpace: Rect,
        otherTopBarOverlays: List<Rect>,
        position: TopBarPosition,
        itemWidthPx: Int,
        screenWidth: Int,
        gapPx: Int
    ): WindowPlacement {
        var effectiveLeft = freeSpace.left
        var effectiveRight = freeSpace.right
        val midX = (freeSpace.left + freeSpace.right) / 2

        for (other in otherTopBarOverlays) {
            if (other.right <= effectiveLeft || other.left >= effectiveRight) continue
            val otherCenter = (other.left + other.right) / 2
            if (otherCenter <= midX) {
                effectiveLeft = maxOf(effectiveLeft, other.right + gapPx)
            } else {
                effectiveRight = minOf(effectiveRight, other.left - gapPx)
            }
        }

        if (effectiveRight - effectiveLeft < itemWidthPx) {
            effectiveLeft = freeSpace.left
            effectiveRight = freeSpace.right
        }

        val barHeight = barBounds.height().coerceAtLeast(28)

        return when (position) {
            TopBarPosition.LEFT -> WindowPlacement(
                gravity = Gravity.TOP or Gravity.LEFT,
                x = effectiveLeft + gapPx,
                y = barBounds.top,
                width = itemWidthPx,
                height = barHeight
            )
            TopBarPosition.RIGHT -> WindowPlacement(
                gravity = Gravity.TOP or Gravity.RIGHT,
                x = (screenWidth - effectiveRight + gapPx).coerceAtLeast(gapPx),
                y = barBounds.top,
                width = itemWidthPx,
                height = barHeight
            )
        }
    }

    fun scan(
        service: AccessibilityService,
        windowManager: WindowManager,
        ownWindowId: Int = -1
    ): TopBarScanResult? {
        val density = service.resources.displayMetrics.density
        val metrics = windowManager.currentWindowMetrics
        val screenW = metrics.bounds.width()
        val screenH = metrics.bounds.height()
        val insetTop = metrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            .top
        val maxH = (if (insetTop > 0) insetTop else (48 * density).toInt()) + 8

        val windows = try {
            service.windows
        } catch (_: Throwable) {
            return null
        }

        val r = Rect()
        val statusBarWindow = windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                w.getBoundsInScreen(r).let {
                    r.top == 0 && r.height() in 1..maxH && r.width() >= screenW / 2
                }
        } ?: return null

        val barBounds = Rect().also { statusBarWindow.getBoundsInScreen(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { service.clearCache() }
        }

        val root = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                statusBarWindow.getRoot(
                    AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                        AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
                )
            } else {
                statusBarWindow.root
            }
        } catch (_: Throwable) {
            statusBarWindow.root
        }

        val rawFree = if (root != null) {
            scanStatusBarNodes(root, barBounds)
        } else {
            Rect(
                (barBounds.width() * 0.12f).toInt(),
                barBounds.top,
                (barBounds.width() * 0.82f).toInt(),
                barBounds.bottom
            )
        }

        // Only hide when a large system panel (Notification Shade / Quick Settings) covers the status bar,
        // ignoring Screenshot preview and regular desktop application windows (which always sit at y=0).
        val coveredBySystemPanel = windows.any { w ->
            if (w.id == statusBarWindow.id || w.id == ownWindowId) return@any false
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            w.getBoundsInScreen(r)
            Rect.intersects(r, rawFree) &&
                r.height() >= barBounds.height() * 4 &&
                w.title?.contains("Screenshot", ignoreCase = true) != true
        }

        val otherOverlays = mutableListOf<Rect>()
        for (w in windows) {
            if (w.id == ownWindowId || w.id == statusBarWindow.id) continue
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                if (w.title?.toString() == WIDGET_LEAF_WINDOW_TITLE) continue
                val bounds = Rect()
                w.getBoundsInScreen(bounds)
                if (bounds.top <= barBounds.bottom &&
                    bounds.height() in 2..(barBounds.height() + 16) &&
                    bounds.width() in 2..(screenW * 3 / 4)
                ) {
                    otherOverlays.add(bounds)
                }
            }
        }

        return TopBarScanResult(
            windowId = statusBarWindow.id,
            barBounds = barBounds,
            freeSpace = rawFree,
            otherTopBarOverlays = otherOverlays,
            screenWidth = screenW,
            screenHeight = screenH,
            shouldHideForFullscreenOrSystemPanel = coveredBySystemPanel
        )
    }

    private fun scanStatusBarNodes(root: AccessibilityNodeInfo, bar: Rect): Rect {
        var spacer: Rect? = null
        val parts = ArrayList<Rect>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < 400) {
            val (n, depth) = stack.removeLast()
            visited++
            val r = Rect().also { n.getBoundsInScreen(it) }
            val id = n.viewIdResourceName.orEmpty()
            if (id.endsWith("DesktopStatusBarSpacer") && r.width() > 24) {
                spacer = Rect(r.left, bar.top, r.right, bar.bottom)
            }
            val text = n.text?.toString().orEmpty()
            val labelled = n.isClickable || !n.contentDescription.isNullOrEmpty() || text.isNotEmpty()
            if (labelled && r.width() in 1 until (bar.width() * 6 / 10) && r.height() > 0) {
                parts.add(Rect(r))
            }
            for (i in n.childCount - 1 downTo 0) {
                n.getChild(i)?.let { stack.add(it to (depth + 1)) }
            }
        }

        return spacer?.takeIf { it.width() > 24 }
            ?: widestGap(bar, parts).takeIf { it.width() > 24 }
            ?: Rect(
                (bar.width() * 0.12f).toInt(),
                bar.top,
                (bar.width() * 0.82f).toInt(),
                bar.bottom
            )
    }

    private fun widestGap(bar: Rect, parts: List<Rect>): Rect {
        val spans = parts.map { it.left to it.right }.sortedBy { it.first }
        var best = Rect(bar.left, bar.top, bar.left, bar.bottom)
        var cursor = bar.left
        for ((l, r) in spans) {
            if (l > cursor && l - cursor > best.width()) {
                best = Rect(cursor, bar.top, l, bar.bottom)
            }
            cursor = maxOf(cursor, r)
        }
        if (bar.right - cursor > best.width()) {
            best = Rect(cursor, bar.top, bar.right, bar.bottom)
        }
        return best
    }
}
