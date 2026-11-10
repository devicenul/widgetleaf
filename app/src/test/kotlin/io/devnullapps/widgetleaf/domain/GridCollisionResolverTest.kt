package io.devnullapps.widgetleaf.domain

import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.usecase.GridCollisionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
