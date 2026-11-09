package io.devnullapps.widgetleaf.domain.model

data class WidgetCatalogItem(
    val providerPackage: String,
    val providerClass: String,
    val appName: String,
    val widgetLabel: String,
    val description: String? = null,
    val defaultSpanX: Int = 2,
    val defaultSpanY: Int = 2,
    val minSpanX: Int = 1,
    val minSpanY: Int = 1,
    val maxSpanX: Int = 8,
    val maxSpanY: Int = 4,
    val resizeMode: Int = PlacedWidget.RESIZE_BOTH,
    val hasConfigurationActivity: Boolean = false,
    val isReconfigurable: Boolean = false,
    val previewLayoutResId: Int = 0
) {
    val componentKey: String
        get() = "$providerPackage/$providerClass"
}
