package io.devnullapps.widgetleaf.domain.usecase

import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.PlacedWidget

enum class EdgeDirection {
    LEFT,
    RIGHT
}

sealed interface EdgeTransferResult {
    data class Success(
        val pages: List<DeckPage>,
        val widgets: List<PlacedWidget>,
        val targetPageIndex: Int
    ) : EdgeTransferResult

    data object BlockedAtLeftEdge : EdgeTransferResult
    data object RejectedFullPage : EdgeTransferResult
}

class EdgePageDragTransferUseCase(
    private val collisionResolver: GridCollisionResolver = GridCollisionResolver()
) {

    fun transferAcrossEdge(
        widgetId: Int,
        direction: EdgeDirection,
        targetCellX: Int,
        targetCellY: Int,
        currentPages: List<DeckPage>,
        currentWidgets: List<PlacedWidget>,
        gridProfile: DesktopGridProfile
    ): EdgeTransferResult {
        val widget = currentWidgets.find { it.appWidgetId == widgetId }
            ?: return EdgeTransferResult.RejectedFullPage

        val sourcePageIndex = widget.pageIndex

        if (direction == EdgeDirection.LEFT && sourcePageIndex == 0) {
            return EdgeTransferResult.BlockedAtLeftEdge
        }

        val targetPageIndex = when (direction) {
            EdgeDirection.LEFT -> sourcePageIndex - 1
            EdgeDirection.RIGHT -> sourcePageIndex + 1
        }

        val clampedX = targetCellX.coerceIn(0, (gridProfile.columns - widget.spanX).coerceAtLeast(0))
        val clampedY = targetCellY.coerceIn(0, (gridProfile.rows - widget.spanY).coerceAtLeast(0))

        if (direction == EdgeDirection.RIGHT && sourcePageIndex >= currentPages.lastIndex) {
            // Dragging RIGHT from the last page: dynamically creates a new page N
            val newPage = DeckPage(pageIndex = currentPages.size, customLabel = null)
            val candidateWidget = widget.copy(
                pageIndex = newPage.pageIndex,
                cellX = clampedX,
                cellY = clampedY
            )
            if (!candidateWidget.bounds.isWithin(gridProfile.columns, gridProfile.rows)) {
                return EdgeTransferResult.RejectedFullPage
            }
            val allPages = currentPages + newPage
            val allWidgets = currentWidgets.filter { it.appWidgetId != widgetId } + candidateWidget
            val (prunedPages, prunedWidgets) = pruneAndReindexPages(allPages, allWidgets)
            val finalPageIndex = prunedWidgets.find { it.appWidgetId == widgetId }?.pageIndex ?: newPage.pageIndex
            return EdgeTransferResult.Success(
                pages = prunedPages,
                widgets = prunedWidgets,
                targetPageIndex = finalPageIndex
            )
        }

        // Moving onto an existing adjacent page
        val existingWidgetsOnTargetPage = currentWidgets.filter { it.pageIndex == targetPageIndex }
        val candidateWidget = widget.copy(
            pageIndex = targetPageIndex,
            cellX = clampedX,
            cellY = clampedY
        )

        val placementResult = collisionResolver.resolvePlacement(
            target = candidateWidget,
            existingOnPage = existingWidgetsOnTargetPage,
            columns = gridProfile.columns,
            rows = gridProfile.rows
        )

        val updatedTargetPageWidgets: List<PlacedWidget>? = when (placementResult) {
            is PlacementResult.Accepted -> placementResult.updatedWidgets
            is PlacementResult.Rejected -> {
                val spot = collisionResolver.findFirstAvailableSpot(
                    spanX = widget.spanX,
                    spanY = widget.spanY,
                    existingOnPage = existingWidgetsOnTargetPage,
                    columns = gridProfile.columns,
                    rows = gridProfile.rows
                )
                if (spot != null) {
                    existingWidgetsOnTargetPage + candidateWidget.copy(cellX = spot.first, cellY = spot.second)
                } else {
                    null
                }
            }
        }

        if (updatedTargetPageWidgets == null) {
            return EdgeTransferResult.RejectedFullPage
        }

        val otherWidgets = currentWidgets.filter { it.pageIndex != targetPageIndex && it.appWidgetId != widgetId }
        val allWidgets = otherWidgets + updatedTargetPageWidgets
        val (prunedPages, prunedWidgets) = pruneAndReindexPages(currentPages, allWidgets)
        val finalPageIndex = prunedWidgets.find { it.appWidgetId == widgetId }?.pageIndex ?: targetPageIndex

        return EdgeTransferResult.Success(
            pages = prunedPages,
            widgets = prunedWidgets,
            targetPageIndex = finalPageIndex
        )
    }

    fun pruneAndReindexPages(
        pages: List<DeckPage>,
        widgets: List<PlacedWidget>
    ): Pair<List<DeckPage>, List<PlacedWidget>> {
        if (widgets.isEmpty()) {
            return Pair(listOf(DeckPage(pageIndex = 0, customLabel = null)), emptyList())
        }

        val occupiedIndices = widgets.map { it.pageIndex }.toSet()
        val existingMap = pages.associateBy { it.pageIndex }
        val pagesToKeep = occupiedIndices.map { index ->
            existingMap[index] ?: DeckPage(pageIndex = index, customLabel = null)
        }.sortedBy { it.pageIndex }

        if (pagesToKeep.isEmpty()) {
            return Pair(listOf(DeckPage(pageIndex = 0, customLabel = null)), emptyList())
        }

        val pageIndexMap = mutableMapOf<Int, Int>()
        val reindexedPages = pagesToKeep.mapIndexed { newIndex, page ->
            pageIndexMap[page.pageIndex] = newIndex
            page.copy(pageIndex = newIndex)
        }

        val reindexedWidgets = widgets.map { widget ->
            val newPageIndex = pageIndexMap[widget.pageIndex] ?: 0
            widget.copy(pageIndex = newPageIndex)
        }

        return Pair(reindexedPages, reindexedWidgets)
    }

    companion object {
        private val defaultUseCase = EdgePageDragTransferUseCase()

        fun pruneAndReindexPages(
            pages: List<DeckPage>,
            widgets: List<PlacedWidget>
        ): Pair<List<DeckPage>, List<PlacedWidget>> = defaultUseCase.pruneAndReindexPages(pages, widgets)

        fun transferAcrossEdge(
            widgetId: Int,
            direction: EdgeDirection,
            targetCellX: Int,
            targetCellY: Int,
            currentPages: List<DeckPage>,
            currentWidgets: List<PlacedWidget>,
            gridProfile: DesktopGridProfile
        ): EdgeTransferResult = defaultUseCase.transferAcrossEdge(
            widgetId = widgetId,
            direction = direction,
            targetCellX = targetCellX,
            targetCellY = targetCellY,
            currentPages = currentPages,
            currentWidgets = currentWidgets,
            gridProfile = gridProfile
        )
    }
}
