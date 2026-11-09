package io.devnullapps.widgetleaf.ui.viewmodel

import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem

sealed interface PendingWidgetAction {
    data class RequestBind(
        val appWidgetId: Int,
        val providerPackage: String,
        val providerClass: String,
        val candidateWidget: PlacedWidget
    ) : PendingWidgetAction

    data class RequestConfigure(
        val appWidgetId: Int,
        val candidateWidget: PlacedWidget
    ) : PendingWidgetAction
}

data class DeckUiState(
    val pages: List<DeckPage> = listOf(DeckPage(pageIndex = 0, customLabel = null)),
    val widgets: List<PlacedWidget> = emptyList(),
    val activePageIndex: Int = 0,
    val editingWidgetId: Int? = null,
    val editingPageLabelIndex: Int? = null,
    val editingPageLabelDraft: String = "",
    val contextMenuPosition: Pair<Float, Float>? = null,
    val isWidgetPickerOpen: Boolean = false,
    val widgetSearchQuery: String = "",
    val catalogItems: List<WidgetCatalogItem> = emptyList(),
    val draggingCatalogItem: WidgetCatalogItem? = null,
    val isOverlaySettingsOpen: Boolean = false,
    val settings: OverlaySettings = OverlaySettings(),
    val gridProfile: DesktopGridProfile = DesktopGridProfile(),
    val isLaunchingInternalFlow: Boolean = false,
    val pendingWidgetAction: PendingWidgetAction? = null
) {
    val activePageWidgets: List<PlacedWidget>
        get() = widgets.filter { it.pageIndex == activePageIndex }

    val showEmptyCanvasHint: Boolean
        get() = pages.size == 1 && widgets.isEmpty()

    val filteredCatalogByApp: Map<String, List<WidgetCatalogItem>>
        get() {
            val query = widgetSearchQuery.trim()
            val filtered = if (query.isEmpty()) {
                catalogItems
            } else {
                catalogItems.filter { item ->
                    item.appName.contains(query, ignoreCase = true) ||
                        item.widgetLabel.contains(query, ignoreCase = true) ||
                        item.description?.contains(query, ignoreCase = true) == true
                }
            }
            return filtered.groupBy { it.appName }
        }
}
