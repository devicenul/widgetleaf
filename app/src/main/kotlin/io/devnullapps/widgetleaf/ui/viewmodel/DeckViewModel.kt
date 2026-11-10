package io.devnullapps.widgetleaf.ui.viewmodel

import android.content.ComponentName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.GridBounds
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.domain.repository.WidgetDeckRepository
import io.devnullapps.widgetleaf.domain.usecase.CalculateResponsiveSizesUseCase
import io.devnullapps.widgetleaf.domain.usecase.CascadeDisplacementResolver
import io.devnullapps.widgetleaf.domain.usecase.EdgeDirection
import io.devnullapps.widgetleaf.domain.usecase.EdgePageDragTransferUseCase
import io.devnullapps.widgetleaf.domain.usecase.EdgeTransferResult
import io.devnullapps.widgetleaf.domain.usecase.GridCollisionResolver
import io.devnullapps.widgetleaf.domain.usecase.ReconcileOrphanWidgetsUseCase
import io.devnullapps.widgetleaf.platform.WidgetHostGateway
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val cascadeDisplacementResolver: CascadeDisplacementResolver = CascadeDisplacementResolver(),
    private val gridCollisionResolver: GridCollisionResolver = GridCollisionResolver()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeckUiState())
    val uiState: StateFlow<DeckUiState> = _uiState.asStateFlow()

    private var dragSnapshotWidgets: List<PlacedWidget>? = null
    private var dragSnapshotPages: List<DeckPage>? = null
    private var dragSnapshotPageCount: Int = 0
    private var dragStartPageIndex: Int? = null
    private var dragDebounceJob: Job? = null
    private var draggingWidgetStartCell: Pair<Int, Int>? = null
    private var resizingWidgetStartBounds: GridBounds? = null
    private var dragSessionToken: Long = 0L

    private var isFirstWidgetsEmission: Boolean = true
    private var lastSyncedProfile: DesktopGridProfile? = null
    private val lastSyncedWidgetSpans = mutableMapOf<Int, Pair<Int, Int>>()
    private var rawPersistedWidgets: List<PlacedWidget> = emptyList()
    private var rawPersistedPages: List<DeckPage> = listOf(DeckPage(pageIndex = 0, customLabel = null))

    private fun syncWidgetSizes(widget: PlacedWidget, profile: DesktopGridProfile) {
        val sizes = calculateResponsiveSizesUseCase(widget.spanX, widget.spanY, profile)
        widgetHostGateway.updateAppWidgetSizes(
            appWidgetId = widget.appWidgetId,
            sizesDp = sizes.responsiveSizes,
            minWidthDp = sizes.minWidthDp,
            minHeightDp = sizes.minHeightDp,
            maxWidthDp = sizes.maxWidthDp,
            maxHeightDp = sizes.maxHeightDp
        )
        lastSyncedWidgetSpans[widget.appWidgetId] = Pair(widget.spanX, widget.spanY)
    }

    init {
        viewModelScope.launch {
            repository.pagesFlow.collect { pages ->
                val safePages = pages.ifEmpty { listOf(DeckPage(pageIndex = 0, customLabel = null)) }
                rawPersistedPages = safePages
                val profile = _uiState.value.gridProfile
                val (displayedPages, displayedWidgets) = deriveDisplayedState(
                    rawPersistedWidgets,
                    rawPersistedPages,
                    profile
                )
                _uiState.update { current ->
                    val clampedIndex = if (current.activePageIndex in displayedPages.indices) {
                        current.activePageIndex
                    } else {
                        (displayedPages.size - 1).coerceAtLeast(0)
                    }
                    current.copy(
                        pages = displayedPages,
                        widgets = displayedWidgets,
                        activePageIndex = clampedIndex
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.widgetsFlow.collect { widgets ->
                rawPersistedWidgets = widgets
                val profile = _uiState.value.gridProfile
                val (displayedPages, displayedWidgets) = deriveDisplayedState(
                    rawPersistedWidgets,
                    rawPersistedPages,
                    profile
                )
                if (isFirstWidgetsEmission) {
                    if (widgets.isNotEmpty()) {
                        isFirstWidgetsEmission = false
                    }
                    lastSyncedProfile = profile
                    for (w in displayedWidgets) {
                        syncWidgetSizes(w, profile)
                    }
                } else {
                    val profileChanged = profile != lastSyncedProfile
                    for (w in displayedWidgets) {
                        val previousSpan = lastSyncedWidgetSpans[w.appWidgetId]
                        val isNew = previousSpan == null
                        val spanChanged = previousSpan != Pair(w.spanX, w.spanY)
                        if (profileChanged || isNew || spanChanged) {
                            syncWidgetSizes(w, profile)
                        }
                    }
                    val currentIds = displayedWidgets.map { it.appWidgetId }.toSet()
                    lastSyncedWidgetSpans.keys.retainAll(currentIds)
                    if (profileChanged) {
                        lastSyncedProfile = profile
                    }
                }
                _uiState.update { current ->
                    current.copy(
                        widgets = displayedWidgets,
                        pages = displayedPages
                    )
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
        cancelWidgetDrag()
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

            // Refresh stored widget limits from installed provider info where available
            val catalogMap = catalog.associateBy { it.providerPackage to it.providerClass }
            val currentWidgets = _uiState.value.widgets
            val refreshedWidgets = currentWidgets.map { w ->
                val provider = catalogMap[w.providerPackage to w.providerClass]
                if (provider != null) {
                    w.copy(
                        minSpanX = provider.minSpanX,
                        minSpanY = provider.minSpanY,
                        maxSpanX = provider.maxSpanX,
                        maxSpanY = provider.maxSpanY,
                        resizeMode = provider.resizeMode,
                        isReconfigurable = provider.isReconfigurable,
                        label = if (w.label.isBlank()) provider.widgetLabel else w.label
                    )
                } else {
                    w
                }
            }
            if (refreshedWidgets != currentWidgets) {
                repository.upsertWidgets(refreshedWidgets)
            }

            for (w in refreshedWidgets) {
                syncWidgetSizes(w, profile)
            }
            lastSyncedProfile = profile

            _uiState.update { current ->
                current.copy(
                    widgets = refreshedWidgets,
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
        cancelWidgetDrag()
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
        cancelWidgetDrag()
        val pages = _uiState.value.pages
        if (pages.size <= 1) return
        val currentIndex = _uiState.value.activePageIndex
        val newIndex = if (currentIndex >= pages.lastIndex) 0 else currentIndex + 1
        _uiState.update { it.copy(activePageIndex = newIndex) }
        viewModelScope.launch {
            repository.updateSettings { it.copy(lastViewedPageIndex = newIndex) }
        }
    }

    /**
     * Navigates to the previous page triggered by a touch or trackpad swipe gesture.
     * Enforces strict non-wrapping boundaries (stops at Page 0).
     */
    fun swipePreviousPage(): Boolean {
        cancelWidgetDrag()
        val pages = _uiState.value.pages
        val currentIndex = _uiState.value.activePageIndex
        if (currentIndex > 0) {
            val newIndex = currentIndex - 1
            _uiState.update { it.copy(activePageIndex = newIndex) }
            viewModelScope.launch {
                repository.updateSettings { it.copy(lastViewedPageIndex = newIndex) }
            }
            return true
        }
        return false
    }

    /**
     * Navigates to the next page triggered by a touch or trackpad swipe gesture.
     * Enforces strict non-wrapping boundaries and does not synthesize speculative pages.
     */
    fun swipeNextPage(): Boolean {
        cancelWidgetDrag()
        val pages = _uiState.value.pages
        val currentIndex = _uiState.value.activePageIndex
        if (currentIndex < pages.lastIndex) {
            val newIndex = currentIndex + 1
            _uiState.update { it.copy(activePageIndex = newIndex) }
            viewModelScope.launch {
                repository.updateSettings { it.copy(lastViewedPageIndex = newIndex) }
            }
            return true
        }
        return false
    }

    fun selectPage(index: Int) {
        cancelWidgetDrag()
        val pages = _uiState.value.pages
        if (index in pages.indices) {
            _uiState.update { it.copy(activePageIndex = index) }
            viewModelScope.launch {
                repository.updateSettings { it.copy(lastViewedPageIndex = index) }
            }
        }
    }

    fun jumpToPageByNumberKey(numberKey: Int) {
        cancelWidgetDrag()
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
        val clampedCellX = cellX.coerceIn(0, (profile.columns - item.defaultSpanX).coerceAtLeast(0))
        val clampedCellY = cellY.coerceIn(0, (profile.rows - item.defaultSpanY).coerceAtLeast(0))
        val candidate = PlacedWidget(
            appWidgetId = appWidgetId,
            providerPackage = item.providerPackage,
            providerClass = item.providerClass,
            pageIndex = pageIndex,
            cellX = clampedCellX,
            cellY = clampedCellY,
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

        val allWidgets = _uiState.value.widgets
        val effectivePageCount = maxOf(_uiState.value.pages.size, pageIndex + 1)
        val result = cascadeDisplacementResolver.resolve(
            dragged = candidate,
            allWidgets = allWidgets,
            columns = profile.columns,
            rows = profile.rows,
            pageCount = effectivePageCount
        )

        if (result == null) {
            widgetHostGateway.deleteAppWidgetId(appWidgetId)
            _uiState.update { it.copy(draggingCatalogItem = null) }
            return
        }

        proceedWidgetPlacement(candidate, result.widgets, result.pageCount)
    }

    private fun proceedWidgetPlacement(
        candidate: PlacedWidget,
        resolvedWidgets: List<PlacedWidget>? = null,
        resolvedPageCount: Int? = null
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
                        candidateWidget = candidate,
                        resolvedWidgets = resolvedWidgets,
                        resolvedPageCount = resolvedPageCount
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
                        candidateWidget = candidate,
                        resolvedWidgets = resolvedWidgets,
                        resolvedPageCount = resolvedPageCount
                    )
                )
            }
            return
        }

        finalizeWidgetPlacement(candidate, resolvedWidgets, resolvedPageCount)
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
        val resolvedWidgets = pending.resolvedWidgets
        val resolvedPageCount = pending.resolvedPageCount
        if (widgetHostGateway.hasConfigurationActivity(appWidgetId)) {
            _uiState.update {
                it.copy(
                    isLaunchingInternalFlow = true,
                    pendingWidgetAction = PendingWidgetAction.RequestConfigure(
                        appWidgetId = appWidgetId,
                        candidateWidget = candidate,
                        resolvedWidgets = resolvedWidgets,
                        resolvedPageCount = resolvedPageCount
                    )
                )
            }
        } else {
            finalizeWidgetPlacement(candidate, resolvedWidgets, resolvedPageCount)
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

        finalizeWidgetPlacement(pending.candidateWidget, pending.resolvedWidgets, pending.resolvedPageCount)
    }

    private fun finalizeWidgetPlacement(
        candidate: PlacedWidget,
        resolvedWidgets: List<PlacedWidget>? = null,
        resolvedPageCount: Int? = null
    ) {
        val profile = _uiState.value.gridProfile
        syncWidgetSizes(candidate, profile)

        val currentPages = _uiState.value.pages
        val targetPageIndex = candidate.pageIndex
        val requiredPageCount = maxOf(resolvedPageCount ?: (targetPageIndex + 1), currentPages.size)

        val newPages = currentPages.toMutableList()
        while (newPages.size < requiredPageCount) {
            newPages.add(DeckPage(pageIndex = newPages.size, customLabel = null))
        }

        val updatedWidgets = resolvedWidgets ?: (_uiState.value.widgets.filter { it.appWidgetId != candidate.appWidgetId } + candidate)

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
    }

    // --- Widget Edit, Move, Resize, Edge-Drag & Remove ---

    fun enterWidgetEditMode(appWidgetId: Int) {
        _uiState.update {
            it.copy(
                editingWidgetId = appWidgetId,
                contextMenuPosition = null
            )
        }
    }

    fun exitWidgetEditMode() {
        cancelWidgetDrag()
        _uiState.update { it.copy(editingWidgetId = null) }
    }

    fun markLaunchingInternalFlow(launching: Boolean) {
        _uiState.update { it.copy(isLaunchingInternalFlow = launching) }
    }

    // --- Drag Session API (Live Cascade Displacement Preview & Commit) ---

    fun startWidgetDrag(appWidgetId: Int) {
        dragDebounceJob?.cancel()
        dragDebounceJob = null
        dragSessionToken++
        val currentWidgets = _uiState.value.widgets
        val currentPages = _uiState.value.pages
        val dragged = currentWidgets.find { it.appWidgetId == appWidgetId }
        dragSnapshotWidgets = currentWidgets
        dragSnapshotPages = currentPages
        dragSnapshotPageCount = currentPages.size
        dragStartPageIndex = dragged?.pageIndex ?: _uiState.value.activePageIndex
        draggingWidgetStartCell = if (dragged != null) Pair(dragged.cellX, dragged.cellY) else null

        _uiState.update {
            it.copy(
                draggingWidgetId = appWidgetId,
                resizingWidgetId = null,
                dragStartPageIndex = dragStartPageIndex,
                previewWidgets = null,
                previewPageCount = null,
                displacedWidgetIds = emptySet(),
                hoveredCell = null
            )
        }
    }

    fun startWidgetResize(appWidgetId: Int) {
        dragDebounceJob?.cancel()
        dragDebounceJob = null
        dragSessionToken++
        val currentWidgets = _uiState.value.widgets
        val currentPages = _uiState.value.pages
        val target = currentWidgets.find { it.appWidgetId == appWidgetId }
        dragSnapshotWidgets = currentWidgets
        dragSnapshotPages = currentPages
        dragSnapshotPageCount = currentPages.size
        dragStartPageIndex = target?.pageIndex ?: _uiState.value.activePageIndex
        draggingWidgetStartCell = null
        resizingWidgetStartBounds = if (target != null) {
            GridBounds(target.cellX, target.cellY, target.spanX, target.spanY)
        } else null

        _uiState.update {
            it.copy(
                draggingWidgetId = null,
                resizingWidgetId = appWidgetId,
                dragStartPageIndex = dragStartPageIndex,
                previewWidgets = null,
                previewPageCount = null,
                displacedWidgetIds = emptySet(),
                hoveredCell = null
            )
        }
    }

    fun onWidgetResizeBoundsChanged(
        appWidgetId: Int,
        cellX: Int,
        cellY: Int,
        spanX: Int,
        spanY: Int
    ) {
        dragDebounceJob?.cancel()

        if (dragSnapshotWidgets == null) {
            startWidgetResize(appWidgetId)
        }

        val snapshot = dragSnapshotWidgets ?: return
        val orig = snapshot.find { it.appWidgetId == appWidgetId } ?: return
        val profile = _uiState.value.gridProfile

        val maxAvailableX = if (orig.maxSpanX in 1 until profile.columns) orig.maxSpanX else profile.columns
        val maxAvailableY = if (orig.maxSpanY in 1 until profile.rows) orig.maxSpanY else profile.rows
        val minAllowedX = orig.minSpanX.coerceIn(1, maxAvailableX)
        val minAllowedY = orig.minSpanY.coerceIn(1, maxAvailableY)

        val allowedSpanX = if ((orig.resizeMode and PlacedWidget.RESIZE_HORIZONTAL) != 0) spanX else orig.spanX
        val allowedSpanY = if ((orig.resizeMode and PlacedWidget.RESIZE_VERTICAL) != 0) spanY else orig.spanY

        val clampedSpanX = allowedSpanX.coerceIn(minAllowedX, maxAvailableX)
        val clampedSpanY = allowedSpanY.coerceIn(minAllowedY, maxAvailableY)
        val clampedCellX = cellX.coerceIn(0, (profile.columns - clampedSpanX).coerceAtLeast(0))
        val clampedCellY = cellY.coerceIn(0, (profile.rows - clampedSpanY).coerceAtLeast(0))

        val activePage = _uiState.value.activePageIndex
        val startPage = dragStartPageIndex ?: activePage

        val isOriginal = activePage == startPage &&
            clampedCellX == orig.cellX &&
            clampedCellY == orig.cellY &&
            clampedSpanX == orig.spanX &&
            clampedSpanY == orig.spanY

        if (isOriginal) {
            _uiState.update {
                it.copy(
                    previewWidgets = null,
                    previewPageCount = null,
                    displacedWidgetIds = emptySet()
                )
            }
            return
        }

        val targetBounds = GridBounds(clampedCellX, clampedCellY, clampedSpanX, clampedSpanY)
        val hasOverlap = snapshot.any {
            it.appWidgetId != appWidgetId && it.pageIndex == activePage && it.bounds.intersects(targetBounds)
        }

        if (!hasOverlap) {
            _uiState.update {
                it.copy(
                    previewWidgets = null,
                    previewPageCount = null,
                    displacedWidgetIds = emptySet()
                )
            }
            return
        }

        val sessionToken = dragSessionToken
        dragDebounceJob = viewModelScope.launch {
            delay(250L)
            if (dragSessionToken != sessionToken ||
                (_uiState.value.resizingWidgetId != appWidgetId && _uiState.value.draggingWidgetId != appWidgetId)) {
                return@launch
            }

            val currentSnapshot = dragSnapshotWidgets ?: return@launch
            val targetWidget = orig.copy(
                pageIndex = activePage,
                cellX = clampedCellX,
                cellY = clampedCellY,
                spanX = clampedSpanX,
                spanY = clampedSpanY
            )
            val pageCount = maxOf(dragSnapshotPageCount, activePage + 1)

            val result = cascadeDisplacementResolver.resolve(
                dragged = targetWidget,
                allWidgets = currentSnapshot,
                columns = profile.columns,
                rows = profile.rows,
                pageCount = pageCount
            )

            if (result != null) {
                _uiState.update { current ->
                    if (dragSessionToken != sessionToken ||
                        (current.resizingWidgetId != appWidgetId && current.draggingWidgetId != appWidgetId)) {
                        current
                    } else {
                        current.copy(
                            previewWidgets = result.widgets,
                            previewPageCount = maxOf(result.pageCount, current.previewPageCount ?: 0),
                            displacedWidgetIds = result.movedWidgetIds
                        )
                    }
                }
            }
        }
    }

    fun onEdgeDwellTriggered(direction: EdgeDirection) {
        val draggingId = _uiState.value.draggingWidgetId ?: return
        val currentPage = _uiState.value.activePageIndex
        val currentPages = _uiState.value.pages
        val targetPageIndex = when (direction) {
            EdgeDirection.LEFT -> {
                if (currentPage <= 0) return // Left stops at page 0
                currentPage - 1
            }
            EdgeDirection.RIGHT -> currentPage + 1
        }

        val newPageCount = maxOf(_uiState.value.effectivePageCount, targetPageIndex + 1)

        _uiState.update {
            it.copy(
                activePageIndex = targetPageIndex,
                previewPageCount = if (newPageCount > currentPages.size) newPageCount else it.previewPageCount
            )
        }

        // Re-trigger cascade displacement computation on the newly active page
        val currentHover = _uiState.value.hoveredCell ?: draggingWidgetStartCell ?: Pair(0, 0)
        onWidgetHoverCellChanged(draggingId, currentHover.first, currentHover.second)
    }

    fun onWidgetHoverCellChanged(appWidgetId: Int, cellX: Int, cellY: Int) {
        dragDebounceJob?.cancel()

        val activePage = _uiState.value.activePageIndex
        val startPage = dragStartPageIndex ?: activePage
        val startCell = draggingWidgetStartCell

        if (activePage == startPage && startCell != null && cellX == startCell.first && cellY == startCell.second) {
            // Hovered back on original start cell on original page: revert preview immediately
            _uiState.update {
                it.copy(
                    hoveredCell = Pair(cellX, cellY),
                    previewWidgets = null,
                    previewPageCount = null,
                    displacedWidgetIds = emptySet()
                )
            }
            return
        }

        _uiState.update {
            it.copy(hoveredCell = Pair(cellX, cellY))
        }

        val sessionToken = dragSessionToken
        dragDebounceJob = viewModelScope.launch {
            delay(250L)
            if (dragSessionToken != sessionToken || _uiState.value.draggingWidgetId != appWidgetId) {
                return@launch
            }

            val currentSnapshot = dragSnapshotWidgets ?: return@launch
            val orig = currentSnapshot.find { it.appWidgetId == appWidgetId } ?: return@launch
            val profile = _uiState.value.gridProfile
            val clampedCellX = cellX.coerceIn(0, (profile.columns - orig.spanX).coerceAtLeast(0))
            val clampedCellY = cellY.coerceIn(0, (profile.rows - orig.spanY).coerceAtLeast(0))
            val targetWidget = orig.copy(
                pageIndex = activePage,
                cellX = clampedCellX,
                cellY = clampedCellY
            )
            val pageCount = maxOf(dragSnapshotPageCount, activePage + 1)

            val result = cascadeDisplacementResolver.resolve(
                dragged = targetWidget,
                allWidgets = currentSnapshot,
                columns = profile.columns,
                rows = profile.rows,
                pageCount = pageCount
            )

            if (result != null) {
                _uiState.update { current ->
                    if (dragSessionToken != sessionToken || current.draggingWidgetId != appWidgetId) {
                        current
                    } else {
                        current.copy(
                            previewWidgets = result.widgets,
                            previewPageCount = maxOf(result.pageCount, current.previewPageCount ?: 0),
                            displacedWidgetIds = result.movedWidgetIds
                        )
                    }
                }
            }
        }
    }

    fun commitWidgetDrag(appWidgetId: Int, cellX: Int, cellY: Int) {
        dragDebounceJob?.cancel()
        dragDebounceJob = null
        dragSessionToken++

        val snapshot = dragSnapshotWidgets ?: _uiState.value.widgets
        val snapshotPageCount = if (dragSnapshotPageCount > 0) dragSnapshotPageCount else _uiState.value.pages.size
        dragSnapshotWidgets = null
        dragSnapshotPages = null
        dragSnapshotPageCount = 0
        dragStartPageIndex = null
        draggingWidgetStartCell = null
        resizingWidgetStartBounds = null

        val orig = snapshot.find { it.appWidgetId == appWidgetId }
        if (orig == null) {
            cancelWidgetDrag()
            return
        }

        val activePage = _uiState.value.activePageIndex
        val profile = _uiState.value.gridProfile
        val clampedCellX = cellX.coerceIn(0, (profile.columns - orig.spanX).coerceAtLeast(0))
        val clampedCellY = cellY.coerceIn(0, (profile.rows - orig.spanY).coerceAtLeast(0))
        val targetWidget = orig.copy(
            pageIndex = activePage,
            cellX = clampedCellX,
            cellY = clampedCellY
        )

        val result = cascadeDisplacementResolver.resolve(
            dragged = targetWidget,
            allWidgets = snapshot,
            columns = profile.columns,
            rows = profile.rows,
            pageCount = maxOf(snapshotPageCount, activePage + 1)
        )

        if (result != null) {
            // Prune unused speculative pages and trailing empties atomically
            val (prunedPages, prunedWidgets) = EdgePageDragTransferUseCase.pruneAndReindexPages(
                pages = _uiState.value.effectivePages,
                widgets = result.widgets
            )
            val finalPageIndex = prunedWidgets.find { it.appWidgetId == appWidgetId }?.pageIndex ?: activePage

            _uiState.update { current ->
                current.copy(
                    pages = prunedPages,
                    widgets = prunedWidgets,
                    activePageIndex = finalPageIndex.coerceIn(0, prunedPages.lastIndex),
                    previewWidgets = null,
                    previewPageCount = null,
                    displacedWidgetIds = emptySet(),
                    draggingWidgetId = null,
                    resizingWidgetId = null,
                    dragStartPageIndex = null,
                    hoveredCell = null
                )
            }
            viewModelScope.launch {
                repository.replaceDeckState(prunedPages, prunedWidgets)
            }
            val committed = prunedWidgets.find { it.appWidgetId == appWidgetId }
            if (committed != null) {
                syncWidgetSizes(committed, profile)
            }
        } else {
            cancelWidgetDrag()
        }
    }

    fun cancelWidgetDrag() {
        dragDebounceJob?.cancel()
        dragDebounceJob = null
        dragSessionToken++
        val restorePages = dragSnapshotPages ?: _uiState.value.pages
        val restorePageIndex = dragStartPageIndex ?: _uiState.value.activePageIndex
        dragSnapshotWidgets = null
        dragSnapshotPages = null
        dragSnapshotPageCount = 0
        dragStartPageIndex = null
        draggingWidgetStartCell = null
        resizingWidgetStartBounds = null

        _uiState.update { current ->
            current.copy(
                pages = restorePages,
                activePageIndex = restorePageIndex.coerceIn(0, restorePages.lastIndex),
                previewWidgets = null,
                previewPageCount = null,
                displacedWidgetIds = emptySet(),
                draggingWidgetId = null,
                resizingWidgetId = null,
                dragStartPageIndex = null,
                hoveredCell = null
            )
        }
    }

    fun cancelWidgetResize() = cancelWidgetDrag()

    fun moveWidget(
        appWidgetId: Int,
        newCellX: Int,
        newCellY: Int
    ) {
        val allWidgets = _uiState.value.widgets
        val current = allWidgets.find { it.appWidgetId == appWidgetId } ?: return
        val profile = _uiState.value.gridProfile
        val clampedCellX = newCellX.coerceIn(0, (profile.columns - current.spanX).coerceAtLeast(0))
        val clampedCellY = newCellY.coerceIn(0, (profile.rows - current.spanY).coerceAtLeast(0))

        val target = current.copy(
            cellX = clampedCellX,
            cellY = clampedCellY
            // spanX, spanY, min/maxSpan are NEVER modified on move
        )

        val result = cascadeDisplacementResolver.resolve(
            dragged = target,
            allWidgets = allWidgets,
            columns = profile.columns,
            rows = profile.rows,
            pageCount = _uiState.value.pages.size
        ) ?: return

        val currentPages = _uiState.value.pages
        val newPages = currentPages.toMutableList()
        while (newPages.size < result.pageCount) {
            newPages.add(DeckPage(pageIndex = newPages.size, customLabel = null))
        }

        _uiState.update {
            it.copy(
                pages = newPages,
                widgets = result.widgets,
                previewWidgets = null,
                previewPageCount = null,
                displacedWidgetIds = emptySet(),
                draggingWidgetId = null,
                hoveredCell = null
            )
        }
        viewModelScope.launch {
            repository.replaceDeckState(newPages, result.widgets)
        }
    }

    fun commitWidgetResize(
        appWidgetId: Int,
        cellX: Int,
        cellY: Int,
        spanX: Int,
        spanY: Int
    ) {
        dragDebounceJob?.cancel()
        dragDebounceJob = null
        dragSessionToken++

        val snapshot = dragSnapshotWidgets ?: _uiState.value.widgets
        val snapshotPageCount = if (dragSnapshotPageCount > 0) dragSnapshotPageCount else _uiState.value.pages.size
        dragSnapshotWidgets = null
        dragSnapshotPages = null
        dragSnapshotPageCount = 0
        dragStartPageIndex = null
        draggingWidgetStartCell = null
        resizingWidgetStartBounds = null

        val orig = snapshot.find { it.appWidgetId == appWidgetId }
        if (orig == null) {
            cancelWidgetDrag()
            return
        }

        val profile = _uiState.value.gridProfile

        // Max span is clamped to explicit provider maxSpan if declared (in 1 until profile columns/rows), else profile columns/rows
        val maxAvailableX = if (orig.maxSpanX in 1 until profile.columns) orig.maxSpanX else profile.columns
        val maxAvailableY = if (orig.maxSpanY in 1 until profile.rows) orig.maxSpanY else profile.rows

        val minAllowedX = orig.minSpanX.coerceIn(1, maxAvailableX)
        val minAllowedY = orig.minSpanY.coerceIn(1, maxAvailableY)

        val allowedSpanX = if ((orig.resizeMode and PlacedWidget.RESIZE_HORIZONTAL) != 0) spanX else orig.spanX
        val allowedSpanY = if ((orig.resizeMode and PlacedWidget.RESIZE_VERTICAL) != 0) spanY else orig.spanY

        val clampedSpanX = allowedSpanX.coerceIn(minAllowedX, maxAvailableX)
        val clampedSpanY = allowedSpanY.coerceIn(minAllowedY, maxAvailableY)
        val clampedCellX = cellX.coerceIn(0, (profile.columns - clampedSpanX).coerceAtLeast(0))
        val clampedCellY = cellY.coerceIn(0, (profile.rows - clampedSpanY).coerceAtLeast(0))

        val activePage = _uiState.value.activePageIndex
        val target = orig.copy(
            pageIndex = activePage,
            cellX = clampedCellX,
            cellY = clampedCellY,
            spanX = clampedSpanX,
            spanY = clampedSpanY
        )

        val result = cascadeDisplacementResolver.resolve(
            dragged = target,
            allWidgets = snapshot,
            columns = profile.columns,
            rows = profile.rows,
            pageCount = maxOf(snapshotPageCount, activePage + 1)
        )

        if (result != null) {
            val (prunedPages, prunedWidgets) = EdgePageDragTransferUseCase.pruneAndReindexPages(
                pages = _uiState.value.effectivePages,
                widgets = result.widgets
            )
            val finalPageIndex = prunedWidgets.find { it.appWidgetId == appWidgetId }?.pageIndex ?: activePage

            _uiState.update { current ->
                current.copy(
                    pages = prunedPages,
                    widgets = prunedWidgets,
                    activePageIndex = finalPageIndex.coerceIn(0, prunedPages.lastIndex),
                    previewWidgets = null,
                    previewPageCount = null,
                    displacedWidgetIds = emptySet(),
                    draggingWidgetId = null,
                    resizingWidgetId = null,
                    dragStartPageIndex = null,
                    hoveredCell = null
                )
            }
            viewModelScope.launch {
                repository.replaceDeckState(prunedPages, prunedWidgets)
            }
            val committed = prunedWidgets.find { it.appWidgetId == appWidgetId }
            if (committed != null) {
                syncWidgetSizes(committed, profile)
            }
        } else {
            cancelWidgetDrag()
        }
    }

    fun resizeWidget(
        appWidgetId: Int,
        newCellX: Int,
        newCellY: Int,
        newSpanX: Int,
        newSpanY: Int
    ) {
        commitWidgetResize(appWidgetId, cellX = newCellX, cellY = newCellY, spanX = newSpanX, spanY = newSpanY)
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

        when (result) {
            is EdgeTransferResult.Success -> {
                val targetIndex = result.targetPageIndex.coerceIn(0, result.pages.lastIndex)
                _uiState.update {
                    it.copy(
                        pages = result.pages,
                        widgets = result.widgets,
                        activePageIndex = targetIndex,
                        previewWidgets = null,
                        previewPageCount = null,
                        displacedWidgetIds = emptySet(),
                        draggingWidgetId = null,
                        hoveredCell = null
                    )
                }
                viewModelScope.launch {
                    repository.replaceDeckState(result.pages, result.widgets)
                }
                val transferred = result.widgets.find { it.appWidgetId == appWidgetId }
                if (transferred != null) {
                    syncWidgetSizes(transferred, state.gridProfile)
                }
            }
            is EdgeTransferResult.BlockedAtLeftEdge -> {
                val current = state.widgets.find { it.appWidgetId == appWidgetId }
                if (current != null) {
                    moveWidget(appWidgetId, 0, targetCellY)
                }
            }
            is EdgeTransferResult.RejectedFullPage -> Unit
        }
    }

    fun removeWidget(appWidgetId: Int) {
        cancelWidgetDrag()
        lastSyncedWidgetSpans.remove(appWidgetId)
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
            current.draggingWidgetId != null || current.resizingWidgetId != null || current.previewWidgets != null -> {
                cancelWidgetDrag()
                true
            }
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
        _uiState.update { current ->
            if (current.editingWidgetId != null ||
                current.isWidgetPickerOpen ||
                current.isOverlaySettingsOpen
            ) {
                current
            } else {
                current.copy(contextMenuPosition = position)
            }
        }
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
        if (profile == _uiState.value.gridProfile) return
        if (profile.columns <= 0 || profile.rows <= 0 ||
            profile.cellWidthDp <= 10f || profile.cellHeightDp <= 10f ||
            profile.availableWidthDp <= 10f || profile.availableHeightDp <= 10f) {
            return
        }

        val (displayedPages, displayedWidgets) = deriveDisplayedState(
            rawPersistedWidgets,
            rawPersistedPages,
            profile
        )

        val safeActivePageIndex = if (_uiState.value.activePageIndex in displayedPages.indices) {
            _uiState.value.activePageIndex
        } else {
            (displayedPages.size - 1).coerceAtLeast(0)
        }

        _uiState.update {
            it.copy(
                gridProfile = profile,
                widgets = displayedWidgets,
                pages = displayedPages,
                activePageIndex = safeActivePageIndex
            )
        }
        lastSyncedProfile = profile
        for (w in displayedWidgets) {
            syncWidgetSizes(w, profile)
        }
    }

    private fun deriveDisplayedState(
        widgets: List<PlacedWidget>,
        pages: List<DeckPage>,
        profile: DesktopGridProfile
    ): Pair<List<DeckPage>, List<PlacedWidget>> {
        val needsReflow = widgets.any { w ->
            w.cellX + w.spanX > profile.columns ||
            w.cellY + w.spanY > profile.rows ||
            w.spanX > profile.columns ||
            w.spanY > profile.rows
        }

        val reflowedWidgets = if (needsReflow) {
            reflowWidgetsToFitGrid(widgets, pages, profile)
        } else {
            widgets
        }

        val newPageCount = maxOf(pages.size, (reflowedWidgets.maxOfOrNull { it.pageIndex } ?: 0) + 1)
        val reflowedPages = if (newPageCount > pages.size) {
            val extended = pages.toMutableList()
            for (p in pages.size until newPageCount) {
                extended.add(DeckPage(pageIndex = p, customLabel = null))
            }
            extended
        } else {
            pages
        }
        return Pair(reflowedPages, reflowedWidgets)
    }

    private fun reflowWidgetsToFitGrid(
        widgets: List<PlacedWidget>,
        pages: List<DeckPage>,
        profile: DesktopGridProfile
    ): List<PlacedWidget> {
        val settled = mutableListOf<PlacedWidget>()
        val sortedWidgets = widgets.sortedWith(compareBy({ it.pageIndex }, { it.cellY }, { it.cellX }))

        for (w in sortedWidgets) {
            val clampedSpanX = w.spanX.coerceAtMost(profile.columns).coerceAtLeast(1)
            val clampedSpanY = w.spanY.coerceAtMost(profile.rows).coerceAtLeast(1)

            val candidateBounds = GridBounds(w.cellX, w.cellY, clampedSpanX, clampedSpanY)
            val settledOnSamePage = settled.filter { it.pageIndex == w.pageIndex }
            val fitsAtCurrent = candidateBounds.isWithin(profile.columns, profile.rows) &&
                settledOnSamePage.none { it.bounds.intersects(candidateBounds) }

            if (fitsAtCurrent) {
                settled.add(w.copy(spanX = clampedSpanX, spanY = clampedSpanY))
            } else {
                var targetPage = w.pageIndex
                var placed = false
                while (!placed) {
                    val settledOnTarget = settled.filter { it.pageIndex == targetPage }
                    val spot = gridCollisionResolver.findFirstAvailableSpot(
                        spanX = clampedSpanX,
                        spanY = clampedSpanY,
                        existingOnPage = settledOnTarget,
                        columns = profile.columns,
                        rows = profile.rows
                    )
                    if (spot != null) {
                        settled.add(
                            w.copy(
                                pageIndex = targetPage,
                                cellX = spot.first,
                                cellY = spot.second,
                                spanX = clampedSpanX,
                                spanY = clampedSpanY
                            )
                        )
                        placed = true
                    } else {
                        targetPage++
                    }
                }
            }
        }
        return settled
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
