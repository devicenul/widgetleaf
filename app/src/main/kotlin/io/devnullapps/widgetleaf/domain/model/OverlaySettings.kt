package io.devnullapps.widgetleaf.domain.model

data class OverlaySettings(
    val blurRadiusDp: Int = 36,
    val scrimAlpha: Float = 0.72f,
    val useDynamicColor: Boolean = false,
    val openOnPageOne: Boolean = false,
    val lastViewedPageIndex: Int = 0,
    val launchOnStartup: Boolean = false,
    val pinTaskbarLeft: Boolean = false,
    val showInRunningTaskbarRight: Boolean = false,
    val pinTopBarLeftStatusIcon: Boolean = false,
    val pinTopBarRightQsTile: Boolean = false,
    val useCenteredBentoCanvas: Boolean = true
)
