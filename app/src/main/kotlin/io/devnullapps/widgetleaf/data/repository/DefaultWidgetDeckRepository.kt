package io.devnullapps.widgetleaf.data.repository

import androidx.room.withTransaction
import io.devnullapps.widgetleaf.data.local.LeafDatabase
import io.devnullapps.widgetleaf.data.local.PreferencesDataStore
import io.devnullapps.widgetleaf.data.local.entity.DeckPageEntity
import io.devnullapps.widgetleaf.data.local.entity.toDomain
import io.devnullapps.widgetleaf.data.local.entity.toEntity
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.repository.WidgetDeckRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class DefaultWidgetDeckRepository(
    private val database: LeafDatabase,
    private val preferencesDataStore: PreferencesDataStore
) : WidgetDeckRepository {

    private val deckPageDao = database.deckPageDao()
    private val widgetPlacementDao = database.widgetPlacementDao()

    override val pagesFlow: Flow<List<DeckPage>> = combine(
        deckPageDao.observeAll(),
        widgetPlacementDao.observeAll()
    ) { pageEntities, widgetEntities ->
        val labelMap = pageEntities.associate { it.pageIndex to it.customLabel }
        val maxPageIndex = maxOf(
            0,
            maxOf(
                pageEntities.maxOfOrNull { it.pageIndex } ?: 0,
                widgetEntities.maxOfOrNull { it.pageIndex } ?: 0
            )
        )
        (0..maxPageIndex).map { index ->
            DeckPage(pageIndex = index, customLabel = labelMap[index])
        }
    }

    override val widgetsFlow: Flow<List<PlacedWidget>> =
        widgetPlacementDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override val settingsFlow: Flow<OverlaySettings> = preferencesDataStore.settingsFlow

    override suspend fun getSnapshot(): Pair<List<DeckPage>, List<PlacedWidget>> {
        val pageEntities = deckPageDao.getAll()
        val widgetEntities = widgetPlacementDao.getAll()
        val labelMap = pageEntities.associate { it.pageIndex to it.customLabel }
        val maxPageIndex = maxOf(
            0,
            maxOf(
                pageEntities.maxOfOrNull { it.pageIndex } ?: 0,
                widgetEntities.maxOfOrNull { it.pageIndex } ?: 0
            )
        )
        val pages = (0..maxPageIndex).map { index ->
            DeckPage(pageIndex = index, customLabel = labelMap[index])
        }
        val widgets = widgetEntities.map { it.toDomain() }
        return Pair(pages, widgets)
    }

    override suspend fun replaceDeckState(pages: List<DeckPage>, widgets: List<PlacedWidget>) {
        database.withTransaction {
            deckPageDao.deleteAll()
            deckPageDao.upsertAll(pages.map { it.toEntity() })
            widgetPlacementDao.deleteAll()
            widgetPlacementDao.upsertAll(widgets.map { it.toEntity() })
        }
    }

    override suspend fun upsertWidget(widget: PlacedWidget) {
        widgetPlacementDao.upsert(widget.toEntity())
    }

    override suspend fun upsertWidgets(widgets: List<PlacedWidget>) {
        widgetPlacementDao.upsertAll(widgets.map { it.toEntity() })
    }

    override suspend fun removeWidget(appWidgetId: Int) {
        database.withTransaction {
            widgetPlacementDao.deleteById(appWidgetId)
            pruneEmptyPagesAndReindexInternal()
        }
    }

    override suspend fun updatePageLabel(pageIndex: Int, customLabel: String?) {
        val normalizedLabel = customLabel?.trim()?.takeIf { it.isNotEmpty() }
        deckPageDao.upsert(DeckPageEntity(pageIndex = pageIndex, customLabel = normalizedLabel))
    }

    override suspend fun pruneEmptyPagesAndReindex() {
        database.withTransaction {
            pruneEmptyPagesAndReindexInternal()
        }
    }

    override suspend fun updateSettings(transform: (OverlaySettings) -> OverlaySettings) {
        preferencesDataStore.updateSettings(transform)
    }

    private suspend fun pruneEmptyPagesAndReindexInternal() {
        val currentWidgets = widgetPlacementDao.getAll()
        if (currentWidgets.isEmpty()) {
            replaceDeckState(
                pages = listOf(DeckPage(pageIndex = 0, customLabel = null)),
                widgets = emptyList()
            )
            return
        }

        val currentPages = deckPageDao.getAll()
        val pageLabelMap = currentPages.associate { it.pageIndex to it.customLabel }

        val activePageIndices = currentWidgets.map { it.pageIndex }.distinct().sorted()
        val oldToNewIndexMap = activePageIndices.mapIndexed { newIndex, oldIndex ->
            oldIndex to newIndex
        }.toMap()

        val newPages = activePageIndices.mapIndexed { newIndex, oldIndex ->
            DeckPage(pageIndex = newIndex, customLabel = pageLabelMap[oldIndex])
        }

        val newWidgets = currentWidgets.map { entity ->
            val domain = entity.toDomain()
            val newPageIndex = oldToNewIndexMap[entity.pageIndex] ?: 0
            domain.copy(pageIndex = newPageIndex)
        }

        replaceDeckState(pages = newPages, widgets = newWidgets)
    }
}
