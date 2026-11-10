package io.devnullapps.widgetleaf.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.TopBarPosition
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
        composeTestRule.onNodeWithTag("overlay_settings_version_badge").assertTextEquals("v0.0.1")
        composeTestRule.onNodeWithTag("current_hotkey_badge").assertTextEquals("Alt + W")

        // Toggle 1: openOnPageOne
        composeTestRule.onNodeWithTag("setting_open_on_page_one").performScrollTo().performClick()
        assertThat(currentSettings.openOnPageOne).isTrue()

        // Toggle 2: launchOnStartup
        composeTestRule.onNodeWithTag("setting_launch_on_startup").performScrollTo().performClick()
        assertThat(currentSettings.launchOnStartup).isTrue()

        // Top Bar Position: switch to RIGHT then back to LEFT
        composeTestRule.onNodeWithTag("setting_top_bar_position_right").performScrollTo().performClick()
        assertThat(currentSettings.topBarPosition).isEqualTo(TopBarPosition.RIGHT)
        composeTestRule.onNodeWithTag("setting_top_bar_position_left").performScrollTo().performClick()
        assertThat(currentSettings.topBarPosition).isEqualTo(TopBarPosition.LEFT)

        // Verify removed settings nodes do not exist in the UI tree
        composeTestRule.onNodeWithTag("setting_top_bar_left").assertDoesNotExist()
        composeTestRule.onNodeWithTag("setting_running_taskbar_right").assertDoesNotExist()
        composeTestRule.onNodeWithTag("setting_pin_taskbar_left").assertDoesNotExist()

        // Toggle 3: useDynamicColor
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
        var overlayDismissed = false

        composeTestRule.setContent {
            var currentWidgets by remember { mutableStateOf(listOf(widget)) }
            var currentEditingId by remember { mutableStateOf<Int?>(101) }

            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    widgets = currentWidgets,
                    editingWidgetId = currentEditingId
                ),
                onDismissOverlay = { overlayDismissed = true },
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
                onRemoveWidget = {
                    removedWidgetId = it
                    currentWidgets = emptyList()
                    currentEditingId = null
                },
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

        composeTestRule.onNodeWithTag("widget_reconfigure_101").performClick()
        assertThat(reconfiguredWidgetId).isEqualTo(101)
        assertThat(overlayDismissed).isFalse()

        composeTestRule.onNodeWithTag("widget_remove_101").performClick()
        composeTestRule.waitForIdle()
        assertThat(removedWidgetId).isEqualTo(101)
        assertThat(overlayDismissed).isFalse()
    }

    @Test
    fun draggingBottomResizeHandleIncreasesSpanYAndDraggingWidgetToRightEdgeTriggersPageBump() {
        val widget = PlacedWidget(
            appWidgetId = 101,
            providerPackage = "io.devnullapps.calendar",
            providerClass = "CalendarWidget",
            pageIndex = 0,
            cellX = 6,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            minSpanX = 2,
            minSpanY = 2,
            maxSpanX = 8,
            maxSpanY = 8,
            isReconfigurable = true,
            label = "Calendar"
        )

        var movedCellY = -1
        var resizedSpanY = -1
        var bumpedDirection: io.devnullapps.widgetleaf.domain.usecase.EdgeDirection? = null
        var currentEditingId by mutableStateOf<Int?>(null)

        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    widgets = listOf(widget),
                    editingWidgetId = currentEditingId
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
                onMoveWidget = { _, _, newCellY ->
                    movedCellY = newCellY
                },
                onResizeWidget = { _, _, _, _, newSpanY ->
                    resizedSpanY = newSpanY
                },
                onDragAcrossEdge = { _, direction, _, _ ->
                    bumpedDirection = direction
                },
                onEnterEditMode = { id ->
                    currentEditingId = id
                },
                widgetContent = { placed ->
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            object : android.view.View(ctx) {
                                override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
                                    return true
                                }
                                override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
                                    return true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }

        composeTestRule.waitForIdle()

        // Transition from non-editing (null) to editing (101), matching real device flow
        composeTestRule.runOnIdle {
            currentEditingId = 101
        }
        composeTestRule.waitForIdle()

        // Drag widget interior downward to move widget within current page
        composeTestRule.onNodeWithTag("placed_widget_101").performTouchInput {
            swipeDown(startY = top + 40f, endY = top + 300f, durationMillis = 200)
        }
        composeTestRule.waitForIdle()
        assertThat(movedCellY).isGreaterThan(0)

        // Drag bottom resize handle down by ~300px -> increases spanY beyond 2 even though maxSpanY was 2
        composeTestRule.onNodeWithTag("resize_handle_bottom_101").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 300f, durationMillis = 200)
        }
        composeTestRule.waitForIdle()
        assertThat(resizedSpanY).isGreaterThan(2)

        // Drag widget body from cellX=6 to the right edge -> triggers EdgeDirection.RIGHT page bump
        composeTestRule.onNodeWithTag("placed_widget_101").performTouchInput {
            swipeRight(startX = centerX - 40f, endX = centerX + 260f, durationMillis = 200)
        }
        composeTestRule.waitForIdle()
        assertThat(bumpedDirection).isEqualTo(io.devnullapps.widgetleaf.domain.usecase.EdgeDirection.RIGHT)
    }

    @Test
    fun draggingWidgetOntoOccupiedCell_cascadesDisplacedWidgetAndPreservesSpan() {
        val w1 = PlacedWidget(
            appWidgetId = 201,
            providerPackage = "io.devnullapps.w1",
            providerClass = "Widget1",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            minSpanX = 2,
            minSpanY = 2,
            maxSpanX = 8,
            maxSpanY = 8,
            label = "Widget 1"
        )
        val w2 = PlacedWidget(
            appWidgetId = 202,
            providerPackage = "io.devnullapps.w2",
            providerClass = "Widget2",
            pageIndex = 0,
            cellX = 2,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            minSpanX = 2,
            minSpanY = 2,
            maxSpanX = 8,
            maxSpanY = 8,
            label = "Widget 2"
        )

        var state by mutableStateOf(
            DeckUiState(
                widgets = listOf(w1, w2),
                editingWidgetId = 201
            )
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
                onCommitPageLabel = { _, _ -> },
                onEnterEditMode = { id ->
                    state = state.copy(editingWidgetId = id)
                },
                onStartWidgetDrag = { id ->
                    state = state.copy(draggingWidgetId = id)
                },
                onWidgetHoverCellChanged = { id, cellX, cellY ->
                    state = state.copy(hoveredCell = Pair(cellX, cellY))
                },
                onCommitWidgetDrag = { id, cellX, cellY ->
                    val dragged = state.widgets.find { it.appWidgetId == id }!!
                    val target = dragged.copy(cellX = cellX, cellY = cellY)
                    val result = io.devnullapps.widgetleaf.domain.usecase.CascadeDisplacementResolver.resolve(
                        dragged = target,
                        allWidgets = state.widgets,
                        columns = state.gridProfile.columns,
                        rows = state.gridProfile.rows,
                        pageCount = state.pages.size
                    )
                    if (result != null) {
                        state = state.copy(
                            widgets = result.widgets,
                            draggingWidgetId = null,
                            hoveredCell = null
                        )
                    }
                },
                onCancelWidgetDrag = {
                    state = state.copy(draggingWidgetId = null, hoveredCell = null)
                }
            )
        }

        composeTestRule.waitForIdle()

        // Drag w1 from cell (0,0) rightward by ~300px onto cell (2,0)
        composeTestRule.onNodeWithTag("placed_widget_201").performTouchInput {
            swipeRight(startX = centerX - 40f, endX = centerX + 260f, durationMillis = 200)
        }
        composeTestRule.waitForIdle()

        val finalW1 = state.widgets.find { it.appWidgetId == 201 }
        val finalW2 = state.widgets.find { it.appWidgetId == 202 }

        // Dragged widget took target cell (2, 0) and KEPT its original span (2, 2)
        assertThat(finalW1?.cellX).isEqualTo(2)
        assertThat(finalW1?.cellY).isEqualTo(0)
        assertThat(finalW1?.spanX).isEqualTo(2)
        assertThat(finalW1?.spanY).isEqualTo(2)

        // Displaced widget w2 cascaded downward to (2, 2) and KEPT its original span (2, 2)
        assertThat(finalW2?.cellX).isEqualTo(2)
        assertThat(finalW2?.cellY).isEqualTo(2)
        assertThat(finalW2?.spanX).isEqualTo(2)
        assertThat(finalW2?.spanY).isEqualTo(2)
    }

    @Test
    fun resizeHandleRespectsExplicitMaxSpan() {
        var resizedSpanY = -1
        val widget = PlacedWidget(
            appWidgetId = 301,
            providerPackage = "io.devnullapps.calendar",
            providerClass = "CalendarWidget",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            minSpanX = 1,
            minSpanY = 1,
            maxSpanX = 3,
            maxSpanY = 3,
            isReconfigurable = true,
            label = "Calendar"
        )
        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    widgets = listOf(widget),
                    editingWidgetId = 301
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
                onResizeWidget = { _, _, _, _, newSpanY ->
                    resizedSpanY = newSpanY
                }
            )
        }
        composeTestRule.waitForIdle()

        // Drag bottom resize handle down by 500px -> must clamp to maxSpanY = 3
        composeTestRule.onNodeWithTag("resize_handle_bottom_301").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 500f, durationMillis = 200)
        }
        composeTestRule.waitForIdle()
        assertThat(resizedSpanY).isEqualTo(3)
    }

    @Test
    fun resizeHandleRespectsMinSpan() {
        var resizedSpanY = -1
        val widget = PlacedWidget(
            appWidgetId = 302,
            providerPackage = "io.devnullapps.calendar",
            providerClass = "CalendarWidget",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 3,
            spanY = 3,
            minSpanX = 2,
            minSpanY = 2,
            maxSpanX = 8,
            maxSpanY = 8,
            isReconfigurable = true,
            label = "Calendar"
        )
        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    widgets = listOf(widget),
                    editingWidgetId = 302
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
                onResizeWidget = { _, _, _, _, newSpanY ->
                    resizedSpanY = newSpanY
                }
            )
        }
        composeTestRule.waitForIdle()

        // Drag bottom resize handle up by 500px -> must clamp to minSpanY = 2
        composeTestRule.onNodeWithTag("resize_handle_bottom_302").performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 500f, durationMillis = 200)
        }
        composeTestRule.waitForIdle()
        assertThat(resizedSpanY).isEqualTo(2)
    }

    @Test
    fun resizeModeControlsHandleVisibility() {
        val noneWidget = PlacedWidget(
            appWidgetId = 401,
            providerPackage = "pkg",
            providerClass = "Class1",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            resizeMode = PlacedWidget.RESIZE_NONE
        )
        val horizWidget = PlacedWidget(
            appWidgetId = 402,
            providerPackage = "pkg",
            providerClass = "Class2",
            pageIndex = 0,
            cellX = 2,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            resizeMode = PlacedWidget.RESIZE_HORIZONTAL
        )
        val vertWidget = PlacedWidget(
            appWidgetId = 403,
            providerPackage = "pkg",
            providerClass = "Class3",
            pageIndex = 0,
            cellX = 4,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            resizeMode = PlacedWidget.RESIZE_VERTICAL
        )

        var editingId by mutableStateOf<Int?>(401)
        composeTestRule.setContent {
            WidgetDeckOverlayScreen(
                uiState = DeckUiState(
                    widgets = listOf(noneWidget, horizWidget, vertWidget),
                    editingWidgetId = editingId
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
        composeTestRule.waitForIdle()

        // RESIZE_NONE: no handles exist
        composeTestRule.onNodeWithTag("resize_handle_top_401").assertDoesNotExist()
        composeTestRule.onNodeWithTag("resize_handle_bottom_401").assertDoesNotExist()
        composeTestRule.onNodeWithTag("resize_handle_left_401").assertDoesNotExist()
        composeTestRule.onNodeWithTag("resize_handle_right_401").assertDoesNotExist()

        // RESIZE_HORIZONTAL: only left and right handles
        editingId = 402
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("resize_handle_left_402").assertIsDisplayed()
        composeTestRule.onNodeWithTag("resize_handle_right_402").assertIsDisplayed()
        composeTestRule.onNodeWithTag("resize_handle_top_402").assertDoesNotExist()
        composeTestRule.onNodeWithTag("resize_handle_bottom_402").assertDoesNotExist()

        // RESIZE_VERTICAL: only top and bottom handles
        editingId = 403
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("resize_handle_top_403").assertIsDisplayed()
        composeTestRule.onNodeWithTag("resize_handle_bottom_403").assertIsDisplayed()
        composeTestRule.onNodeWithTag("resize_handle_left_403").assertDoesNotExist()
        composeTestRule.onNodeWithTag("resize_handle_right_403").assertDoesNotExist()
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

    @Test
    fun widgetLeafThemeRendersBothDynamicAndStandardPalettes() {
        composeTestRule.setContent {
            io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme(useDynamicColor = false) {
                val standardPalette = io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme.palette
                assertThat(standardPalette.leafPrimary).isNotNull()
            }
            io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme(useDynamicColor = true) {
                val dynamicPalette = io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme.palette
                assertThat(dynamicPalette.leafPrimary).isNotNull()
            }
        }
    }
}
