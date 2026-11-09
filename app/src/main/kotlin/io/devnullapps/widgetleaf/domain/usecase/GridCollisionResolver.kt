package io.devnullapps.widgetleaf.domain.usecase

import io.devnullapps.widgetleaf.domain.model.GridBounds
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import kotlin.math.abs

sealed interface PlacementResult {
    data class Accepted(val updatedWidgets: List<PlacedWidget>) : PlacementResult
    data object Rejected : PlacementResult
}

class GridCollisionResolver {

    fun resolvePlacement(
        target: PlacedWidget,
        existingOnPage: List<PlacedWidget>,
        columns: Int,
        rows: Int
    ): PlacementResult {
        if (!target.bounds.isWithin(columns, rows)) {
            return PlacementResult.Rejected
        }

        val oldTarget = existingOnPage.find { it.appWidgetId == target.appWidgetId }
        val otherWidgets = existingOnPage.filter { it.appWidgetId != target.appWidgetId }

        // If target doesn't collide with any widget on the page, accept directly
        if (otherWidgets.none { it.bounds.intersects(target.bounds) }) {
            return PlacementResult.Accepted(otherWidgets + target)
        }

        val deltaX = if (oldTarget != null) target.cellX - oldTarget.cellX else 0
        val deltaY = if (oldTarget != null) target.cellY - oldTarget.cellY else 0

        // If moving along a vector, prioritize shifting displaced widgets along the movement vector
        if (deltaX != 0 || deltaY != 0) {
            val directionalResult = tryDirectionalPush(
                target = target,
                otherWidgets = otherWidgets,
                deltaX = deltaX,
                deltaY = deltaY,
                columns = columns,
                rows = rows
            )
            if (directionalResult != null) {
                return PlacementResult.Accepted(directionalResult)
            }
        }

        // Fallback: Manhattan distance search with cascading bump support
        val manhattanResult = solveWithManhattan(
            target = target,
            otherWidgets = otherWidgets,
            columns = columns,
            rows = rows
        )

        return if (manhattanResult != null) {
            PlacementResult.Accepted(manhattanResult)
        } else {
            PlacementResult.Rejected
        }
    }

    private fun tryDirectionalPush(
        target: PlacedWidget,
        otherWidgets: List<PlacedWidget>,
        deltaX: Int,
        deltaY: Int,
        columns: Int,
        rows: Int
    ): List<PlacedWidget>? {
        val placedMap = mutableMapOf(target.appWidgetId to target)
        val queue = ArrayDeque<PlacedWidget>()

        val initiallyColliding = otherWidgets.filter { it.bounds.intersects(target.bounds) }
        val unplaced = (otherWidgets - initiallyColliding.toSet()).associateBy { it.appWidgetId }.toMutableMap()

        queue.addAll(initiallyColliding)

        val isHorizontal = abs(deltaX) >= abs(deltaY)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            // Find which placed widget is colliding with current
            val obstacle = placedMap.values.find { it.bounds.intersects(current.bounds) } ?: continue

            val newCellX: Int
            val newCellY: Int

            if (isHorizontal) {
                newCellY = current.cellY
                newCellX = if (deltaX >= 0) {
                    obstacle.bounds.right
                } else {
                    obstacle.bounds.cellX - current.spanX
                }
            } else {
                newCellX = current.cellX
                newCellY = if (deltaY >= 0) {
                    obstacle.bounds.bottom
                } else {
                    obstacle.bounds.cellY - current.spanY
                }
            }

            val candidateBounds = GridBounds(newCellX, newCellY, current.spanX, current.spanY)
            if (!candidateBounds.isWithin(columns, rows)) {
                return null // Exceeded board bounds in this direction
            }

            val movedCurrent = current.copy(cellX = newCellX, cellY = newCellY)
            // Check if it overlaps any already-placed widget (other than the obstacle it just moved away from)
            if (placedMap.values.any { it.bounds.intersects(candidateBounds) }) {
                return null
            }

            placedMap[movedCurrent.appWidgetId] = movedCurrent

            // Check if this newly placed widget now collides with any unplaced widget
            val newlyColliding = unplaced.values.filter { it.bounds.intersects(candidateBounds) }
            for (nc in newlyColliding) {
                unplaced.remove(nc.appWidgetId)
                queue.addLast(nc)
            }
        }

