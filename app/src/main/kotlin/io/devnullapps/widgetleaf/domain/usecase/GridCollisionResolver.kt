package io.devnullapps.widgetleaf.domain.usecase

import io.devnullapps.widgetleaf.domain.model.GridBounds
import io.devnullapps.widgetleaf.domain.model.PlacedWidget

class GridCollisionResolver {

    /**
     * Finds the first available spot for a widget of size [spanX] x [spanY] among [existingOnPage]
     * using COLUMN-MAJOR order (downward first within a column, then next column right),
     * consistent with the cascade displacement order in [CascadeDisplacementResolver].
     */
    fun findFirstAvailableSpot(
        spanX: Int,
        spanY: Int,
        existingOnPage: List<PlacedWidget>,
        columns: Int,
        rows: Int
    ): Pair<Int, Int>? {
        if (spanX > columns || spanY > rows) return null
        for (x in 0..(columns - spanX)) {
            for (y in 0..(rows - spanY)) {
                val bounds = GridBounds(cellX = x, cellY = y, spanX = spanX, spanY = spanY)
                val hasOverlap = existingOnPage.any { it.bounds.intersects(bounds) }
                if (!hasOverlap) {
                    return Pair(x, y)
                }
            }
        }
        return null
    }

    companion object {
        private val defaultResolver = GridCollisionResolver()

        fun findFirstAvailableSpot(
            spanX: Int,
            spanY: Int,
            existingOnPage: List<PlacedWidget>,
            columns: Int,
            rows: Int
        ): Pair<Int, Int>? = defaultResolver.findFirstAvailableSpot(spanX, spanY, existingOnPage, columns, rows)
    }
}
