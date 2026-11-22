package io.devnullapps.widgetleaf.ui.hostview

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import android.view.ViewGroup
import java.lang.ref.WeakReference

data class HostViewEnvironment(
    val displayId: Int,
    val densityDpi: Int,
    val uiMode: Int
) {
    companion object {
        fun from(
            context: Context,
            configuration: Configuration = context.resources.configuration
        ): HostViewEnvironment {
            val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    context.display?.displayId ?: Display.DEFAULT_DISPLAY
                } catch (_: Throwable) {
                    Display.DEFAULT_DISPLAY
                }
            } else {
                Display.DEFAULT_DISPLAY
            }
            val densityDpi = configuration.densityDpi.takeIf { it > 0 }
                ?: context.resources.displayMetrics.densityDpi
            val uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return HostViewEnvironment(
                displayId = displayId,
                densityDpi = densityDpi,
                uiMode = uiMode
            )
        }
    }
}

data class HostViewKey(
    val appWidgetId: Int,
    val environment: HostViewEnvironment
)

open class WidgetDeckHost(
    context: Context,
    hostId: Int = DEFAULT_HOST_ID
) : AppWidgetHost(context, hostId) {

    private data class CachedHostView(
        val key: HostViewKey,
        val view: DeckAppWidgetHostView,
        val contextRef: WeakReference<Context>
    )

    private val hostViewCache = mutableMapOf<Int, CachedHostView>()

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView {
        return DeckAppWidgetHostView(context)
    }

    fun getHostView(appWidgetId: Int): DeckAppWidgetHostView? = hostViewCache[appWidgetId]?.view

    internal fun cachedEnvironment(appWidgetId: Int): HostViewEnvironment? =
        hostViewCache[appWidgetId]?.key?.environment

    internal fun cacheSize(): Int = hostViewCache.size

    fun createHostView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): DeckAppWidgetHostView {
        val env = HostViewEnvironment.from(context)
        val key = HostViewKey(appWidgetId, env)
        val existing = hostViewCache[appWidgetId]
        val existingCtx = existing?.contextRef?.get()
        val existingActivity = unwrapActivity(existingCtx)
        val targetActivity = unwrapActivity(context)
        val sameOwner = if (targetActivity != null) {
            existingActivity === targetActivity && !targetActivity.isDestroyed && !targetActivity.isFinishing
        } else {
            existingCtx === context
        }

        if (existing != null && existing.key == key && sameOwner) {
            (existing.view.parent as? ViewGroup)?.removeView(existing.view)
            return existing.view
        }

        if (existing != null) {
            cleanupCachedView(existing.view)
            hostViewCache.remove(appWidgetId)
        }

        val view = createView(context, appWidgetId, appWidget) as DeckAppWidgetHostView
        hostViewCache[appWidgetId] = CachedHostView(
            key = key,
            view = view,
            contextRef = WeakReference(context)
        )
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun releaseViewsForContext(context: Context) {
        val targetActivity = unwrapActivity(context)
        val toRemove = hostViewCache.entries
            .filter { (_, entry) ->
                val entryCtx = entry.contextRef.get()
                entryCtx == null ||
                    entryCtx === context ||
                    (targetActivity != null && unwrapActivity(entryCtx) === targetActivity)
            }
            .map { it.key }

        for (id in toRemove) {
            hostViewCache.remove(id)?.let { entry ->
                cleanupCachedView(entry.view)
            }
        }

        if (hostViewCache.isEmpty()) {
            try {
                super.clearViews()
            } catch (_: Throwable) {
            }
        }
    }

    override fun deleteAppWidgetId(appWidgetId: Int) {
        hostViewCache.remove(appWidgetId)?.let { entry ->
            cleanupCachedView(entry.view)
        }
        super.deleteAppWidgetId(appWidgetId)
    }

    public override fun clearViews() {
        for (entry in hostViewCache.values) {
            cleanupCachedView(entry.view)
        }
        hostViewCache.clear()
        super.clearViews()
    }

    private fun cleanupCachedView(view: DeckAppWidgetHostView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.onEnterEditModeListener = null
        view.onHorizontalPageScrollListener = null
        view.onErrorRemoveOrReloadListener = null
        view.onWidgetClickedListener = null
        view.onWidgetInteractionListener = null
    }

    private tailrec fun unwrapActivity(context: Context?): Activity? {
        return when (context) {
            null -> null
            is Activity -> context
            is ContextWrapper -> unwrapActivity(context.baseContext)
            else -> null
        }
    }

    companion object {
        const val DEFAULT_HOST_ID = 1042
    }
}
