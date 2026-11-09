package io.devnullapps.widgetleaf.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.devnullapps.widgetleaf.domain.model.PlacedWidget

@Entity(tableName = "widget_placements")
data class WidgetPlacementEntity(
    @PrimaryKey val appWidgetId: Int,
    val providerPackage: String,
    val providerClass: String,
    val pageIndex: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val minSpanX: Int,
    val minSpanY: Int,
    val maxSpanX: Int,
    val maxSpanY: Int,
    val resizeMode: Int,
    val isReconfigurable: Boolean,
    val label: String
)

fun WidgetPlacementEntity.toDomain(): PlacedWidget = PlacedWidget(
    appWidgetId = appWidgetId,
    providerPackage = providerPackage,
    providerClass = providerClass,
    pageIndex = pageIndex,
    cellX = cellX,
    cellY = cellY,
    spanX = spanX,
    spanY = spanY,
    minSpanX = minSpanX,
    minSpanY = minSpanY,
    maxSpanX = maxSpanX,
    maxSpanY = maxSpanY,
    resizeMode = resizeMode,
    isReconfigurable = isReconfigurable,
    label = label
)

fun PlacedWidget.toEntity(): WidgetPlacementEntity = WidgetPlacementEntity(
    appWidgetId = appWidgetId,
    providerPackage = providerPackage,
    providerClass = providerClass,
    pageIndex = pageIndex,
    cellX = cellX,
    cellY = cellY,
    spanX = spanX,
    spanY = spanY,
    minSpanX = minSpanX,
    minSpanY = minSpanY,
    maxSpanX = maxSpanX,
    maxSpanY = maxSpanY,
    resizeMode = resizeMode,
    isReconfigurable = isReconfigurable,
    label = label
)
