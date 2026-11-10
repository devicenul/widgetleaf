package io.devnullapps.widgetleaf.ui.hostview

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.devnullapps.widgetleaf.domain.model.PlacedWidget

/**
 * Detaches the given [hostView] (or all children) from [container] ONLY if their current parent
 * is strictly equal to [container]. This prevents an outgoing AndroidView from removing the cached
 * [DeckAppWidgetHostView] from a new incoming container after re-parenting.
 */
fun detachHostViewIfOwned(container: ViewGroup, hostView: View? = null) {
    if (hostView != null) {
        if (hostView.parent === container) {
            container.removeView(hostView)
        }
    } else {
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child.parent === container) {
                container.removeView(child)
            }
        }
    }
}

/**
 * Robust wrapper around [DeckAppWidgetHostView] using a lightweight [FrameLayout] container.
 * This guarantees:
 * 1. Safe re-parenting of cached host views between WidgetGridPage and WidgetEditDragLayer.
 * 2. OnRelease / OnReset only remove children owned by this specific container.
 * 3. Exactly one host view is rendered with zero padding and full Bento-grid cell sizing.
 */
@Composable
fun DeckWidgetHostContainer(
    placedWidget: PlacedWidget,
    isEditing: Boolean,
    createHostView: (Context) -> DeckAppWidgetHostView,
    modifier: Modifier = Modifier,
    onEnterEditMode: ((Int) -> Unit)? = null,
    onHorizontalPageScroll: ((Float) -> Unit)? = null,
    onErrorRemoveOrReload: ((Int) -> Unit)? = null
) {
    AndroidView<FrameLayout>(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val container = FrameLayout(ctx).apply {
                setPadding(0, 0, 0, 0)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val hostView = createHostView(ctx).apply {
                (parent as? ViewGroup)?.removeView(this)
                setPadding(0, 0, 0, 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.interactionMode = if (isEditing) {
                    DeckAppWidgetHostView.HostInteractionMode.EDIT_MODE
                } else {
                    DeckAppWidgetHostView.HostInteractionMode.INTERACTIVE
                }
                onEnterEditMode?.let { listener ->
                    onEnterEditModeListener = { id, _, _ -> listener(id) }
                }
                onHorizontalPageScroll?.let { listener ->
                    onHorizontalPageScrollListener = { delta -> listener(delta) }
                }
                onErrorRemoveOrReload?.let { listener ->
                    onErrorRemoveOrReloadListener = { id -> listener(id) }
                }
            }
            container.addView(hostView)
            container
        },
        onReset = { container ->
            detachHostViewIfOwned(container)
        },
        onRelease = { container ->
            detachHostViewIfOwned(container)
        },
        update = { container ->
            var hostView = (0 until container.childCount)
                .map { container.getChildAt(it) }
                .filterIsInstance<DeckAppWidgetHostView>()
                .firstOrNull()

            if (hostView == null) {
                val view = createHostView(container.context).apply {
                    setPadding(0, 0, 0, 0)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                if (view.parent == null) {
                    container.addView(view)
                    hostView = view
                } else if (view.parent === container) {
                    hostView = view
                }
            }

            hostView?.interactionMode = if (isEditing) {
                DeckAppWidgetHostView.HostInteractionMode.EDIT_MODE
            } else {
                DeckAppWidgetHostView.HostInteractionMode.INTERACTIVE
            }
        }
    )
}
