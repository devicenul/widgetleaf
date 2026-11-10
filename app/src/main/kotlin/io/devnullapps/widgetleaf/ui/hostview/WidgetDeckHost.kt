package io.devnullapps.widgetleaf.ui.hostview

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

import android.view.ViewGroup

open class WidgetDeckHost(
    context: Context,
    hostId: Int = DEFAULT_HOST_ID
) : AppWidgetHost(context, hostId) {

    private val hostViewCache = mutableMapOf<Int, DeckAppWidgetHostView>()

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView {
        return DeckAppWidgetHostView(context)
    }

    fun createHostView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): DeckAppWidgetHostView {
        val view = hostViewCache.getOrPut(appWidgetId) {
            createView(context, appWidgetId, appWidget) as DeckAppWidgetHostView
        }
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    override fun deleteAppWidgetId(appWidgetId: Int) {
        hostViewCache.remove(appWidgetId)
        super.deleteAppWidgetId(appWidgetId)
    }

    override fun clearViews() {
        hostViewCache.clear()
        super.clearViews()
    }

    companion object {
        const val DEFAULT_HOST_ID = 1042
    }
}
