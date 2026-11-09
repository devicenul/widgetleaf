package io.devnullapps.widgetleaf.services

import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.TopBarPosition
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
}
