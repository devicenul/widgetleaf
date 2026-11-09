package io.devnullapps.widgetleaf.ui.viewmodel

import android.content.ComponentName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.domain.repository.WidgetDeckRepository
import io.devnullapps.widgetleaf.domain.usecase.CalculateResponsiveSizesUseCase
import io.devnullapps.widgetleaf.domain.usecase.EdgeDirection
import io.devnullapps.widgetleaf.domain.usecase.EdgePageDragTransferUseCase
import io.devnullapps.widgetleaf.domain.usecase.EdgeTransferResult
import io.devnullapps.widgetleaf.domain.usecase.GridCollisionResolver
import io.devnullapps.widgetleaf.domain.usecase.PlacementResult
import io.devnullapps.widgetleaf.domain.usecase.ReconcileOrphanWidgetsUseCase
import io.devnullapps.widgetleaf.platform.WidgetHostGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeckViewModel(
    private val repository: WidgetDeckRepository,
    private val widgetHostGateway: WidgetHostGateway,
    private val reconcileOrphanWidgetsUseCase: ReconcileOrphanWidgetsUseCase = ReconcileOrphanWidgetsUseCase(
        repository,
        widgetHostGateway
    ),
    private val calculateResponsiveSizesUseCase: CalculateResponsiveSizesUseCase = CalculateResponsiveSizesUseCase(),
    private val edgePageDragTransferUseCase: EdgePageDragTransferUseCase = EdgePageDragTransferUseCase(),
    private val gridCollisionResolver: GridCollisionResolver = GridCollisionResolver()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeckUiState())
    val uiState: StateFlow<DeckUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.pagesFlow.collect { pages ->
                _uiState.update { current ->
                    val safePages = pages.ifEmpty { listOf(DeckPage(pageIndex = 0, customLabel = null)) }
                    val clampedIndex = if (current.activePageIndex in safePages.indices) {
                        current.activePageIndex
                    } else {
                        (safePages.size - 1).coerceAtLeast(0)
                    }
                    current.copy(
                        pages = safePages,
                        activePageIndex = clampedIndex
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.widgetsFlow.collect { widgets ->
                _uiState.update { current ->
                    current.copy(widgets = widgets)
                }
            }
        }

        viewModelScope.launch {
            repository.settingsFlow.collect { settings ->
                _uiState.update { current ->
                    current.copy(settings = settings)
                }
            }
        }
    }

    // --- Overlay Lifecycle & Startup ---

    fun onOverlayShown() {
        viewModelScope.launch {
            reconcileOrphanWidgetsUseCase()
            val profile = _uiState.value.gridProfile
            val catalog = widgetHostGateway.getInstalledCatalog(profile)
            val settings = _uiState.value.settings
            val pages = _uiState.value.pages
            val targetPage = if (settings.openOnPageOne) {
                0
            } else {
                settings.lastViewedPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            }
            _uiState.update { current ->
                current.copy(
                    catalogItems = catalog,
                    activePageIndex = targetPage,
                    isLaunchingInternalFlow = false,
                    pendingWidgetAction = null
                )
            }
        }
    }

    // --- Page Navigation ---

    fun navigatePreviousPage() {
        val pages = _uiState.value.pages
        if (pages.size <= 1) return
        val currentIndex = _uiState.value.activePageIndex
        val newIndex = if (currentIndex <= 0) pages.lastIndex else currentIndex - 1
        _uiState.update { it.copy(activePageIndex = newIndex) }
        viewModelScope.launch {
            repository.updateSettings { it.copy(lastViewedPageIndex = newIndex) }
        }
    }

    fun navigateNextPage() {
        val pages = _uiState.value.pages
        if (pages.size <= 1) return
        val currentIndex = _uiState.value.activePageIndex
        val newIndex = if (currentIndex >= pages.lastIndex) 0 else currentIndex + 1
        _uiState.update { it.copy(activePageIndex = newIndex) }
        viewModelScope.launch {
            repository.updateSettings { it.copy(lastViewedPageIndex = newIndex) }
        }
    }

    fun selectPage(index: Int) {
        val pages = _uiState.value.pages
        if (index in pages.indices) {
            _uiState.update { it.copy(activePageIndex = index) }
            viewModelScope.launch {
                repository.updateSettings { it.copy(lastViewedPageIndex = index) }
            }
        }
    }

    fun jumpToPageByNumberKey(numberKey: Int) {
        val targetIndex = when (numberKey) {
            in 1..9 -> numberKey - 1
            0 -> 9
            else -> return
        }
        val pages = _uiState.value.pages
        if (targetIndex in pages.indices) {
            _uiState.update { it.copy(activePageIndex = targetIndex) }
            viewModelScope.launch {
                repository.updateSettings { it.copy(lastViewedPageIndex = targetIndex) }
            }
        }
    }

    // --- Inline Page Pill Label Editing ---

    fun startEditingPageLabel(pageIndex: Int) {
        val page = _uiState.value.pages.getOrNull(pageIndex) ?: return
        _uiState.update {
            it.copy(
                editingPageLabelIndex = pageIndex,
                editingPageLabelDraft = page.customLabel.orEmpty()
            )
        }
    }

    fun updateEditingPageLabelDraft(draft: String) {
        _uiState.update { it.copy(editingPageLabelDraft = draft) }
    }

    fun commitPageLabel(pageIndex: Int, rawText: String) {
        val cleanLabel = rawText.trim().takeIf { it.isNotEmpty() }
        _uiState.update {
            it.copy(
                editingPageLabelIndex = null,
                editingPageLabelDraft = ""
            )
        }
        viewModelScope.launch {
            repository.updatePageLabel(pageIndex, cleanLabel)
        }
    }

    fun cancelEditingPageLabel() {
        _uiState.update {
            it.copy(
                editingPageLabelIndex = null,
                editingPageLabelDraft = ""
            )
        }
    }

    // --- Widget Placement ---

    fun addWidgetAutoPlace(item: WidgetCatalogItem) {
        val profile = _uiState.value.gridProfile
        val activePage = _uiState.value.activePageIndex
        val activeWidgets = _uiState.value.activePageWidgets

        val spot = gridCollisionResolver.findFirstAvailableSpot(
            spanX = item.defaultSpanX,
            spanY = item.defaultSpanY,
            existingOnPage = activeWidgets,
            columns = profile.columns,
            rows = profile.rows
        )

        val targetPageIndex: Int
        val targetCellX: Int
        val targetCellY: Int

        if (spot != null) {
            targetPageIndex = activePage
            targetCellX = spot.first
            targetCellY = spot.second
        } else {
            // Active page is full -> dynamically place onto new page at (0, 0)
            targetPageIndex = _uiState.value.pages.size
            targetCellX = 0
            targetCellY = 0
        }

        val appWidgetId = widgetHostGateway.allocateAppWidgetId()
        val candidate = PlacedWidget(
            appWidgetId = appWidgetId,
            providerPackage = item.providerPackage,
            providerClass = item.providerClass,
            pageIndex = targetPageIndex,
            cellX = targetCellX,
            cellY = targetCellY,
            spanX = item.defaultSpanX,
            spanY = item.defaultSpanY,
            minSpanX = item.minSpanX,
            minSpanY = item.minSpanY,
            maxSpanX = item.maxSpanX,
            maxSpanY = item.maxSpanY,
            resizeMode = item.resizeMode,
            isReconfigurable = item.isReconfigurable,
            label = item.widgetLabel
        )

        proceedWidgetPlacement(candidate)
    }

    fun addWidgetAtCell(item: WidgetCatalogItem, pageIndex: Int, cellX: Int, cellY: Int) {
        dropCatalogWidgetAtCell(item, pageIndex, cellX, cellY)
    }

    fun dropCatalogWidgetAtCell(
        item: WidgetCatalogItem,
        pageIndex: Int,
        cellX: Int,
        cellY: Int
    ) {
        val profile = _uiState.value.gridProfile
        val appWidgetId = widgetHostGateway.allocateAppWidgetId()
        val candidate = PlacedWidget(
            appWidgetId = appWidgetId,
            providerPackage = item.providerPackage,
            providerClass = item.providerClass,
            pageIndex = pageIndex,
            cellX = cellX,
            cellY = cellY,
            spanX = item.defaultSpanX,
            spanY = item.defaultSpanY,
            minSpanX = item.minSpanX,
            minSpanY = item.minSpanY,
            maxSpanX = item.maxSpanX,
            maxSpanY = item.maxSpanY,
            resizeMode = item.resizeMode,
            isReconfigurable = item.isReconfigurable,
            label = item.widgetLabel
        )

        val existingOnPage = _uiState.value.widgets.filter { it.pageIndex == pageIndex }
        val result = gridCollisionResolver.resolvePlacement(candidate, existingOnPage, profile.columns, profile.rows)
        when (result) {
            is PlacementResult.Rejected -> {
                widgetHostGateway.deleteAppWidgetId(appWidgetId)
                _uiState.update { it.copy(draggingCatalogItem = null) }
            }
            is PlacementResult.Accepted -> {
                proceedWidgetPlacement(candidate, result.updatedWidgets)
            }
        }
    }

    private fun proceedWidgetPlacement(
        candidate: PlacedWidget,
        displacedWidgetsOnPage: List<PlacedWidget>? = null
    ) {
        val bound = widgetHostGateway.bindAppWidgetIdIfAllowed(
            candidate.appWidgetId,
            ComponentName(candidate.providerPackage, candidate.providerClass)
        )

        if (!bound) {
            _uiState.update {
                it.copy(
                    isLaunchingInternalFlow = true,
                    pendingWidgetAction = PendingWidgetAction.RequestBind(
                        appWidgetId = candidate.appWidgetId,
                        providerPackage = candidate.providerPackage,
                        providerClass = candidate.providerClass,
                        candidateWidget = candidate
                    )
                )
            }
            return
        }

        if (widgetHostGateway.hasConfigurationActivity(candidate.appWidgetId)) {
            _uiState.update {
                it.copy(
                    isLaunchingInternalFlow = true,
                    pendingWidgetAction = PendingWidgetAction.RequestConfigure(
                        appWidgetId = candidate.appWidgetId,
                        candidateWidget = candidate
                    )
                )
            }
            return
        }

        finalizeWidgetPlacement(candidate, displacedWidgetsOnPage)
    }

    fun onBindWidgetResult(appWidgetId: Int, granted: Boolean) {
        val pending = _uiState.value.pendingWidgetAction
        if (pending !is PendingWidgetAction.RequestBind || pending.appWidgetId != appWidgetId) {
            return
        }

        if (!granted) {
            widgetHostGateway.deleteAppWidgetId(appWidgetId)
            _uiState.update {
                it.copy(
                    isLaunchingInternalFlow = false,
                    pendingWidgetAction = null
                )
            }
            return
        }

        val candidate = pending.candidateWidget
        if (widgetHostGateway.hasConfigurationActivity(appWidgetId)) {
            _uiState.update {
                it.copy(
                    isLaunchingInternalFlow = true,
                    pendingWidgetAction = PendingWidgetAction.RequestConfigure(
                        appWidgetId = appWidgetId,
                        candidateWidget = candidate
                    )
                )
            }
        } else {
            finalizeWidgetPlacement(candidate)
        }
    }

    fun onConfigureWidgetResult(appWidgetId: Int, resultOk: Boolean) {
        val pending = _uiState.value.pendingWidgetAction
        if (pending !is PendingWidgetAction.RequestConfigure || pending.appWidgetId != appWidgetId) {
            return
        }

        if (!resultOk) {
            widgetHostGateway.deleteAppWidgetId(appWidgetId)
            _uiState.update {
                it.copy(
                    isLaunchingInternalFlow = false,
                    pendingWidgetAction = null
                )
            }
            return
        }

        finalizeWidgetPlacement(pending.candidateWidget)
    }

    private fun finalizeWidgetPlacement(
        candidate: PlacedWidget,
        displacedWidgetsOnPage: List<PlacedWidget>? = null
    ) {
        val profile = _uiState.value.gridProfile
        val sizes = calculateResponsiveSizesUseCase(candidate.spanX, candidate.spanY, profile)
        widgetHostGateway.updateAppWidgetSizes(
            appWidgetId = candidate.appWidgetId,
            sizesDp = sizes.responsiveSizes,
            minWidthDp = sizes.minWidthDp,
            minHeightDp = sizes.minHeightDp,
            maxWidthDp = sizes.maxWidthDp,
            maxHeightDp = sizes.maxHeightDp
        )

        val currentPages = _uiState.value.pages
        val targetPageIndex = candidate.pageIndex

        if (targetPageIndex >= currentPages.size) {
            val newPages = currentPages.toMutableList()
            while (newPages.size <= targetPageIndex) {
                newPages.add(DeckPage(pageIndex = newPages.size, customLabel = null))
            }
            val updatedWidgets = if (displacedWidgetsOnPage != null) {
                _uiState.value.widgets.filter { it.pageIndex != targetPageIndex } + displacedWidgetsOnPage
            } else {
                _uiState.value.widgets + candidate
            }
            _uiState.update { current ->
                current.copy(
                    pages = newPages,
                    widgets = updatedWidgets,
                    activePageIndex = targetPageIndex,
                    isWidgetPickerOpen = false,
                    draggingCatalogItem = null,
                    isLaunchingInternalFlow = false,
                    pendingWidgetAction = null
                )
            }
            viewModelScope.launch {
                repository.replaceDeckState(newPages, updatedWidgets)
            }
        } else {
            val updatedWidgets = if (displacedWidgetsOnPage != null) {
                val others = _uiState.value.widgets.filter { it.pageIndex != targetPageIndex }
                others + displacedWidgetsOnPage
            } else {
                _uiState.value.widgets.filter { it.appWidgetId != candidate.appWidgetId } + candidate
            }
            _uiState.update { current ->
                current.copy(
                    widgets = updatedWidgets,
                    activePageIndex = targetPageIndex,
                    isWidgetPickerOpen = false,
                    draggingCatalogItem = null,
                    isLaunchingInternalFlow = false,
                    pendingWidgetAction = null
                )
            }
            viewModelScope.launch {
                if (displacedWidgetsOnPage != null) {
                    repository.upsertWidgets(displacedWidgetsOnPage)
                } else {
                    repository.upsertWidget(candidate)
                }
            }
        }
    }

    // --- Widget Edit, Move, Resize, Edge-Drag & Remove ---

    fun enterWidgetEditMode(appWidgetId: Int) {
        _uiState.update { it.copy(editingWidgetId = appWidgetId) }
    }

    fun exitWidgetEditMode() {
        _uiState.update { it.copy(editingWidgetId = null) }
    }

    fun moveOrResizeWidget(
        appWidgetId: Int,
        newCellX: Int,
        newCellY: Int,
        newSpanX: Int,
        newSpanY: Int
    ) {
        val current = _uiState.value.widgets.find { it.appWidgetId == appWidgetId } ?: return
        val profile = _uiState.value.gridProfile
        val clampedSpanX = newSpanX.coerceIn(current.minSpanX, current.maxSpanX).coerceIn(1, profile.columns)
        val clampedSpanY = newSpanY.coerceIn(current.minSpanY, current.maxSpanY).coerceIn(1, profile.rows)
        val clampedCellX = newCellX.coerceIn(0, profile.columns - clampedSpanX)
        val clampedCellY = newCellY.coerceIn(0, profile.rows - clampedSpanY)

        val target = current.copy(
            cellX = clampedCellX,
            cellY = clampedCellY,
            spanX = clampedSpanX,
            spanY = clampedSpanY
        )

        val pageWidgets = _uiState.value.widgets.filter { it.pageIndex == current.pageIndex }
        val result = gridCollisionResolver.resolvePlacement(target, pageWidgets, profile.columns, profile.rows)

        if (result is PlacementResult.Accepted) {
            val otherWidgets = _uiState.value.widgets.filter { it.pageIndex != current.pageIndex }
            val allWidgets = otherWidgets + result.updatedWidgets
            _uiState.update { it.copy(widgets = allWidgets) }
            viewModelScope.launch {
                repository.upsertWidgets(result.updatedWidgets)
            }
            for (w in result.updatedWidgets) {
                val sizes = calculateResponsiveSizesUseCase(w.spanX, w.spanY, profile)
                widgetHostGateway.updateAppWidgetSizes(
                    appWidgetId = w.appWidgetId,
                    sizesDp = sizes.responsiveSizes,
                    minWidthDp = sizes.minWidthDp,
                    minHeightDp = sizes.minHeightDp,
                    maxWidthDp = sizes.maxWidthDp,
                    maxHeightDp = sizes.maxHeightDp
                )
            }
        }
    }

    fun dragWidgetAcrossEdge(
        appWidgetId: Int,
        direction: EdgeDirection,
        targetCellX: Int,
        targetCellY: Int
    ) {
        val state = _uiState.value
        val result = edgePageDragTransferUseCase.transferAcrossEdge(
            widgetId = appWidgetId,
            direction = direction,
            targetCellX = targetCellX,
            targetCellY = targetCellY,
            currentPages = state.pages,
            currentWidgets = state.widgets,
            gridProfile = state.gridProfile
        )

        if (result is EdgeTransferResult.Success) {
            val targetIndex = result.targetPageIndex.coerceIn(0, result.pages.lastIndex)
            _uiState.update {
                it.copy(
                    pages = result.pages,
                    widgets = result.widgets,
                    activePageIndex = targetIndex
                )
            }
            viewModelScope.launch {
                repository.replaceDeckState(result.pages, result.widgets)
            }
            val transferred = result.widgets.find { it.appWidgetId == appWidgetId }
            if (transferred != null) {
                val sizes = calculateResponsiveSizesUseCase(transferred.spanX, transferred.spanY, state.gridProfile)
                widgetHostGateway.updateAppWidgetSizes(
                    appWidgetId = transferred.appWidgetId,
                    sizesDp = sizes.responsiveSizes,
                    minWidthDp = sizes.minWidthDp,
                    minHeightDp = sizes.minHeightDp,
                    maxWidthDp = sizes.maxWidthDp,
                    maxHeightDp = sizes.maxHeightDp
                )
            }
        }
    }

    fun removeWidget(appWidgetId: Int) {
        widgetHostGateway.deleteAppWidgetId(appWidgetId)
        _uiState.update { current ->
            val remainingWidgets = current.widgets.filter { it.appWidgetId != appWidgetId }
            val (prunedPages, reindexedWidgets) = EdgePageDragTransferUseCase.pruneAndReindexPages(
                current.pages,
                remainingWidgets
            )
            val newActivePage = current.activePageIndex.coerceIn(0, prunedPages.lastIndex)
            val newEditingId = if (current.editingWidgetId == appWidgetId) null else current.editingWidgetId
            current.copy(
                pages = prunedPages,
                widgets = reindexedWidgets,
                activePageIndex = newActivePage,
                editingWidgetId = newEditingId
            )
        }
        viewModelScope.launch {
            repository.removeWidget(appWidgetId)
            repository.pruneEmptyPagesAndReindex()
        }
    }

    fun deleteSelectedWidget() {
        val selectedId = _uiState.value.editingWidgetId ?: return
        removeWidget(selectedId)
    }

    // --- Overlay Auto-Dismiss & Escape Key Decisions ---

    fun shouldAutoDismissOnUserLeave(): Boolean {
        return !_uiState.value.isLaunchingInternalFlow
    }

    fun handleEscapeKey(): Boolean {
        val current = _uiState.value
        return when {
            current.editingPageLabelIndex != null -> {
                cancelEditingPageLabel()
                true
            }
            current.isOverlaySettingsOpen -> {
                closeOverlaySettings()
                true
            }
            current.isWidgetPickerOpen -> {
                closeWidgetPicker()
                true
            }
            current.contextMenuPosition != null -> {
                closeContextMenu()
                true
            }
            current.editingWidgetId != null -> {
                exitWidgetEditMode()
                true
            }
            else -> false
        }
    }

    // --- Context Menu, Settings & Picker UI Actions ---

    fun openContextMenu(position: Pair<Float, Float>) {
        _uiState.update { it.copy(contextMenuPosition = position) }
    }

    fun closeContextMenu() {
        _uiState.update { it.copy(contextMenuPosition = null) }
    }

    fun openWidgetPicker() {
        _uiState.update {
            it.copy(
                isWidgetPickerOpen = true,
                contextMenuPosition = null,
                widgetSearchQuery = ""
            )
        }
        refreshCatalog()
    }

    fun closeWidgetPicker() {
        _uiState.update {
            it.copy(
                isWidgetPickerOpen = false,
                widgetSearchQuery = "",
                draggingCatalogItem = null
            )
        }
    }

    fun updateWidgetSearchQuery(query: String) {
        _uiState.update { it.copy(widgetSearchQuery = query) }
    }

    fun openOverlaySettings() {
        _uiState.update {
            it.copy(
                isOverlaySettingsOpen = true,
                contextMenuPosition = null
            )
        }
    }

    fun closeOverlaySettings() {
        _uiState.update { it.copy(isOverlaySettingsOpen = false) }
    }

    fun updateSettings(transform: (OverlaySettings) -> OverlaySettings) {
        viewModelScope.launch {
            repository.updateSettings(transform)
        }
    }

    fun updateGridProfile(profile: DesktopGridProfile) {
        _uiState.update { it.copy(gridProfile = profile) }
    }

    fun refreshCatalog() {
        val catalog = widgetHostGateway.getInstalledCatalog(_uiState.value.gridProfile)
        _uiState.update { it.copy(catalogItems = catalog) }
    }

    fun startCatalogDragOut(item: WidgetCatalogItem) {
        _uiState.update { it.copy(draggingCatalogItem = item) }
    }

    fun clearDraggingCatalogItem() {
        _uiState.update { it.copy(draggingCatalogItem = null) }
    }
}
