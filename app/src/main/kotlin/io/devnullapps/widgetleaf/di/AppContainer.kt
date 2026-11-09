package io.devnullapps.widgetleaf.di

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.room.Room
import io.devnullapps.widgetleaf.data.local.LeafDatabase
import io.devnullapps.widgetleaf.data.local.PreferencesDataStore
import io.devnullapps.widgetleaf.data.repository.DefaultWidgetDeckRepository
import io.devnullapps.widgetleaf.domain.repository.WidgetDeckRepository
import io.devnullapps.widgetleaf.domain.usecase.CalculateResponsiveSizesUseCase
import io.devnullapps.widgetleaf.domain.usecase.EdgePageDragTransferUseCase
import io.devnullapps.widgetleaf.domain.usecase.GridCollisionResolver
import io.devnullapps.widgetleaf.domain.usecase.ReconcileOrphanWidgetsUseCase
import io.devnullapps.widgetleaf.domain.usecase.WidgetSpanCalculator
import io.devnullapps.widgetleaf.platform.AndroidWidgetHostGateway
import io.devnullapps.widgetleaf.platform.AndroidWindowBlurController
import io.devnullapps.widgetleaf.platform.BlurController
import io.devnullapps.widgetleaf.platform.DesktopGridProfileResolver
import io.devnullapps.widgetleaf.platform.WidgetHostGateway
import io.devnullapps.widgetleaf.services.StatusBarTriggerManager
import io.devnullapps.widgetleaf.services.TaskbarPlacementManager
import io.devnullapps.widgetleaf.ui.hostview.WidgetDeckHost

open class AppContainer(
    context: Context,
    private val useInMemoryDb: Boolean = false
) {

    private val appContext = context.applicationContext

    val database: LeafDatabase by lazy {
        if (useInMemoryDb) {
            Room.inMemoryDatabaseBuilder(appContext, LeafDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        } else {
            Room.databaseBuilder(
                appContext,
                LeafDatabase::class.java,
                "widgetleaf_deck.db"
            ).fallbackToDestructiveMigration().build()
        }
    }

    val preferencesDataStore: PreferencesDataStore by lazy {
        PreferencesDataStore.createDataStore(appContext)
    }

    val repository: WidgetDeckRepository by lazy {
        DefaultWidgetDeckRepository(
            database = database,
            preferencesDataStore = preferencesDataStore
        )
    }

    val widgetDeckHost: WidgetDeckHost by lazy {
        WidgetDeckHost(appContext, WidgetDeckHost.DEFAULT_HOST_ID)
    }

    val appWidgetManager: AppWidgetManager by lazy {
        AppWidgetManager.getInstance(appContext)
    }

    val widgetSpanCalculator: WidgetSpanCalculator by lazy {
        WidgetSpanCalculator()
    }

    val widgetHostGateway: WidgetHostGateway by lazy {
        AndroidWidgetHostGateway(
            context = appContext,
            widgetDeckHost = widgetDeckHost,
            appWidgetManager = appWidgetManager,
            spanCalculator = widgetSpanCalculator
        )
    }

    val gridProfileResolver: DesktopGridProfileResolver = DesktopGridProfileResolver

    val blurController: BlurController by lazy {
        AndroidWindowBlurController(appContext)
    }

    val statusBarTriggerManager: StatusBarTriggerManager by lazy {
        StatusBarTriggerManager(appContext)
    }

    val taskbarPlacementManager: TaskbarPlacementManager by lazy {
        TaskbarPlacementManager()
    }

    val gridCollisionResolver: GridCollisionResolver by lazy {
        GridCollisionResolver()
    }

    val calculateResponsiveSizesUseCase: CalculateResponsiveSizesUseCase by lazy {
        CalculateResponsiveSizesUseCase()
    }

    val edgePageDragTransferUseCase: EdgePageDragTransferUseCase by lazy {
        EdgePageDragTransferUseCase(gridCollisionResolver)
    }

    val reconcileOrphanWidgetsUseCase: ReconcileOrphanWidgetsUseCase by lazy {
        ReconcileOrphanWidgetsUseCase(repository, widgetHostGateway)
    }
}
