package io.devnullapps.widgetleaf.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.TopBarPosition
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1440dp-h1080dp")
class StatusBarSpaceScannerTest {

    @Test
    fun computeCoexistingPlacement_placesIconAtLeftOrRightOfDesktopStatusBarSpacer() {
        val barBounds = Rect(0, 0, 1920, 41)
        val freeSpace = Rect(168, 0, 1681, 41)

        val leftPlacement = StatusBarSpaceScanner.computeCoexistingPlacement(
            barBounds = barBounds,
            freeSpace = freeSpace,
            otherTopBarOverlays = emptyList(),
            position = TopBarPosition.LEFT,
            itemWidthPx = 36,
            screenWidth = 1920,
            gapPx = 6
        )
        assertThat(leftPlacement.gravity).isEqualTo(Gravity.TOP or Gravity.LEFT)
        assertThat(leftPlacement.x).isEqualTo(174) // 168 + 6
        assertThat(leftPlacement.height).isEqualTo(41)

        val rightPlacement = StatusBarSpaceScanner.computeCoexistingPlacement(
            barBounds = barBounds,
            freeSpace = freeSpace,
            otherTopBarOverlays = emptyList(),
            position = TopBarPosition.RIGHT,
            itemWidthPx = 36,
            screenWidth = 1920,
            gapPx = 6
        )
        assertThat(rightPlacement.gravity).isEqualTo(Gravity.TOP or Gravity.RIGHT)
        assertThat(rightPlacement.x).isEqualTo(1920 - 1681 + 6)
        assertThat(rightPlacement.height).isEqualTo(41)
    }

    @Test
    fun computeCoexistingPlacement_offsetsBesideBentoBarOnSameSideWithoutCollision() {
        val barBounds = Rect(0, 0, 1920, 41)
        val freeSpace = Rect(168, 0, 1681, 41)

        // Case 1: BentoBar strip is docked on the Left of DesktopStatusBarSpacer [174..394]
        val bentoBarLeft = Rect(174, 0, 394, 41)
        val widgetLeafLeft = StatusBarSpaceScanner.computeCoexistingPlacement(
            barBounds = barBounds,
            freeSpace = freeSpace,
            otherTopBarOverlays = listOf(bentoBarLeft),
            position = TopBarPosition.LEFT,
            itemWidthPx = 36,
            screenWidth = 1920,
            gapPx = 6
        )
        assertThat(widgetLeafLeft.gravity).isEqualTo(Gravity.TOP or Gravity.LEFT)
        assertThat(widgetLeafLeft.x).isGreaterThan(bentoBarLeft.right)

        // Case 2: BentoBar strip is docked on the Right of DesktopStatusBarSpacer [1455..1675]
        val bentoBarRight = Rect(1455, 0, 1675, 41)
        val widgetLeafRight = StatusBarSpaceScanner.computeCoexistingPlacement(
            barBounds = barBounds,
            freeSpace = freeSpace,
            otherTopBarOverlays = listOf(bentoBarRight),
            position = TopBarPosition.RIGHT,
            itemWidthPx = 36,
            screenWidth = 1920,
            gapPx = 6
        )
        assertThat(widgetLeafRight.gravity).isEqualTo(Gravity.TOP or Gravity.RIGHT)
        val widgetLeafRightEdgeOnScreen = 1920 - widgetLeafRight.x
        assertThat(widgetLeafRightEdgeOnScreen).isLessThan(bentoBarRight.left)
    }

    @Test
    fun overlaySettings_hotkeyFormattingAndMatchingWorksCorrectly() {
        val defaultSettings = OverlaySettings()
        assertThat(defaultSettings.formatHotkeyLabel()).isEqualTo("Alt + W")

        val altWDown = KeyEvent(
            0L,
            0L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_W,
            0,
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        )
        assertThat(defaultSettings.matchesKeyEvent(altWDown)).isTrue()

        val plainWDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W)
        assertThat(defaultSettings.matchesKeyEvent(plainWDown)).isFalse()

