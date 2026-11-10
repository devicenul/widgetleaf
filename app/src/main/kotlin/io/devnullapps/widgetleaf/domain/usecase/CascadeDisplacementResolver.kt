package io.devnullapps.widgetleaf.domain.usecase

import io.devnullapps.widgetleaf.domain.model.GridBounds
import io.devnullapps.widgetleaf.domain.model.PlacedWidget

data class CascadeResult(
    val widgets: List<PlacedWidget>,      // ALL widgets across all pages after the move (dragged widget included at its target)
    val pageCount: Int,                    // max(inputPageCount, highest pageIndex used + 1)
    val movedWidgetIds: Set<Int>           // ids of displaced widgets whose page/cell changed (excludes dragged)
)

class CascadeDisplacementResolver {

    /**
     * dragged = the widget at its TARGET page/cell with its ORIGINAL span (never resized).
     * allWidgets = snapshot of ALL widgets on all pages (positions at drag START; may contain dragged at its old spot).
     * Returns null only if dragged's target bounds are not within columns x rows.
     */
    fun resolve(
        dragged: PlacedWidget,
        allWidgets: List<PlacedWidget>,
        columns: Int,
        rows: Int,
        pageCount: Int
    ): CascadeResult? {
        if (!dragged.bounds.isWithin(columns, rows) || dragged.pageIndex < 0) {
            return null
        }

        // Record initial positions of non-dragged widgets to track movedWidgetIds later
        val originalPositions = allWidgets
            .filter { it.appWidgetId != dragged.appWidgetId }
            .associate { it.appWidgetId to Triple(it.pageIndex, it.cellX, it.cellY) }

        // 1. Remove dragged's old entry from allWidgets; its vacated spot is free.
        // Dragged is "settled" at its target. Widgets NEVER change span.
        val settled = mutableListOf<PlacedWidget>(dragged)

        // Store untouched/unsettled widgets in a LinkedHashMap for deterministic order
        val unsettled = LinkedHashMap<Int, PlacedWidget>()
        for (w in allWidgets) {
            if (w.appWidgetId != dragged.appWidgetId) {
                unsettled[w.appWidgetId] = w
            }
        }

        val queue = ArrayDeque<PlacedWidget>()

        // 2. Widgets on dragged's target page that intersect it are displaced.
        // Enqueue them in column order: sort by cellX, then cellY (FIFO queue).
        val initiallyDisplaced = unsettled.values
            .filter { it.pageIndex == dragged.pageIndex && it.bounds.intersects(dragged.bounds) }
            .sortedWith(
                compareBy<PlacedWidget> { it.cellX }
                    .thenBy { it.cellY }
                    .thenBy { it.appWidgetId }
            )

        for (w in initiallyDisplaced) {
            unsettled.remove(w.appWidgetId)
            queue.addLast(w)
        }

        // 3. A displaced widget W searches for a new spot starting from its OWN current position
        // (page p, cellX, cellY), in COLUMN-MAJOR order...
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val settledWidget = findSpotForWidget(
                widget = current,
                settled = settled,
                columns = columns,
                rows = rows,
                pageCount = pageCount,
                unsettled = unsettled,
                queue = queue
            )
            settled.add(settledWidget)

            // 4. Any not-yet-settled widgets on that page that W's new bounds intersect
            // are removed from the unsettled set and appended to the queue
            // (sorted column-major among themselves); they then search the same way
            // from their OWN original position.
            val newlyDisplaced = unsettled.values
                .filter { it.pageIndex == settledWidget.pageIndex && it.bounds.intersects(settledWidget.bounds) }
                .sortedWith(
                    compareBy<PlacedWidget> { it.cellX }
                        .thenBy { it.cellY }
                        .thenBy { it.appWidgetId }
                )

            for (w in newlyDisplaced) {
                unsettled.remove(w.appWidgetId)
                queue.addLast(w)
            }
        }

        // 5. Repeat until queue empty. Untouched widgets keep positions.
        val allSettled = settled + unsettled.values

        // Finalize widgets list: deterministic ordering preserving allWidgets order
        val settledMap = allSettled.associateBy { it.appWidgetId }
        val resultWidgets = mutableListOf<PlacedWidget>()
        val seenIds = mutableSetOf<Int>()

