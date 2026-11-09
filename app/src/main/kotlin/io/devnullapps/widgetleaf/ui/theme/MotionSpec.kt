package io.devnullapps.widgetleaf.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

const val OVERLAY_ENTRY_DURATION_MS = 220
const val OVERLAY_EXIT_DURATION_MS = 140
const val PAGE_FLIP_DURATION_MS = 280
const val COLUMN_STAGGER_DELAY_MS = 15
const val PAGE_FLIP_SPRING_DAMPING = 0.82f
const val PAGE_FLIP_SPRING_STIFFNESS = 380f

val OverlayEntryEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
val OverlayExitEasing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

object MotionSpec {
    const val OVERLAY_ENTRY_DURATION_MS = io.devnullapps.widgetleaf.ui.theme.OVERLAY_ENTRY_DURATION_MS
    const val OVERLAY_EXIT_DURATION_MS = io.devnullapps.widgetleaf.ui.theme.OVERLAY_EXIT_DURATION_MS
    const val PAGE_FLIP_DURATION_MS = io.devnullapps.widgetleaf.ui.theme.PAGE_FLIP_DURATION_MS
    const val COLUMN_STAGGER_DELAY_MS = io.devnullapps.widgetleaf.ui.theme.COLUMN_STAGGER_DELAY_MS
    const val PAGE_FLIP_SPRING_DAMPING = io.devnullapps.widgetleaf.ui.theme.PAGE_FLIP_SPRING_DAMPING
    const val PAGE_FLIP_SPRING_STIFFNESS = io.devnullapps.widgetleaf.ui.theme.PAGE_FLIP_SPRING_STIFFNESS

    val OverlayEntryEasing = io.devnullapps.widgetleaf.ui.theme.OverlayEntryEasing
    val OverlayExitEasing = io.devnullapps.widgetleaf.ui.theme.OverlayExitEasing
}