        val customSettings = defaultSettings.copy(
            hotkeyKeyCode = KeyEvent.KEYCODE_SPACE,
            hotkeyCtrl = true,
            hotkeyAlt = false,
            hotkeyShift = true
        )
        assertThat(customSettings.formatHotkeyLabel()).isEqualTo("Ctrl + Shift + Space")
    }

    @Test
    fun computeCoexistingPlacement_splitsFreeSpaceAroundCenterCutoutAndSignalsNoRoomWhenBlocked() {
        val barBounds = Rect(0, 0, 1440, 48)
        val freeSpace = Rect(200, 0, 1200, 48)
        val centerCutout = Rect(640, 0, 800, 48)

        // With LEFT and center cutout, BentoBar occupies most of left segment -> moves to right segment cleanly
        val bentoLeft = Rect(206, 0, 620, 48)
        val placed = StatusBarSpaceScanner.computeCoexistingPlacement(
            barBounds = barBounds,
            freeSpace = freeSpace,
            otherTopBarOverlays = listOf(bentoLeft),
            position = TopBarPosition.LEFT,
            itemWidthPx = 36,
            screenWidth = 1440,
            gapPx = 6,
            cutoutRects = listOf(centerCutout),
            density = 1f
        )
        assertThat(placed.hasRoom).isTrue()
        assertThat(placed.x).isAtLeast(centerCutout.right)
        assertThat(placed.x + placed.width).isAtMost(freeSpace.right)

        // Full-width cutout leaves no room -> hasRoom = false
        val fullCutout = Rect(180, 0, 1220, 48)
        val blocked = StatusBarSpaceScanner.computeCoexistingPlacement(
            barBounds = barBounds,
            freeSpace = freeSpace,
            otherTopBarOverlays = emptyList(),
            position = TopBarPosition.LEFT,
            itemWidthPx = 36,
            screenWidth = 1440,
            gapPx = 6,
            cutoutRects = listOf(fullCutout),
            density = 1f
        )
        assertThat(blocked.hasRoom).isFalse()
    }

    @Test
    fun scan_filtersWindowsPerDisplayAcceptsNonZeroTopAndRejectsBottomTaskbar() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val shadowService = Shadows.shadowOf(service as AccessibilityService)

        try {
            // Display 0: bottom taskbar only -> scan returns null
            val bottomTaskbar = AccessibilityWindowInfo.obtain().also { win ->
                val s = Shadows.shadowOf(win)
                s.setId(101)
                s.setDisplayId(Display.DEFAULT_DISPLAY)
                s.setType(AccessibilityWindowInfo.TYPE_SYSTEM)
                s.setBoundsInScreen(Rect(0, 1000, 1440, 1080))
            }
            shadowService.setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(bottomTaskbar))

            assertThat(
                StatusBarSpaceScanner.scan(
                    service = service,
                    windowManager = wm,
                    displayId = Display.DEFAULT_DISPLAY,
                    density = 1f
                )
            ).isNull()

            // Secondary display (displayId = 2): status bar offset 8dp from top + BentoBar + WidgetLeaf window
            val secBarBounds = Rect(0, 8, 1440, 56)
            val secRoot = AccessibilityNodeInfo.obtain().apply {
                setBoundsInScreen(secBarBounds)
            }
            val spacerNode = AccessibilityNodeInfo.obtain().apply {
                setBoundsInScreen(Rect(240, 8, 1180, 56))
                viewIdResourceName = "com.android.systemui:id/DesktopStatusBarSpacer"
            }
            Shadows.shadowOf(secRoot).addChild(spacerNode)

            val secStatusBar = AccessibilityWindowInfo.obtain().also { win ->
                val s = Shadows.shadowOf(win)
                s.setId(201)
                s.setDisplayId(2)
                s.setType(AccessibilityWindowInfo.TYPE_SYSTEM)
                s.setBoundsInScreen(secBarBounds)
                s.setRoot(secRoot)
            }
            val bentoBar = AccessibilityWindowInfo.obtain().also { win ->
                val s = Shadows.shadowOf(win)
                s.setId(202)
                s.setDisplayId(2)
                s.setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
                s.setTitle("BentoBar")
                s.setBoundsInScreen(Rect(250, 8, 430, 56))
            }
            val ownWidgetLeaf = AccessibilityWindowInfo.obtain().also { win ->
                val s = Shadows.shadowOf(win)
                s.setId(203)
                s.setDisplayId(2)
                s.setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
                s.setTitle("${StatusBarSpaceScanner.WIDGET_LEAF_WINDOW_TITLE}:2")
                s.setBoundsInScreen(Rect(440, 8, 476, 56))
            }
            shadowService.setWindowsOnDisplay(2, listOf(secStatusBar, bentoBar, ownWidgetLeaf))

            val secResult = StatusBarSpaceScanner.scan(
                service = service,
                windowManager = wm,
                displayId = 2,
                density = 2f
            )
            assertThat(secResult).isNotNull()
            requireNotNull(secResult)
            assertThat(secResult.displayId).isEqualTo(2)
            assertThat(secResult.barBounds).isEqualTo(secBarBounds)
            assertThat(secResult.freeSpace).isEqualTo(Rect(240, 8, 1180, 56))
            assertThat(secResult.otherTopBarOverlays).containsExactly(Rect(250, 8, 430, 56))
            assertThat(secResult.shouldHideForFullscreenOrSystemPanel).isFalse()

            // Non-existent displayId=99 does not leak DEFAULT_DISPLAY windows
            assertThat(
                StatusBarSpaceScanner.scan(
                    service = service,
                    windowManager = wm,
                    displayId = 99,
                    density = 1f
                )
            ).isNull()
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun scan_computesWidestGapFromChildNodesWhenNoSpacerIdAndRespectsDpDensityThresholds() {
        val controller = Robolectric.buildService(WidgetLeafTopBarService::class.java)
        val service = controller.create().get()
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val shadowService = Shadows.shadowOf(service as AccessibilityService)

        try {
            // 3x density bar of 96px (32dp) height — well within 48dp+8dp=56dp threshold
            val barBounds = Rect(0, 0, 1440, 96)
            val root = AccessibilityNodeInfo.obtain().apply {
                setBoundsInScreen(barBounds)
            }
            val leftClock = AccessibilityNodeInfo.obtain().apply {
                setBoundsInScreen(Rect(16, 0, 180, 96))
                text = "10:15"
            }
            val rightCluster = AccessibilityNodeInfo.obtain().apply {
                setBoundsInScreen(Rect(1100, 0, 1420, 96))
                contentDescription = "System icons"
            }
            Shadows.shadowOf(root).addChild(leftClock)
            Shadows.shadowOf(root).addChild(rightCluster)

            val barWindow = AccessibilityWindowInfo.obtain().also { win ->
                val s = Shadows.shadowOf(win)
                s.setId(301)
                s.setDisplayId(Display.DEFAULT_DISPLAY)
                s.setType(AccessibilityWindowInfo.TYPE_SYSTEM)
                s.setBoundsInScreen(barBounds)
                s.setRoot(root)
            }
            shadowService.setWindowsOnDisplay(Display.DEFAULT_DISPLAY, listOf(barWindow))

            val result = StatusBarSpaceScanner.scan(
                service = service,
                windowManager = wm,
                displayId = Display.DEFAULT_DISPLAY,
                density = 3f
            )
            assertThat(result).isNotNull()
            assertThat(result?.freeSpace).isEqualTo(Rect(180, 0, 1100, 96))
        } finally {
            controller.destroy()
        }
    }
}
