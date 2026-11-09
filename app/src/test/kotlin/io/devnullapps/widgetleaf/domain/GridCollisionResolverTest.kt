package io.devnullapps.widgetleaf.domain

import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.usecase.GridCollisionResolver
import io.devnullapps.widgetleaf.domain.usecase.PlacementResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridCollisionResolverTest {

    private val resolver = GridCollisionResolver()

    private fun createWidget(
        id: Int,
        cellX: Int,
        cellY: Int,
        spanX: Int = 2,
        spanY: Int = 2,
        pageIndex: Int = 0
    ) = PlacedWidget(
        appWidgetId = id,
        providerPackage = "com.example.pkg$id",
        providerClass = "com.example.Widget$id",
        pageIndex = pageIndex,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY
    )

    @Test
    fun emptyPage_placementAccepted() {
        val target = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val result = resolver.resolvePlacement(
            target = target,
            existingOnPage = emptyList(),
            columns = 8,
            rows = 4
        )

        assertTrue(result is PlacementResult.Accepted)
        val accepted = result as PlacementResult.Accepted
        assertEquals(listOf(target), accepted.updatedWidgets)
    }

    @Test
    fun directNonOverlappingMove_accepted() {
        val existing = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val target = createWidget(id = 2, cellX = 2, cellY = 0, spanX = 2, spanY = 2)

        val result = resolver.resolvePlacement(
            target = target,
            existingOnPage = listOf(existing),
            columns = 8,
            rows = 4
        )

        assertTrue(result is PlacementResult.Accepted)
        val accepted = result as PlacementResult.Accepted
        assertEquals(2, accepted.updatedWidgets.size)
        assertTrue(accepted.updatedWidgets.contains(existing))
        assertTrue(accepted.updatedWidgets.contains(target))
    }

    @Test
    fun overlappingMove_horizontalAutoBump_accepted() {
        // existing widget at (2,0). Target moves from (0,0) to (2,0), moving RIGHT by 2 cells.
        val oldTarget = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val bumpedWidget = createWidget(id = 2, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        val newTarget = oldTarget.copy(cellX = 2, cellY = 0)

        val result = resolver.resolvePlacement(
            target = newTarget,
            existingOnPage = listOf(oldTarget, bumpedWidget),
            columns = 8,
            rows = 4
        )

        assertTrue(result is PlacementResult.Accepted)
        val accepted = result as PlacementResult.Accepted
        val updatedMap = accepted.updatedWidgets.associateBy { it.appWidgetId }

        assertEquals(newTarget.bounds, updatedMap[1]?.bounds)
        // Bumped widget shifted horizontally to the right
        assertEquals(4, updatedMap[2]?.cellX)
        assertEquals(0, updatedMap[2]?.cellY)
    }

    @Test
    fun overlappingMove_verticalAutoBump_accepted() {
        // existing widget at (0,2). Target moves from (0,0) to (0,2), moving DOWN by 2 cells.
        val oldTarget = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val bumpedWidget = createWidget(id = 2, cellX = 0, cellY = 2, spanX = 2, spanY = 2)
        val newTarget = oldTarget.copy(cellX = 0, cellY = 2)

        val result = resolver.resolvePlacement(
            target = newTarget,
            existingOnPage = listOf(oldTarget, bumpedWidget),
            columns = 8,
            rows = 6
        )

        assertTrue(result is PlacementResult.Accepted)
        val accepted = result as PlacementResult.Accepted
        val updatedMap = accepted.updatedWidgets.associateBy { it.appWidgetId }

        assertEquals(newTarget.bounds, updatedMap[1]?.bounds)
        // Bumped widget shifted vertically downward
        assertEquals(0, updatedMap[2]?.cellX)
        assertEquals(4, updatedMap[2]?.cellY)
    }

    @Test
    fun cascadingAutoBump_widgetABumpsBBumpsC_accepted() {
        // On an 8x4 grid, widgets at (2,0) and (4,0). Target moves from (0,0) to (2,0).
        val oldTarget = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val widgetB = createWidget(id = 2, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        val widgetC = createWidget(id = 3, cellX = 4, cellY = 0, spanX = 2, spanY = 2)
        val newTarget = oldTarget.copy(cellX = 2, cellY = 0)

        val result = resolver.resolvePlacement(
            target = newTarget,
            existingOnPage = listOf(oldTarget, widgetB, widgetC),
            columns = 8,
            rows = 4
        )

        assertTrue(result is PlacementResult.Accepted)
        val accepted = result as PlacementResult.Accepted
        val updatedMap = accepted.updatedWidgets.associateBy { it.appWidgetId }

        assertEquals(2, updatedMap[1]?.cellX)
        assertEquals(4, updatedMap[2]?.cellX)
        assertEquals(6, updatedMap[3]?.cellX)
    }

    @Test
    fun resize_triggersAutoBump_accepted() {
        // Target is resized at (0,0) from spanX=2 to spanX=4, overlapping widget at (2,0).
        val oldTarget = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val bumpedWidget = createWidget(id = 2, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        val resizedTarget = oldTarget.copy(spanX = 4)

        val result = resolver.resolvePlacement(
            target = resizedTarget,
            existingOnPage = listOf(oldTarget, bumpedWidget),
            columns = 8,
            rows = 4
        )

        assertTrue(result is PlacementResult.Accepted)
        val accepted = result as PlacementResult.Accepted
        val updatedMap = accepted.updatedWidgets.associateBy { it.appWidgetId }

        assertEquals(resizedTarget.bounds, updatedMap[1]?.bounds)
        // Bumped widget finds free cell nearby (e.g. (4,0))
        assertEquals(4, updatedMap[2]?.cellX)
        assertEquals(0, updatedMap[2]?.cellY)
    }

    @Test
    fun outOfBounds_placementRejected() {
        val outOfBoundsTarget = createWidget(id = 1, cellX = 7, cellY = 3, spanX = 2, spanY = 2)
        val result = resolver.resolvePlacement(
            target = outOfBoundsTarget,
            existingOnPage = emptyList(),
            columns = 8,
            rows = 4
        )
        assertEquals(PlacementResult.Rejected, result)

        val negativeTarget = createWidget(id = 2, cellX = -1, cellY = 0, spanX = 2, spanY = 2)
        val resultNegative = resolver.resolvePlacement(
            target = negativeTarget,
            existingOnPage = emptyList(),
            columns = 8,
            rows = 4
        )
        assertEquals(PlacementResult.Rejected, resultNegative)
    }

    @Test
    fun fullPage_unresolvableCollision_rejected() {
        // Grid 2x2, full of two 2x1 widgets
        val w1 = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 1)
        val w2 = createWidget(id = 2, cellX = 0, cellY = 1, spanX = 2, spanY = 1)
        // A new 2x1 widget cannot fit on this full 2x2 page
        val target = createWidget(id = 3, cellX = 0, cellY = 0, spanX = 2, spanY = 1)

        val result = resolver.resolvePlacement(
            target = target,
            existingOnPage = listOf(w1, w2),
            columns = 2,
            rows = 2
        )

        assertEquals(PlacementResult.Rejected, result)
    }

    @Test
    fun findFirstAvailableSpot_findsFirstEmptySpotRowByRow() {
        val w1 = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        // Grid 4x4. Row 0 has x=0,1 occupied. Spot (2,0) for 2x2 is free!
        val spot = resolver.findFirstAvailableSpot(
            spanX = 2,
            spanY = 2,
            existingOnPage = listOf(w1),
            columns = 4,
            rows = 4
        )
        assertEquals(Pair(2, 0), spot)

        // If (2,0) is also occupied:
        val w2 = createWidget(id = 2, cellX = 2, cellY = 0, spanX = 2, spanY = 2)
        // Row 0 and 1 are full. Next spot should be at row 2: (0, 2)
        val nextSpot = resolver.findFirstAvailableSpot(
            spanX = 2,
            spanY = 2,
            existingOnPage = listOf(w1, w2),
            columns = 4,
            rows = 4
        )
        assertEquals(Pair(0, 2), nextSpot)
    }

    @Test
    fun findFirstAvailableSpot_returnsNullWhenNoRoom() {
        val w1 = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        val spot = resolver.findFirstAvailableSpot(
            spanX = 2,
            spanY = 2,
            existingOnPage = listOf(w1),
            columns = 2,
            rows = 2
        )
        assertNull(spot)
    }
}
