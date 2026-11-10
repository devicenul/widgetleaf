package io.devnullapps.widgetleaf.domain.usecase

import io.devnullapps.widgetleaf.domain.model.GridBounds
import io.devnullapps.widgetleaf.domain.model.PlacedWidget

class GridCollisionResolver {

    fun findFirstAvailableSpot(
        spanX: Int,
        spanY: Int,
        existingOnPage: List<PlacedWidget>,
        columns: Int,
        rows: Int
    ): Pair<Int, Int>? {
        for (y in 0..(rows - spanY)) {
            for (x in 0..(columns - spanX)) {
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
