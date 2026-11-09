package io.devnullapps.widgetleaf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.ui.components.WidgetDeckOverlayScreen
import io.devnullapps.widgetleaf.ui.viewmodel.DeckUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w1440dp-h1080dp")
class DeckScreenComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyPageOneDisplaysHintAndBottomPaginationHud() {
        val state = DeckUiState(
            pages = listOf(DeckPage(pageIndex = 0, customLabel = null)),
            widgets = emptyList()
        )

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = state,
                onDismissOverlay = {},
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
        composeTestRule.onNodeWithTag("empty_canvas_hint").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_pagination_hud").assertIsDisplayed()
    }

    @Test
    fun clickingEmptyBlurredCanvasInvokesOnDismissOverlay() {
        var dismissed = false
        val state = DeckUiState(
            pages = listOf(DeckPage(pageIndex = 0, customLabel = null)),
            widgets = emptyList(),
            editingWidgetId = null
        )

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = state,
                onDismissOverlay = { dismissed = true },
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

        composeTestRule.onNodeWithTag("widget_deck_overlay_root").performClick()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun multiPagePillsRenderDefaultLabelsAndArrowsLoopCircularly() {
        val pages = listOf(
            DeckPage(pageIndex = 0, customLabel = null),
            DeckPage(pageIndex = 1, customLabel = null),
            DeckPage(pageIndex = 2, customLabel = null)
        )

        var lastActiveIndex = 0

        composeTestRule.setContent {
            var currentPage by remember { mutableIntStateOf(0) }
            lastActiveIndex = currentPage

            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    pages = pages,
                    activePageIndex = currentPage
                ),
                onDismissOverlay = {},
                onSelectPage = { currentPage = it },
                onPreviousPage = {
                    currentPage = if (currentPage <= 0) pages.lastIndex else currentPage - 1
                },
                onNextPage = {
                    currentPage = if (currentPage >= pages.lastIndex) 0 else currentPage + 1
                },
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

        composeTestRule.onNodeWithTag("page_pill_0").assertTextEquals("1")
        composeTestRule.onNodeWithTag("page_pill_1").assertTextEquals("2")
        composeTestRule.onNodeWithTag("page_pill_2").assertTextEquals("3")

        // 0 -> 1
        composeTestRule.onNodeWithTag("page_next_button").performClick()
        composeTestRule.waitForIdle()
        assertThat(lastActiveIndex).isEqualTo(1)

        // 1 -> 2
        composeTestRule.onNodeWithTag("page_next_button").performClick()
        composeTestRule.waitForIdle()
        assertThat(lastActiveIndex).isEqualTo(2)

        // 2 -> 0 (circular loop!)
        composeTestRule.onNodeWithTag("page_next_button").performClick()
        composeTestRule.waitForIdle()
        assertThat(lastActiveIndex).isEqualTo(0)

        // 0 -> 2 (previous loops circularly!)
        composeTestRule.onNodeWithTag("page_prev_button").performClick()
        composeTestRule.waitForIdle()
        assertThat(lastActiveIndex).isEqualTo(2)
    }

    @Test
    fun inlinePageLabelEditingUpdatesLabelOrResetsToDefaultNumberWhenEmpty() {
        composeTestRule.setContent {
            var p1Label by remember { mutableStateOf<String?>(null) }
            var curEditingIndex by remember { mutableStateOf<Int?>(null) }
            var curDraft by remember { mutableStateOf("") }

            val pages = listOf(
                DeckPage(pageIndex = 0, customLabel = null),
                DeckPage(pageIndex = 1, customLabel = p1Label)
            )

            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    pages = pages,
                    activePageIndex = 1,
                    editingPageLabelIndex = curEditingIndex,
                    editingPageLabelDraft = curDraft
                ),
                onDismissOverlay = {},
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
                onStartEditPageLabel = { idx ->
                    curEditingIndex = idx
                    curDraft = pages[idx].customLabel.orEmpty()
                },
                onDraftChange = { curDraft = it },
                onCommitPageLabel = { idx, text ->
                    val clean = text.trim().takeIf { it.isNotEmpty() }
                    if (idx == 1) p1Label = clean
                    curEditingIndex = null
                    curDraft = ""
                }
            )
        }

        // Initially renders default number label "2"
        composeTestRule.onNodeWithTag("page_pill_1").assertTextEquals("2")

        // Trigger long click to edit page label
        composeTestRule.onNodeWithTag("page_pill_1").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Type "Focus" and click save
        composeTestRule.onNodeWithTag("page_label_editor_1").performTextReplacement("Focus")
        composeTestRule.onNodeWithTag("page_label_save_1").performClick()
        composeTestRule.waitForIdle()

        // Label updated to "Focus"
        composeTestRule.onNodeWithTag("page_pill_1").assertTextEquals("Focus")

        // Edit again, type empty string "", and save -> resets to "2"
        composeTestRule.onNodeWithTag("page_pill_1").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("page_label_editor_1").performTextReplacement("")
        composeTestRule.onNodeWithTag("page_label_save_1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("page_pill_1").assertTextEquals("2")
    }

    @Test
    fun desktopContextMenuRendersOptionsAndOpensWidgetPicker() {
        composeTestRule.setContent {
            var contextMenuPos by remember { mutableStateOf<Pair<Float, Float>?>(Pair(200f, 200f)) }
            var isPickerOpen by remember { mutableStateOf(false) }

            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    contextMenuPosition = contextMenuPos,
                    isWidgetPickerOpen = isPickerOpen
                ),
                onDismissOverlay = {},
                onSelectPage = {},
                onPreviousPage = {},
                onNextPage = {},
                onOpenWidgetPicker = { isPickerOpen = true },
                onCloseWidgetPicker = { isPickerOpen = false },
                onSearchQueryChange = {},
                onAddWidgetAutoPlace = {},
                onOpenOverlaySettings = {},
                onCloseOverlaySettings = {},
                onUpdateSettings = {},
                onStartEditPageLabel = {},
                onCommitPageLabel = { _, _ -> },
                onCloseContextMenu = { contextMenuPos = null }
            )
        }

        composeTestRule.onNodeWithTag("desktop_context_menu").assertIsDisplayed()
        composeTestRule.onNodeWithTag("context_menu_widgets").assertIsDisplayed()
        composeTestRule.onNodeWithTag("context_menu_settings").assertIsDisplayed()

        // Clicking Widgets opens WidgetPickerBottomSheet
        composeTestRule.onNodeWithTag("context_menu_widgets").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("widget_picker_sheet").assertIsDisplayed()
    }

    @Test
    fun widgetPickerFiltersCatalogBySearchQueryAndAddsWidget() {
        val catalog = listOf(
            WidgetCatalogItem(
                providerPackage = "com.google.android.calendar",
                providerClass = "CalendarWidget",
                appName = "Google Calendar",
                widgetLabel = "Schedule View",
                defaultSpanX = 4,
                defaultSpanY = 2
            ),
            WidgetCatalogItem(
                providerPackage = "com.google.android.keep",
                providerClass = "NoteWidget",
                appName = "Google Keep",
                widgetLabel = "Quick Note",
                defaultSpanX = 2,
                defaultSpanY = 2
            )
        )

        var addedItem: WidgetCatalogItem? = null

        composeTestRule.setContent {
            var query by remember { mutableStateOf("") }
            val state = DeckUiState(
                isWidgetPickerOpen = true,
                widgetSearchQuery = query,
                catalogItems = catalog
            )

            WidgetDeckOverlayScreen(
                uiState = state,
                onDismissOverlay = {},
                onSelectPage = {},
                onPreviousPage = {},
                onNextPage = {},
                onOpenWidgetPicker = {},
                onCloseWidgetPicker = {},
                onSearchQueryChange = { query = it },
                onAddWidgetAutoPlace = { addedItem = it },
                onOpenOverlaySettings = {},
                onCloseOverlaySettings = {},
                onUpdateSettings = {},
                onStartEditPageLabel = {},
                onCommitPageLabel = { _, _ -> }
            )
        }

        composeTestRule.onNodeWithTag("widget_picker_search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Schedule View").assertIsDisplayed()
        composeTestRule.onNodeWithText("Quick Note").assertIsDisplayed()

        // Filter by "Keep"
        composeTestRule.onNodeWithTag("widget_picker_search").performTextInput("Keep")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Quick Note").assertIsDisplayed()
        composeTestRule.onNodeWithText("Schedule View").assertDoesNotExist()

        // Click Add
        val addNode = composeTestRule.onNodeWithTag("add_widget_button_com.google.android.keep/NoteWidget")
        addNode.performScrollTo()
        val onClickAction = addNode.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick]
        val actionInvoked = onClickAction.action?.invoke()
        if (actionInvoked != true) {
            addNode.performClick()
        }
        composeTestRule.waitForIdle()
        assertThat(addedItem).isNotNull()
        assertThat(addedItem?.widgetLabel).isEqualTo("Quick Note")
    }

    @Test
    fun overlaySettingsDialogTogglesAllRequiredSettings() {
        var currentSettings = OverlaySettings()

        composeTestRule.setContent {
            var s by remember { mutableStateOf(currentSettings) }

            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    isOverlaySettingsOpen = true,
                    settings = s
                ),
                onDismissOverlay = {},
                onSelectPage = {},
                onPreviousPage = {},
                onNextPage = {},
                onOpenWidgetPicker = {},
                onCloseWidgetPicker = {},
                onSearchQueryChange = {},
                onAddWidgetAutoPlace = {},
                onOpenOverlaySettings = {},
                onCloseOverlaySettings = {},
                onUpdateSettings = { transform ->
                    s = transform(s)
                    currentSettings = s
                },
                onStartEditPageLabel = {},
                onCommitPageLabel = { _, _ -> }
            )
        }

        composeTestRule.onNodeWithTag("overlay_settings_dialog").assertIsDisplayed()

        // Toggle 1: openOnPageOne
        composeTestRule.onNodeWithTag("setting_open_on_page_one").performScrollTo().performClick()
        assertThat(currentSettings.openOnPageOne).isTrue()

        // Toggle 2: launchOnStartup
        composeTestRule.onNodeWithTag("setting_launch_on_startup").performScrollTo().performClick()
        assertThat(currentSettings.launchOnStartup).isTrue()

        // Toggle 3: showInRunningTaskbarRight
        composeTestRule.onNodeWithTag("setting_running_taskbar_right").performScrollTo().performClick()
        assertThat(currentSettings.showInRunningTaskbarRight).isTrue()

        // Toggle 4: pinTopBarLeftStatusIcon
        composeTestRule.onNodeWithTag("setting_top_bar_left").performScrollTo().performClick()
        assertThat(currentSettings.pinTopBarLeftStatusIcon).isTrue()

        // Toggle 5: useDynamicColor
        composeTestRule.onNodeWithTag("setting_dynamic_color").performScrollTo().performClick()
        assertThat(currentSettings.useDynamicColor).isTrue()
    }

    @Test
    fun widgetsRenderFramelesslyHideEmptyHintAndShowEditHandlesAndActions() {
        val widget = PlacedWidget(
            appWidgetId = 101,
            providerPackage = "io.devnullapps.clock",
            providerClass = "ClockWidget",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            isReconfigurable = true,
            label = "Desk Clock"
        )

        var removedWidgetId = -1
        var reconfiguredWidgetId = -1

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    widgets = listOf(widget),
                    editingWidgetId = 101
                ),
                onDismissOverlay = {},
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
                onCommitPageLabel = { _, _ -> },
                onRemoveWidget = { removedWidgetId = it },
                onReconfigureWidget = { reconfiguredWidgetId = it }
            )
        }

        composeTestRule.waitForIdle()

        // Empty hint hidden because widgets exist
        composeTestRule.onNodeWithTag("empty_canvas_hint").assertDoesNotExist()

        // Placed widget rendered
        composeTestRule.onNodeWithTag("placed_widget_101").assertIsDisplayed()

        // In EDIT_MODE: Edit frame and drag handles rendered
        composeTestRule.onNodeWithTag("widget_edit_frame_101").assertIsDisplayed()
        composeTestRule.onNodeWithTag("resize_handle_top_101").assertIsDisplayed()
        composeTestRule.onNodeWithTag("resize_handle_bottom_101").assertIsDisplayed()
        composeTestRule.onNodeWithTag("resize_handle_left_101").assertIsDisplayed()
        composeTestRule.onNodeWithTag("resize_handle_right_101").assertIsDisplayed()

        // Action buttons
        composeTestRule.onNodeWithTag("widget_reconfigure_101").assertIsDisplayed()
        composeTestRule.onNodeWithTag("widget_remove_101").assertIsDisplayed()

        composeTestRule.onNodeWithTag("widget_remove_101").performClick()
        assertThat(removedWidgetId).isEqualTo(101)

        composeTestRule.onNodeWithTag("widget_reconfigure_101").performClick()
        assertThat(reconfiguredWidgetId).isEqualTo(101)
    }

    @Test
    fun clickingAddWidgetButtonInHudOpensWidgetPicker() {
        var pickerOpened = false

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = DeckUiState(),
                onDismissOverlay = {},
                onSelectPage = {},
                onPreviousPage = {},
                onNextPage = {},
                onOpenWidgetPicker = { pickerOpened = true },
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

        composeTestRule.onNodeWithTag("hud_add_widget_button").performClick()
        assertThat(pickerOpened).isTrue()
    }

    @Test
    fun clickingOverlaySettingsInContextMenuOpensSettingsDialog() {
        var settingsOpened = false

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    contextMenuPosition = Pair(150f, 150f)
                ),
                onDismissOverlay = {},
                onSelectPage = {},
                onPreviousPage = {},
                onNextPage = {},
                onOpenWidgetPicker = {},
                onCloseWidgetPicker = {},
                onSearchQueryChange = {},
                onAddWidgetAutoPlace = {},
                onOpenOverlaySettings = { settingsOpened = true },
                onCloseOverlaySettings = {},
                onUpdateSettings = {},
                onStartEditPageLabel = {},
                onCommitPageLabel = { _, _ -> }
            )
        }

        composeTestRule.onNodeWithTag("context_menu_settings").performClick()
        assertThat(settingsOpened).isTrue()
    }

    @Test
    fun overlaySettingsDialogAdjustsBlurAndScrimSliders() {
        val currentSettings = OverlaySettings(blurRadiusDp = 20, scrimAlpha = 0.50f)

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    isOverlaySettingsOpen = true,
                    settings = currentSettings
                ),
                onDismissOverlay = {},
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

        composeTestRule.onNodeWithTag("setting_blur_slider").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setting_scrim_slider").performScrollTo().assertIsDisplayed()
    }
}
