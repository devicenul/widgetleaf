package io.devnullapps.widgetleaf.domain.repository

import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface WidgetDeckRepository {
    val pagesFlow: Flow<List<DeckPage>>
    val widgetsFlow: Flow<List<PlacedWidget>>
    val settingsFlow: Flow<OverlaySettings>
    val lastGoodGridSizeFlow: Flow<Pair<Int, Int>?>
        get() = flowOf(null)

    suspend fun getSnapshot(): Pair<List<DeckPage>, List<PlacedWidget>>
    suspend fun replaceDeckState(pages: List<DeckPage>, widgets: List<PlacedWidget>)
    suspend fun upsertWidget(widget: PlacedWidget)
    suspend fun upsertWidgets(widgets: List<PlacedWidget>)
    suspend fun removeWidget(appWidgetId: Int)
    suspend fun updatePageLabel(pageIndex: Int, customLabel: String?)
    suspend fun pruneEmptyPagesAndReindex()
    suspend fun updateSettings(transform: (OverlaySettings) -> OverlaySettings)
    suspend fun updateLastGoodGridSize(columns: Int, rows: Int) {}
}
