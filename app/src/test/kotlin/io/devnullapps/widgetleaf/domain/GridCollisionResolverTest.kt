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
    fun findFirstAvailableSpot_findsFirstEmptySpotColumnByColumn() {
        val w1 = createWidget(id = 1, cellX = 0, cellY = 0, spanX = 2, spanY = 2)
        // Grid 4x4. Column 0 has x=0,1 y=0,1 occupied. In column-major order, spot (0, 2) for 2x2 is free!
        val spot = resolver.findFirstAvailableSpot(
            spanX = 2,
            spanY = 2,
            existingOnPage = listOf(w1),
            columns = 4,
            rows = 4
        )
        assertEquals(Pair(0, 2), spot)

        // If (0, 2) is also occupied:
        val w2 = createWidget(id = 2, cellX = 0, cellY = 2, spanX = 2, spanY = 2)
        // Column 0 and 1 are full (x=0,1 for y=0..3). Next spot should be at column 2: (2, 0)
        val nextSpot = resolver.findFirstAvailableSpot(
            spanX = 2,
            spanY = 2,
            existingOnPage = listOf(w1, w2),
            columns = 4,
            rows = 4
        )
        assertEquals(Pair(2, 0), nextSpot)
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
