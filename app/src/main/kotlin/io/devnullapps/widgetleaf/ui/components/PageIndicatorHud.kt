package io.devnullapps.widgetleaf.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.ui.theme.WidgetLeafTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageIndicatorHud(
    pages: List<DeckPage>,
    activePageIndex: Int,
    editingPageLabelIndex: Int?,
    editingPageLabelDraft: String,
    onSelectPage: (pageIndex: Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onOpenWidgetPicker: () -> Unit,
    onOpenOverlaySettings: () -> Unit = {},
    onStartEditPageLabel: (pageIndex: Int) -> Unit,
    onDraftChange: (draft: String) -> Unit = {},
    onCommitPageLabel: (pageIndex: Int, draft: String) -> Unit,
    onCancelEditPageLabel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    GlassHudSurface(
        modifier = modifier
            .testTag("bottom_pagination_hud")
            .height(52.dp),
        shape = RoundedCornerShape(26.dp),
        backgroundColor = Color(0xD9161B22),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Previous page button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPreviousPage)
                    .testTag("page_prev_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "◀",
                    color = WidgetLeafTheme.palette.textSecondary,
                    fontSize = 12.sp
                )
            }

            // Interactive page pills
            pages.forEach { page ->
                val isCurrent = page.pageIndex == activePageIndex
                val isEditing = editingPageLabelIndex == page.pageIndex

                if (isEditing) {
                    var localDraft by remember(page.pageIndex, editingPageLabelDraft) {
                        mutableStateOf(editingPageLabelDraft)
                    }
                    val focusRequester = remember { FocusRequester() }

                    LaunchedEffect(isEditing) {
                        focusRequester.requestFocus()
                    }

                    Row(
                        modifier = Modifier
                            .testTag("page_pill_${page.pageIndex}")
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(WidgetLeafTheme.palette.glassSurfaceHover)
                            .border(1.dp, WidgetLeafTheme.palette.leafPrimary, RoundedCornerShape(17.dp))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BasicTextField(
                            value = localDraft,
                            onValueChange = {
                                localDraft = it
                                onDraftChange(it)
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = WidgetLeafTheme.palette.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            cursorBrush = SolidColor(WidgetLeafTheme.palette.leafPrimary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { onCommitPageLabel(page.pageIndex, localDraft) }
                            ),
                            modifier = Modifier
                                .widthIn(min = 36.dp, max = 120.dp)
                                .focusRequester(focusRequester)
                                .testTag("page_label_editor_${page.pageIndex}")
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                                        onCommitPageLabel(page.pageIndex, localDraft)
                                        true
                                    } else if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                                        onCancelEditPageLabel()
                                        true
                                    } else {
                                        false
                                    }
                                }
                        )

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.35f))
                                .testTag("page_label_save_${page.pageIndex}")
                                .clickable {
                                    onCommitPageLabel(page.pageIndex, localDraft)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓",
                                color = WidgetLeafTheme.palette.leafBright,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .testTag("page_pill_${page.pageIndex}")
                            .height(34.dp)
                            .widthIn(min = 34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(
                                if (isCurrent) {
                                    WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.22f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .border(
                                width = if (isCurrent) 1.dp else 0.5.dp,
                                color = if (isCurrent) {
                                    WidgetLeafTheme.palette.leafPrimary
                                } else {
                                    WidgetLeafTheme.palette.glassBorder
                                },
                                shape = RoundedCornerShape(17.dp)
                            )
                            .pointerInput(page.pageIndex) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (event.type == PointerEventType.Press &&
                                            (event.buttons.isSecondaryPressed ||
                                                event.keyboardModifiers.isCtrlPressed ||
                                                pressedCount >= 2)
                                        ) {
                                            event.changes.forEach { it.consume() }
                                            onStartEditPageLabel(page.pageIndex)
                                            do {
                                                val next = awaitPointerEvent(PointerEventPass.Initial)
                                                next.changes.forEach { it.consume() }
                                            } while (next.changes.any { it.pressed })
                                        }
                                    }
                                }
                            }
                            .combinedClickable(
                                onClick = { onSelectPage(page.pageIndex) },
                                onLongClick = { onStartEditPageLabel(page.pageIndex) }
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = page.displayLabel,
                            color = if (isCurrent) {
                                WidgetLeafTheme.palette.leafBright
                            } else {
                                WidgetLeafTheme.palette.textSecondary
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            // Next page button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNextPage)
                    .testTag("page_next_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "▶",
                    color = WidgetLeafTheme.palette.textSecondary,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            // Add widget button (34dp circular pill with clean separation)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.22f))
                    .border(0.5.dp, WidgetLeafTheme.palette.leafPrimary.copy(alpha = 0.6f), CircleShape)
                    .clickable(onClick = onOpenWidgetPicker)
                    .testTag("hud_add_widget_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    color = WidgetLeafTheme.palette.leafBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Overlay settings button (34dp circular pill with clean separation)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(WidgetLeafTheme.palette.glassSurfaceHover)
                    .border(0.5.dp, WidgetLeafTheme.palette.glassBorder, CircleShape)
                    .clickable(onClick = onOpenOverlaySettings)
                    .testTag("hud_settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚙",
                    color = WidgetLeafTheme.palette.textSecondary,
                    fontSize = 15.sp
                )
            }
        }
    }
}