        for (w in allWidgets) {
            if (seenIds.add(w.appWidgetId)) {
                settledMap[w.appWidgetId]?.let { resultWidgets.add(it) }
            }
        }
        if (seenIds.add(dragged.appWidgetId)) {
            resultWidgets.add(dragged)
        }
        for (w in allSettled) {
            if (seenIds.add(w.appWidgetId)) {
                resultWidgets.add(w)
            }
        }

        // Identify moved displaced widgets (excluding dragged)
        val movedWidgetIds = mutableSetOf<Int>()
        for (w in allSettled) {
            if (w.appWidgetId == dragged.appWidgetId) continue
            val orig = originalPositions[w.appWidgetId]
            if (orig != null && (w.pageIndex != orig.first || w.cellX != orig.second || w.cellY != orig.third)) {
                movedWidgetIds.add(w.appWidgetId)
            }
        }

        val maxPageIndexUsed = allSettled.maxOfOrNull { it.pageIndex } ?: 0
        val finalPageCount = maxOf(pageCount, maxPageIndexUsed + 1)

        return CascadeResult(
            widgets = resultWidgets,
            pageCount = finalPageCount,
            movedWidgetIds = movedWidgetIds
        )
    }

    private fun findSpotForWidget(
        widget: PlacedWidget,
        settled: List<PlacedWidget>,
        columns: Int,
        rows: Int,
        pageCount: Int,
        unsettled: Map<Int, PlacedWidget>,
        queue: Collection<PlacedWidget>
    ): PlacedWidget {
        val spanX = widget.spanX
        val spanY = widget.spanY

        // Defensive: if W's span exceeds the grid, just place it at (0,0) of a new page
        if (spanX > columns || spanY > rows) {
            val usedPages = (settled.map { it.pageIndex } +
                    unsettled.values.map { it.pageIndex } +
                    queue.map { it.pageIndex } +
                    (0 until pageCount)).toSet()
            var brandNewPage = pageCount
            while (brandNewPage in usedPages) {
                brandNewPage++
            }
            return widget.copy(pageIndex = brandNewPage, cellX = 0, cellY = 0)
        }

        val startPage = maxOf(0, widget.pageIndex)
        val startX = widget.cellX
        val startY = widget.cellY

        var currentPage = startPage
        while (true) {
            if (currentPage == startPage) {
                // Column x = startX, step y downward from startY to rows - spanY
                if (startX in 0..(columns - spanX)) {
                    val fromY = maxOf(0, startY)
                    val toY = rows - spanY
                    for (y in fromY..toY) {
                        val candidateBounds = GridBounds(cellX = startX, cellY = y, spanX = spanX, spanY = spanY)
                        if (isCandidateValid(currentPage, candidateBounds, settled, columns, rows)) {
                            return widget.copy(pageIndex = currentPage, cellX = startX, cellY = y)
                        }
                    }
                }

                // Next columns x from startX + 1 to columns - spanX, y from 0 to rows - spanY
                val fromX = maxOf(0, startX + 1)
                val toX = columns - spanX
                for (x in fromX..toX) {
                    for (y in 0..(rows - spanY)) {
                        val candidateBounds = GridBounds(cellX = x, cellY = y, spanX = spanX, spanY = spanY)
                        if (isCandidateValid(currentPage, candidateBounds, settled, columns, rows)) {
                            return widget.copy(pageIndex = currentPage, cellX = x, cellY = y)
                        }
                    }
                }
            } else {
                // Next page p + 1 starting at (0,0) in column-major order
                for (x in 0..(columns - spanX)) {
                    for (y in 0..(rows - spanY)) {
                        val candidateBounds = GridBounds(cellX = x, cellY = y, spanX = spanX, spanY = spanY)
                        if (isCandidateValid(currentPage, candidateBounds, settled, columns, rows)) {
                            return widget.copy(pageIndex = currentPage, cellX = x, cellY = y)
                        }
                    }
                }
            }
            currentPage++
        }
    }

    private fun isCandidateValid(
        page: Int,
        bounds: GridBounds,
        settled: List<PlacedWidget>,
        columns: Int,
        rows: Int
    ): Boolean {
        if (!bounds.isWithin(columns, rows)) return false
        return settled.none { it.pageIndex == page && it.bounds.intersects(bounds) }
    }

    companion object {
        private val defaultResolver = CascadeDisplacementResolver()

        fun resolve(
            dragged: PlacedWidget,
            allWidgets: List<PlacedWidget>,
            columns: Int,
            rows: Int,
            pageCount: Int
        ): CascadeResult? = defaultResolver.resolve(dragged, allWidgets, columns, rows, pageCount)
    }
}
