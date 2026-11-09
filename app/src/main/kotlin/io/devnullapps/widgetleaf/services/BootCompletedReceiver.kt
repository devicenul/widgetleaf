package io.devnullapps.widgetleaf.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered on system startup or package replacement.
 *
 * Restores user-configured top-bar status trigger and taskbar dynamic shortcuts
 * when [OverlaySettings.launchOnStartup] or [OverlaySettings.pinTopBarLeftStatusIcon] is enabled.
 */
class BootCompletedReceiver(
    private val statusBarTriggerManagerProvider: (Context) -> StatusBarTriggerManager = {
        DefaultServicesContainer.getStatusBarTriggerManager(it)
    },
    private val taskbarPlacementManagerProvider: (Context) -> TaskbarPlacementManager = {
        DefaultServicesContainer.getTaskbarPlacementManager(it)
    },
    private val settingsProvider: (suspend (Context) -> OverlaySettings)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : BroadcastReceiver() {

    constructor() : this(
        statusBarTriggerManagerProvider = { DefaultServicesContainer.getStatusBarTriggerManager(it) },
        taskbarPlacementManagerProvider = { DefaultServicesContainer.getTaskbarPlacementManager(it) },
        settingsProvider = null,
        dispatcher = Dispatchers.IO
    )

    companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val statusBarTriggerManager = statusBarTriggerManagerProvider(context)
        val taskbarPlacementManager = taskbarPlacementManagerProvider(context)

        val pendingResult = try {
            goAsync()
        } catch (_: Throwable) {
            null
        }

        CoroutineScope(dispatcher).launch {
            try {
                val settings = settingsProvider?.invoke(context)
                    ?: DefaultServicesContainer.getPreferencesDataStore(context).settingsFlow.first()

                val shouldEnableTopBar = settings.launchOnStartup ||
                    settings.pinToTopBar ||
                    settings.pinTopBarLeftStatusIcon
                statusBarTriggerManager.syncStatusBarTrigger(shouldEnableTopBar)

                if (shouldEnableTopBar) {
                    taskbarPlacementManager.syncRunningTaskbarPresence(
                        context,
                        settings.effectiveShowInRunningTaskbar
                    )
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
