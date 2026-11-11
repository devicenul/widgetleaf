package io.devnullapps.widgetleaf.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.di.AppContainer
import io.devnullapps.widgetleaf.domain.model.DeckPage
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class WidgetConfigureTrampolineActivityTest {

    private lateinit var appContainer: AppContainer
    private lateinit var fakeGateway: FakeWidgetHostGateway
    private lateinit var fakeRepo: FakeWidgetDeckRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        appContainer = AppContainer(context, useInMemoryDb = true)
        fakeGateway = FakeWidgetHostGateway()
        fakeRepo = FakeWidgetDeckRepository()
        // Inject fakes into appContainer
        val gatewayField = AppContainer::class.java.getDeclaredField("widgetHostGateway\$delegate")
        gatewayField.isAccessible = true
        // Set testContainerOverride
        WidgetConfigureTrampolineActivity.testContainerOverride = appContainer
    }

    @After
    fun tearDown() {
        WidgetConfigureTrampolineActivity.testContainerOverride = null
        WidgetConfigureTrampolineActivity.testScopeOverride = null
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun createCandidate(id: Int, pageIndex: Int = 0): PlacedWidget {
        return PlacedWidget(
            appWidgetId = id,
            providerPackage = "com.test.pkg",
            providerClass = "com.test.pkg.TestWidget",
            pageIndex = pageIndex,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 2
        )
    }

    @Test
    fun onCreate_invalidAppWidgetId_relaunchesOverlayAndFinishes() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetConfigureTrampolineActivity::class.java)
        val controller = Robolectric.buildActivity(WidgetConfigureTrampolineActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertThat(activity.isFinishing).isTrue()
            val shadowApp = Shadows.shadowOf(context)
            val nextIntent = shadowApp.nextStartedActivity
            assertThat(nextIntent).isNotNull()
            assertThat(nextIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
        } finally {
            controller.destroy()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun createConfigureLaunchOptions_includesDisplayIdAndBalMode() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WidgetConfigureTrampolineActivity::class.java).apply {
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_APPWIDGET_ID, 42)
        }
        val controller = Robolectric.buildActivity(WidgetConfigureTrampolineActivity::class.java, intent).create()
        try {
            val activity = controller.get()
            val options = activity.createConfigureLaunchOptions()
            assertThat(options.launchDisplayId).isEqualTo(ActiveDisplayLaunchHelper.getActiveDisplayId(activity))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                assertThat(options.pendingIntentBackgroundActivityStartMode)
                    .isEqualTo(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
        } finally {
            controller.destroy()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun onActivityResult_resultOk_newWidget_persistsWidgetAndRelaunchesOverlay() = runTest {
        WidgetConfigureTrampolineActivity.testScopeOverride = this
        val context = ApplicationProvider.getApplicationContext<Application>()
        val candidate = createCandidate(id = 55, pageIndex = 1)
        val intent = Intent(context, WidgetConfigureTrampolineActivity::class.java).apply {
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_APPWIDGET_ID, 55)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_IS_RECONFIGURE, false)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_CANDIDATE_WIDGET, candidate)
        }
        val controller = Robolectric.buildActivity(WidgetConfigureTrampolineActivity::class.java, intent).setup()
        try {
            val activity = controller.get()

            // Deliver RESULT_OK
            activity.handleConfigureResult(resultOk = true)?.join()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            // Widget must be saved into repository
            val (pages, widgets) = appContainer.repository.getSnapshot()
            val saved = widgets.find { it.appWidgetId == 55 }
            assertThat(saved).isNotNull()
            assertThat(saved?.pageIndex).isEqualTo(1)
            assertThat(pages.size).isAtLeast(2)

            // Overlay relaunched
            assertThat(activity.isFinishing).isTrue()
            val shadowApp = Shadows.shadowOf(context)
            val nextIntent = shadowApp.nextStartedActivity
            assertThat(nextIntent).isNotNull()
            assertThat(nextIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
            assertThat((nextIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0).isTrue()
            assertThat((nextIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0).isTrue()
        } finally {
            controller.destroy()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun onActivityResult_resultCanceled_newWidget_deletesAllocatedIdAndRelaunches() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val candidate = createCandidate(id = 66, pageIndex = 0)
        val intent = Intent(context, WidgetConfigureTrampolineActivity::class.java).apply {
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_APPWIDGET_ID, 66)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_IS_RECONFIGURE, false)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_CANDIDATE_WIDGET, candidate)
        }
        val controller = Robolectric.buildActivity(WidgetConfigureTrampolineActivity::class.java, intent).create()
        try {
            val activity = controller.get()

            // Deliver RESULT_CANCELED
            activity.handleConfigureResult(resultOk = false)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            // Widget NOT persisted
            val (_, widgets) = appContainer.repository.getSnapshot()
            assertThat(widgets.find { it.appWidgetId == 66 }).isNull()

            // Overlay relaunched and activity finished
            assertThat(activity.isFinishing).isTrue()
            val shadowApp = Shadows.shadowOf(context)
            val nextIntent = shadowApp.nextStartedActivity
            assertThat(nextIntent).isNotNull()
            assertThat(nextIntent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
        } finally {
            controller.destroy()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun reconfigure_resultOk_doesNotDuplicateAndRelaunches() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val existing = createCandidate(id = 77, pageIndex = 0)
        appContainer.repository.upsertWidget(existing)

        val intent = Intent(context, WidgetConfigureTrampolineActivity::class.java).apply {
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_APPWIDGET_ID, 77)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_IS_RECONFIGURE, true)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_CANDIDATE_WIDGET, null as PlacedWidget?)
        }
        val controller = Robolectric.buildActivity(WidgetConfigureTrampolineActivity::class.java, intent).create()
        try {
            val activity = controller.get()

            activity.handleConfigureResult(resultOk = true)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            val (_, widgets) = appContainer.repository.getSnapshot()
            assertThat(widgets.filter { it.appWidgetId == 77 }).hasSize(1)
            assertThat(activity.isFinishing).isTrue()
        } finally {
            controller.destroy()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun reconfigure_resultCanceled_doesNotDeleteWidget() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val existing = createCandidate(id = 88, pageIndex = 0)
        appContainer.repository.upsertWidget(existing)

        val intent = Intent(context, WidgetConfigureTrampolineActivity::class.java).apply {
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_APPWIDGET_ID, 88)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_IS_RECONFIGURE, true)
        }
        val controller = Robolectric.buildActivity(WidgetConfigureTrampolineActivity::class.java, intent).create()
        try {
            val activity = controller.get()

            activity.handleConfigureResult(resultOk = false)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            val (_, widgets) = appContainer.repository.getSnapshot()
            assertThat(widgets.find { it.appWidgetId == 88 }).isNotNull()
            assertThat(activity.isFinishing).isTrue()
        } finally {
            controller.destroy()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }
}
