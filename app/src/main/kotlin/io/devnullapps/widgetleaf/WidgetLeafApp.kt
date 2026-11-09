package io.devnullapps.widgetleaf

import android.app.Application
import io.devnullapps.widgetleaf.di.AppContainer

class WidgetLeafApp : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }

    fun overrideContainerForTesting(container: AppContainer) {
        appContainer = container
    }
}
