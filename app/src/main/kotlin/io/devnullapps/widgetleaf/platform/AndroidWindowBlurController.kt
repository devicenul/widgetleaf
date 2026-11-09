package io.devnullapps.widgetleaf.platform

import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.function.Consumer

class AndroidWindowBlurController(
    private val context: Context
) : BlurController {

    private val windowManager: WindowManager? = try {
        context.getSystemService(WindowManager::class.java)
    } catch (e: Throwable) {
        null
    }

    private val _isBlurSupportedAndEnabled = MutableStateFlow(checkBlurSupported())
    override val isBlurSupportedAndEnabled: StateFlow<Boolean> = _isBlurSupportedAndEnabled.asStateFlow()

    private val blurEnabledListener = Consumer<Boolean> { enabled ->
        _isBlurSupportedAndEnabled.value = enabled
    }

    init {
        registerBlurListener()
    }

    private fun checkBlurSupported(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager != null) {
                windowManager.isCrossWindowBlurEnabled
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun registerBlurListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager != null) {
            try {
                val executor = ContextCompat.getMainExecutor(context)
                windowManager.addCrossWindowBlurEnabledListener(executor, blurEnabledListener)
            } catch (t: Throwable) {
                // Ignore if listener cannot be added in current environment
            }
        }
    }

    override fun attachToWindow(window: Window, blurRadiusDp: Int) {
        val density = window.context.resources.displayMetrics.density
        val radiusPx = (blurRadiusDp * density).toInt().coerceAtLeast(0)

        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val params = window.attributes
            params.blurBehindRadius = radiusPx
            window.attributes = params

            try {
                window.setBackgroundBlurRadius(radiusPx)
            } catch (ignored: Throwable) {
            }
        }
    }

    override fun updateBlurRadius(window: Window, blurRadiusDp: Int) {
        val density = window.context.resources.displayMetrics.density
        val radiusPx = (blurRadiusDp * density).toInt().coerceAtLeast(0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val params = window.attributes
            params.blurBehindRadius = radiusPx
            window.attributes = params

            try {
                window.setBackgroundBlurRadius(radiusPx)
            } catch (ignored: Throwable) {
            }
        }
    }

    override fun detachFromWindow(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val params = window.attributes
            params.blurBehindRadius = 0
            window.attributes = params

            try {
                window.setBackgroundBlurRadius(0)
            } catch (ignored: Throwable) {
            }
        }
    }

    override fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager != null) {
            try {
                windowManager.removeCrossWindowBlurEnabledListener(blurEnabledListener)
            } catch (t: Throwable) {
            }
        }
    }
}
