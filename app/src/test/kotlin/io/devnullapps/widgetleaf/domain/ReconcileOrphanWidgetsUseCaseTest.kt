package io.devnullapps.widgetleaf.domain

import android.content.ComponentName
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.domain.repository.WidgetDeckRepository
import io.devnullapps.widgetleaf.domain.usecase.ReconcileOrphanWidgetsUseCase
import io.devnullapps.widgetleaf.platform.ResponsiveWidgetSizeDp
import io.devnullapps.widgetleaf.platform.WidgetHostGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcileOrphanWidgetsUseCaseTest {

    private class FakeWidgetDeckRepository(
        initialPages: List<DeckPage> = listOf(DeckPage(0)),
        initialWidgets: List<PlacedWidget> = emptyList()
    ) : WidgetDeckRepository {
        val pages = initialPages.toMutableList()
        val widgets = initialWidgets.toMutableList()
        val removedWidgetIds = mutableListOf<Int>()
        var pruneCallsCount = 0

        override val pagesFlow: Flow<List<DeckPage>> = MutableStateFlow(pages)
        override val widgetsFlow: Flow<List<PlacedWidget>> = MutableStateFlow(widgets)
        override val settingsFlow: Flow<OverlaySettings> = MutableStateFlow(OverlaySettings())

        override suspend fun getSnapshot(): Pair<List<DeckPage>, List<PlacedWidget>> {
            return Pair(pages.toList(), widgets.toList())
        }

        override suspend fun replaceDeckState(pages: List<DeckPage>, widgets: List<PlacedWidget>) {
            this.pages.clear()
            this.pages.addAll(pages)
            this.widgets.clear()
            this.widgets.addAll(widgets)
        }

        override suspend fun upsertWidget(widget: PlacedWidget) {
            widgets.removeAll { it.appWidgetId == widget.appWidgetId }
            widgets.add(widget)
        }

        override suspend fun upsertWidgets(widgets: List<PlacedWidget>) {
            for (w in widgets) upsertWidget(w)
        }

        override suspend fun removeWidget(appWidgetId: Int) {
            removedWidgetIds.add(appWidgetId)
            widgets.removeAll { it.appWidgetId == appWidgetId }
        }

        override suspend fun updatePageLabel(pageIndex: Int, customLabel: String?) {}

        override suspend fun pruneEmptyPagesAndReindex() {
            pruneCallsCount++
        }

        override suspend fun updateSettings(transform: (OverlaySettings) -> OverlaySettings) {}
    }

    private class FakeWidgetHostGateway(
        var allocatedIds: List<Int> = emptyList(),
        var installedPackages: Set<String> = emptySet()
    ) : WidgetHostGateway {
        val deletedHostWidgetIds = mutableListOf<Int>()

        override fun startListening() {}
        override fun stopListening() {}
        override fun allocateAppWidgetId(): Int = 999
        override fun deleteAppWidgetId(appWidgetId: Int) {
            deletedHostWidgetIds.add(appWidgetId)
        }

        override fun getAllocatedAppWidgetIds(): IntArray = allocatedIds.toIntArray()
        override fun bindAppWidgetIdIfAllowed(appWidgetId: Int, provider: ComponentName): Boolean = true
        override fun getInstalledCatalog(gridProfile: DesktopGridProfile): List<WidgetCatalogItem> = emptyList()
        override fun isProviderInstalled(providerPackage: String, providerClass: String): Boolean {
            return installedPackages.contains(providerPackage)
        }

        override fun hasConfigurationActivity(appWidgetId: Int): Boolean = false
        override fun isReconfigurable(appWidgetId: Int): Boolean = false
        override fun updateAppWidgetSizes(
            appWidgetId: Int,
            sizesDp: List<ResponsiveWidgetSizeDp>,
            minWidthDp: Int,
            minHeightDp: Int,
            maxWidthDp: Int,
            maxHeightDp: Int
        ) {}
    }

    private fun createWidget(id: Int, pkg: String = "com.test.pkg") = PlacedWidget(
        appWidgetId = id,
        providerPackage = pkg,
        providerClass = "$pkg.Widget",
        pageIndex = 0,
        cellX = 0,
        cellY = 0,
        spanX = 2,
        spanY = 2
    )

    @Test
    fun persistedWidgetNotInHost_isRemovedFromRepository() = runBlocking {
        val w1 = createWidget(id = 101, pkg = "com.app.installed")
        val repository = FakeWidgetDeckRepository(initialWidgets = listOf(w1))
        val gateway = FakeWidgetHostGateway(
            allocatedIds = emptyList(), // Not allocated in host
            installedPackages = setOf("com.app.installed")
        )
        val useCase = ReconcileOrphanWidgetsUseCase(repository, gateway)

        useCase()

        assertEquals(listOf(101), repository.removedWidgetIds)
        // Since not in host allocated IDs, host delete should NOT be called
        assertTrue(gateway.deletedHostWidgetIds.isEmpty())
        assertEquals(1, repository.pruneCallsCount)
    }

    @Test
    fun persistedWidgetWithUninstalledProvider_isRemovedFromRepoAndDeleteCalled() = runBlocking {
        val w1 = createWidget(id = 102, pkg = "com.app.uninstalled")
        val repository = FakeWidgetDeckRepository(initialWidgets = listOf(w1))
        val gateway = FakeWidgetHostGateway(
            allocatedIds = listOf(102),
            installedPackages = emptySet() // Not installed!
        )
        val useCase = ReconcileOrphanWidgetsUseCase(repository, gateway)

        useCase()

        assertEquals(listOf(102), repository.removedWidgetIds)
        assertEquals(listOf(102), gateway.deletedHostWidgetIds)
        assertEquals(1, repository.pruneCallsCount)
    }

    @Test
    fun hostAllocatedIdNotInRepository_isDeletedFromHost() = runBlocking {
        val repository = FakeWidgetDeckRepository(initialWidgets = emptyList())
        val gateway = FakeWidgetHostGateway(
            allocatedIds = listOf(201, 202),
            installedPackages = emptySet()
        )
        val useCase = ReconcileOrphanWidgetsUseCase(repository, gateway)

        useCase()

        assertTrue(repository.removedWidgetIds.isEmpty())
        assertEquals(listOf(201, 202), gateway.deletedHostWidgetIds)
        assertEquals(1, repository.pruneCallsCount)
    }

    @Test
    fun validPersistedWidget_isKeptIntact() = runBlocking {
        val w1 = createWidget(id = 301, pkg = "com.valid.pkg")
        val repository = FakeWidgetDeckRepository(initialWidgets = listOf(w1))
        val gateway = FakeWidgetHostGateway(
            allocatedIds = listOf(301),
            installedPackages = setOf("com.valid.pkg")
        )
        val useCase = ReconcileOrphanWidgetsUseCase(repository, gateway)

        useCase()

        assertTrue(repository.removedWidgetIds.isEmpty())
        assertTrue(gateway.deletedHostWidgetIds.isEmpty())
        assertEquals(1, repository.pruneCallsCount)
    }
}
