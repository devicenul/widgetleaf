package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.devnullapps.widgetleaf.domain.model.TopBarPosition

/**
 * Scans SystemUI's desktop status bar window (`StatusBar`) per display for its empty
 * `DesktopStatusBarSpacer` / widest unoccupied region and accounts for display cutouts and
 * other top-bar overlay windows (such as BentoBar `com.meridian11.books.bentobar`) so WidgetLeaf
 * and BentoBar coexist side-by-side without overlapping system UI or camera cutouts.
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
        val shouldHideForFullscreenOrSystemPanel: Boolean,
        val displayId: Int = Display.DEFAULT_DISPLAY,
        val cutoutRects: List<Rect> = emptyList()
    )

    data class WindowPlacement(
        val gravity: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val hasRoom: Boolean = true
    )

    /**
     * Subtracts display cutout bounding rects intersecting [region] vertically/horizontally,
     * returning sorted disjoint horizontal free segments within [region].
     */
    fun subtractCutouts(region: Rect, cutouts: List<Rect>): List<Rect> {
        if (region.width() <= 0 || region.height() <= 0) return emptyList()
        var segments = listOf(Rect(region))
        for (cutout in cutouts) {
            if (cutout.width() <= 0 || cutout.height() <= 0) continue
            if (cutout.bottom <= region.top || cutout.top >= region.bottom) continue
            if (cutout.right <= region.left || cutout.left >= region.right) continue
            val next = mutableListOf<Rect>()
            for (seg in segments) {
                if (cutout.right <= seg.left || cutout.left >= seg.right) {
                    next.add(seg)
                } else {
                    if (cutout.left > seg.left) {
                        next.add(Rect(seg.left, seg.top, cutout.left, seg.bottom))
                    }
                    if (cutout.right < seg.right) {
                        next.add(Rect(cutout.right, seg.top, seg.right, seg.bottom))
                    }
                }
            }
            segments = next
            if (segments.isEmpty()) break
        }
        return segments.sortedBy { it.left }
    }

    /**
     * Pure placement solver that subtracts [cutoutRects] and any other top-bar accessibility
     * overlays (e.g. BentoBar) from [freeSpace] and calculates non-overlapping coordinates for
     * [position]. Sets [WindowPlacement.hasRoom] to `false` if no valid segment can fit
     * [itemWidthPx].
     */
    fun computeCoexistingPlacement(
        barBounds: Rect,
        freeSpace: Rect,
        otherTopBarOverlays: List<Rect>,
        position: TopBarPosition,
        itemWidthPx: Int,
        screenWidth: Int,
        gapPx: Int,
        cutoutRects: List<Rect> = emptyList(),
        density: Float = 1f
    ): WindowPlacement {
        val safeDensity = if (density > 0f) density else 1f
        val minBarHeightPx = (28 * safeDensity).toInt().coerceAtLeast(1)
        val barHeight = barBounds.height().coerceAtLeast(minBarHeightPx)
        val defaultGravity = when (position) {
            TopBarPosition.LEFT -> Gravity.TOP or Gravity.LEFT
            TopBarPosition.RIGHT -> Gravity.TOP or Gravity.RIGHT
        }

        val segments = subtractCutouts(freeSpace, cutoutRects)
        if (segments.isEmpty()) {
            return WindowPlacement(
                gravity = defaultGravity,
                x = 0,
                y = barBounds.top,
                width = 0,
                height = barHeight,
                hasRoom = false
            )
        }

        val orderedSegments = when (position) {
            TopBarPosition.LEFT -> segments
            TopBarPosition.RIGHT -> segments.asReversed()
        }

        fun resolveSegmentBounds(seg: Rect): Pair<Int, Int> {
            var effectiveLeft = seg.left
            var effectiveRight = seg.right
            val midX = (seg.left + seg.right) / 2
            for (other in otherTopBarOverlays) {
                if (other.right <= effectiveLeft || other.left >= effectiveRight) continue
                val otherCenter = (other.left + other.right) / 2
                if (otherCenter <= midX) {
                    effectiveLeft = maxOf(effectiveLeft, other.right + gapPx)
                } else {
                    effectiveRight = minOf(effectiveRight, other.left - gapPx)
                }
            }
            return Pair(effectiveLeft, effectiveRight)
        }

        var chosenRange: Pair<Int, Int>? = null
        // Prefer segments with room for item + gap
        for (seg in orderedSegments) {
            val (l, r) = resolveSegmentBounds(seg)
            if (r - l >= itemWidthPx + gapPx) {
                chosenRange = Pair(l, r)
                break
            }
        }
        // Fallback to exact itemWidthPx fit if gap cannot fit
        if (chosenRange == null) {
            for (seg in orderedSegments) {
                val (l, r) = resolveSegmentBounds(seg)
                if (r - l >= itemWidthPx) {
                    chosenRange = Pair(l, r)
                    break
                }
            }
        }

        if (chosenRange == null) {
            return WindowPlacement(
                gravity = defaultGravity,
                x = freeSpace.left + gapPx,
                y = barBounds.top,
                width = itemWidthPx,
                height = barHeight,
                hasRoom = false
            )
        }

        val (effectiveLeft, effectiveRight) = chosenRange
        return when (position) {
            TopBarPosition.LEFT -> {
                val maxX = (effectiveRight - itemWidthPx).coerceAtLeast(effectiveLeft)
                WindowPlacement(
                    gravity = Gravity.TOP or Gravity.LEFT,
                    x = (effectiveLeft + gapPx).coerceAtMost(maxX),
                    y = barBounds.top,
                    width = itemWidthPx,
                    height = barHeight,
                    hasRoom = true
                )
            }
            TopBarPosition.RIGHT -> {
                val minOffsetFromRight = (screenWidth - effectiveRight).coerceAtLeast(0)
                val maxOffsetFromRight = (screenWidth - effectiveLeft - itemWidthPx)
                    .coerceAtLeast(minOffsetFromRight)
                val preferredOffset = (screenWidth - effectiveRight + gapPx)
                    .coerceAtLeast(gapPx)
                    .coerceIn(minOffsetFromRight, maxOffsetFromRight)
                WindowPlacement(
                    gravity = Gravity.TOP or Gravity.RIGHT,
                    x = preferredOffset,
                    y = barBounds.top,
                    width = itemWidthPx,
                    height = barHeight,
                    hasRoom = true
                )
            }
        }
    }

    internal fun getWindowsForDisplay(
        service: AccessibilityService,
        displayId: Int
    ): List<AccessibilityWindowInfo> {
        val allDisplays = try {
            service.windowsOnAllDisplays
        } catch (_: Throwable) {
            null
        }
        if (allDisplays != null && allDisplays.size() > 0) {
            val idx = allDisplays.indexOfKey(displayId)
            if (idx >= 0) {
                val displayWindows = allDisplays.valueAt(idx).orEmpty()
                return displayWindows.filter {
                    it.displayId == displayId || it.displayId == Display.INVALID_DISPLAY
                }
            }
            // If windowsOnAllDisplays is populated for other displays but not this displayId,
            // don't accidentally fall back to DEFAULT_DISPLAY's windows for a secondary monitor.
            if (displayId != Display.DEFAULT_DISPLAY) {
                return emptyList()
            }
        }

        return if (displayId == Display.DEFAULT_DISPLAY) {
            try {
                service.windows.orEmpty().filter {
                    it.displayId == displayId || it.displayId == Display.INVALID_DISPLAY
                }
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun scan(
        service: AccessibilityService,
        windowManager: WindowManager,
        ownWindowId: Int = -1,
        displayId: Int = Display.DEFAULT_DISPLAY,
        density: Float = service.resources.displayMetrics.density,
        windowsOverride: List<AccessibilityWindowInfo>? = null
    ): TopBarScanResult? {
        val safeDensity = if (density > 0f) density else 1f
        val metrics = windowManager.currentWindowMetrics
        val screenW = metrics.bounds.width()
        val screenH = metrics.bounds.height()
        val insetTop = maxOf(
            metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top,
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        )
        val maxH = (if (insetTop > 0) insetTop else (48 * safeDensity).toInt()) + (8 * safeDensity).toInt()
        val maxTopPx = maxOf(insetTop, (16 * safeDensity).toInt())
        val cutoutRects = metrics.windowInsets.displayCutout?.boundingRects.orEmpty()

        val windows = windowsOverride ?: getWindowsForDisplay(service, displayId)
        if (windows.isEmpty()) return null

        val r = Rect()
        val statusBarWindow = windows.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                w.getBoundsInScreen(r).let {
                    r.top in 0..maxTopPx &&
                        (screenH <= 0 || r.bottom <= screenH / 3) &&
                        r.height() in 1..maxH &&
                        r.width() >= screenW / 2
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
                ) ?: statusBarWindow.root
            } else {
                statusBarWindow.root
            }
        } catch (_: Throwable) {
            statusBarWindow.root
        }

        val rawFree = if (root != null) {
            scanStatusBarNodes(root, barBounds, safeDensity, cutoutRects)
        } else {
            Rect(
                barBounds.left + (barBounds.width() * 0.12f).toInt(),
                barBounds.top,
                barBounds.left + (barBounds.width() * 0.82f).toInt(),
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

        val minOverlaySizePx = (2 * safeDensity).toInt().coerceAtLeast(1)
        val extraBarTolerancePx = (16 * safeDensity).toInt()
        val otherOverlays = mutableListOf<Rect>()
        for (w in windows) {
            if (w.id == ownWindowId || w.id == statusBarWindow.id) continue
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                val titleStr = w.title?.toString()
                if (titleStr != null && titleStr.startsWith(WIDGET_LEAF_WINDOW_TITLE)) continue
                val bounds = Rect()
                w.getBoundsInScreen(bounds)
                if (bounds.top <= barBounds.bottom + extraBarTolerancePx &&
                    bounds.bottom >= barBounds.top &&
                    bounds.height() in minOverlaySizePx..(barBounds.height() + extraBarTolerancePx) &&
                    bounds.width() in minOverlaySizePx..(screenW * 3 / 4)
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
            shouldHideForFullscreenOrSystemPanel = coveredBySystemPanel,
            displayId = displayId,
            cutoutRects = cutoutRects
        )
    }

    private fun scanStatusBarNodes(
        root: AccessibilityNodeInfo,
        bar: Rect,
        density: Float,
        cutoutRects: List<Rect>
    ): Rect {
        val minSpacerWidthPx = (24 * density).toInt().coerceAtLeast(1)
        var spacer: Rect? = null
        val parts = ArrayList<Rect>()
        for (cutout in cutoutRects) {
            if (cutout.bottom > bar.top && cutout.top < bar.bottom && cutout.width() > 0) {
                parts.add(Rect(cutout.left, bar.top, cutout.right, bar.bottom))
            }
        }
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < 400) {
            val (n, depth) = stack.removeLast()
            visited++
            val r = Rect().also { n.getBoundsInScreen(it) }
            val id = n.viewIdResourceName.orEmpty()
            if (id.endsWith("DesktopStatusBarSpacer") && r.width() > minSpacerWidthPx) {
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

        return spacer?.takeIf { it.width() > minSpacerWidthPx }
            ?: widestGap(bar, parts).takeIf { it.width() > minSpacerWidthPx }
            ?: Rect(
                bar.left + (bar.width() * 0.12f).toInt(),
                bar.top,
                bar.left + (bar.width() * 0.82f).toInt(),
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
