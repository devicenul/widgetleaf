package io.devnullapps.widgetleaf.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.data.local.LeafDatabase
import io.devnullapps.widgetleaf.data.local.PreferencesDataStore
import io.devnullapps.widgetleaf.data.repository.DefaultWidgetDeckRepository
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.domain.model.TopBarPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class WidgetDeckRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: LeafDatabase
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesDataStore: PreferencesDataStore
    private lateinit var repository: DefaultWidgetDeckRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LeafDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStoreFile = tempFolder.newFile("test_settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile }
        )
        preferencesDataStore = PreferencesDataStore(dataStore)
        repository = DefaultWidgetDeckRepository(database, preferencesDataStore)
    }

    @After
    fun tearDown() {
        database.close()
        dataStoreScope.cancel()
    }

    private fun sampleWidget(
        id: Int,
        pageIndex: Int,
        cellX: Int = 0,
        cellY: Int = 0,
        spanX: Int = 2,
        spanY: Int = 2,
        label: String = "Widget $id"
    ) = PlacedWidget(
        appWidgetId = id,
        providerPackage = "io.devnullapps.widgetleaf.test",
        providerClass = "io.devnullapps.widgetleaf.test.Provider$id",
        pageIndex = pageIndex,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY,
        label = label
    )

    @Test
    fun defaultEmptyState_emitsSinglePage1() = runTest {
        val pages = repository.pagesFlow.first()
        assertThat(pages).hasSize(1)
        assertThat(pages[0].pageIndex).isEqualTo(0)
        assertThat(pages[0].customLabel).isNull()
        assertThat(pages[0].displayLabel).isEqualTo("1")

        val widgets = repository.widgetsFlow.first()
        assertThat(widgets).isEmpty()

        val (snapshotPages, snapshotWidgets) = repository.getSnapshot()
        assertThat(snapshotPages).hasSize(1)
        assertThat(snapshotPages[0].pageIndex).isEqualTo(0)
        assertThat(snapshotPages[0].displayLabel).isEqualTo("1")
        assertThat(snapshotWidgets).isEmpty()
    }

    @Test
    fun upsertWidgetsAcrossPages_updatesPagesAndWidgetsFlow() = runTest {
        val widget0 = sampleWidget(id = 101, pageIndex = 0, cellX = 0, cellY = 0)
        val widget1 = sampleWidget(id = 102, pageIndex = 1, cellX = 1, cellY = 0)
        val widget2 = sampleWidget(id = 103, pageIndex = 2, cellX = 0, cellY = 1)

        repository.upsertWidget(widget0)
        repository.upsertWidgets(listOf(widget1, widget2))

        val pages = repository.pagesFlow.first()
        assertThat(pages).hasSize(3)
        assertThat(pages.map { it.pageIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(pages.map { it.displayLabel }).containsExactly("1", "2", "3").inOrder()

        val widgets = repository.widgetsFlow.first()
        assertThat(widgets).hasSize(3)
        assertThat(widgets.map { it.appWidgetId }).containsExactly(101, 102, 103)
    }

    @Test
    fun inlinePageRename_setsCustomLabelAndResetsOnBlankOrEmpty() = runTest {
        repository.upsertWidget(sampleWidget(id = 1, pageIndex = 0))
        repository.upsertWidget(sampleWidget(id = 2, pageIndex = 1))

        // Set custom label "Work" on page 1
        repository.updatePageLabel(pageIndex = 1, customLabel = "Work")
        var pages = repository.pagesFlow.first()
        assertThat(pages).hasSize(2)
        assertThat(pages[1].customLabel).isEqualTo("Work")
        assertThat(pages[1].displayLabel).isEqualTo("Work")
        assertThat(pages[0].displayLabel).isEqualTo("1")

        // Set empty string -> resets to null and displayLabel "2"
        repository.updatePageLabel(pageIndex = 1, customLabel = "")
        pages = repository.pagesFlow.first()
        assertThat(pages[1].customLabel).isNull()
        assertThat(pages[1].displayLabel).isEqualTo("2")

        // Set another label
        repository.updatePageLabel(pageIndex = 1, customLabel = "Personal")
        pages = repository.pagesFlow.first()
        assertThat(pages[1].customLabel).isEqualTo("Personal")
        assertThat(pages[1].displayLabel).isEqualTo("Personal")

        // Set whitespace string -> resets to null and displayLabel "2"
        repository.updatePageLabel(pageIndex = 1, customLabel = "   ")
        pages = repository.pagesFlow.first()
        assertThat(pages[1].customLabel).isNull()
        assertThat(pages[1].displayLabel).isEqualTo("2")
    }

    @Test
    fun removeLastWidgetOnCustomLabeledPage_deletesPageAndReindexesRemainingPages() = runTest {
        // Setup 3 pages with widgets
        repository.upsertWidget(sampleWidget(id = 1, pageIndex = 0))
        repository.upsertWidget(sampleWidget(id = 2, pageIndex = 1))
        repository.upsertWidget(sampleWidget(id = 3, pageIndex = 2))

        // Give pages 1 and 2 custom labels
        repository.updatePageLabel(pageIndex = 1, customLabel = "Work")
        repository.updatePageLabel(pageIndex = 2, customLabel = "Dashboard")

        var pages = repository.pagesFlow.first()
        assertThat(pages.map { it.displayLabel }).containsExactly("1", "Work", "Dashboard").inOrder()

        // Remove the only widget on page 1 (custom labeled "Work")
        repository.removeWidget(2)

        // Page 1 should be dropped regardless of custom label, and page 2 re-indexed to 1
        pages = repository.pagesFlow.first()
        assertThat(pages).hasSize(2)
        assertThat(pages[0].pageIndex).isEqualTo(0)
        assertThat(pages[0].customLabel).isNull()
        assertThat(pages[0].displayLabel).isEqualTo("1")

        assertThat(pages[1].pageIndex).isEqualTo(1)
        assertThat(pages[1].customLabel).isEqualTo("Dashboard")
        assertThat(pages[1].displayLabel).isEqualTo("Dashboard")

        // Widget 3 must be re-indexed to pageIndex 1
        val widgets = repository.widgetsFlow.first()
        assertThat(widgets).hasSize(2)
        val widget3 = widgets.first { it.appWidgetId == 3 }
        assertThat(widget3.pageIndex).isEqualTo(1)
        val widget1 = widgets.first { it.appWidgetId == 1 }
        assertThat(widget1.pageIndex).isEqualTo(0)

        // Now remove widget on page 0: page 1 (Dashboard) should become new page 0
        repository.removeWidget(1)
        pages = repository.pagesFlow.first()
        assertThat(pages).hasSize(1)
        assertThat(pages[0].pageIndex).isEqualTo(0)
        assertThat(pages[0].customLabel).isEqualTo("Dashboard")
        assertThat(pages[0].displayLabel).isEqualTo("Dashboard")

        val remainingWidgets = repository.widgetsFlow.first()
        assertThat(remainingWidgets).hasSize(1)
        assertThat(remainingWidgets[0].appWidgetId).isEqualTo(3)
        assertThat(remainingWidgets[0].pageIndex).isEqualTo(0)
    }

    @Test
    fun removeLastWidgetOnOnlyRemainingPage_resetsToSingleEmptyPage1() = runTest {
        // Setup 1 widget on page 0 with custom label "Solo"
        repository.upsertWidget(sampleWidget(id = 10, pageIndex = 0))
        repository.updatePageLabel(pageIndex = 0, customLabel = "Solo")

        var pages = repository.pagesFlow.first()
        assertThat(pages).hasSize(1)
        assertThat(pages[0].displayLabel).isEqualTo("Solo")

        // Remove the very last widget
        repository.removeWidget(10)

        // Resets to single empty page 1 with customLabel = null and displayLabel = "1"
        pages = repository.pagesFlow.first()
        assertThat(pages).hasSize(1)
        assertThat(pages[0].pageIndex).isEqualTo(0)
        assertThat(pages[0].customLabel).isNull()
        assertThat(pages[0].displayLabel).isEqualTo("1")

        val widgets = repository.widgetsFlow.first()
        assertThat(widgets).isEmpty()
    }

    @Test
    fun overlaySettings_updateAndObserveViaPreferencesDataStore() = runTest {
        // Observe initial default settings
        val initial = repository.settingsFlow.first()
        assertThat(initial.blurRadiusDp).isEqualTo(36)
        assertThat(initial.scrimAlpha).isEqualTo(0.72f)
        assertThat(initial.useDynamicColor).isFalse()
        assertThat(initial.openOnPageOne).isFalse()
        assertThat(initial.lastViewedPageIndex).isEqualTo(0)
        assertThat(initial.launchOnStartup).isFalse()
        assertThat(initial.topBarPosition).isEqualTo(TopBarPosition.LEFT)
        assertThat(initial.pinTopBarRightQsTile).isFalse()
        assertThat(initial.useCenteredBentoCanvas).isTrue()

        // Update settings
        repository.updateSettings { current ->
            current.copy(
                blurRadiusDp = 48,
                scrimAlpha = 0.5f,
                useDynamicColor = true,
                openOnPageOne = true,
                lastViewedPageIndex = 3,
                launchOnStartup = true,
                topBarPosition = TopBarPosition.RIGHT,
                pinTopBarRightQsTile = true,
                useCenteredBentoCanvas = false
            )
        }

        val updated = repository.settingsFlow.first()
        assertThat(updated.blurRadiusDp).isEqualTo(48)
        assertThat(updated.scrimAlpha).isEqualTo(0.5f)
        assertThat(updated.useDynamicColor).isTrue()
        assertThat(updated.openOnPageOne).isTrue()
        assertThat(updated.lastViewedPageIndex).isEqualTo(3)
        assertThat(updated.launchOnStartup).isTrue()
        assertThat(updated.topBarPosition).isEqualTo(TopBarPosition.RIGHT)
        assertThat(updated.pinTopBarRightQsTile).isTrue()
        assertThat(updated.useCenteredBentoCanvas).isFalse()
    }

    @Test
    fun replaceDeckState_overwritesPagesAndWidgets() = runTest {
        // Initial setup
        repository.upsertWidget(sampleWidget(id = 1, pageIndex = 0))

        // Replace entire state
        val newPages = listOf(
            DeckPage(pageIndex = 0, customLabel = "Alpha"),
            DeckPage(pageIndex = 1, customLabel = "Beta")
        )
        val newWidgets = listOf(
            sampleWidget(id = 20, pageIndex = 0),
            sampleWidget(id = 21, pageIndex = 1)
        )

        repository.replaceDeckState(pages = newPages, widgets = newWidgets)

        val (snapshotPages, snapshotWidgets) = repository.getSnapshot()
        assertThat(snapshotPages).hasSize(2)
        assertThat(snapshotPages.map { it.customLabel }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(snapshotWidgets.map { it.appWidgetId }).containsExactly(20, 21).inOrder()
    }
}