        val allResult = placedMap.values.toList() + unplaced.values.toList()
        // Final sanity check: ensure no two widgets intersect and all within bounds
        for (i in allResult.indices) {
            val w1 = allResult[i]
            if (!w1.bounds.isWithin(columns, rows)) return null
            for (j in i + 1 until allResult.size) {
                if (w1.bounds.intersects(allResult[j].bounds)) return null
            }
        }

        return allResult
    }

    private fun solveWithManhattan(
        target: PlacedWidget,
        otherWidgets: List<PlacedWidget>,
        columns: Int,
        rows: Int
    ): List<PlacedWidget>? {
        val placedMap = mutableMapOf(target.appWidgetId to target)
        val initiallyColliding = otherWidgets.filter { it.bounds.intersects(target.bounds) }
        val unaffected = (otherWidgets - initiallyColliding.toSet()).associateBy { it.appWidgetId }.toMutableMap()
        val pending = ArrayDeque(initiallyColliding)

        return searchArrangement(
            placedMap = placedMap,
            unaffected = unaffected,
            pending = pending,
            columns = columns,
            rows = rows
        )
    }

    private fun searchArrangement(
        placedMap: MutableMap<Int, PlacedWidget>,
        unaffected: MutableMap<Int, PlacedWidget>,
        pending: ArrayDeque<PlacedWidget>,
        columns: Int,
        rows: Int
    ): List<PlacedWidget>? {
        if (pending.isEmpty()) {
            return placedMap.values.toList() + unaffected.values.toList()
        }

        val current = pending.removeFirst()

        data class Candidate(val x: Int, val y: Int, val dist: Int, val bumpCount: Int)

        val candidates = mutableListOf<Candidate>()
        for (y in 0..(rows - current.spanY)) {
            for (x in 0..(columns - current.spanX)) {
                val candidateBounds = GridBounds(x, y, current.spanX, current.spanY)
                // Cannot overlap with target or already resolved widgets
                if (placedMap.values.any { it.bounds.intersects(candidateBounds) }) {
                    continue
                }
                val dist = abs(x - current.cellX) + abs(y - current.cellY)
                val bumpCount = unaffected.values.count { it.bounds.intersects(candidateBounds) }
                candidates.add(Candidate(x, y, dist, bumpCount))
            }
        }

        // Sort: closest Manhattan distance, then fewer secondary bumps, then top-to-bottom, left-to-right
        candidates.sortWith(
            compareBy<Candidate> { it.dist }
                .thenBy { it.bumpCount }
                .thenBy { it.y }
                .thenBy { it.x }
        )

        for (cand in candidates) {
            val candidateBounds = GridBounds(cand.x, cand.y, current.spanX, current.spanY)
            val displaced = unaffected.values.filter { it.bounds.intersects(candidateBounds) }

            // Apply placement
            val placedWidget = current.copy(cellX = cand.x, cellY = cand.y)
            placedMap[current.appWidgetId] = placedWidget
            for (d in displaced) {
                unaffected.remove(d.appWidgetId)
                pending.addLast(d)
            }

            val result = searchArrangement(placedMap, unaffected, pending, columns, rows)
            if (result != null) {
                return result
            }

            // Backtrack
            placedMap.remove(current.appWidgetId)
            for (d in displaced) {
                pending.removeLast()
                unaffected[d.appWidgetId] = d
            }
        }

        pending.addFirst(current)
        return null
    }

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

        fun resolvePlacement(
            target: PlacedWidget,
            existingOnPage: List<PlacedWidget>,
            columns: Int,
            rows: Int
        ): PlacementResult = defaultResolver.resolvePlacement(target, existingOnPage, columns, rows)

        fun findFirstAvailableSpot(
            spanX: Int,
            spanY: Int,
            existingOnPage: List<PlacedWidget>,
            columns: Int,
            rows: Int
        ): Pair<Int, Int>? = defaultResolver.findFirstAvailableSpot(spanX, spanY, existingOnPage, columns, rows)
    }
}
