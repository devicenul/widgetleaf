package io.devnullapps.widgetleaf.platform

import android.content.ComponentName
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem

data class ResponsiveWidgetSizeDp(
    val widthDp: Float,
    val heightDp: Float
)

interface WidgetHostGateway {
    fun startListening()
    fun stopListening()
    fun allocateAppWidgetId(): Int
    fun deleteAppWidgetId(appWidgetId: Int)
    fun getAllocatedAppWidgetIds(): IntArray
    fun bindAppWidgetIdIfAllowed(appWidgetId: Int, provider: ComponentName): Boolean
    fun getInstalledCatalog(gridProfile: DesktopGridProfile): List<WidgetCatalogItem>
    fun isProviderInstalled(providerPackage: String, providerClass: String): Boolean
    fun hasConfigurationActivity(appWidgetId: Int): Boolean
    fun isReconfigurable(appWidgetId: Int): Boolean
    fun updateAppWidgetSizes(
        appWidgetId: Int,
        sizesDp: List<ResponsiveWidgetSizeDp>,
        minWidthDp: Int,
        minHeightDp: Int,
        maxWidthDp: Int,
        maxHeightDp: Int
    )
}
