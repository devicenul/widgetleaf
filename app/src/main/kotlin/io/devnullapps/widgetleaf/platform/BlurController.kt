package io.devnullapps.widgetleaf.platform

import android.view.Window
import kotlinx.coroutines.flow.StateFlow

interface BlurController {

    val isBlurSupportedAndEnabled: StateFlow<Boolean>

    fun attachToWindow(window: Window, blurRadiusDp: Int)

    fun updateBlurRadius(window: Window, blurRadiusDp: Int)

    fun detachFromWindow(window: Window)

    fun release() {}

    fun effectiveScrimAlpha(configuredAlpha: Float, isBlurActive: Boolean): Float {
        return if (isBlurActive) {
            configuredAlpha
        } else {
            (configuredAlpha + 0.20f).coerceAtMost(0.94f)
        }
    }
}
