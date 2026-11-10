package io.devnullapps.widgetleaf.domain

import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.usecase.EdgeDirection
import io.devnullapps.widgetleaf.domain.usecase.EdgePageDragTransferUseCase
import io.devnullapps.widgetleaf.domain.usecase.EdgeTransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgePageDragTransferUseCaseTest {

    private val useCase = EdgePageDragTransferUseCase()
    private val profile = DesktopGridProfile(
        columns = 8,
        rows = 4,
        cellWidthDp = 140f,
        cellHeightDp = 140f,
        gutterDp = 16f
    )

    private fun createWidget(id: Int, pageIndex: Int, cellX: Int, cellY: Int, spanX: Int = 2, spanY: Int = 2) =
        PlacedWidget(
            appWidgetId = id,
            providerPackage = "com.pkg$id",
            providerClass = "com.Widget$id",
            pageIndex = pageIndex,
            cellX = cellX,
            cellY = cellY,
            spanX = spanX,
            spanY = spanY
        )

    @Test
    fun dragLeftOnPageZero_isBlockedAtLeftEdge() {
        val page0 = DeckPage(pageIndex = 0, customLabel = "Main")
        val widget = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0)

        val result = useCase.transferAcrossEdge(
            widgetId = 1,
            direction = EdgeDirection.LEFT,
            targetCellX = 0,
            targetCellY = 0,
            currentPages = listOf(page0),
            currentWidgets = listOf(widget),
            gridProfile = profile
        )

        assertEquals(EdgeTransferResult.BlockedAtLeftEdge, result)
    }

    @Test
    fun dragRightFromLastPage_createsNewPageAndTransfersWidget() {
        val page0 = DeckPage(pageIndex = 0, customLabel = "Main")
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0)
        val w2 = createWidget(id = 2, pageIndex = 0, cellX = 2, cellY = 0)

        val result = useCase.transferAcrossEdge(
            widgetId = 1,
            direction = EdgeDirection.RIGHT,
            targetCellX = 0,
            targetCellY = 0,
            currentPages = listOf(page0),
            currentWidgets = listOf(w1, w2),
            gridProfile = profile
        )

        assertTrue(result is EdgeTransferResult.Success)
        val success = result as EdgeTransferResult.Success
        assertEquals(2, success.pages.size)
        assertEquals(1, success.targetPageIndex)

        val transferred = success.widgets.find { it.appWidgetId == 1 }
        assertEquals(1, transferred?.pageIndex)
        assertEquals(0, transferred?.cellX)
        assertEquals(0, transferred?.cellY)

        val remaining = success.widgets.find { it.appWidgetId == 2 }
        assertEquals(0, remaining?.pageIndex)
    }

    @Test
    fun dragRightFromLastPage_prunesEmptySourcePage() {
        // Only 1 widget on page 0. Dragging right moves it to a new page, making page 0 empty.
        // Rule: Any page with 0 widgets (when total > 1) is pruned, so the new page becomes page 0.
        val page0 = DeckPage(pageIndex = 0, customLabel = "OnlyPage")
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0)

        val result = useCase.transferAcrossEdge(
            widgetId = 1,
            direction = EdgeDirection.RIGHT,
            targetCellX = 0,
            targetCellY = 0,
            currentPages = listOf(page0),
            currentWidgets = listOf(w1),
            gridProfile = profile
        )

        assertTrue(result is EdgeTransferResult.Success)
        val success = result as EdgeTransferResult.Success
        assertEquals(1, success.pages.size)
        assertEquals(0, success.targetPageIndex)
        assertEquals(0, success.widgets.single().pageIndex)
    }

    @Test
    fun dragLeftOntoExistingAdjacentPage_resolvesPlacementAndPrunesSourcePageIfEmpty() {
        val page0 = DeckPage(pageIndex = 0, customLabel = "Home")
        val page1 = DeckPage(pageIndex = 1, customLabel = "Media")

        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0)
        val w2 = createWidget(id = 2, pageIndex = 1, cellX = 0, cellY = 0)

        val result = useCase.transferAcrossEdge(
            widgetId = 2,
            direction = EdgeDirection.LEFT,
            targetCellX = 2,
            targetCellY = 0,
            currentPages = listOf(page0, page1),
            currentWidgets = listOf(w1, w2),
            gridProfile = profile
        )

        assertTrue(result is EdgeTransferResult.Success)
        val success = result as EdgeTransferResult.Success

        // Page 1 now has 0 widgets, so it was pruned! Only Page 0 remains.
        assertEquals(1, success.pages.size)
        assertEquals("Home", success.pages[0].customLabel)
        assertEquals(0, success.targetPageIndex)

        val movedW2 = success.widgets.find { it.appWidgetId == 2 }
        assertEquals(0, movedW2?.pageIndex)
        assertEquals(2, movedW2?.cellX)
        assertEquals(0, movedW2?.cellY)
    }

    @Test
    fun dragOntoFullAdjacentPage_cascadesDisplacedWidgetsToNextPage() {
        val smallProfile = DesktopGridProfile(columns = 2, rows = 2)
        val page0 = DeckPage(pageIndex = 0)
        val page1 = DeckPage(pageIndex = 1)

        // Page 0 has two 2x1 widgets filling 2x2 board completely
        val p0w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 1)
        val p0w2 = createWidget(id = 2, pageIndex = 0, cellX = 0, cellY = 1, spanX = 2, spanY = 1)

        // Page 1 has w3 (cell 0,0) and w4 (cell 0,1)
        val p1w3 = createWidget(id = 3, pageIndex = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 1)
        val p1w4 = createWidget(id = 4, pageIndex = 1, cellX = 0, cellY = 1, spanX = 2, spanY = 1)

        val result = useCase.transferAcrossEdge(
            widgetId = 3,
            direction = EdgeDirection.LEFT,
            targetCellX = 0,
            targetCellY = 0,
            currentPages = listOf(page0, page1),
            currentWidgets = listOf(p0w1, p0w2, p1w3, p1w4),
            gridProfile = smallProfile
        )

        assertTrue(result is EdgeTransferResult.Success)
        val success = result as EdgeTransferResult.Success
        assertEquals(0, success.targetPageIndex)

        val movedW3 = success.widgets.find { it.appWidgetId == 3 }
        assertEquals(0, movedW3?.pageIndex)
        assertEquals(0, movedW3?.cellX)
        assertEquals(0, movedW3?.cellY)

        // p0w1 was displaced from (0,0) of page 0 downward to (0,1), displacing p0w2 onto page 1 at (0,0)
        val displacedW1 = success.widgets.find { it.appWidgetId == 1 }
        assertEquals(0, displacedW1?.pageIndex)
        assertEquals(0, displacedW1?.cellX)
        assertEquals(1, displacedW1?.cellY)

        val displacedW2 = success.widgets.find { it.appWidgetId == 2 }
        assertEquals(1, displacedW2?.pageIndex)
        assertEquals(0, displacedW2?.cellX)
        assertEquals(0, displacedW2?.cellY)
    }

    @Test
    fun dragOutOfBounds_returnsRejectedFullPage() {
        val page0 = DeckPage(pageIndex = 0)
        // Widget span 3 exceeds 2x2 grid
        val w1 = createWidget(id = 1, pageIndex = 0, cellX = 0, cellY = 0, spanX = 3, spanY = 1)
        val result = useCase.transferAcrossEdge(
            widgetId = 1,
            direction = EdgeDirection.RIGHT,
            targetCellX = 0,
            targetCellY = 0,
            currentPages = listOf(page0),
            currentWidgets = listOf(w1),
            gridProfile = DesktopGridProfile(columns = 2, rows = 2)
        )
        assertEquals(EdgeTransferResult.RejectedFullPage, result)
    }

    @Test
    fun pruneAndReindexPages_emptyWidgets_returnsSingleDefaultPage() {
        val pages = listOf(
            DeckPage(pageIndex = 0, customLabel = "Custom 1"),
            DeckPage(pageIndex = 1, customLabel = "Custom 2")
        )

        val (prunedPages, prunedWidgets) = useCase.pruneAndReindexPages(pages, emptyList())

        assertEquals(1, prunedPages.size)
        assertEquals(0, prunedPages[0].pageIndex)
        assertNull(prunedPages[0].customLabel)
        assertTrue(prunedWidgets.isEmpty())
    }

    @Test
    fun pruneAndReindexPages_preservesSurvivingPageLabels() {
        val page0 = DeckPage(pageIndex = 0, customLabel = "FirstEmpty")
        val page1 = DeckPage(pageIndex = 1, customLabel = "Work")
        val page2 = DeckPage(pageIndex = 2, customLabel = "Entertainment")

        // Page 0 has 0 widgets, Page 1 has w1, Page 2 has w2
        val w1 = createWidget(id = 1, pageIndex = 1, cellX = 0, cellY = 0)
        val w2 = createWidget(id = 2, pageIndex = 2, cellX = 0, cellY = 0)

        val (prunedPages, prunedWidgets) = useCase.pruneAndReindexPages(
            pages = listOf(page0, page1, page2),
            widgets = listOf(w1, w2)
        )

        assertEquals(2, prunedPages.size)
        // Old Page 1 -> New Page 0 with label "Work"
        assertEquals(0, prunedPages[0].pageIndex)
        assertEquals("Work", prunedPages[0].customLabel)
        // Old Page 2 -> New Page 1 with label "Entertainment"
        assertEquals(1, prunedPages[1].pageIndex)
        assertEquals("Entertainment", prunedPages[1].customLabel)

        // Widgets reindexed
        val updatedW1 = prunedWidgets.find { it.appWidgetId == 1 }
        val updatedW2 = prunedWidgets.find { it.appWidgetId == 2 }
        assertEquals(0, updatedW1?.pageIndex)
        assertEquals(1, updatedW2?.pageIndex)
    }
}
