package io.devnullapps.widgetleaf.ui.hostview

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

open class WidgetDeckHost(
    context: Context,
    hostId: Int = DEFAULT_HOST_ID
) : AppWidgetHost(context, hostId) {

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
        return createView(context, appWidgetId, appWidget) as DeckAppWidgetHostView
    }

    companion object {
        const val DEFAULT_HOST_ID = 1042
    }
}
