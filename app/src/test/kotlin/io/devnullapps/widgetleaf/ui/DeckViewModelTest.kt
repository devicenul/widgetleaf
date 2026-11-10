package io.devnullapps.widgetleaf.ui

import android.content.ComponentName
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.domain.repository.WidgetDeckRepository
import io.devnullapps.widgetleaf.domain.usecase.CalculateResponsiveSizesUseCase
import io.devnullapps.widgetleaf.domain.usecase.EdgeDirection
import io.devnullapps.widgetleaf.domain.usecase.EdgePageDragTransferUseCase
import io.devnullapps.widgetleaf.platform.ResponsiveWidgetSizeDp
import io.devnullapps.widgetleaf.platform.WidgetHostGateway
import io.devnullapps.widgetleaf.ui.viewmodel.DeckUiState
import io.devnullapps.widgetleaf.ui.viewmodel.DeckViewModel
import io.devnullapps.widgetleaf.ui.viewmodel.PendingWidgetAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var fakeRepository: FakeWidgetDeckRepository
    private lateinit var fakeGateway: FakeWidgetHostGateway
    private lateinit var viewModel: DeckViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeWidgetDeckRepository()
        fakeGateway = FakeWidgetHostGateway()
        viewModel = DeckViewModel(fakeRepository, fakeGateway)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- 1. Circular (Looping) Navigation Tests ---

    @Test
    fun circularNavigation_loopsFromFirstToLast_andLastToFirst() = runTest {
        val pages = listOf(DeckPage(0), DeckPage(1), DeckPage(2))
        fakeRepository.setPages(pages)

        assertEquals(3, viewModel.uiState.value.pages.size)
        assertEquals(0, viewModel.uiState.value.activePageIndex)

        // Loop previous from page 0 -> page 2
        viewModel.navigatePreviousPage()
        assertEquals(2, viewModel.uiState.value.activePageIndex)
        assertEquals(2, fakeRepository.settings.lastViewedPageIndex)

        // Loop next from page 2 -> page 0
        viewModel.navigateNextPage()
        assertEquals(0, viewModel.uiState.value.activePageIndex)
        assertEquals(0, fakeRepository.settings.lastViewedPageIndex)

        // Step forward from page 0 -> page 1
        viewModel.navigateNextPage()
        assertEquals(1, viewModel.uiState.value.activePageIndex)
        assertEquals(1, fakeRepository.settings.lastViewedPageIndex)
    }

    @Test
    fun swipeNavigation_nonWrappingAndBoundsEnforced() = runTest {
        val pages = listOf(DeckPage(0), DeckPage(1), DeckPage(2))
        fakeRepository.setPages(pages)

        assertEquals(0, viewModel.uiState.value.activePageIndex)

        // Swiping previous from Page 0 must NOT wrap around to Page 2
        val prevResultFromZero = viewModel.swipePreviousPage()
        assertFalse(prevResultFromZero)
        assertEquals(0, viewModel.uiState.value.activePageIndex)

        // Swiping next from Page 0 steps to Page 1
        val nextResult1 = viewModel.swipeNextPage()
        assertTrue(nextResult1)
        assertEquals(1, viewModel.uiState.value.activePageIndex)
        assertEquals(1, fakeRepository.settings.lastViewedPageIndex)

        // Swiping next from Page 1 steps to Page 2
        val nextResult2 = viewModel.swipeNextPage()
        assertTrue(nextResult2)
        assertEquals(2, viewModel.uiState.value.activePageIndex)
        assertEquals(2, fakeRepository.settings.lastViewedPageIndex)

        // Swiping next from last page (Page 2) must NOT wrap around or create speculative pages
        val nextResultFromLast = viewModel.swipeNextPage()
        assertFalse(nextResultFromLast)
        assertEquals(2, viewModel.uiState.value.activePageIndex)
        assertEquals(3, viewModel.uiState.value.pages.size)

        // Swiping previous from Page 2 steps back to Page 1
        val prevResult2 = viewModel.swipePreviousPage()
        assertTrue(prevResult2)
        assertEquals(1, viewModel.uiState.value.activePageIndex)
    }

    @Test
    fun circularNavigation_singlePage_doesNotLoop() = runTest {
        val pages = listOf(DeckPage(0))
        fakeRepository.setPages(pages)

        viewModel.navigatePreviousPage()
        assertEquals(0, viewModel.uiState.value.activePageIndex)

        viewModel.navigateNextPage()
        assertEquals(0, viewModel.uiState.value.activePageIndex)
    }

    @Test
    fun selectPage_switchesActivePageAndPersists() = runTest {
        fakeRepository.setPages(listOf(DeckPage(0), DeckPage(1), DeckPage(2)))

        viewModel.selectPage(1)
        assertEquals(1, viewModel.uiState.value.activePageIndex)
        assertEquals(1, fakeRepository.settings.lastViewedPageIndex)

        // Invalid page index ignored
        viewModel.selectPage(5)
        assertEquals(1, viewModel.uiState.value.activePageIndex)
    }

    // --- 2. Direct Number Key Jumps (1..9 and 0) ---

    @Test
    fun jumpToPageByNumberKey_mapsKeysCorrectly() = runTest {
        val tenPages = (0..9).map { DeckPage(it) }
        fakeRepository.setPages(tenPages)

        // Key 1 -> Page index 0
        viewModel.jumpToPageByNumberKey(1)
        assertEquals(0, viewModel.uiState.value.activePageIndex)

        // Key 3 -> Page index 2
        viewModel.jumpToPageByNumberKey(3)
        assertEquals(2, viewModel.uiState.value.activePageIndex)

        // Key 9 -> Page index 8
        viewModel.jumpToPageByNumberKey(9)
        assertEquals(8, viewModel.uiState.value.activePageIndex)

        // Key 0 -> Page index 9 (Page 10)
        viewModel.jumpToPageByNumberKey(0)
        assertEquals(9, viewModel.uiState.value.activePageIndex)
        assertEquals(9, fakeRepository.settings.lastViewedPageIndex)

        // Invalid key ignored
        viewModel.jumpToPageByNumberKey(12)
        assertEquals(9, viewModel.uiState.value.activePageIndex)
    }

    @Test
    fun jumpToPageByNumberKey_ignoresKeysBeyondPageCount() = runTest {
        fakeRepository.setPages(listOf(DeckPage(0), DeckPage(1)))

        // Key 5 would target index 4, which does not exist
        viewModel.jumpToPageByNumberKey(5)
        assertEquals(0, viewModel.uiState.value.activePageIndex)
    }

    // --- 3. Inline Page Pill Label Editing ---

    @Test
    fun pageLabelEditing_startUpdateCommitAndCancel() = runTest {
        fakeRepository.setPages(listOf(DeckPage(0, "Work"), DeckPage(1)))

        viewModel.startEditingPageLabel(0)
        assertEquals(0, viewModel.uiState.value.editingPageLabelIndex)
        assertEquals("Work", viewModel.uiState.value.editingPageLabelDraft)

        viewModel.updateEditingPageLabelDraft("Dashboard")
        assertEquals("Dashboard", viewModel.uiState.value.editingPageLabelDraft)

        viewModel.commitPageLabel(0, "Dashboard")
        assertNull(viewModel.uiState.value.editingPageLabelIndex)
        assertEquals("Dashboard", fakeRepository.updatedPageLabels[0])

        // Committing whitespace or empty resets custom label to null (default 1..N)
        viewModel.startEditingPageLabel(0)
        viewModel.commitPageLabel(0, "   ")
        assertNull(viewModel.uiState.value.editingPageLabelIndex)
        assertNull(fakeRepository.updatedPageLabels[0])

        // Cancelling clears editing state without calling updatePageLabel
        fakeRepository.updatedPageLabels.clear()
        viewModel.startEditingPageLabel(1)
        viewModel.cancelEditingPageLabel()
        assertNull(viewModel.uiState.value.editingPageLabelIndex)
        assertTrue(fakeRepository.updatedPageLabels.isEmpty())
    }

    // --- 4. Widget Placement: Auto-Place and Dynamic Page Creation ---

    @Test
    fun addWidgetAutoPlace_placesOnActivePageWhenSpaceAvailable() = runTest {
        val item = createCatalogItem("com.clock", "ClockWidget", 2, 2)

        viewModel.addWidgetAutoPlace(item)

        assertEquals(1, viewModel.uiState.value.widgets.size)
        val placed = viewModel.uiState.value.widgets.first()
        assertEquals(0, placed.pageIndex)
        assertEquals(0, placed.cellX)
        assertEquals(0, placed.cellY)
        assertEquals(2, placed.spanX)
        assertEquals(2, placed.spanY)

        // Verifies sizes updated on gateway and widget persisted in repo
        assertTrue(fakeGateway.updatedSizes.containsKey(placed.appWidgetId))
        assertEquals(listOf(placed), fakeRepository.widgets)
        assertFalse(viewModel.uiState.value.isWidgetPickerOpen)
    }

    @Test
    fun addWidgetAutoPlace_activePageFull_automaticallyPlacesOnNewPage() = runTest {
        // Set up a tiny 2x2 grid profile and fill page 0 with a 2x2 widget
        val tinyProfile = DesktopGridProfile(columns = 2, rows = 2)
        viewModel.updateGridProfile(tinyProfile)

        val existing = PlacedWidget(
            appWidgetId = 50,
            providerPackage = "com.full",
            providerClass = "FullWidget",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2
        )
        fakeRepository.setWidgets(listOf(existing))
        fakeRepository.setPages(listOf(DeckPage(0)))

        val newItem = createCatalogItem("com.weather", "WeatherWidget", 2, 2)
        viewModel.addWidgetAutoPlace(newItem)

        // Page 0 was full -> dynamically placed on new page 1 at (0, 0)
        assertEquals(2, viewModel.uiState.value.pages.size)
        assertEquals(1, viewModel.uiState.value.activePageIndex)

        val newPlaced = viewModel.uiState.value.widgets.find { it.providerPackage == "com.weather" }
        assertNotNull(newPlaced)
        assertEquals(1, newPlaced!!.pageIndex)
        assertEquals(0, newPlaced.cellX)
        assertEquals(0, newPlaced.cellY)
    }

    // --- 5. Widget Bind & Configure State Machine ---

    @Test
    fun widgetPlacement_unboundWidget_emitsRequestBind_andDeletesOnDenial() = runTest {
        fakeGateway.allowBind = false
        val item = createCatalogItem("com.unbound", "UnboundWidget", 2, 2)

        viewModel.addWidgetAutoPlace(item)

        assertTrue(viewModel.uiState.value.isLaunchingInternalFlow)
        val action = viewModel.uiState.value.pendingWidgetAction
        assertTrue(action is PendingWidgetAction.RequestBind)
        val bindAction = action as PendingWidgetAction.RequestBind
        val widgetId = bindAction.appWidgetId

        // User denies bind permission in system dialog
        viewModel.onBindWidgetResult(widgetId, granted = false)

        assertFalse(viewModel.uiState.value.isLaunchingInternalFlow)
        assertNull(viewModel.uiState.value.pendingWidgetAction)
        assertTrue(fakeGateway.deletedIds.contains(widgetId))
        assertTrue(fakeRepository.widgets.isEmpty())
    }

    @Test
    fun widgetPlacement_configuredWidget_handlesCancellationAndSuccess() = runTest {
        fakeGateway.allowBind = true
        fakeGateway.hasConfig = true
        val item = createCatalogItem("com.configured", "ConfigWidget", 2, 2)

        viewModel.addWidgetAutoPlace(item)

        assertTrue(viewModel.uiState.value.isLaunchingInternalFlow)
        val action = viewModel.uiState.value.pendingWidgetAction
        assertTrue(action is PendingWidgetAction.RequestConfigure)
        val configAction = action as PendingWidgetAction.RequestConfigure
        val widgetId = configAction.appWidgetId

        // User cancels configuration activity (RESULT_CANCELED)
        viewModel.onConfigureWidgetResult(widgetId, resultOk = false)

        assertFalse(viewModel.uiState.value.isLaunchingInternalFlow)
        assertNull(viewModel.uiState.value.pendingWidgetAction)
        assertTrue(fakeGateway.deletedIds.contains(widgetId))
        assertTrue(fakeRepository.widgets.isEmpty())

        // Now test successful configuration activity
        viewModel.addWidgetAutoPlace(item)
        val newConfigAction = viewModel.uiState.value.pendingWidgetAction as PendingWidgetAction.RequestConfigure
        val newWidgetId = newConfigAction.appWidgetId

        viewModel.onConfigureWidgetResult(newWidgetId, resultOk = true)

        assertFalse(viewModel.uiState.value.isLaunchingInternalFlow)
        assertNull(viewModel.uiState.value.pendingWidgetAction)
        assertEquals(1, fakeRepository.widgets.size)
        assertEquals(newWidgetId, fakeRepository.widgets.first().appWidgetId)
        assertTrue(fakeGateway.updatedSizes.containsKey(newWidgetId))
    }

    // --- 6. Move, Resize and Auto-Bump ---

    @Test
    fun moveWidget_neverChangesSpanOrMinMax() = runTest {
        val widget = PlacedWidget(
            appWidgetId = 10,
            providerPackage = "com.sample",
            providerClass = "SampleWidget",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 3,
            minSpanX = 2,
            minSpanY = 2,
            maxSpanX = 4,
            maxSpanY = 4
        )
        fakeRepository.setWidgets(listOf(widget))

        viewModel.moveWidget(10, newCellX = 3, newCellY = 1)

        val updated = viewModel.uiState.value.widgets.first()
        assertEquals(3, updated.cellX)
        assertEquals(1, updated.cellY)
        // Verify span and min/max span are strictly untouched
        assertEquals(2, updated.spanX)
        assertEquals(3, updated.spanY)
        assertEquals(2, updated.minSpanX)
        assertEquals(2, updated.minSpanY)
        assertEquals(4, updated.maxSpanX)
        assertEquals(4, updated.maxSpanY)
    }

    @Test
    fun resizeWidget_updatesSpanAndPersists() = runTest {
        val widget = PlacedWidget(
            appWidgetId = 10,
            providerPackage = "com.sample",
            providerClass = "SampleWidget",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            minSpanX = 1,
            minSpanY = 1,
            maxSpanX = 8,
            maxSpanY = 8
        )
        fakeRepository.setWidgets(listOf(widget))

        viewModel.enterWidgetEditMode(10)
        assertEquals(10, viewModel.uiState.value.editingWidgetId)

        // Move to cell (1, 1) and resize to span (3, 3)
        viewModel.resizeWidget(10, newCellX = 1, newCellY = 1, newSpanX = 3, newSpanY = 3)

        val updated = viewModel.uiState.value.widgets.first()
        assertEquals(1, updated.cellX)
        assertEquals(1, updated.cellY)
        assertEquals(3, updated.spanX)
        assertEquals(3, updated.spanY)
        assertTrue(fakeGateway.updatedSizes.containsKey(10))

        // Even if widget originally had restrictive maxSpanY=2 in DB, user can resize up to profile.rows
        viewModel.updateGridProfile(DesktopGridProfile(columns = 8, rows = 6))
        viewModel.resizeWidget(10, newCellX = 0, newCellY = 0, newSpanX = 4, newSpanY = 6)
        val stretched = viewModel.uiState.value.widgets.first()
        assertEquals(4, stretched.spanX)
        assertEquals(6, stretched.spanY)

        viewModel.exitWidgetEditMode()
        assertNull(viewModel.uiState.value.editingWidgetId)
    }

    @Test
    fun widgetsFlow_syncsSizesOnInitialLoad() = runTest {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 3, spanY = 2)

        fakeGateway.updateSizesCalls.clear()
        fakeRepository.setWidgets(listOf(w1, w2))

        val syncedIds = fakeGateway.updateSizesCalls.map { it.appWidgetId }
        assertTrue(syncedIds.contains(1))
        assertTrue(syncedIds.contains(2))
        assertEquals(2, fakeGateway.updateSizesCalls.size)
    }

    @Test
    fun widgetsFlow_notResentForUnchangedWidgetsOnSubsequentEmissions() = runTest {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1))

        assertEquals(1, fakeGateway.updateSizesCalls.size)

        // Emit exact same widget again
        fakeRepository.setWidgets(listOf(w1))

        // No new updateAppWidgetSizes call
        assertEquals(1, fakeGateway.updateSizesCalls.size)
    }

    @Test
    fun widgetsFlow_resentOnSpanChange() = runTest {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        assertEquals(2, fakeGateway.updateSizesCalls.size)

        // Change span of w1, keep w2 unchanged
        val w1Resized = w1.copy(spanX = 4, spanY = 3)
        fakeRepository.setWidgets(listOf(w1Resized, w2))

        // Only w1 was re-synced, w2 was not re-sent
        assertEquals(3, fakeGateway.updateSizesCalls.size)
        assertEquals(1, fakeGateway.updateSizesCalls.last().appWidgetId)
        val lastCall = fakeGateway.updateSizesCalls.last()
        val expected = CalculateResponsiveSizesUseCase()(4, 3, viewModel.uiState.value.gridProfile)
        assertEquals(expected.responsiveSizes, lastCall.sizesDp)
    }

    @Test
    fun onOverlayShown_syncsSizesForAllWidgets() = runTest {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 3, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        fakeGateway.updateSizesCalls.clear()

        viewModel.onOverlayShown()

        val syncedIds = fakeGateway.updateSizesCalls.map { it.appWidgetId }
        assertTrue(syncedIds.contains(1))
        assertTrue(syncedIds.contains(2))
        assertEquals(2, fakeGateway.updateSizesCalls.size)
    }

    private fun createWidget(
        id: Int,
        pageIndex: Int,
        cellX: Int,
        cellY: Int,
        spanX: Int = 2,
        spanY: Int = 2,
        minSpanX: Int = 1,
        minSpanY: Int = 1,
        maxSpanX: Int = 4,
        maxSpanY: Int = 4
    ) = PlacedWidget(
        appWidgetId = id,
        providerPackage = "com.pkg$id",
        providerClass = "com.Widget$id",
        pageIndex = pageIndex,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY,
        minSpanX = minSpanX,
        minSpanY = minSpanY,
        maxSpanX = maxSpanX,
        maxSpanY = maxSpanY
    )

    @Test
    fun dragSession_previewAppearsAfterDebounce_andRevertsOnHoverBack() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.startWidgetDrag(1)
        assertEquals(1, viewModel.uiState.value.draggingWidgetId)
        assertNull(viewModel.uiState.value.previewWidgets)

        // Hover over cell (2, 0) where w2 is located
        viewModel.onWidgetHoverCellChanged(1, cellX = 2, cellY = 0)
        // Before debounce delay (250ms), preview is still null
        assertNull(viewModel.uiState.value.previewWidgets)

        // Advance virtual time by 260ms
        advanceTimeBy(260)

        // Preview state is now published!
        val preview = viewModel.uiState.value.previewWidgets
        assertNotNull(preview)
        val previewW1 = preview!!.find { it.appWidgetId == 1 }
        val previewW2 = preview.find { it.appWidgetId == 2 }
        assertEquals(2, previewW1?.cellX)
        assertEquals(0, previewW1?.cellY)
        // w2 was displaced column-major (downward to cellY = 2)
        assertEquals(2, previewW2?.cellX)
        assertEquals(2, previewW2?.cellY)
        assertTrue(viewModel.uiState.value.displacedWidgetIds.contains(2))

        // Hover back to the drag-start cell (0, 0) -> preview immediately clears
        viewModel.onWidgetHoverCellChanged(1, cellX = 0, cellY = 0)
        assertNull(viewModel.uiState.value.previewWidgets)
        assertTrue(viewModel.uiState.value.displacedWidgetIds.isEmpty())
    }

    @Test
    fun dragSession_revertOnCancel() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.startWidgetDrag(1)
        viewModel.onWidgetHoverCellChanged(1, cellX = 2, cellY = 0)
        advanceTimeBy(260)
        assertNotNull(viewModel.uiState.value.previewWidgets)

        viewModel.cancelWidgetDrag()
        assertNull(viewModel.uiState.value.previewWidgets)
        assertNull(viewModel.uiState.value.draggingWidgetId)
        assertTrue(viewModel.uiState.value.displacedWidgetIds.isEmpty())

        // Original widgets in uiState remain untouched
        val currentW1 = viewModel.uiState.value.widgets.find { it.appWidgetId == 1 }
        assertEquals(0, currentW1?.cellX)
        assertEquals(0, currentW1?.cellY)
    }

    @Test
    fun dragSession_commitPersists_includingNewPage() = runTest(testDispatcher) {
        viewModel.updateGridProfile(DesktopGridProfile(columns = 2, rows = 2))
        val page0 = DeckPage(0)
        fakeRepository.setPages(listOf(page0))

        // Grid 2x2: w1 (2x1 at 0,0), w2 (2x1 at 0,1) fills page 0
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 1)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 0, cellY = 1, spanX = 2, spanY = 1)
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.startWidgetDrag(1)
        // Drag w1 onto w2 at (0, 1) -> w2 will cascade onto Page 1!
        viewModel.commitWidgetDrag(1, cellX = 0, cellY = 1)

        // Debounce cancelled, immediately committed & pages extended
        assertNull(viewModel.uiState.value.previewWidgets)
        assertNull(viewModel.uiState.value.draggingWidgetId)
        assertEquals(2, viewModel.uiState.value.pages.size)

        val committedW1 = viewModel.uiState.value.widgets.find { it.appWidgetId == 1 }
        val committedW2 = viewModel.uiState.value.widgets.find { it.appWidgetId == 2 }
        assertEquals(0, committedW1?.pageIndex)
        assertEquals(0, committedW1?.cellX)
        assertEquals(1, committedW1?.cellY)

        assertEquals(1, committedW2?.pageIndex)
        assertEquals(0, committedW2?.cellX)
        assertEquals(0, committedW2?.cellY)

        // Verified persisted to repository
        assertEquals(2, fakeRepository.pages.size)
        assertEquals(2, fakeRepository.widgets.size)
    }

    @Test
    fun dragSession_lateDebounceAfterCommit_doesNotPublishPreview() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.startWidgetDrag(1)
        viewModel.onWidgetHoverCellChanged(1, cellX = 2, cellY = 0)
        // Immediately commit before debounce triggers
        viewModel.commitWidgetDrag(1, cellX = 2, cellY = 0)

        // Advance time past debounce delay
        advanceTimeBy(300)

        // Preview should remain null; stale debounce job did not publish
        assertNull(viewModel.uiState.value.previewWidgets)
        assertNull(viewModel.uiState.value.draggingWidgetId)
    }

    @Test
    fun dragSession_lateDebounceAfterCancel_doesNotPublishPreview() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.startWidgetDrag(1)
        viewModel.onWidgetHoverCellChanged(1, cellX = 2, cellY = 0)
        // Immediately cancel before debounce triggers
        viewModel.cancelWidgetDrag()

        // Advance time past debounce delay
        advanceTimeBy(300)

        assertNull(viewModel.uiState.value.previewWidgets)
        assertNull(viewModel.uiState.value.draggingWidgetId)
    }

    @Test
    fun dragSession_hoverDebounceCommitEquivalence() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.startWidgetDrag(1)
        viewModel.onWidgetHoverCellChanged(1, cellX = 2, cellY = 0)
        advanceTimeBy(260)

        val previewWidgets = viewModel.uiState.value.previewWidgets!!
        viewModel.commitWidgetDrag(1, cellX = 2, cellY = 0)

        assertEquals(previewWidgets, viewModel.uiState.value.widgets)
    }

    @Test
    fun exitEditMode_cancelsDragSession() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.enterWidgetEditMode(1)
        viewModel.startWidgetDrag(1)
        viewModel.onWidgetHoverCellChanged(1, cellX = 2, cellY = 0)
        advanceTimeBy(260)
        assertNotNull(viewModel.uiState.value.previewWidgets)

        viewModel.exitWidgetEditMode()
        assertNull(viewModel.uiState.value.editingWidgetId)
        assertNull(viewModel.uiState.value.previewWidgets)
        assertNull(viewModel.uiState.value.draggingWidgetId)
    }

    @Test
    fun escapeKey_cancelsActiveDragSession() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.enterWidgetEditMode(1)
        viewModel.startWidgetDrag(1)
        viewModel.onWidgetHoverCellChanged(1, cellX = 2, cellY = 0)
        advanceTimeBy(260)
        assertNotNull(viewModel.uiState.value.previewWidgets)

        val handled = viewModel.handleEscapeKey()
        assertTrue(handled)
        assertNull(viewModel.uiState.value.previewWidgets)
        assertNull(viewModel.uiState.value.draggingWidgetId)
    }

    @Test
    fun pageNavigation_cancelsActiveDragSession() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setPages(listOf(DeckPage(0), DeckPage(1)))
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.startWidgetDrag(1)
        viewModel.onWidgetHoverCellChanged(1, cellX = 2, cellY = 0)
        advanceTimeBy(260)
        assertNotNull(viewModel.uiState.value.previewWidgets)

        viewModel.selectPage(1)
        assertNull(viewModel.uiState.value.previewWidgets)
        assertNull(viewModel.uiState.value.draggingWidgetId)
    }

    @Test
    fun onEdgeDwellTriggered_right_advancesPageAndSynthesizesSpeculativePage() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setPages(listOf(DeckPage(0)))
        fakeRepository.setWidgets(listOf(w1))

        viewModel.startWidgetDrag(1)
        assertEquals(0, viewModel.uiState.value.activePageIndex)
        assertEquals(1, viewModel.uiState.value.effectivePageCount)

        // Dwell right: advances to speculative page 1
        viewModel.onEdgeDwellTriggered(EdgeDirection.RIGHT)
        assertEquals(1, viewModel.uiState.value.activePageIndex)
        assertEquals(2, viewModel.uiState.value.effectivePageCount)
        assertEquals(2, viewModel.uiState.value.effectivePages.size)
        // Persisted pages in repository untouched while dragging
        assertEquals(1, fakeRepository.pages.size)
    }

    @Test
    fun onEdgeDwellTriggered_left_atPageZero_isNoOp() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setPages(listOf(DeckPage(0)))
        fakeRepository.setWidgets(listOf(w1))

        viewModel.startWidgetDrag(1)
        viewModel.onEdgeDwellTriggered(EdgeDirection.LEFT)

        assertEquals(0, viewModel.uiState.value.activePageIndex)
        assertEquals(1, viewModel.uiState.value.effectivePageCount)
    }

    @Test
    fun commitWidgetDrag_afterEdgeDwell_prunesUnusedPagesAndPersistsContiguously() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setPages(listOf(DeckPage(0)))
        fakeRepository.setWidgets(listOf(w1))

        viewModel.startWidgetDrag(1)
        // Dwell right past page 0 -> speculative page 1
        viewModel.onEdgeDwellTriggered(EdgeDirection.RIGHT)
        assertEquals(1, viewModel.uiState.value.activePageIndex)

        // Dwell right again past page 1 -> speculative page 2
        viewModel.onEdgeDwellTriggered(EdgeDirection.RIGHT)
        assertEquals(2, viewModel.uiState.value.activePageIndex)

        // Dwell left back to page 1
        viewModel.onEdgeDwellTriggered(EdgeDirection.LEFT)
        assertEquals(1, viewModel.uiState.value.activePageIndex)

        // Drop on page 1 at (0, 0)
        viewModel.commitWidgetDrag(1, cellX = 0, cellY = 0)

        // Page 0 (now empty because w1 was only widget and moved to page 1) is pruned,
        // and speculative page 2 is pruned! Page indices re-indexed contiguously.
        val committedPages = viewModel.uiState.value.pages
        val committedWidgets = viewModel.uiState.value.widgets
        assertEquals(1, committedPages.size)
        assertEquals(0, committedPages[0].pageIndex)
        assertEquals(1, committedWidgets.size)
        assertEquals(0, committedWidgets[0].pageIndex)
        assertEquals(0, committedWidgets[0].cellX)
        assertEquals(0, committedWidgets[0].cellY)
        assertEquals(committedPages, fakeRepository.pages)
        assertEquals(committedWidgets, fakeRepository.widgets)
    }

    @Test
    fun cancelWidgetDrag_afterEdgeDwell_discardsSpeculativePagesAndRestoresStartPage() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setPages(listOf(DeckPage(0)))
        fakeRepository.setWidgets(listOf(w1))

        viewModel.startWidgetDrag(1)
        viewModel.onEdgeDwellTriggered(EdgeDirection.RIGHT)
        assertEquals(1, viewModel.uiState.value.activePageIndex)
        assertEquals(2, viewModel.uiState.value.effectivePageCount)

        viewModel.cancelWidgetDrag()
        assertEquals(0, viewModel.uiState.value.activePageIndex)
        assertEquals(1, viewModel.uiState.value.pages.size)
        assertEquals(1, viewModel.uiState.value.effectivePageCount)
        assertNull(viewModel.uiState.value.previewWidgets)
        assertNull(viewModel.uiState.value.draggingWidgetId)
    }

    @Test
    fun cascadeDisplacement_runsOnNewPageDuringDebouncedHover() = runTest(testDispatcher) {
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setPages(listOf(DeckPage(0), DeckPage(1)))
        fakeRepository.setWidgets(listOf(w1, w2))

        viewModel.startWidgetDrag(1)
        // Flip to page 1
        viewModel.onEdgeDwellTriggered(EdgeDirection.RIGHT)
        assertEquals(1, viewModel.uiState.value.activePageIndex)

        // Hover over (0, 0) on page 1 where w2 resides
        viewModel.onWidgetHoverCellChanged(1, cellX = 0, cellY = 0)
        advanceTimeBy(260)

        val preview = viewModel.uiState.value.previewWidgets
        assertNotNull(preview)
        val previewW1 = preview!!.first { it.appWidgetId == 1 }
        val previewW2 = preview.first { it.appWidgetId == 2 }

        // w1 is at (0, 0) on page 1
        assertEquals(1, previewW1.pageIndex)
        assertEquals(0, previewW1.cellX)
        assertEquals(0, previewW1.cellY)

        // w2 was displaced downward on page 1 in column-major order
        assertEquals(1, previewW2.pageIndex)
        assertTrue(previewW2.cellY >= 2 || previewW2.cellX >= 2)
    }

    @Test
    fun resizeWidget_respectsMinSpan() = runTest {
        val widget = createWidget(id = 10, pageIndex = 0, cellX = 0, cellY = 0, spanX = 3, spanY = 3, minSpanX = 2, minSpanY = 2)
        fakeRepository.setWidgets(listOf(widget))

        // Attempt to shrink to 1x1 below minimum span 2x2
        viewModel.resizeWidget(10, newCellX = 0, newCellY = 0, newSpanX = 1, newSpanY = 1)
        val clamped = viewModel.uiState.value.widgets.first()
        assertEquals(2, clamped.spanX)
        assertEquals(2, clamped.spanY)
    }

    @Test
    fun resizeWidget_respectsExplicitMaxSpan() = runTest {
        viewModel.updateGridProfile(DesktopGridProfile(columns = 8, rows = 8))
        // Explicit provider max is 3x3 on an 8x8 grid
        val widget = createWidget(id = 10, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2, maxSpanX = 3, maxSpanY = 3)
        fakeRepository.setWidgets(listOf(widget))

        // Attempt to expand to 6x6 beyond explicit maxSpan 3x3
        viewModel.resizeWidget(10, newCellX = 0, newCellY = 0, newSpanX = 6, newSpanY = 6)
        val clamped = viewModel.uiState.value.widgets.first()
        assertEquals(3, clamped.spanX)
        assertEquals(3, clamped.spanY)
    }

    @Test
    fun resizeWidget_noExplicitMax_allowsResizingUpToGrid() = runTest {
        viewModel.updateGridProfile(DesktopGridProfile(columns = 8, rows = 6))
        // maxSpan is equal to grid columns (no explicit provider max cap)
        val widget = createWidget(id = 10, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2, maxSpanX = 8, maxSpanY = 8)
        fakeRepository.setWidgets(listOf(widget))

        viewModel.resizeWidget(10, newCellX = 0, newCellY = 0, newSpanX = 6, newSpanY = 5)
        val expanded = viewModel.uiState.value.widgets.first()
        assertEquals(6, expanded.spanX)
        assertEquals(5, expanded.spanY)
    }

    @Test
    fun onOverlayShown_refreshesPersistedWidgetLimitsFromCatalog() = runTest {
        val staleWidget = createWidget(
            id = 50,
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2,
            minSpanX = 1,
            minSpanY = 1,
            maxSpanX = 12,
            maxSpanY = 8
        )
        fakeGateway.allocatedIds = listOf(50)
        fakeGateway.installedProviders.add(staleWidget.providerPackage to staleWidget.providerClass)
        fakeRepository.setWidgets(listOf(staleWidget))

        fakeGateway.catalogToReturn = listOf(
            WidgetCatalogItem(
                providerPackage = staleWidget.providerPackage,
                providerClass = staleWidget.providerClass,
                appName = "App50",
                widgetLabel = "Widget50",
                defaultSpanX = 2,
                defaultSpanY = 2,
                minSpanX = 2,
                minSpanY = 3,
                maxSpanX = 4,
                maxSpanY = 5,
                resizeMode = PlacedWidget.RESIZE_VERTICAL
            )
        )

        viewModel.onOverlayShown()

        val refreshed = viewModel.uiState.value.widgets.first { it.appWidgetId == 50 }
        assertEquals(2, refreshed.minSpanX)
        assertEquals(3, refreshed.minSpanY)
        assertEquals(4, refreshed.maxSpanX)
        assertEquals(5, refreshed.maxSpanY)
        assertEquals(PlacedWidget.RESIZE_VERTICAL, refreshed.resizeMode)
    }

    // --- 7. Cross-Edge Drag Transitions & Auto-Pruning ---

    @Test
    fun dragWidgetAcrossEdge_rightCreatesNewPage_leftBlockedAtPageZero() = runTest {
        val w1 = PlacedWidget(
            appWidgetId = 20,
            providerPackage = "com.edge1",
            providerClass = "EdgeWidget1",
            pageIndex = 0,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2
        )
        val w2 = PlacedWidget(
            appWidgetId = 21,
            providerPackage = "com.edge2",
            providerClass = "EdgeWidget2",
            pageIndex = 0,
            cellX = 2,
            cellY = 0,
            spanX = 2,
            spanY = 2
        )
        fakeRepository.setWidgets(listOf(w1, w2))
        fakeRepository.setPages(listOf(DeckPage(0)))

        // Dragging left from Page 0 is blocked
        viewModel.dragWidgetAcrossEdge(20, EdgeDirection.LEFT, targetCellX = 0, targetCellY = 0)
        assertEquals(0, viewModel.uiState.value.activePageIndex)
        assertEquals(1, viewModel.uiState.value.pages.size)

        // Dragging right from the last page creates new page 1
        viewModel.dragWidgetAcrossEdge(20, EdgeDirection.RIGHT, targetCellX = 1, targetCellY = 1)
        assertEquals(1, viewModel.uiState.value.activePageIndex)
        assertEquals(2, viewModel.uiState.value.pages.size)
        val movedWidget = viewModel.uiState.value.widgets.find { it.appWidgetId == 20 }
        assertEquals(1, movedWidget?.pageIndex)
    }

    @Test
    fun removeWidget_callsGatewayDelete_andPrunesEmptyPages() = runTest {
        val w1 = PlacedWidget(31, "com.p1", "W1", pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = PlacedWidget(32, "com.p2", "W2", pageIndex = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)

        fakeRepository.setPages(listOf(DeckPage(0), DeckPage(1, "CustomPage2")))
        fakeRepository.setWidgets(listOf(w1, w2))
        viewModel.selectPage(1)
        viewModel.enterWidgetEditMode(32)

        // Remove w2, leaving page 1 empty -> page 1 is auto-pruned
        viewModel.removeWidget(32)

        assertTrue(fakeGateway.deletedIds.contains(32))
        assertEquals(listOf(32), fakeRepository.removedWidgetIds)
        assertNull(viewModel.uiState.value.editingWidgetId)
        assertEquals(1, viewModel.uiState.value.pages.size)
        assertEquals(0, viewModel.uiState.value.activePageIndex)
    }

    @Test
    fun deleteSelectedWidget_removesCurrentEditingWidget() = runTest {
        val w1 = PlacedWidget(41, "com.p1", "W1", pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1))

        viewModel.enterWidgetEditMode(41)
        viewModel.deleteSelectedWidget()

        assertTrue(fakeGateway.deletedIds.contains(41))
        assertTrue(viewModel.uiState.value.widgets.isEmpty())
    }

    // --- 8. Startup Page Preference & Orphan Reconcile ---

    @Test
    fun onOverlayShown_openOnPageOne_setsActivePageIndexToZero() = runTest {
        val widget = PlacedWidget(101, "com.cat", "CatWidget", pageIndex = 2, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeGateway.allocatedIds = listOf(101)
        fakeGateway.installedProviders.add("com.cat" to "CatWidget")
        fakeRepository.setWidgets(listOf(widget))
        fakeRepository.settings = OverlaySettings(openOnPageOne = true, lastViewedPageIndex = 2)
        fakeRepository.setPages(listOf(DeckPage(0), DeckPage(1), DeckPage(2)))
        fakeGateway.catalogToReturn = listOf(createCatalogItem("com.cat", "CatalogWidget", 2, 2))

        viewModel.onOverlayShown()

        assertEquals(0, viewModel.uiState.value.activePageIndex)
        assertEquals(1, viewModel.uiState.value.catalogItems.size)
    }

    @Test
    fun onOverlayShown_openOnPageOneFalse_restoresLastViewedPageIndex() = runTest {
        val w0 = PlacedWidget(101, "com.cat", "CatWidget", pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w1 = PlacedWidget(102, "com.cat", "CatWidget", pageIndex = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = PlacedWidget(103, "com.cat", "CatWidget", pageIndex = 2, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeGateway.allocatedIds = listOf(101, 102, 103)
        fakeGateway.installedProviders.add("com.cat" to "CatWidget")
        fakeRepository.setWidgets(listOf(w0, w1, w2))
        fakeRepository.settings = OverlaySettings(openOnPageOne = false, lastViewedPageIndex = 2)
        fakeRepository.setPages(listOf(DeckPage(0), DeckPage(1), DeckPage(2)))

        viewModel.onOverlayShown()

        assertEquals(2, viewModel.uiState.value.activePageIndex)
    }

    // --- 9. Escape Key Hierarchy Priority Chain ---

    @Test
    fun escapeKeyHierarchy_strictPriorityOrder() = runTest {
        // Priority 1: Inline page label editing
        fakeRepository.setPages(listOf(DeckPage(0)))
        viewModel.startEditingPageLabel(0)
        assertTrue(viewModel.handleEscapeKey())
        assertNull(viewModel.uiState.value.editingPageLabelIndex)

        // Priority 2: Overlay settings open
        viewModel.openOverlaySettings()
        assertTrue(viewModel.uiState.value.isOverlaySettingsOpen)
        assertTrue(viewModel.handleEscapeKey())
        assertFalse(viewModel.uiState.value.isOverlaySettingsOpen)

        // Priority 3: Widget picker open
        viewModel.openWidgetPicker()
        assertTrue(viewModel.uiState.value.isWidgetPickerOpen)
        assertTrue(viewModel.handleEscapeKey())
        assertFalse(viewModel.uiState.value.isWidgetPickerOpen)

        // Priority 4: Context menu open
        viewModel.openContextMenu(100f to 200f)
        assertNotNull(viewModel.uiState.value.contextMenuPosition)
        assertTrue(viewModel.handleEscapeKey())
        assertNull(viewModel.uiState.value.contextMenuPosition)

        // Priority 5: Widget edit mode active
        viewModel.enterWidgetEditMode(99)
        assertEquals(99, viewModel.uiState.value.editingWidgetId)
        assertTrue(viewModel.handleEscapeKey())
        assertNull(viewModel.uiState.value.editingWidgetId)

        // Priority 6: Clean state -> returns false (overlay Activity dismisses)
        assertFalse(viewModel.handleEscapeKey())
    }

    // --- 10. Smart Auto-Dismiss Decision ---

    @Test
    fun smartAutoDismiss_returnsTrueOnlyWhenNotLaunchingInternalFlow() = runTest {
        // In standard state: clicking inside a widget launching an external Activity auto-dismisses
        assertTrue(viewModel.shouldAutoDismissOnUserLeave())

        // When launching bind permission dialog: stays open
        fakeGateway.allowBind = false
        viewModel.addWidgetAutoPlace(createCatalogItem("com.bind", "BindWidget", 2, 2))
        assertTrue(viewModel.uiState.value.isLaunchingInternalFlow)
        assertFalse(viewModel.shouldAutoDismissOnUserLeave())
    }

    // --- 11. DeckUiState Helper Properties ---

    @Test
    fun uiState_helpersComputeCorrectly() = runTest {
        val w1 = PlacedWidget(1, "com.a", "W1", pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = PlacedWidget(2, "com.b", "W2", pageIndex = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)

        val item1 = WidgetCatalogItem("com.alpha", "Cls1", "AlphaApp", "Calendar", "Monthly view")
        val item2 = WidgetCatalogItem("com.alpha", "Cls2", "AlphaApp", "Clock", "Digital clock")
        val item3 = WidgetCatalogItem("com.beta", "Cls3", "BetaApp", "Notes", "Quick notes")

        val state = DeckUiState(
            pages = listOf(DeckPage(0), DeckPage(1)),
            widgets = listOf(w1, w2),
            activePageIndex = 0,
            catalogItems = listOf(item1, item2, item3),
            widgetSearchQuery = "clock"
        )

        // activePageWidgets filters by activePageIndex
        assertEquals(listOf(w1), state.activePageWidgets)

        // showEmptyCanvasHint is false since widgets exist
        assertFalse(state.showEmptyCanvasHint)

        // Empty canvas hint is true only when 1 page and 0 widgets
        assertTrue(DeckUiState(pages = listOf(DeckPage(0)), widgets = emptyList()).showEmptyCanvasHint)

        // filteredCatalogByApp matches "clock" in widgetLabel and groups by appName
        val filtered = state.filteredCatalogByApp
        assertEquals(1, filtered.size)
        assertTrue(filtered.containsKey("AlphaApp"))
        assertEquals(listOf(item2), filtered["AlphaApp"])
    }

    // --- 12. Grid Profile Update & Reflow Tests ---

    @Test
    fun updateGridProfile_columnsGrow_existingWidgetsDoNotMove() = runTest(testDispatcher) {
        val initialProfile = DesktopGridProfile(columns = 6, rows = 4)
        viewModel.updateGridProfile(initialProfile)

        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 3, cellY = 1, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        // Expand columns from 6 to 8
        val expandedProfile = DesktopGridProfile(columns = 8, rows = 6)
        viewModel.updateGridProfile(expandedProfile)

        val widgetsAfter = viewModel.uiState.value.widgets
        val resW1 = widgetsAfter.first { it.appWidgetId == 1 }
        val resW2 = widgetsAfter.first { it.appWidgetId == 2 }

        assertEquals(0, resW1.cellX)
        assertEquals(0, resW1.cellY)
        assertEquals(3, resW2.cellX)
        assertEquals(1, resW2.cellY)
        // Sizes re-synced via gateway
        assertTrue(fakeGateway.updatedSizes.containsKey(1))
        assertTrue(fakeGateway.updatedSizes.containsKey(2))
    }

    @Test
    fun updateGridProfile_columnsShrink_outOfBoundsWidgetsReflowSensiblyWithoutOverlaps() = runTest(testDispatcher) {
        val initialProfile = DesktopGridProfile(columns = 8, rows = 6)
        viewModel.updateGridProfile(initialProfile)

        // w1 at (0, 0, 2x2) fits in 4x4; w2 at (5, 0, 2x2) exceeds 4 columns!
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 5, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1, w2))

        // Shrink columns to 4x4
        val shrunkProfile = DesktopGridProfile(columns = 4, rows = 4)
        viewModel.updateGridProfile(shrunkProfile)

        val widgetsAfter = viewModel.uiState.value.widgets
        val resW1 = widgetsAfter.first { it.appWidgetId == 1 }
        val resW2 = widgetsAfter.first { it.appWidgetId == 2 }

        // w1 stayed at (0, 0)
        assertEquals(0, resW1.cellX)
        assertEquals(0, resW1.cellY)

        // w2 was reflowed to a free spot on page 0 without overlapping w1
        assertTrue(resW2.cellX + resW2.spanX <= shrunkProfile.columns)
        assertTrue(resW2.cellY + resW2.spanY <= shrunkProfile.rows)
        assertFalse(resW1.bounds.intersects(resW2.bounds))

        // Sizes re-synced via gateway
        assertTrue(fakeGateway.updatedSizes.containsKey(1))
        assertTrue(fakeGateway.updatedSizes.containsKey(2))

        // Display-only reflow: persisted repository state was NOT written automatically!
        assertEquals(listOf(w1, w2), fakeRepository.widgets)
        assertEquals(5, fakeRepository.widgets.first { it.appWidgetId == 2 }.cellX)

        // Grow back to initial 8x6 profile: original positions restored!
        viewModel.updateGridProfile(initialProfile)
        val restoredWidgets = viewModel.uiState.value.widgets
        val restoredW2 = restoredWidgets.first { it.appWidgetId == 2 }
        assertEquals(5, restoredW2.cellX)
        assertEquals(0, restoredW2.cellY)
    }

    @Test
    fun updateGridProfile_degenerateMeasurements_ignored() = runTest(testDispatcher) {
        val initialProfile = DesktopGridProfile(columns = 8, rows = 6)
        viewModel.updateGridProfile(initialProfile)

        // Degenerate measurements: 0 columns, 0 rows, 0 available dimensions
        viewModel.updateGridProfile(DesktopGridProfile(columns = 0, rows = 0))
        assertEquals(initialProfile, viewModel.uiState.value.gridProfile)

        viewModel.updateGridProfile(DesktopGridProfile(columns = 8, rows = 6, availableWidthDp = 5f))
        assertEquals(initialProfile, viewModel.uiState.value.gridProfile)

        viewModel.updateGridProfile(DesktopGridProfile(columns = 8, rows = 6, cellWidthDp = 0f))
        assertEquals(initialProfile, viewModel.uiState.value.gridProfile)
    }

    @Test
    fun updateGridProfile_unchangedProfile_isNoOp() = runTest(testDispatcher) {
        val initialProfile = DesktopGridProfile(columns = 8, rows = 6)
        viewModel.updateGridProfile(initialProfile)

        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        fakeRepository.setWidgets(listOf(w1))
        fakeGateway.updatedSizes.clear()

        // Calling with the same profile does not re-sync or modify state
        viewModel.updateGridProfile(initialProfile)
        assertTrue(fakeGateway.updatedSizes.isEmpty())
    }

    private fun createCatalogItem(pkg: String, label: String, spanX: Int, spanY: Int) = WidgetCatalogItem(
        providerPackage = pkg,
        providerClass = "$pkg.Widget",
        appName = "App-$label",
        widgetLabel = label,
        defaultSpanX = spanX,
        defaultSpanY = spanY
    )

    // --- Test Fakes ---

    private class FakeWidgetDeckRepository(
        initialPages: List<DeckPage> = listOf(DeckPage(0)),
        initialWidgets: List<PlacedWidget> = emptyList(),
        initialSettings: OverlaySettings = OverlaySettings()
    ) : WidgetDeckRepository {
        private val _pagesFlow = MutableStateFlow(initialPages)
        private val _widgetsFlow = MutableStateFlow(initialWidgets)
        private val _settingsFlow = MutableStateFlow(initialSettings)

        override val pagesFlow: Flow<List<DeckPage>> = _pagesFlow
        override val widgetsFlow: Flow<List<PlacedWidget>> = _widgetsFlow
        override val settingsFlow: Flow<OverlaySettings> = _settingsFlow

        val pages: List<DeckPage> get() = _pagesFlow.value
        val widgets: List<PlacedWidget> get() = _widgetsFlow.value
        var settings: OverlaySettings
            get() = _settingsFlow.value
            set(value) { _settingsFlow.value = value }

        val updatedPageLabels = mutableMapOf<Int, String?>()
        val removedWidgetIds = mutableListOf<Int>()
        var pruneCallCount = 0

        fun setPages(newPages: List<DeckPage>) {
            _pagesFlow.value = newPages
        }

        fun setWidgets(newWidgets: List<PlacedWidget>) {
            _widgetsFlow.value = newWidgets
        }

        override suspend fun getSnapshot(): Pair<List<DeckPage>, List<PlacedWidget>> {
            return Pair(_pagesFlow.value, _widgetsFlow.value)
        }

        override suspend fun replaceDeckState(pages: List<DeckPage>, widgets: List<PlacedWidget>) {
            _pagesFlow.value = pages
            _widgetsFlow.value = widgets
        }

        override suspend fun upsertWidget(widget: PlacedWidget) {
            val filtered = _widgetsFlow.value.filter { it.appWidgetId != widget.appWidgetId }
            _widgetsFlow.value = filtered + widget
        }

        override suspend fun upsertWidgets(widgets: List<PlacedWidget>) {
            val map = _widgetsFlow.value.associateBy { it.appWidgetId }.toMutableMap()
            for (w in widgets) {
                map[w.appWidgetId] = w
            }
            _widgetsFlow.value = map.values.toList()
        }

        override suspend fun removeWidget(appWidgetId: Int) {
            removedWidgetIds.add(appWidgetId)
            _widgetsFlow.value = _widgetsFlow.value.filter { it.appWidgetId != appWidgetId }
        }

        override suspend fun updatePageLabel(pageIndex: Int, customLabel: String?) {
            updatedPageLabels[pageIndex] = customLabel
            val currentPages = _pagesFlow.value.map { page ->
                if (page.pageIndex == pageIndex) page.copy(customLabel = customLabel) else page
            }
            _pagesFlow.value = currentPages
        }

        override suspend fun pruneEmptyPagesAndReindex() {
            pruneCallCount++
            val (prunedPages, reindexedWidgets) = EdgePageDragTransferUseCase.pruneAndReindexPages(
                _pagesFlow.value,
                _widgetsFlow.value
            )
            _pagesFlow.value = prunedPages
            _widgetsFlow.value = reindexedWidgets
        }

        override suspend fun updateSettings(transform: (OverlaySettings) -> OverlaySettings) {
            _settingsFlow.value = transform(_settingsFlow.value)
        }
    }

    private class FakeWidgetHostGateway : WidgetHostGateway {
        var nextAllocatedId = 100
        val deletedIds = mutableListOf<Int>()
        var allowBind = true
        var hasConfig = false
        var catalogToReturn = listOf<WidgetCatalogItem>()
        var allocatedIds = listOf<Int>()
        var installedProviders = mutableSetOf<Pair<String, String>>()
        val updatedSizes = mutableMapOf<Int, List<ResponsiveWidgetSizeDp>>()
        val updateSizesCalls = mutableListOf<UpdateSizesCall>()

        override fun startListening() {}
        override fun stopListening() {}

        override fun allocateAppWidgetId(): Int = nextAllocatedId++

        override fun deleteAppWidgetId(appWidgetId: Int) {
            deletedIds.add(appWidgetId)
        }

        override fun getAllocatedAppWidgetIds(): IntArray = allocatedIds.toIntArray()

        override fun bindAppWidgetIdIfAllowed(appWidgetId: Int, provider: ComponentName): Boolean {
            return allowBind
        }

        override fun getInstalledCatalog(gridProfile: DesktopGridProfile): List<WidgetCatalogItem> {
            return catalogToReturn
        }

        override fun isProviderInstalled(providerPackage: String, providerClass: String): Boolean {
            return installedProviders.contains(providerPackage to providerClass)
        }

        override fun hasConfigurationActivity(appWidgetId: Int): Boolean = hasConfig

        override fun isReconfigurable(appWidgetId: Int): Boolean = false

        override fun updateAppWidgetSizes(
            appWidgetId: Int,
            sizesDp: List<ResponsiveWidgetSizeDp>,
            minWidthDp: Int,
            minHeightDp: Int,
            maxWidthDp: Int,
            maxHeightDp: Int
        ) {
            updatedSizes[appWidgetId] = sizesDp
            updateSizesCalls.add(
                UpdateSizesCall(appWidgetId, sizesDp, minWidthDp, minHeightDp, maxWidthDp, maxHeightDp)
            )
        }
    }

    data class UpdateSizesCall(
        val appWidgetId: Int,
        val sizesDp: List<ResponsiveWidgetSizeDp>,
        val minWidthDp: Int,
        val minHeightDp: Int,
        val maxWidthDp: Int,
        val maxHeightDp: Int
    )
}
