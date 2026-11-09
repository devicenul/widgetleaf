package io.devnullapps.widgetleaf.platform

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.domain.usecase.WidgetSpanCalculator
import io.devnullapps.widgetleaf.ui.hostview.WidgetDeckHost

class AndroidWidgetHostGateway(
    private val context: Context,
    private val widgetDeckHost: WidgetDeckHost = WidgetDeckHost(context),
    private val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context),
    private val spanCalculator: WidgetSpanCalculator = WidgetSpanCalculator()
) : WidgetHostGateway {

    override fun startListening() {
        try {
            widgetDeckHost.startListening()
        } catch (ignored: Throwable) {
        }
    }

    override fun stopListening() {
        try {
            widgetDeckHost.stopListening()
        } catch (ignored: Throwable) {
        }
    }

    override fun allocateAppWidgetId(): Int {
        return widgetDeckHost.allocateAppWidgetId()
    }

    override fun deleteAppWidgetId(appWidgetId: Int) {
        try {
            widgetDeckHost.deleteAppWidgetId(appWidgetId)
        } catch (ignored: Throwable) {
        }
    }

    override fun getAllocatedAppWidgetIds(): IntArray {
        return try {
            widgetDeckHost.appWidgetIds ?: IntArray(0)
        } catch (t: Throwable) {
            IntArray(0)
        }
    }

    override fun bindAppWidgetIdIfAllowed(appWidgetId: Int, provider: ComponentName): Boolean {
        return appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
    }

    override fun getInstalledCatalog(gridProfile: DesktopGridProfile): List<WidgetCatalogItem> {
        val providers = try {
            appWidgetManager.installedProviders ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }

        val pm = context.packageManager
        return providers.mapNotNull { info ->
            val component = info.provider ?: return@mapNotNull null
            val providerPackage = component.packageName
            val providerClass = component.className

            val appName = try {
                val appInfo = pm.getApplicationInfo(providerPackage, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (t: Throwable) {
                providerPackage
            }

            val widgetLabel = try {
                info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: appName
            } catch (t: Throwable) {
                appName
            }

            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    info.loadDescription(context)?.toString()
                } catch (t: Throwable) {
                    null
                }
            } else {
                null
            }

            val targetCellWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.targetCellWidth else 0
            val targetCellHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.targetCellHeight else 0
            val maxResizeWidthDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.maxResizeWidth.toFloat() else 0f
            val maxResizeHeightDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.maxResizeHeight.toFloat() else 0f

            val spans = spanCalculator.calculateSpans(
                targetCellWidth = targetCellWidth,
                targetCellHeight = targetCellHeight,
                minWidthDp = info.minWidth.toFloat(),
                minHeightDp = info.minHeight.toFloat(),
                minResizeWidthDp = info.minResizeWidth.toFloat(),
                minResizeHeightDp = info.minResizeHeight.toFloat(),
                maxResizeWidthDp = maxResizeWidthDp,
                maxResizeHeightDp = maxResizeHeightDp,
                resizeMode = info.resizeMode,
                profile = gridProfile
            )

            val hasConfig = info.configure != null
            val isReconfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0
            } else {
                false
            }

            val previewLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.previewLayout
            } else {
                0
            }

            WidgetCatalogItem(
                providerPackage = providerPackage,
                providerClass = providerClass,
                appName = appName,
                widgetLabel = widgetLabel,
                description = description,
                defaultSpanX = spans.defaultSpanX,
                defaultSpanY = spans.defaultSpanY,
                minSpanX = spans.minSpanX,
                minSpanY = spans.minSpanY,
                maxSpanX = spans.maxSpanX,
                maxSpanY = spans.maxSpanY,
                resizeMode = info.resizeMode,
                hasConfigurationActivity = hasConfig,
                isReconfigurable = isReconfig,
                previewLayoutResId = previewLayout
            )
        }.sortedWith(compareBy({ it.appName }, { it.widgetLabel }))
    }

    override fun isProviderInstalled(providerPackage: String, providerClass: String): Boolean {
        val providers = try {
            appWidgetManager.installedProviders ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        return providers.any { it.provider?.packageName == providerPackage && it.provider?.className == providerClass }
    }

    override fun hasConfigurationActivity(appWidgetId: Int): Boolean {
        val info = try {
            appWidgetManager.getAppWidgetInfo(appWidgetId)
        } catch (t: Throwable) {
            null
        }
        return info?.configure != null
    }

    override fun isReconfigurable(appWidgetId: Int): Boolean {
        val info = try {
            appWidgetManager.getAppWidgetInfo(appWidgetId)
        } catch (t: Throwable) {
            null
        } ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0
        } else {
            false
        }
    }

    override fun updateAppWidgetSizes(
        appWidgetId: Int,
        sizesDp: List<ResponsiveWidgetSizeDp>,
        minWidthDp: Int,
        minHeightDp: Int,
        maxWidthDp: Int,
        maxHeightDp: Int
    ) {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeightDp)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizeList = ArrayList<SizeF>(sizesDp.map { SizeF(it.widthDp, it.heightDp) })
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizeList)
            }
        }

        try {
            appWidgetManager.updateAppWidgetOptions(appWidgetId, options)
        } catch (ignored: Throwable) {
        }
    }
}
