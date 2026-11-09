package io.devnullapps.widgetleaf.services

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BootCompletedReceiverTest {

    private lateinit var context: Context
    private lateinit var taskbarPlacementManager: TaskbarPlacementManager
    private lateinit var statusBarTriggerManager: StatusBarTriggerManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        taskbarPlacementManager = TaskbarPlacementManager()
        statusBarTriggerManager = StatusBarTriggerManager(context)
    }

    @After
    fun tearDown() {
        statusBarTriggerManager.cancelTriggerNotification()
        DefaultServicesContainer.setContainerForTesting(null)
    }

    @Test
    fun onReceiveWithBootCompletedAndLaunchOnStartupTrueRestoresTriggers() {
        var syncTriggerCalled: Boolean? = null
        var syncShortcutsCalled = false

        val receiver = BootCompletedReceiver(
            statusBarTriggerManagerProvider = {
                object : StatusBarTriggerManager(context) {
                    override fun syncStatusBarTrigger(enabled: Boolean) {
                        syncTriggerCalled = enabled
                    }
                }
            },
            taskbarPlacementManagerProvider = {
                object : TaskbarPlacementManager() {
                    override fun syncDynamicShortcuts(context: Context): Boolean {
                        syncShortcutsCalled = true
                        return true
                    }
                }
            },
            settingsProvider = {
                OverlaySettings(launchOnStartup = true, pinTopBarLeftStatusIcon = false)
            },
            dispatcher = Dispatchers.Unconfined
        )

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertThat(syncTriggerCalled).isEqualTo(true)
        assertThat(syncShortcutsCalled).isTrue()
    }

    @Test
    fun onReceiveWithBootCompletedAndLaunchOnStartupFalseCancelsTriggers() {
        var syncTriggerCalled: Boolean? = null
        var syncShortcutsCalled = false

        val receiver = BootCompletedReceiver(
            statusBarTriggerManagerProvider = {
                object : StatusBarTriggerManager(context) {
                    override fun syncStatusBarTrigger(enabled: Boolean) {
                        syncTriggerCalled = enabled
                    }
                }
            },
            taskbarPlacementManagerProvider = {
                object : TaskbarPlacementManager() {
                    override fun syncDynamicShortcuts(context: Context): Boolean {
                        syncShortcutsCalled = true
                        return true
                    }
                }
            },
            settingsProvider = {
                OverlaySettings(launchOnStartup = false, pinTopBarLeftStatusIcon = false)
            },
            dispatcher = Dispatchers.Unconfined
        )

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertThat(syncTriggerCalled).isEqualTo(false)
        assertThat(syncShortcutsCalled).isFalse()
    }

    @Test
    fun onReceiveWithPinTopBarLeftStatusIconTrueEnablesStatusTriggerEvenIfLaunchOnStartupFalse() {
        var syncTriggerCalled: Boolean? = null
        var syncShortcutsCalled = false

        val receiver = BootCompletedReceiver(
            statusBarTriggerManagerProvider = {
                object : StatusBarTriggerManager(context) {
                    override fun syncStatusBarTrigger(enabled: Boolean) {
                        syncTriggerCalled = enabled
                    }
                }
            },
            taskbarPlacementManagerProvider = {
                object : TaskbarPlacementManager() {
                    override fun syncDynamicShortcuts(context: Context): Boolean {
                        syncShortcutsCalled = true
                        return true
                    }
                }
            },
            settingsProvider = {
                OverlaySettings(launchOnStartup = false, pinTopBarLeftStatusIcon = true)
            },
            dispatcher = Dispatchers.Unconfined
        )

        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        assertThat(syncTriggerCalled).isEqualTo(true)
        assertThat(syncShortcutsCalled).isTrue()
    }

    @Test
    fun onReceiveIgnoresUnsupportedAction() {
        var syncTriggerCalled = false

        val receiver = BootCompletedReceiver(
            statusBarTriggerManagerProvider = {
                object : StatusBarTriggerManager(context) {
                    override fun syncStatusBarTrigger(enabled: Boolean) {
                        syncTriggerCalled = true
                    }
                }
            },
            taskbarPlacementManagerProvider = { taskbarPlacementManager },
            settingsProvider = {
                OverlaySettings(launchOnStartup = true)
            },
            dispatcher = Dispatchers.Unconfined
        )

        receiver.onReceive(context, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))

        assertThat(syncTriggerCalled).isFalse()
    }

    @Test
    fun onReceiveHandlesMyPackageReplacedAction() {
        var syncTriggerCalled: Boolean? = null

        val receiver = BootCompletedReceiver(
            statusBarTriggerManagerProvider = {
                object : StatusBarTriggerManager(context) {
                    override fun syncStatusBarTrigger(enabled: Boolean) {
                        syncTriggerCalled = enabled
                    }
                }
            },
            taskbarPlacementManagerProvider = { taskbarPlacementManager },
            settingsProvider = {
                OverlaySettings(launchOnStartup = true)
            },
            dispatcher = Dispatchers.Unconfined
        )

        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertThat(syncTriggerCalled).isEqualTo(true)
    }

    @Test
    fun taskbarPlacementManagerSyncDynamicShortcutsAddsShortcuts() {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val success = taskbarPlacementManager.syncDynamicShortcuts(context)

        assertThat(success).isTrue()
        val dynamicShortcuts = shortcutManager.dynamicShortcuts
        assertThat(dynamicShortcuts).hasSize(2)
        val shortcutIds = dynamicShortcuts.map { it.id }
        assertThat(shortcutIds).contains(TaskbarPlacementManager.SHORTCUT_ADD_WIDGET)
        assertThat(shortcutIds).contains(TaskbarPlacementManager.SHORTCUT_OVERLAY_SETTINGS)
    }

    @Test
    fun taskbarPlacementManagerRequestPinToTaskbarLeftHandlesUnsupportedOrSupported() {
        val result = taskbarPlacementManager.requestPinToTaskbarLeft(context)
        val sm = context.getSystemService(ShortcutManager::class.java)
        if (sm.isRequestPinShortcutSupported) {
            assertThat(result).isTrue()
        } else {
            assertThat(result).isFalse()
        }
    }

    @Test
    fun taskbarPlacementManagerSyncRunningTaskbarPresenceExecutesCleanly() {
        taskbarPlacementManager.syncRunningTaskbarPresence(context, keepInRunningTaskbarRight = true)
        val sm = context.getSystemService(ShortcutManager::class.java)
        assertThat(sm.dynamicShortcuts).hasSize(2)

        taskbarPlacementManager.syncRunningTaskbarPresence(context, keepInRunningTaskbarRight = false)
        assertThat(sm.dynamicShortcuts).hasSize(2)
    }

    @Test
    fun activeDisplayLaunchHelperCreatesValidIntentsAndOptions() {
        val intent = ActiveDisplayLaunchHelper.createLaunchIntent(
            context,
            ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
        )
        assertThat(intent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
        assertThat(intent.component?.className).isEqualTo(ActiveDisplayLaunchHelper.DECK_ACTIVITY_CLASS_NAME)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)

        val pickerIntent = ActiveDisplayLaunchHelper.createLaunchIntent(
            context,
            ActiveDisplayLaunchHelper.ACTION_OPEN_WIDGET_PICKER
        )
        assertThat(pickerIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_OPEN_WIDGET_PICKER)

        val settingsIntent = ActiveDisplayLaunchHelper.createLaunchIntent(
            context,
            ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS
        )
        assertThat(settingsIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS)

        val displayId = ActiveDisplayLaunchHelper.getActiveDisplayId(context)
        assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)

        val options = ActiveDisplayLaunchHelper.createLaunchOptions(context, displayId)
        assertThat(options.launchDisplayId).isEqualTo(displayId)

        val pendingIntent = ActiveDisplayLaunchHelper.createLaunchPendingIntent(context)
        assertThat(pendingIntent).isNotNull()
    }
}
