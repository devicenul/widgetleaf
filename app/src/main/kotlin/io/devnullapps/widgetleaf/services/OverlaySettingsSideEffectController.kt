package io.devnullapps.widgetleaf.services

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import io.devnullapps.widgetleaf.domain.model.OverlaySettings

/**
 * Applies immediate system-level side effects whenever [OverlaySettings] loads or changes:
 * - Enables/disables [BootCompletedReceiver] with [PackageManager.DONT_KILL_APP] based on
 *   [OverlaySettings.launchOnStartup].
 * - Enables/disables [WidgetDeckTileService] with [PackageManager.DONT_KILL_APP] based on
 *   [OverlaySettings.pinTopBarRightQsTile], requests Quick Settings listening state when enabled,
 *   and triggers the system tile-pin prompt only on explicit false -> true user transitions.
 * - Keeps status-bar notification trigger and taskbar recents exclusion synchronized.
 */
open class OverlaySettingsSideEffectController(
    private val context: Context,
    private val statusBarTriggerManager: StatusBarTriggerManager,
    private val taskbarPlacementManager: TaskbarPlacementManager,
    private val tileListeningRequester: (Context, ComponentName) -> Unit = { ctx, component ->
        try {
            TileService.requestListeningState(ctx, component)
        } catch (_: Throwable) {
            // Ignored in headless / unsupported test environments
        }
    },
    private val pinQsTileRequester: (Context, (Int) -> Unit) -> Unit = { ctx, onResult ->
        WidgetDeckTileService.requestPinQsTile(ctx, onResult)
    }
) {
    private val appContext: Context = context.applicationContext

    open fun applySettings(
        settings: OverlaySettings,
        previousSettings: OverlaySettings?,
        promptContext: Context = context,
        onPinPromptStarted: (() -> Unit)? = null,
        onPinPromptFinished: ((Int) -> Unit)? = null
    ) {
        syncComponentEnabledState(
            component = ComponentName(appContext, BootCompletedReceiver::class.java),
            enabled = settings.launchOnStartup
        )

        val tileComponent = ComponentName(appContext, WidgetDeckTileService::class.java)
        syncComponentEnabledState(
            component = tileComponent,
            enabled = settings.pinTopBarRightQsTile
        )

        if (settings.pinTopBarRightQsTile) {
            if (previousSettings == null || !previousSettings.pinTopBarRightQsTile) {
                tileListeningRequester(appContext, tileComponent)
            }
            val isUserToggleOn = previousSettings != null && !previousSettings.pinTopBarRightQsTile
            if (isUserToggleOn) {
                onPinPromptStarted?.invoke()
                pinQsTileRequester(promptContext) { result ->
                    onPinPromptFinished?.invoke(result)
                }
            }
        }

        statusBarTriggerManager.syncStatusBarTrigger(true)
        taskbarPlacementManager.syncRunningTaskbarPresence(appContext)
    }

    internal fun syncComponentEnabledState(component: ComponentName, enabled: Boolean) {
        val pm = appContext.packageManager
        val desiredState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            if (pm.getComponentEnabledSetting(component) != desiredState) {
                pm.setComponentEnabledSetting(component, desiredState, PackageManager.DONT_KILL_APP)
            }
        } catch (_: Throwable) {
            // Ignored if component cannot be resolved in stripped test manifests
        }
    }
}
