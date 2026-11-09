package io.devnullapps.widgetleaf.services

import android.content.Context
import io.devnullapps.widgetleaf.data.local.PreferencesDataStore

/**
 * Dependency Injection container interface for WidgetLeaf background triggers and placement services.
 */
interface ServicesContainer {
    fun getStatusBarTriggerManager(context: Context): StatusBarTriggerManager
    fun getTaskbarPlacementManager(context: Context): TaskbarPlacementManager
    fun getPreferencesDataStore(context: Context): PreferencesDataStore
}

/**
 * Default singleton DI container with test override capabilities.
 */
object DefaultServicesContainer : ServicesContainer {

    @Volatile
    private var customContainer: ServicesContainer? = null

    /**
     * Overrides the active container for unit testing. Pass null to reset to defaults.
     */
    fun setContainerForTesting(container: ServicesContainer?) {
        customContainer = container
    }

    override fun getStatusBarTriggerManager(context: Context): StatusBarTriggerManager {
        return customContainer?.getStatusBarTriggerManager(context)
            ?: StatusBarTriggerManager(context.applicationContext)
    }

    override fun getTaskbarPlacementManager(context: Context): TaskbarPlacementManager {
        return customContainer?.getTaskbarPlacementManager(context)
            ?: TaskbarPlacementManager()
    }

    override fun getPreferencesDataStore(context: Context): PreferencesDataStore {
        return customContainer?.getPreferencesDataStore(context)
            ?: PreferencesDataStore.createDataStore(context.applicationContext)
    }
}
