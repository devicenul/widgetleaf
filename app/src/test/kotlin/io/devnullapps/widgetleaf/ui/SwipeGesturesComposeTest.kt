package io.devnullapps.widgetleaf.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.ui.components.WidgetDeckOverlayScreen
import io.devnullapps.widgetleaf.ui.viewmodel.DeckUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w1440dp-h1080dp")
class SwipeGesturesComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun touchSwipeHorizontal_navigatesBothWaysWithinBounds() {
        var activePage by mutableIntStateOf(0)
        val pages = listOf(DeckPage(0), DeckPage(1), DeckPage(2))

        composeTestRule.setContent {
            val state = DeckUiState(
                isReady = true,
                pages = pages,
                activePageIndex = activePage,
                widgets = emptyList()
            )

            WidgetDeckOverlayScreen(
                uiState = state,
                onDismissOverlay = {},
                onSelectPage = { activePage = it },
                onPreviousPage = { if (activePage > 0) activePage-- },
                onNextPage = { if (activePage < pages.lastIndex) activePage++ },
                onSwipePrevious = { if (activePage > 0) activePage-- },
                onSwipeNext = { if (activePage < pages.lastIndex) activePage++ },
                onOpenWidgetPicker = {},
                onCloseWidgetPicker = {},
                onSearchQueryChange = {},
                onAddWidgetAutoPlace = {},
                onOpenOverlaySettings = {},
                onCloseOverlaySettings = {},
                onUpdateSettings = {},
                onStartEditPageLabel = {},
                onCommitPageLabel = { _, _ -> }
            )
        }

        composeTestRule.waitForIdle()
        assertThat(activePage).isEqualTo(0)

        // 1. Swipe right while on first page (page 0): bounds check, must stay on Page 0 (no wrap-around)
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeRight()
        }
        composeTestRule.waitForIdle()
        assertThat(activePage).isEqualTo(0)

        // 2. Swipe left: advances from Page 0 to Page 1
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()
        assertThat(activePage).isEqualTo(1)

        // 3. Swipe left again: advances from Page 1 to Page 2 (last page)
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()
        assertThat(activePage).isEqualTo(2)

        // 4. Swipe left while on last page: bounds check, must stay on Page 2 (no wrap-around or page creation)
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()
        assertThat(activePage).isEqualTo(2)

        // 5. Swipe right: steps back from Page 2 to Page 1
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeRight()
        }
        composeTestRule.waitForIdle()
        assertThat(activePage).isEqualTo(1)
    }

    @Test
    fun touchSwipeVertical_onBackground_dismissesPane() {
        var dismissedDown = false
        var dismissedUp = false

        composeTestRule.setContent {
            var isDownDismiss by remember { mutableStateOf(false) }
            val state = DeckUiState(
                isReady = true,
                pages = listOf(DeckPage(0)),
                activePageIndex = 0,
                widgets = emptyList()
            )

            WidgetDeckOverlayScreen(
                uiState = state,
                onDismissOverlay = {
                    if (!isDownDismiss) {
                        dismissedDown = true
                        isDownDismiss = true
                    } else {
                        dismissedUp = true
                    }
                },
                onSelectPage = {},
                onPreviousPage = {},
                onNextPage = {},
                onOpenWidgetPicker = {},
                onCloseWidgetPicker = {},
                onSearchQueryChange = {},
                onAddWidgetAutoPlace = {},
                onOpenOverlaySettings = {},
                onCloseOverlaySettings = {},
                onUpdateSettings = {},
                onStartEditPageLabel = {},
                onCommitPageLabel = { _, _ -> }
            )
        }

        composeTestRule.waitForIdle()

        // Vertical swipe down dismisses
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeDown()
        }
        composeTestRule.waitForIdle()
        assertThat(dismissedDown).isTrue()

        // Vertical swipe up dismisses
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeUp()
        }
        composeTestRule.waitForIdle()
        assertThat(dismissedUp).isTrue()
    }

    @Test
    fun touchSwipeVertical_overScrollableWidget_doesNotDismissPane() {
        var dismissed = false
        val placed = PlacedWidget(
            appWidgetId = 101,
            providerPackage = "io.devnullapps.test",
            providerClass = "io.devnullapps.test.TestWidget",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 4,
            spanY = 4
        )
        val state = DeckUiState(
            isReady = true,
            pages = listOf(DeckPage(0)),
            activePageIndex = 0,
            widgets = listOf(placed)
        )

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = state,
                onDismissOverlay = { dismissed = true },
                canWidgetScroll = { _, _, _, dirY ->
                    // Widget under touch reports that it CAN scroll vertically
                    dirY != 0
                },
                onSelectPage = {},
                onPreviousPage = {},
                onNextPage = {},
                onOpenWidgetPicker = {},
                onCloseWidgetPicker = {},
                onSearchQueryChange = {},
                onAddWidgetAutoPlace = {},
                onOpenOverlaySettings = {},
                onCloseOverlaySettings = {},
                onUpdateSettings = {},
                onStartEditPageLabel = {},
                onCommitPageLabel = { _, _ -> }
            )
        }

        composeTestRule.waitForIdle()

        // Swiping over vertically scrollable widget must NOT dismiss
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeDown()
        }
        composeTestRule.waitForIdle()
        assertThat(dismissed).isFalse()

        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeUp()
        }
        composeTestRule.waitForIdle()
        assertThat(dismissed).isFalse()
    }

    @Test
    fun touchSwipeGestures_disabledInEditMode() {
        var activePage by mutableIntStateOf(0)
        var dismissed = false

        val placed = PlacedWidget(
            appWidgetId = 202,
            providerPackage = "io.devnullapps.test",
            providerClass = "io.devnullapps.test.TestWidget",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 4,
            spanY = 4
        )
        val state = DeckUiState(
            isReady = true,
            pages = listOf(DeckPage(0), DeckPage(1)),
            activePageIndex = activePage,
            widgets = listOf(placed),
            editingWidgetId = 202 // EDIT MODE IS ACTIVE!
        )

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = state,
                onDismissOverlay = { dismissed = true },
                onSelectPage = { activePage = it },
                onPreviousPage = { activePage = (activePage - 1).coerceAtLeast(0) },
                onNextPage = { activePage = (activePage + 1).coerceAtMost(1) },
                onSwipePrevious = { activePage = (activePage - 1).coerceAtLeast(0) },
                onSwipeNext = { activePage = (activePage + 1).coerceAtMost(1) },
                onOpenWidgetPicker = {},
                onCloseWidgetPicker = {},
                onSearchQueryChange = {},
                onAddWidgetAutoPlace = {},
                onOpenOverlaySettings = {},
                onCloseOverlaySettings = {},
                onUpdateSettings = {},
                onStartEditPageLabel = {},
                onCommitPageLabel = { _, _ -> }
            )
        }

        composeTestRule.waitForIdle()

        // Horizontal swipe left in edit mode must NOT change page
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()
        assertThat(activePage).isEqualTo(0)

        // Vertical swipe in edit mode must NOT dismiss
        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performTouchInput {
            swipeDown()
        }
        composeTestRule.waitForIdle()
        assertThat(dismissed).isFalse()
    }
}
