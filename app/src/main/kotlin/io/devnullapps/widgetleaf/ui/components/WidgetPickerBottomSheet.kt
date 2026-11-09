package io.devnullapps.widgetleaf.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.devnullapps.widgetleaf.domain.model.WidgetCatalogItem
import io.devnullapps.widgetleaf.ui.theme.GlassSurfaceLight
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme

@Composable
fun WidgetPickerBottomSheet(
    isOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredCatalogByApp: Map<String, List<WidgetCatalogItem>>,
    onAddWidgetAutoPlace: (WidgetCatalogItem) -> Unit,
    onDragOutCatalogItem: ((item: WidgetCatalogItem, targetCellX: Int, targetCellY: Int) -> Unit)? = null,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val collapsedState = remember { mutableStateMapOf<String, Boolean>() }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Backdrop for outside click dismissal
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WidgetLeafTheme.palette.scrimDark.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
        )

        // Bottom sheet card
        GlassHudSurface(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 560.dp)
                .testTag("widget_picker_sheet")
                .padding(20.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Widgets Catalog",
                        color = WidgetLeafTheme.palette.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(
                            text = "✕",
                            color = WidgetLeafTheme.palette.textSecondary,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("widget_picker_search"),
                    placeholder = {
                        Text(
                            text = "Search widgets...",
                            color = WidgetLeafTheme.palette.textTertiary,
                            fontSize = 14.sp
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = WidgetLeafTheme.palette.textPrimary,
                        unfocusedTextColor = WidgetLeafTheme.palette.textPrimary,
                        focusedBorderColor = WidgetLeafTheme.palette.leafPrimary,
                        unfocusedBorderColor = WidgetLeafTheme.palette.glassBorder,
                        cursorColor = WidgetLeafTheme.palette.leafPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Catalog grouped by application
                if (filteredCatalogByApp.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No widgets found",
                            color = WidgetLeafTheme.palette.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    val catalogScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(catalogScrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        filteredCatalogByApp.forEach { (appName, items) ->
                            val isCollapsed = collapsedState[appName] == true

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        collapsedState[appName] = !isCollapsed
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isCollapsed) "▶ " else "▼ ",
                                        color = WidgetLeafTheme.palette.textSecondary,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = appName,
                                        color = WidgetLeafTheme.palette.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(WidgetLeafTheme.palette.glassSurfaceHover)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${items.size}",
                                        color = WidgetLeafTheme.palette.textSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            if (!isCollapsed) {
                                items.forEach { catalogItem ->
                                    WidgetCatalogItemCard(
                                        item = catalogItem,
                                        onAdd = { onAddWidgetAutoPlace(catalogItem) },
                                        onDragOut = { targetX, targetY ->
                                            onDragOutCatalogItem?.invoke(catalogItem, targetX, targetY)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCatalogItemCard(
    item: WidgetCatalogItem,
    onAdd: () -> Unit,
    onDragOut: (targetCellX: Int, targetCellY: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurfaceLight)
            .border(0.5.dp, WidgetLeafTheme.palette.glassBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Drag-out handle
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .pointerInput(item.componentKey) {
                        detectDragGestures(
                            onDrag = { change, _ ->
                                change.consume()
                            },
                            onDragEnd = {
                                onDragOut(0, 0)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⋮⋮",
                    color = WidgetLeafTheme.palette.textTertiary,
                    fontSize = 14.sp
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.widgetLabel,
                        color = WidgetLeafTheme.palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Span badge e.g. "2×2"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.20f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${item.defaultSpanX}×${item.defaultSpanY}",
                            color = WidgetLeafTheme.palette.leafBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (!item.description.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.description,
                        color = WidgetLeafTheme.palette.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Add button
            Box(
                modifier = Modifier
                    .testTag("add_widget_button_${item.componentKey}")
                    .clip(RoundedCornerShape(10.dp))
                    .background(WidgetLeafTheme.palette.leafPrimary)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Add",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
