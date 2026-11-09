package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.devnullapps.widgetleaf.domain.model.TopBarPosition

/**
 * Scans the Android desktop top status bar (`StatusBar`) for its empty `DesktopStatusBarSpacer`
 * region and accounts for other top-bar overlay windows (such as BentoBar `com.meridian11.books.bentobar`)
 * so WidgetLeaf and BentoBar coexist side-by-side without overlapping.
 */
object StatusBarSpaceScanner {

    data class TopBarScanResult(
        val barBounds: Rect,
        val freeSpace: Rect,
        val screenWidth: Int,
        val screenHeight: Int,
        val shouldHideForFullscreenOrSystemPanel: Boolean
    )

    data class WindowPlacement(
        val gravity: Int,
        val x: Int,
        val y: Int,
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

        return when (position) {
            TopBarPosition.LEFT -> WindowPlacement(
                gravity = Gravity.TOP or Gravity.LEFT,
                x = effectiveLeft + gapPx,
                y = barBounds.top,
                height = barBounds.height().coerceAtLeast(28)
            )
            TopBarPosition.RIGHT -> WindowPlacement(
                gravity = Gravity.TOP or Gravity.RIGHT,
                x = (screenWidth - effectiveRight + gapPx).coerceAtLeast(gapPx),
                y = barBounds.top,
                height = barBounds.height().coerceAtLeast(28)
            )
        }
    }

    fun scan(service: AccessibilityService, ownWindowId: Int = -1): TopBarScanResult? {
        val dm = service.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val windows = try {
            service.windows
        } catch (_: Throwable) {
            return null
        }

        val r = Rect()

        val statusBarWindow = windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                w.getBoundsInScreen(r).let {
                    r.top == 0 && r.height() in 16..120 && r.width() >= screenW / 2
                }
        } ?: return null

        val barBounds = Rect().also { statusBarWindow.getBoundsInScreen(it) }

        val shouldHide = windows.any { w ->
            if (w.id == statusBarWindow.id || w.id == ownWindowId) return@any false
            w.getBoundsInScreen(r)
            when (w.type) {
                AccessibilityWindowInfo.TYPE_SYSTEM ->
                    r.top <= barBounds.bottom && r.height() > barBounds.height() * 2
                AccessibilityWindowInfo.TYPE_APPLICATION ->
                    r.top == 0 && r.width() == screenW && r.height() == screenH
                else -> false
            }
        }

        val root = statusBarWindow.root
        val rawFree = if (root != null) {
            try {
                findSpacerNode(root, barBounds) ?: findWidestChildGap(root, barBounds)
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        } else {
            null
        } ?: Rect(
            (barBounds.width() * 0.12f).toInt(),
            barBounds.top,
            (barBounds.width() * 0.78f).toInt(),
            barBounds.bottom
        )

        val otherOverlays = mutableListOf<Rect>()
        for (w in windows) {
            if (w.id == ownWindowId || w.id == statusBarWindow.id) continue
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                val bounds = Rect()
                w.getBoundsInScreen(bounds)
                if (bounds.top <= barBounds.bottom &&
                    bounds.height() in 1..(barBounds.height() + 16) &&
                    bounds.width() < screenW / 2
                ) {
                    otherOverlays.add(bounds)
                }
            }
        }

        var adjustedLeft = rawFree.left
        var adjustedRight = rawFree.right
        val midX = (rawFree.left + rawFree.right) / 2
        for (other in otherOverlays) {
            if (other.right <= adjustedLeft || other.left >= adjustedRight) continue
            if (other.centerX() <= midX) {
                adjustedLeft = maxOf(adjustedLeft, other.right)
            } else {
                adjustedRight = minOf(adjustedRight, other.left)
            }
        }

        val finalFree = if (adjustedRight > adjustedLeft + 32) {
            Rect(adjustedLeft, barBounds.top, adjustedRight, barBounds.bottom)
        } else {
            rawFree
        }

        return TopBarScanResult(
            barBounds = barBounds,
            freeSpace = finalFree,
            screenWidth = screenW,
            screenHeight = screenH,
            shouldHideForFullscreenOrSystemPanel = shouldHide
        )
    }

    private fun findSpacerNode(node: AccessibilityNodeInfo, bar: Rect): Rect? {
        if (node.viewIdResourceName?.endsWith("DesktopStatusBarSpacer") == true) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 32) {
                return Rect(r.left, bar.top, r.right, bar.bottom)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSpacerNode(child, bar)
            @Suppress("DEPRECATION")
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findWidestChildGap(root: AccessibilityNodeInfo, bar: Rect): Rect? {
        val occupied = mutableListOf<IntRange>()
        val r = Rect()

        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return
            if (depth > 0 && r.width() < bar.width() * 3 / 4) {
                occupied += r.left..r.right
                return
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                collect(c, depth + 1)
                @Suppress("DEPRECATION")
                c.recycle()
            }
        }

        collect(root, 0)
        if (occupied.isEmpty()) return null

        val sorted = occupied.sortedBy { it.first }
        var bestStart = bar.left
        var bestEnd = bar.left
        var cursor = bar.left
        for (span in sorted) {
            if (span.first > cursor && span.first - cursor > bestEnd - bestStart) {
                bestStart = cursor
                bestEnd = span.first
            }
            if (span.last > cursor) cursor = span.last
        }
        if (bar.right - cursor > bestEnd - bestStart) {
            bestStart = cursor
            bestEnd = bar.right
        }
        return if (bestEnd - bestStart >= 32) {
            Rect(bestStart, bar.top, bestEnd, bar.bottom)
        } else {
            null
        }
    }
}
