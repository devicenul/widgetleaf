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
import io.devnullapps.widgetleaf.platform.DesktopGridProfileResolver
import io.devnullapps.widgetleaf.platform.WidgetHostGateway
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var draggingWidgetDisplayedStartCell: Pair<Int, Int>? = null
    private var resizingWidgetStartBounds: GridBounds? = null
    private var resizingWidgetDisplayedStartBounds: GridBounds? = null
    private var dragSessionToken: Long = 0L

    private var isFirstWidgetsEmission: Boolean = true
    private var lastSyncedProfile: DesktopGridProfile? = null
    private val lastSyncedWidgetSpans = mutableMapOf<Int, Pair<Int, Int>>()
    private var rawPersistedWidgets: List<PlacedWidget> = emptyList()
    private var rawPersistedPages: List<DeckPage> = listOf(DeckPage(pageIndex = 0, customLabel = null))
    private var lastGoodProfile: DesktopGridProfile? = null
    private var persistedLastGoodGridSize: Pair<Int, Int>? = null
    private val settingsUpdateMutex = Mutex()
    private var pendingSettingsMutations: Int = 0

    private data class ResolvedCascadeState(
        val rawWidgets: List<PlacedWidget>,
        val rawPages: List<DeckPage>,
        val displayedPages: List<DeckPage>,
        val displayedWidgets: List<PlacedWidget>,
        val movedWidgetIds: Set<Int>
    )

    private fun minimumRawBoundingSize(widgets: List<PlacedWidget>): Pair<Int, Int> {
        if (widgets.isEmpty()) return Pair(0, 0)
        val maxCol = widgets.maxOf { (it.cellX + it.spanX).coerceAtLeast(1) }
        val maxRow = widgets.maxOf { (it.cellY + it.spanY).coerceAtLeast(1) }
        return Pair(maxCol, maxRow)
    }

    private fun buildBaselineProfile(
        columns: Int,
        rows: Int,
        reference: DesktopGridProfile
    ): DesktopGridProfile {
        return reference.copy(
            columns = maxOf(columns, reference.columns),
            rows = maxOf(rows, reference.rows),
            cellWidthDp = maxOf(reference.cellWidthDp, DesktopGridProfileResolver.DEFAULT_CELL_WIDTH_DP),
            cellHeightDp = maxOf(reference.cellHeightDp, DesktopGridProfileResolver.DEFAULT_CELL_HEIGHT_DP),
            gutterDp = maxOf(reference.gutterDp, DesktopGridProfileResolver.DEFAULT_GUTTER_DP)
        )
    }

    internal fun getEffectiveGridProfile(currentProfile: DesktopGridProfile): DesktopGridProfile {
        val good = lastGoodProfile
        val saved = persistedLastGoodGridSize
        val rawNeedsReflow = widgetsNeedReflow(rawPersistedWidgets, currentProfile)
        val rawBounds = minimumRawBoundingSize(rawPersistedWidgets)

        var bestCols = currentProfile.columns
        var bestRows = currentProfile.rows
        var cellW = currentProfile.cellWidthDp
        var cellH = currentProfile.cellHeightDp
        var gutter = currentProfile.gutterDp

        if (good != null && ((good.columns >= currentProfile.columns && good.rows >= currentProfile.rows) || rawNeedsReflow)) {
            bestCols = maxOf(bestCols, good.columns)
            bestRows = maxOf(bestRows, good.rows)
            cellW = maxOf(cellW, good.cellWidthDp)
            cellH = maxOf(cellH, good.cellHeightDp)
            gutter = maxOf(gutter, good.gutterDp)
        }
        if (saved != null && ((saved.first >= currentProfile.columns && saved.second >= currentProfile.rows) || rawNeedsReflow)) {
            bestCols = maxOf(bestCols, saved.first)
            bestRows = maxOf(bestRows, saved.second)
            cellW = maxOf(cellW, DesktopGridProfileResolver.DEFAULT_CELL_WIDTH_DP)
            cellH = maxOf(cellH, DesktopGridProfileResolver.DEFAULT_CELL_HEIGHT_DP)
            gutter = maxOf(gutter, DesktopGridProfileResolver.DEFAULT_GUTTER_DP)
        }
        if (rawNeedsReflow) {
            bestCols = maxOf(bestCols, rawBounds.first)
            bestRows = maxOf(bestRows, rawBounds.second)
        }

        return if (bestCols == currentProfile.columns && bestRows == currentProfile.rows) {
            currentProfile
        } else {
            currentProfile.copy(
                columns = bestCols,
                rows = bestRows,
                cellWidthDp = cellW,
                cellHeightDp = cellH,
                gutterDp = gutter
            )
        }
    }

    private fun widgetsNeedReflow(widgets: List<PlacedWidget>, profile: DesktopGridProfile): Boolean {
        return widgets.any { w ->
            w.cellX + w.spanX > profile.columns ||
            w.cellY + w.spanY > profile.rows ||
            w.spanX > profile.columns ||
            w.spanY > profile.rows
        }
    }

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
                    if (pendingSettingsMutations == 0) {
                        current.copy(settings = settings, isSettingsLoaded = true)
                    } else {
                        current.copy(isSettingsLoaded = true)
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.lastGoodGridSizeFlow.collect { savedSize ->
                persistedLastGoodGridSize = savedSize
                if (savedSize != null) {
                    val current = _uiState.value.gridProfile
                    val currentGood = lastGoodProfile
                    if (currentGood == null ||
                        (savedSize.first >= currentGood.columns && savedSize.second >= currentGood.rows) ||
                        widgetsNeedReflow(rawPersistedWidgets, current)
                    ) {
                        lastGoodProfile = buildBaselineProfile(savedSize.first, savedSize.second, current)
                    }
                }
            }
        }
    }

    // --- Overlay Lifecycle & Startup ---

    fun onOverlayShown(targetPageOverride: Int? = null) {
        cancelWidgetDrag()
        viewModelScope.launch {
            reconcileOrphanWidgetsUseCase()
            val (snapshotPages, snapshotWidgets) = repository.getSnapshot()
            val loadedSettings = repository.settingsFlow.first()
            val savedLastGoodSize = repository.lastGoodGridSizeFlow.first()
            if (savedLastGoodSize != null) {
                persistedLastGoodGridSize = savedLastGoodSize
            }

            val effectiveSettings = if (pendingSettingsMutations > 0) {
                _uiState.value.settings
            } else {
                loadedSettings
            }
            val rawPages = if (snapshotPages.isNotEmpty()) {
                snapshotPages
            } else {
                rawPersistedPages.ifEmpty { listOf(DeckPage(pageIndex = 0, customLabel = null)) }
            }
            val rawWidgets = snapshotWidgets
            val profile = _uiState.value.gridProfile
            val fitsRaw = !widgetsNeedReflow(rawWidgets, profile)

            if (fitsRaw) {
                val saved = persistedLastGoodGridSize
                if (rawWidgets.isNotEmpty() && (saved == null || (profile.columns >= saved.first && profile.rows >= saved.second && Pair(profile.columns, profile.rows) != saved))) {
                    persistedLastGoodGridSize = Pair(profile.columns, profile.rows)
                    repository.updateLastGoodGridSize(profile.columns, profile.rows)
                }
            }
            val effectiveProfile = getEffectiveGridProfile(profile)
            if (lastGoodProfile == null || fitsRaw) {
                lastGoodProfile = effectiveProfile
            }

            // Refresh metadata strictly against RAW persisted widgets using the baseline profile,
            // never writing display-reflowed positions or clamped spans back to Room (INV-14).
            val catalog = widgetHostGateway.getInstalledCatalog(effectiveProfile)
            val catalogMap = catalog.associateBy { it.providerPackage to it.providerClass }
            val refreshedRawWidgets = rawWidgets.map { w ->
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
            if (refreshedRawWidgets != rawWidgets) {
                repository.upsertWidgets(refreshedRawWidgets)
            }

            rawPersistedWidgets = refreshedRawWidgets
            rawPersistedPages = rawPages

            val (displayedPages, displayedWidgets) = deriveDisplayedState(
                refreshedRawWidgets,
                rawPages,
                profile
            )

            val targetPage = targetPageOverride?.coerceIn(0, (displayedPages.size - 1).coerceAtLeast(0))
                ?: if (effectiveSettings.openOnPageOne) {
                    0
                } else {
                    effectiveSettings.lastViewedPageIndex.coerceIn(0, (displayedPages.size - 1).coerceAtLeast(0))
                }

            for (w in displayedWidgets) {
                syncWidgetSizes(w, profile)
            }
            lastSyncedProfile = profile

            _uiState.update { current ->
                current.copy(
                    widgets = displayedWidgets,
                    pages = displayedPages,
                    settings = if (pendingSettingsMutations > 0) current.settings else loadedSettings,
                    isSettingsLoaded = true,
                    catalogItems = catalog,
                    activePageIndex = targetPage,
                    isReady = true,
                    snapPageTransitions = true,
                    isLaunchingInternalFlow = false,
                    pendingWidgetAction = null
                )
            }
        }
    }

    fun enablePageAnimations() {
        _uiState.update { it.copy(snapPageTransitions = false) }
    }

    // --- Page Navigation ---

    fun navigatePreviousPage() {
        cancelWidgetDrag()
        val pages = _uiState.value.pages
        if (pages.size <= 1) return
        val currentIndex = _uiState.value.activePageIndex
        val newIndex = if (currentIndex <= 0) pages.lastIndex else currentIndex - 1
        _uiState.update { it.copy(activePageIndex = newIndex, snapPageTransitions = false) }
        updateSettings { it.copy(lastViewedPageIndex = newIndex) }
    }

    fun navigateNextPage() {
        cancelWidgetDrag()
        val pages = _uiState.value.pages
        if (pages.size <= 1) return
        val currentIndex = _uiState.value.activePageIndex
        val newIndex = if (currentIndex >= pages.lastIndex) 0 else currentIndex + 1
        _uiState.update { it.copy(activePageIndex = newIndex, snapPageTransitions = false) }
        updateSettings { it.copy(lastViewedPageIndex = newIndex) }
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
            _uiState.update { it.copy(activePageIndex = newIndex, snapPageTransitions = false) }
            updateSettings { it.copy(lastViewedPageIndex = newIndex) }
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
            _uiState.update { it.copy(activePageIndex = newIndex, snapPageTransitions = false) }
            updateSettings { it.copy(lastViewedPageIndex = newIndex) }
            return true
        }
        return false
    }

    fun selectPage(index: Int) {
        cancelWidgetDrag()
        val pages = _uiState.value.pages
        if (index in pages.indices) {
            _uiState.update { it.copy(activePageIndex = index, snapPageTransitions = false) }
            updateSettings { it.copy(lastViewedPageIndex = index) }
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
            _uiState.update { it.copy(activePageIndex = targetIndex, snapPageTransitions = false) }
            updateSettings { it.copy(lastViewedPageIndex = targetIndex) }
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

    data class NewWidgetPlacement(
        val pageIndex: Int,
        val cellX: Int,
        val cellY: Int
    )

    /**
     * Finds a free spot for a newly added widget without displacing existing widgets.
     * USER RULE (v0.0.4 Fix 1): When adding a NEW widget (catalog picker auto-place or catalog drag-drop),
     * if it cannot fit in free space on the target page, do NOT displace existing widgets:
     * 1. Start page ([startPageIndex], active page or target drop page)
     * 2. Subsequent pages in order ([startPageIndex + 1] until pages.size)
     * 3. A brand new page appended at the end (index = pages.size, cell = 0, 0)
     *
     * Order of scanning within any page is COLUMN-MAJOR (downward within a column, then right),
     * consistent with cascade resolution.
     */
    private fun findSpotForNewWidget(
        spanX: Int,
        spanY: Int,
        startPageIndex: Int,
        currentPages: List<DeckPage>,
        allWidgets: List<PlacedWidget>,
        profile: DesktopGridProfile
    ): NewWidgetPlacement {
        val clampedSpanX = spanX.coerceIn(1, profile.columns)
        val clampedSpanY = spanY.coerceIn(1, profile.rows)

        // 1. Check start page first
        val safeStartPage = startPageIndex.coerceAtLeast(0)
        val widgetsOnStartPage = allWidgets.filter { it.pageIndex == safeStartPage }
        val startSpot = gridCollisionResolver.findFirstAvailableSpot(
            spanX = clampedSpanX,
            spanY = clampedSpanY,
            existingOnPage = widgetsOnStartPage,
            columns = profile.columns,
            rows = profile.rows
        )
        if (startSpot != null) {
            return NewWidgetPlacement(safeStartPage, startSpot.first, startSpot.second)
        }

        // 2. Check subsequent pages in order
        for (p in (safeStartPage + 1) until currentPages.size) {
            val widgetsOnPage = allWidgets.filter { it.pageIndex == p }
            val nextSpot = gridCollisionResolver.findFirstAvailableSpot(
                spanX = clampedSpanX,
                spanY = clampedSpanY,
                existingOnPage = widgetsOnPage,
                columns = profile.columns,
                rows = profile.rows
            )
            if (nextSpot != null) {
                return NewWidgetPlacement(p, nextSpot.first, nextSpot.second)
            }
        }

        // 3. All checked pages full -> append a new page at the end
        val newPageIndex = currentPages.size
        return NewWidgetPlacement(newPageIndex, 0, 0)
    }

    fun addWidgetAutoPlace(item: WidgetCatalogItem) {
        val currentProfile = _uiState.value.gridProfile
        val effectiveProfile = getEffectiveGridProfile(currentProfile)
        val activePage = _uiState.value.activePageIndex
        val basePages = rawPersistedPages.ifEmpty { _uiState.value.pages }
        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }

        val placement = findSpotForNewWidget(
            spanX = item.defaultSpanX,
            spanY = item.defaultSpanY,
            startPageIndex = activePage,
            currentPages = basePages,
            allWidgets = baseWidgets,
            profile = effectiveProfile
        )

        val appWidgetId = widgetHostGateway.allocateAppWidgetId()
        val candidate = PlacedWidget(
            appWidgetId = appWidgetId,
            providerPackage = item.providerPackage,
            providerClass = item.providerClass,
            pageIndex = placement.pageIndex,
            cellX = placement.cellX,
            cellY = placement.cellY,
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
        val currentProfile = _uiState.value.gridProfile
        val effectiveProfile = getEffectiveGridProfile(currentProfile)
        val basePages = rawPersistedPages.ifEmpty { _uiState.value.pages }
        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }

        val effectiveSpanX = item.defaultSpanX.coerceIn(1, effectiveProfile.columns)
        val effectiveSpanY = item.defaultSpanY.coerceIn(1, effectiveProfile.rows)
        val clampedCellX = cellX.coerceIn(0, (effectiveProfile.columns - effectiveSpanX).coerceAtLeast(0))
        val clampedCellY = cellY.coerceIn(0, (effectiveProfile.rows - effectiveSpanY).coerceAtLeast(0))
        val targetBounds = GridBounds(clampedCellX, clampedCellY, effectiveSpanX, effectiveSpanY)

        val widgetsOnTargetPage = baseWidgets.filter { it.pageIndex == pageIndex }
        val overlaps = widgetsOnTargetPage.any { it.bounds.intersects(targetBounds) }

        val placement = if (!overlaps) {
            NewWidgetPlacement(pageIndex, clampedCellX, clampedCellY)
        } else {
            // Explicit drop onto occupied cell -> also no displacement!
            // If the dropped spot overlaps, place at the first free spot on that page,
            // else next page with space / new page
            findSpotForNewWidget(
                spanX = effectiveSpanX,
                spanY = effectiveSpanY,
                startPageIndex = pageIndex,
                currentPages = basePages,
                allWidgets = baseWidgets,
                profile = effectiveProfile
            )
        }

        val appWidgetId = widgetHostGateway.allocateAppWidgetId()
        val candidate = PlacedWidget(
            appWidgetId = appWidgetId,
            providerPackage = item.providerPackage,
            providerClass = item.providerClass,
            pageIndex = placement.pageIndex,
            cellX = placement.cellX,
            cellY = placement.cellY,
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

        // USER RULE & INV-14/INV-19: An ADD must only add the new widget to the RAW
        // persisted layout. Existing widgets in rawPersistedWidgets must NEVER have their
        // spans or positions modified by reflow!
        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }
        val basePages = rawPersistedPages.ifEmpty { _uiState.value.pages }

        val targetPageIndex = candidate.pageIndex
        val requiredPageCount = maxOf(resolvedPageCount ?: (targetPageIndex + 1), basePages.size)

        val newPages = basePages.toMutableList()
        while (newPages.size < requiredPageCount) {
            newPages.add(DeckPage(pageIndex = newPages.size, customLabel = null))
        }

        val updatedWidgets = resolvedWidgets ?: (baseWidgets.filter { it.appWidgetId != candidate.appWidgetId } + candidate)
        rawPersistedWidgets = updatedWidgets
        rawPersistedPages = newPages

        // Derive displayed state from the updated raw layout for current window profile
        val (displayedPages, displayedWidgets) = deriveDisplayedState(updatedWidgets, newPages, profile)

        _uiState.update { current ->
            current.copy(
                pages = displayedPages,
                widgets = displayedWidgets,
                activePageIndex = targetPageIndex.coerceIn(0, displayedPages.lastIndex),
                snapPageTransitions = false,
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
        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }
        val basePages = rawPersistedPages.ifEmpty { _uiState.value.pages }
        val dragged = baseWidgets.find { it.appWidgetId == appWidgetId }
        val displayedDragged = _uiState.value.widgets.find { it.appWidgetId == appWidgetId }
        dragSnapshotWidgets = baseWidgets
        dragSnapshotPages = basePages
        dragSnapshotPageCount = basePages.size
        dragStartPageIndex = displayedDragged?.pageIndex ?: dragged?.pageIndex ?: _uiState.value.activePageIndex
        draggingWidgetStartCell = if (dragged != null) Pair(dragged.cellX, dragged.cellY) else null
        draggingWidgetDisplayedStartCell = if (displayedDragged != null) {
            Pair(displayedDragged.cellX, displayedDragged.cellY)
        } else {
            draggingWidgetStartCell
        }
        resizingWidgetStartBounds = null
        resizingWidgetDisplayedStartBounds = null

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
        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }
        val basePages = rawPersistedPages.ifEmpty { _uiState.value.pages }
        val target = baseWidgets.find { it.appWidgetId == appWidgetId }
        val displayedTarget = _uiState.value.widgets.find { it.appWidgetId == appWidgetId }
        dragSnapshotWidgets = baseWidgets
        dragSnapshotPages = basePages
        dragSnapshotPageCount = basePages.size
        dragStartPageIndex = displayedTarget?.pageIndex ?: target?.pageIndex ?: _uiState.value.activePageIndex
        draggingWidgetStartCell = null
        draggingWidgetDisplayedStartCell = null
        resizingWidgetStartBounds = target?.bounds
        resizingWidgetDisplayedStartBounds = displayedTarget?.bounds ?: target?.bounds

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

    private fun resolveCascadeForDisplayAndRaw(
        appWidgetId: Int,
        targetWidget: PlacedWidget,
        snapshot: List<PlacedWidget>,
        basePages: List<DeckPage>,
        snapshotPageCount: Int,
        activePage: Int,
        currentProfile: DesktopGridProfile,
        effectiveProfile: DesktopGridProfile,
        baselineDisplayedWidgets: List<PlacedWidget>,
        pruneEmpty: Boolean
    ): ResolvedCascadeState? {
        val result = cascadeDisplacementResolver.resolve(
            dragged = targetWidget,
            allWidgets = snapshot,
            columns = effectiveProfile.columns,
            rows = effectiveProfile.rows,
            pageCount = maxOf(snapshotPageCount, activePage + 1)
        ) ?: return null

        val (rawPages, rawWidgets) = if (pruneEmpty) {
            EdgePageDragTransferUseCase.pruneAndReindexPages(
                pages = basePages,
                widgets = result.widgets
            )
        } else {
            val paddedPages = basePages.toMutableList()
            val targetPages = maxOf(result.pageCount, activePage + 1)
            while (paddedPages.size < targetPages) {
                paddedPages.add(DeckPage(pageIndex = paddedPages.size, customLabel = null))
            }
            Pair(paddedPages, result.widgets)
        }

        val (displayedPages, displayedWidgets) = deriveDisplayedState(
            widgets = rawWidgets,
            pages = rawPages,
            profile = currentProfile
        )

        val baselineMap = baselineDisplayedWidgets.associateBy { it.appWidgetId }
        val displayedMovedIds = displayedWidgets.mapNotNull { w ->
            if (w.appWidgetId == appWidgetId) {
                null
            } else {
                val prev = baselineMap[w.appWidgetId]
                if (prev != null && (prev.pageIndex != w.pageIndex || prev.cellX != w.cellX || prev.cellY != w.cellY)) {
                    w.appWidgetId
                } else {
                    null
                }
            }
        }.toSet()

        return ResolvedCascadeState(
            rawWidgets = rawWidgets,
            rawPages = rawPages,
            displayedPages = displayedPages,
            displayedWidgets = displayedWidgets,
            movedWidgetIds = result.movedWidgetIds + displayedMovedIds
        )
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
        val basePages = dragSnapshotPages ?: rawPersistedPages.ifEmpty { _uiState.value.pages }
        val snapshotPageCount = if (dragSnapshotPageCount > 0) dragSnapshotPageCount else basePages.size
        val orig = snapshot.find { it.appWidgetId == appWidgetId } ?: return
        val currentProfile = _uiState.value.gridProfile
        val effectiveProfile = getEffectiveGridProfile(currentProfile)

        val maxAvailableX = if (orig.maxSpanX in 1 until effectiveProfile.columns) orig.maxSpanX else effectiveProfile.columns
        val maxAvailableY = if (orig.maxSpanY in 1 until effectiveProfile.rows) orig.maxSpanY else effectiveProfile.rows
        val minAllowedX = orig.minSpanX.coerceIn(1, maxAvailableX)
        val minAllowedY = orig.minSpanY.coerceIn(1, maxAvailableY)

        val allowedSpanX = if ((orig.resizeMode and PlacedWidget.RESIZE_HORIZONTAL) != 0) spanX else orig.spanX
        val allowedSpanY = if ((orig.resizeMode and PlacedWidget.RESIZE_VERTICAL) != 0) spanY else orig.spanY

        val clampedSpanX = allowedSpanX.coerceIn(minAllowedX, maxAvailableX)
        val clampedSpanY = allowedSpanY.coerceIn(minAllowedY, maxAvailableY)
        val clampedCellX = cellX.coerceIn(0, (effectiveProfile.columns - clampedSpanX).coerceAtLeast(0))
        val clampedCellY = cellY.coerceIn(0, (effectiveProfile.rows - clampedSpanY).coerceAtLeast(0))

        val activePage = _uiState.value.activePageIndex
        val startPage = dragStartPageIndex ?: activePage
        val displayedStart = resizingWidgetDisplayedStartBounds ?: orig.bounds

        val isOriginal = activePage == startPage &&
            ((clampedCellX == orig.cellX &&
                clampedCellY == orig.cellY &&
                clampedSpanX == orig.spanX &&
                clampedSpanY == orig.spanY) ||
                (cellX == displayedStart.cellX &&
                    cellY == displayedStart.cellY &&
                    spanX == displayedStart.spanX &&
                    spanY == displayedStart.spanY))

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
        val isShrunkGrid = currentProfile.columns < effectiveProfile.columns || currentProfile.rows < effectiveProfile.rows

        if (!hasOverlap && !isShrunkGrid) {
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
        val baselineDisplayed = _uiState.value.widgets
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
            val resolved = resolveCascadeForDisplayAndRaw(
                appWidgetId = appWidgetId,
                targetWidget = targetWidget,
                snapshot = currentSnapshot,
                basePages = basePages,
                snapshotPageCount = snapshotPageCount,
                activePage = activePage,
                currentProfile = currentProfile,
                effectiveProfile = effectiveProfile,
                baselineDisplayedWidgets = baselineDisplayed,
                pruneEmpty = false
            )

            if (resolved != null) {
                _uiState.update { current ->
                    if (dragSessionToken != sessionToken ||
                        (current.resizingWidgetId != appWidgetId && current.draggingWidgetId != appWidgetId)) {
                        current
                    } else {
                        current.copy(
                            previewWidgets = resolved.displayedWidgets,
                            previewPageCount = maxOf(resolved.displayedPages.size, current.previewPageCount ?: 0),
                            displacedWidgetIds = resolved.movedWidgetIds
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
                previewPageCount = if (newPageCount > currentPages.size) newPageCount else it.previewPageCount,
                snapPageTransitions = false
            )
        }

        // Re-trigger cascade displacement computation on the newly active page
        val currentHover = _uiState.value.hoveredCell ?: draggingWidgetDisplayedStartCell ?: draggingWidgetStartCell ?: Pair(0, 0)
        onWidgetHoverCellChanged(draggingId, currentHover.first, currentHover.second)
    }

    fun onWidgetHoverCellChanged(appWidgetId: Int, cellX: Int, cellY: Int) {
        dragDebounceJob?.cancel()

        val activePage = _uiState.value.activePageIndex
        val startPage = dragStartPageIndex ?: activePage
        val startCell = draggingWidgetDisplayedStartCell ?: draggingWidgetStartCell

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
        val currentProfile = _uiState.value.gridProfile
        val effectiveProfile = getEffectiveGridProfile(currentProfile)
        val basePages = dragSnapshotPages ?: rawPersistedPages.ifEmpty { _uiState.value.pages }
        val snapshotPageCount = if (dragSnapshotPageCount > 0) dragSnapshotPageCount else basePages.size
        val baselineDisplayed = _uiState.value.widgets

        dragDebounceJob = viewModelScope.launch {
            delay(250L)
            if (dragSessionToken != sessionToken || _uiState.value.draggingWidgetId != appWidgetId) {
                return@launch
            }

            val currentSnapshot = dragSnapshotWidgets ?: return@launch
            val orig = currentSnapshot.find { it.appWidgetId == appWidgetId } ?: return@launch
            val clampedCellX = cellX.coerceIn(0, (effectiveProfile.columns - orig.spanX).coerceAtLeast(0))
            val clampedCellY = cellY.coerceIn(0, (effectiveProfile.rows - orig.spanY).coerceAtLeast(0))
            val targetWidget = orig.copy(
                pageIndex = activePage,
                cellX = clampedCellX,
                cellY = clampedCellY
            )

            val resolved = resolveCascadeForDisplayAndRaw(
                appWidgetId = appWidgetId,
                targetWidget = targetWidget,
                snapshot = currentSnapshot,
                basePages = basePages,
                snapshotPageCount = snapshotPageCount,
                activePage = activePage,
                currentProfile = currentProfile,
                effectiveProfile = effectiveProfile,
                baselineDisplayedWidgets = baselineDisplayed,
                pruneEmpty = false
            )

            if (resolved != null) {
                _uiState.update { current ->
                    if (dragSessionToken != sessionToken || current.draggingWidgetId != appWidgetId) {
                        current
                    } else {
                        current.copy(
                            previewWidgets = resolved.displayedWidgets,
                            previewPageCount = maxOf(resolved.displayedPages.size, current.previewPageCount ?: 0),
                            displacedWidgetIds = resolved.movedWidgetIds
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

        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }
        val snapshot = dragSnapshotWidgets ?: baseWidgets
        val basePages = dragSnapshotPages ?: rawPersistedPages.ifEmpty { _uiState.value.pages }
        val snapshotPageCount = if (dragSnapshotPageCount > 0) dragSnapshotPageCount else basePages.size
        val activePage = _uiState.value.activePageIndex

        dragSnapshotWidgets = null
        dragSnapshotPages = null
        dragSnapshotPageCount = 0
        dragStartPageIndex = null
        draggingWidgetStartCell = null
        draggingWidgetDisplayedStartCell = null
        resizingWidgetStartBounds = null
        resizingWidgetDisplayedStartBounds = null

        val orig = snapshot.find { it.appWidgetId == appWidgetId }
        if (orig == null) {
            cancelWidgetDrag()
            return
        }

        val currentProfile = _uiState.value.gridProfile
        val effectiveProfile = getEffectiveGridProfile(currentProfile)

        val clampedCellX = cellX.coerceIn(0, (effectiveProfile.columns - orig.spanX).coerceAtLeast(0))
        val clampedCellY = cellY.coerceIn(0, (effectiveProfile.rows - orig.spanY).coerceAtLeast(0))
        val targetWidget = orig.copy(
            pageIndex = activePage,
            cellX = clampedCellX,
            cellY = clampedCellY
        )

        val resolved = resolveCascadeForDisplayAndRaw(
            appWidgetId = appWidgetId,
            targetWidget = targetWidget,
            snapshot = snapshot,
            basePages = basePages,
            snapshotPageCount = snapshotPageCount,
            activePage = activePage,
            currentProfile = currentProfile,
            effectiveProfile = effectiveProfile,
            baselineDisplayedWidgets = _uiState.value.widgets,
            pruneEmpty = true
        )

        if (resolved != null) {
            rawPersistedWidgets = resolved.rawWidgets
            rawPersistedPages = resolved.rawPages
            val finalPageIndex = resolved.displayedWidgets.find { it.appWidgetId == appWidgetId }?.pageIndex
                ?: resolved.rawWidgets.find { it.appWidgetId == appWidgetId }?.pageIndex
                ?: activePage

            _uiState.update { current ->
                current.copy(
                    pages = resolved.displayedPages,
                    widgets = resolved.displayedWidgets,
                    activePageIndex = finalPageIndex.coerceIn(0, resolved.displayedPages.lastIndex),
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
                repository.replaceDeckState(resolved.rawPages, resolved.rawWidgets)
            }
            val committed = resolved.displayedWidgets.find { it.appWidgetId == appWidgetId }
            if (committed != null) {
                syncWidgetSizes(committed, currentProfile)
            }
        } else {
            cancelWidgetDrag()
        }
    }

    fun cancelWidgetDrag() {
        dragDebounceJob?.cancel()
        dragDebounceJob = null
        dragSessionToken++

        val snapshotWidgets = dragSnapshotWidgets
        val snapshotPages = dragSnapshotPages
        val restorePageIndex = dragStartPageIndex

        dragSnapshotWidgets = null
        dragSnapshotPages = null
        dragSnapshotPageCount = 0
        dragStartPageIndex = null
        draggingWidgetStartCell = null
        draggingWidgetDisplayedStartCell = null
        resizingWidgetStartBounds = null
        resizingWidgetDisplayedStartBounds = null

        if (snapshotWidgets != null || snapshotPages != null) {
            val basePages = snapshotPages ?: rawPersistedPages.ifEmpty { _uiState.value.pages }
            val baseWidgets = snapshotWidgets ?: rawPersistedWidgets.ifEmpty { _uiState.value.widgets }
            val (displayedPages, displayedWidgets) = deriveDisplayedState(
                baseWidgets,
                basePages,
                _uiState.value.gridProfile
            )
            val targetIndex = (restorePageIndex ?: _uiState.value.activePageIndex).coerceIn(0, displayedPages.lastIndex)
            _uiState.update { current ->
                current.copy(
                    pages = displayedPages,
                    widgets = displayedWidgets,
                    activePageIndex = targetIndex,
                    previewWidgets = null,
                    previewPageCount = null,
                    displacedWidgetIds = emptySet(),
                    draggingWidgetId = null,
                    resizingWidgetId = null,
                    dragStartPageIndex = null,
                    hoveredCell = null
                )
            }
        } else {
            _uiState.update { current ->
                if (current.previewWidgets != null ||
                    current.draggingWidgetId != null ||
                    current.resizingWidgetId != null ||
                    current.displacedWidgetIds.isNotEmpty() ||
                    current.hoveredCell != null
                ) {
                    current.copy(
                        previewWidgets = null,
                        previewPageCount = null,
                        displacedWidgetIds = emptySet(),
                        draggingWidgetId = null,
                        resizingWidgetId = null,
                        dragStartPageIndex = null,
                        hoveredCell = null
                    )
                } else {
                    current
                }
            }
        }
    }

    fun cancelWidgetResize() = cancelWidgetDrag()

    fun moveWidget(
        appWidgetId: Int,
        newCellX: Int,
        newCellY: Int
    ) {
        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }
        val current = baseWidgets.find { it.appWidgetId == appWidgetId } ?: return
        val currentProfile = _uiState.value.gridProfile
        val effectiveProfile = getEffectiveGridProfile(currentProfile)

        val clampedCellX = newCellX.coerceIn(0, (effectiveProfile.columns - current.spanX).coerceAtLeast(0))
        val clampedCellY = newCellY.coerceIn(0, (effectiveProfile.rows - current.spanY).coerceAtLeast(0))

        val target = current.copy(
            cellX = clampedCellX,
            cellY = clampedCellY
            // spanX, spanY, min/maxSpan are NEVER modified on move
        )

        val basePages = rawPersistedPages.ifEmpty { _uiState.value.pages }
        val result = cascadeDisplacementResolver.resolve(
            dragged = target,
            allWidgets = baseWidgets,
            columns = effectiveProfile.columns,
            rows = effectiveProfile.rows,
            pageCount = basePages.size
        ) ?: return

        val newPages = basePages.toMutableList()
        while (newPages.size < result.pageCount) {
            newPages.add(DeckPage(pageIndex = newPages.size, customLabel = null))
        }

        rawPersistedWidgets = result.widgets
        rawPersistedPages = newPages

        val (displayedPages, displayedWidgets) = deriveDisplayedState(result.widgets, newPages, currentProfile)

        _uiState.update {
            it.copy(
                pages = displayedPages,
                widgets = displayedWidgets,
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

        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }
        val snapshot = dragSnapshotWidgets ?: baseWidgets
        val basePages = dragSnapshotPages ?: rawPersistedPages.ifEmpty { _uiState.value.pages }
        val snapshotPageCount = if (dragSnapshotPageCount > 0) dragSnapshotPageCount else basePages.size
        dragSnapshotWidgets = null
        dragSnapshotPages = null
        dragSnapshotPageCount = 0
        dragStartPageIndex = null
        draggingWidgetStartCell = null
        draggingWidgetDisplayedStartCell = null
        resizingWidgetStartBounds = null
        resizingWidgetDisplayedStartBounds = null

        val orig = snapshot.find { it.appWidgetId == appWidgetId }
        if (orig == null) {
            cancelWidgetDrag()
            return
        }

        val currentProfile = _uiState.value.gridProfile
        val effectiveProfile = getEffectiveGridProfile(currentProfile)

        // Max span is clamped to explicit provider maxSpan if declared (in 1 until effectiveProfile columns/rows), else effectiveProfile columns/rows
        val maxAvailableX = if (orig.maxSpanX in 1 until effectiveProfile.columns) orig.maxSpanX else effectiveProfile.columns
        val maxAvailableY = if (orig.maxSpanY in 1 until effectiveProfile.rows) orig.maxSpanY else effectiveProfile.rows

        val minAllowedX = orig.minSpanX.coerceIn(1, maxAvailableX)
        val minAllowedY = orig.minSpanY.coerceIn(1, maxAvailableY)

        val allowedSpanX = if ((orig.resizeMode and PlacedWidget.RESIZE_HORIZONTAL) != 0) spanX else orig.spanX
        val allowedSpanY = if ((orig.resizeMode and PlacedWidget.RESIZE_VERTICAL) != 0) spanY else orig.spanY

        val clampedSpanX = allowedSpanX.coerceIn(minAllowedX, maxAvailableX)
        val clampedSpanY = allowedSpanY.coerceIn(minAllowedY, maxAvailableY)
        val clampedCellX = cellX.coerceIn(0, (effectiveProfile.columns - clampedSpanX).coerceAtLeast(0))
        val clampedCellY = cellY.coerceIn(0, (effectiveProfile.rows - clampedSpanY).coerceAtLeast(0))

        val activePage = _uiState.value.activePageIndex
        val target = orig.copy(
            pageIndex = activePage,
            cellX = clampedCellX,
            cellY = clampedCellY,
            spanX = clampedSpanX,
            spanY = clampedSpanY
        )

        val resolved = resolveCascadeForDisplayAndRaw(
            appWidgetId = appWidgetId,
            targetWidget = target,
            snapshot = snapshot,
            basePages = basePages,
            snapshotPageCount = snapshotPageCount,
            activePage = activePage,
            currentProfile = currentProfile,
            effectiveProfile = effectiveProfile,
            baselineDisplayedWidgets = _uiState.value.widgets,
            pruneEmpty = true
        )

        if (resolved != null) {
            rawPersistedWidgets = resolved.rawWidgets
            rawPersistedPages = resolved.rawPages
            val finalPageIndex = resolved.displayedWidgets.find { it.appWidgetId == appWidgetId }?.pageIndex
                ?: resolved.rawWidgets.find { it.appWidgetId == appWidgetId }?.pageIndex
                ?: activePage

            _uiState.update { current ->
                current.copy(
                    pages = resolved.displayedPages,
                    widgets = resolved.displayedWidgets,
                    activePageIndex = finalPageIndex.coerceIn(0, resolved.displayedPages.lastIndex),
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
                repository.replaceDeckState(resolved.rawPages, resolved.rawWidgets)
            }
            val committed = resolved.displayedWidgets.find { it.appWidgetId == appWidgetId }
            if (committed != null) {
                syncWidgetSizes(committed, currentProfile)
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
        val currentProfile = _uiState.value.gridProfile
        val effectiveProfile = getEffectiveGridProfile(currentProfile)
        val basePages = rawPersistedPages.ifEmpty { _uiState.value.pages }
        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }

        val result = edgePageDragTransferUseCase.transferAcrossEdge(
            widgetId = appWidgetId,
            direction = direction,
            targetCellX = targetCellX,
            targetCellY = targetCellY,
            currentPages = basePages,
            currentWidgets = baseWidgets,
            gridProfile = effectiveProfile
        )

        when (result) {
            is EdgeTransferResult.Success -> {
                rawPersistedWidgets = result.widgets
                rawPersistedPages = result.pages
                val targetIndex = result.targetPageIndex.coerceIn(0, result.pages.lastIndex)
                val (displayedPages, displayedWidgets) = deriveDisplayedState(result.widgets, result.pages, currentProfile)
                _uiState.update {
                    it.copy(
                        pages = displayedPages,
                        widgets = displayedWidgets,
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
                    syncWidgetSizes(transferred, currentProfile)
                }
            }
            is EdgeTransferResult.BlockedAtLeftEdge -> {
                val current = baseWidgets.find { it.appWidgetId == appWidgetId }
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
        val basePages = rawPersistedPages.ifEmpty { _uiState.value.pages }
        val baseWidgets = rawPersistedWidgets.ifEmpty { _uiState.value.widgets }
        val remainingWidgets = baseWidgets.filter { it.appWidgetId != appWidgetId }
        val (prunedPages, reindexedWidgets) = EdgePageDragTransferUseCase.pruneAndReindexPages(
            basePages,
            remainingWidgets
        )
        rawPersistedWidgets = reindexedWidgets
        rawPersistedPages = prunedPages
        val (displayedPages, displayedWidgets) = deriveDisplayedState(
            reindexedWidgets,
            prunedPages,
            _uiState.value.gridProfile
        )
        val newActivePage = _uiState.value.activePageIndex.coerceIn(0, displayedPages.lastIndex)
        val newEditingId = if (_uiState.value.editingWidgetId == appWidgetId) null else _uiState.value.editingWidgetId
        _uiState.update { current ->
            current.copy(
                pages = displayedPages,
                widgets = displayedWidgets,
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
        pendingSettingsMutations++
        _uiState.update { current ->
            current.copy(settings = transform(current.settings))
        }
        viewModelScope.launch {
            try {
                settingsUpdateMutex.withLock {
                    repository.updateSettings(transform)
                }
            } finally {
                pendingSettingsMutations = (pendingSettingsMutations - 1).coerceAtLeast(0)
                if (pendingSettingsMutations == 0) {
                    val latest = repository.settingsFlow.first()
                    _uiState.update { current ->
                        current.copy(settings = latest, isSettingsLoaded = true)
                    }
                }
            }
        }
    }

    fun updateGridProfile(profile: DesktopGridProfile) {
        if (profile == _uiState.value.gridProfile) return
        if (profile.columns <= 0 || profile.rows <= 0 ||
            profile.cellWidthDp <= 10f || profile.cellHeightDp <= 10f ||
            profile.availableWidthDp <= 10f || profile.availableHeightDp <= 10f) {
            return
        }

        val currentGood = lastGoodProfile
        val saved = persistedLastGoodGridSize
        val fitsRaw = !widgetsNeedReflow(rawPersistedWidgets, profile)
        if (fitsRaw) {
            val exceedsSaved = saved == null ||
                (profile.columns >= saved.first && profile.rows >= saved.second)
            val exceedsGood = currentGood == null ||
                (profile.columns >= currentGood.columns && profile.rows >= currentGood.rows)
            if (exceedsSaved && exceedsGood) {
                lastGoodProfile = profile
            } else if (currentGood == null && saved != null) {
                lastGoodProfile = buildBaselineProfile(saved.first, saved.second, profile)
            }
            if (rawPersistedWidgets.isNotEmpty() &&
                (saved == null || (profile.columns >= saved.first && profile.rows >= saved.second && Pair(profile.columns, profile.rows) != saved))
            ) {
                persistedLastGoodGridSize = Pair(profile.columns, profile.rows)
                viewModelScope.launch {
                    repository.updateLastGoodGridSize(profile.columns, profile.rows)
                }
            }
        } else {
            val rawBounds = minimumRawBoundingSize(rawPersistedWidgets)
            val baselineCols = maxOf(
                profile.columns,
                currentGood?.columns ?: 0,
                saved?.first ?: 0,
                rawBounds.first
            )
            val baselineRows = maxOf(
                profile.rows,
                currentGood?.rows ?: 0,
                saved?.second ?: 0,
                rawBounds.second
            )
            lastGoodProfile = buildBaselineProfile(baselineCols, baselineRows, currentGood ?: profile)
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

    fun resyncWidgetSizes() {
        val profile = _uiState.value.gridProfile
        for (w in _uiState.value.widgets) {
            syncWidgetSizes(w, profile)
        }
        lastSyncedProfile = profile
    }

    private fun deriveDisplayedState(
        widgets: List<PlacedWidget>,
        pages: List<DeckPage>,
        profile: DesktopGridProfile
    ): Pair<List<DeckPage>, List<PlacedWidget>> {
        val needsReflow = widgetsNeedReflow(widgets, profile)

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
        val effectiveProfile = getEffectiveGridProfile(_uiState.value.gridProfile)
        val catalog = widgetHostGateway.getInstalledCatalog(effectiveProfile)
        _uiState.update { it.copy(catalogItems = catalog) }
    }

    fun startCatalogDragOut(item: WidgetCatalogItem) {
        _uiState.update { it.copy(draggingCatalogItem = item) }
    }

    fun clearDraggingCatalogItem() {
        _uiState.update { it.copy(draggingCatalogItem = null) }
    }
}
