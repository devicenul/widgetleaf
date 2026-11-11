package io.devnullapps.widgetleaf.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import io.devnullapps.widgetleaf.WidgetLeafApp
import io.devnullapps.widgetleaf.di.AppContainer
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Isolated trampoline activity that hosts 3rd-party widget configuration flows
 * in a dedicated task (`io.devnullapps.widgetleaf.configure`).
 *
 * In Android Desktop Windowing (Android 14/15/16 desktop mode), external configuration
 * activities run in `WINDOWING_MODE_FREEFORM` with title bars and window decorations.
 * Launching them from [WidgetDeckActivity] previously attached the configure activity to
 * the overlay task, mutating the task into freeform desktop mode.
 *
 * By isolating configuration inside this invisible trampoline activity with its own
 * [taskAffinity] and finishing [WidgetDeckActivity] prior to launch, the overlay task is
 * never contaminated by desktop windowing. Upon receiving configuration completion
 * ([onActivityResult]), this activity finalizes placement (or cleans up on cancel) and
 * relaunches a fresh fullscreen overlay via [ActiveDisplayLaunchHelper.launchOverlay].
 */
class WidgetConfigureTrampolineActivity : ComponentActivity() {

    private val container: AppContainer by lazy {
        testContainerOverride
            ?: (application as? WidgetLeafApp)?.appContainer
            ?: AppContainer(applicationContext, useInMemoryDb = true)
    }

    private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activityScope: CoroutineScope
        get() = testScopeOverride ?: defaultScope

    private var appWidgetId: Int = -1
    private var isReconfigure: Boolean = false
    private var candidateWidget: PlacedWidget? = null
    private var originPageIndex: Int = 0
    private var hasLaunchedConfigure: Boolean = false

    override fun onDestroy() {
        defaultScope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            appWidgetId = savedInstanceState.getInt(EXTRA_APPWIDGET_ID, -1)
            isReconfigure = savedInstanceState.getBoolean(EXTRA_IS_RECONFIGURE, false)
            originPageIndex = savedInstanceState.getInt(EXTRA_ORIGIN_PAGE_INDEX, 0)
            candidateWidget = BundleCompatHelper.getSerializable(
                savedInstanceState,
                EXTRA_CANDIDATE_WIDGET,
                PlacedWidget::class.java
            )
            hasLaunchedConfigure = savedInstanceState.getBoolean(KEY_HAS_LAUNCHED, false)
        } else {
            appWidgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, -1)
            isReconfigure = intent.getBooleanExtra(EXTRA_IS_RECONFIGURE, false)
            originPageIndex = intent.getIntExtra(EXTRA_ORIGIN_PAGE_INDEX, 0)
            candidateWidget = BundleCompatHelper.getSerializable(
                intent.extras,
                EXTRA_CANDIDATE_WIDGET,
                PlacedWidget::class.java
            )
        }

        if (appWidgetId == -1) {
            relaunchOverlayAndFinish(targetPageIndex = originPageIndex)
            return
        }

        if (!hasLaunchedConfigure) {
            hasLaunchedConfigure = true
            launchConfigureActivity()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_APPWIDGET_ID, appWidgetId)
        outState.putBoolean(EXTRA_IS_RECONFIGURE, isReconfigure)
        outState.putInt(EXTRA_ORIGIN_PAGE_INDEX, originPageIndex)
        candidateWidget?.let { outState.putSerializable(EXTRA_CANDIDATE_WIDGET, it) }
        outState.putBoolean(KEY_HAS_LAUNCHED, hasLaunchedConfigure)
    }

    private fun launchConfigureActivity() {
        val options = createConfigureLaunchOptions()
        try {
            container.widgetDeckHost.startAppWidgetConfigureActivityForResult(
                this,
                appWidgetId,
                0,
                REQUEST_CODE_CONFIGURE_WIDGET,
                options.toBundle()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start widget configure activity for id $appWidgetId", t)
            handleConfigureResult(resultOk = true)
        }
    }

    internal fun createConfigureLaunchOptions(): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = ActiveDisplayLaunchHelper.getActiveDisplayId(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        return options
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CONFIGURE_WIDGET) {
            val resultOk = (resultCode == Activity.RESULT_OK)
            handleConfigureResult(resultOk)
        }
    }

    internal fun handleConfigureResult(resultOk: Boolean): Job? {
        val widget = candidateWidget
        val targetPage = if (resultOk) {
            widget?.pageIndex ?: originPageIndex
        } else {
            originPageIndex
        }
        return if (resultOk) {
            if (!isReconfigure && widget != null) {
                activityScope.launch {
                    val (pages, widgets) = container.repository.getSnapshot()
                    val newWidgets = widgets.filter { it.appWidgetId != widget.appWidgetId } + widget
                    val requiredPageCount = maxOf(widget.pageIndex + 1, pages.size)
                    val newPages = pages.toMutableList()
                    while (newPages.size < requiredPageCount) {
                        newPages.add(DeckPage(pageIndex = newPages.size, customLabel = null))
                    }
                    container.repository.replaceDeckState(newPages, newWidgets)
                    container.repository.updateSettings { it.copy(lastViewedPageIndex = widget.pageIndex) }
                    relaunchOverlayAndFinish(targetPageIndex = targetPage)
                }
            } else {
                relaunchOverlayAndFinish(targetPageIndex = targetPage)
                null
            }
        } else {
            if (!isReconfigure && appWidgetId != -1) {
                container.widgetHostGateway.deleteAppWidgetId(appWidgetId)
            }
            relaunchOverlayAndFinish(targetPageIndex = targetPage)
            null
        }
    }

    internal fun relaunchOverlayAndFinish(targetPageIndex: Int? = null) {
        ActiveDisplayLaunchHelper.launchOverlay(
            this,
            ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK,
            targetPageIndex = targetPageIndex
        )
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val TAG = "WidgetConfigureTrampoline"
        const val REQUEST_CODE_CONFIGURE_WIDGET = 4042

        const val EXTRA_APPWIDGET_ID = "io.devnullapps.widgetleaf.EXTRA_APPWIDGET_ID"
        const val EXTRA_IS_RECONFIGURE = "io.devnullapps.widgetleaf.EXTRA_IS_RECONFIGURE"
        const val EXTRA_CANDIDATE_WIDGET = "io.devnullapps.widgetleaf.EXTRA_CANDIDATE_WIDGET"
        const val EXTRA_ORIGIN_PAGE_INDEX = "io.devnullapps.widgetleaf.EXTRA_ORIGIN_PAGE_INDEX"
        private const val KEY_HAS_LAUNCHED = "has_launched_configure"

        var testContainerOverride: AppContainer? = null
        var testScopeOverride: CoroutineScope? = null
    }
}

internal object BundleCompatHelper {
    fun <T : java.io.Serializable> getSerializable(bundle: Bundle?, key: String, clazz: Class<T>): T? {
        if (bundle == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getSerializable(key, clazz)
        } else {
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            bundle.getSerializable(key) as? T
        }
    }
}
