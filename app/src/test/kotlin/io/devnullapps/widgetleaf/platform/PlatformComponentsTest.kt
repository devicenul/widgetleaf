package io.devnullapps.widgetleaf.platform

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.devnullapps.widgetleaf.WidgetLeafApp
import io.devnullapps.widgetleaf.domain.model.DesktopGridProfile
import io.devnullapps.widgetleaf.domain.usecase.WidgetSpanCalculator
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper
import io.devnullapps.widgetleaf.ui.hostview.DeckAppWidgetHostView
import io.devnullapps.widgetleaf.ui.hostview.WidgetDeckHost
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PlatformComponentsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- DesktopGridProfileResolver Tests ---

    @Test
    fun desktopGridProfileResolverResolvesStandard1440x900To8x4() {
        val profile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 1440f,
            screenHeightDp = 900f,
            useCenteredBentoCanvas = true
        )

        assertThat(profile.columns).isEqualTo(8)
        assertThat(profile.rows).isEqualTo(4)
        assertThat(profile.cellWidthDp).isEqualTo(140f)
        assertThat(profile.cellHeightDp).isEqualTo(140f)
        assertThat(profile.gutterDp).isEqualTo(16f)
        assertThat(profile.marginDp).isEqualTo(48f)
        assertThat(profile.maxCanvasWidthDp).isEqualTo(1440f)
        assertThat(profile.availableWidthDp).isEqualTo(1440f)
        assertThat(profile.availableHeightDp).isEqualTo(900f)
    }

    @Test
    fun desktopGridProfileResolverEnsuresMinimum4ColumnsAnd3RowsOnSmallerWindows() {
        val smallProfile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 500f,
            screenHeightDp = 400f,
            useCenteredBentoCanvas = true
        )

        assertThat(smallProfile.columns).isEqualTo(4)
        assertThat(smallProfile.rows).isEqualTo(3)
    }

    @Test
    fun desktopGridProfileResolverCapsCenteredBentoCanvasAt1440Dp() {
        val wideProfileCentered = DesktopGridProfileResolver.resolve(
            screenWidthDp = 2560f,
            screenHeightDp = 1080f,
            useCenteredBentoCanvas = true
        )

        // With centered canvas, width is capped at 1440dp -> 8 columns
        assertThat(wideProfileCentered.columns).isEqualTo(8)
        assertThat(wideProfileCentered.rows).isEqualTo(5)

        val wideProfileUncapped = DesktopGridProfileResolver.resolve(
            screenWidthDp = 2560f,
            screenHeightDp = 1080f,
            useCenteredBentoCanvas = false
        )

        // Usable width: 2560 - 96 = 2464. (2464 + 16) / 156 = 15 columns
        assertThat(wideProfileUncapped.columns).isEqualTo(15)
        assertThat(wideProfileUncapped.rows).isEqualTo(5)
    }

    // --- BlurController Tests ---

    @Test
    fun effectiveScrimAlphaReturnsConfiguredWhenBlurIsActive() {
        val blurController = AndroidWindowBlurController(context)
        val alpha = blurController.effectiveScrimAlpha(configuredAlpha = 0.72f, isBlurActive = true)
        assertThat(alpha).isEqualTo(0.72f)
    }

    @Test
    fun effectiveScrimAlphaReturnsBoostedFallbackCoercedAtMost094WhenBlurDisabled() {
        val blurController = AndroidWindowBlurController(context)

        val normalFallback = blurController.effectiveScrimAlpha(configuredAlpha = 0.70f, isBlurActive = false)
        assertThat(normalFallback).isWithin(0.001f).of(0.90f)

        val highFallback = blurController.effectiveScrimAlpha(configuredAlpha = 0.85f, isBlurActive = false)
        assertThat(highFallback).isEqualTo(0.94f)
    }

    // --- WidgetDeckHost Tests ---

    @Test
    fun widgetDeckHostUsesDefaultHostIdAndCreatesDeckAppWidgetHostView() {
        assertThat(WidgetDeckHost.DEFAULT_HOST_ID).isEqualTo(1042)

        val host = WidgetDeckHost(context)
        val hostView = host.createHostView(context, 77, null)

        assertThat(hostView).isInstanceOf(DeckAppWidgetHostView::class.java)
        assertThat(hostView.appWidgetId).isEqualTo(77)
    }

    // --- AndroidWidgetHostGateway Tests ---

    @Test
    fun androidWidgetHostGatewayAllocatesAndDeletesWidgetIds() {
        val host = WidgetDeckHost(context)
        val gateway = AndroidWidgetHostGateway(
            context = context,
            widgetDeckHost = host
        )

        val widgetId = gateway.allocateAppWidgetId()
        assertThat(widgetId).isAtLeast(0)

        // Deleting allocated widget ID should complete cleanly without throwing
        gateway.deleteAppWidgetId(widgetId)
    }

    @Test
    fun androidWidgetHostGatewayUpdatesAppWidgetSizesWithSizesBundle() {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val host = WidgetDeckHost(context)
        val gateway = AndroidWidgetHostGateway(
            context = context,
            widgetDeckHost = host,
            appWidgetManager = appWidgetManager
        )

        val sizes = listOf(
            ResponsiveWidgetSizeDp(140f, 140f),
            ResponsiveWidgetSizeDp(296f, 140f)
        )

        // updateAppWidgetSizes should build Bundle with MIN, MAX, and OPTION_APPWIDGET_SIZES without crashing
        gateway.updateAppWidgetSizes(
            appWidgetId = 1,
            sizesDp = sizes,
            minWidthDp = 140,
            minHeightDp = 140,
            maxWidthDp = 296,
            maxHeightDp = 140
        )
    }

    @Test
    fun androidWidgetHostGatewayLifecycleAndQueryMethods() {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val shadowAwm = Shadows.shadowOf(appWidgetManager)
        shadowAwm.setAllowedToBindAppWidgets(true)
        shadowAwm.setValidWidgetProviderComponentName(true)

        val host = WidgetDeckHost(context)
        val gateway = AndroidWidgetHostGateway(
            context = context,
            widgetDeckHost = host,
            appWidgetManager = appWidgetManager
        )

        // Lifecycle calls
        gateway.startListening()
        gateway.stopListening()

        // Allocated IDs
        val ids = gateway.getAllocatedAppWidgetIds()
        assertThat(ids).isNotNull()

        // Provider configuration and checks
        val providerComponent = ComponentName(context.packageName, "io.devnullapps.widgetleaf.TestWidgetProvider")
        val info = AppWidgetProviderInfo().apply {
            provider = providerComponent
            minWidth = 140
            minHeight = 140
            minResizeWidth = 140
            minResizeHeight = 140
            resizeMode = AppWidgetProviderInfo.RESIZE_BOTH
        }
        shadowAwm.addInstalledProvider(info)

        val isInstalled = gateway.isProviderInstalled(context.packageName, "io.devnullapps.widgetleaf.TestWidgetProvider")
        assertThat(isInstalled).isTrue()

        val isNotInstalled = gateway.isProviderInstalled("non.existent", "Provider")
        assertThat(isNotInstalled).isFalse()

        // Catalog
        val profile = DesktopGridProfileResolver.resolve(1440f, 900f)
        val catalog = gateway.getInstalledCatalog(profile)
        assertThat(catalog).isNotEmpty()
        val found = catalog.firstOrNull { it.providerClass == "io.devnullapps.widgetleaf.TestWidgetProvider" }
        assertThat(found).isNotNull()

        // Bind and config
        val widgetId = gateway.allocateAppWidgetId()
        val bound = gateway.bindAppWidgetIdIfAllowed(widgetId, providerComponent)
        assertThat(bound).isTrue()
        val hasConfig = gateway.hasConfigurationActivity(widgetId)
        assertThat(hasConfig).isFalse()
        val isReconfig = gateway.isReconfigurable(widgetId)
        assertThat(isReconfig).isFalse()

        gateway.deleteAppWidgetId(widgetId)
    }

    // --- Blur Controller Window Attach Tests ---

    @Test
    fun blurControllerAttachesUpdatesAndDetachesWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = activity.window
        val blurController = AndroidWindowBlurController(context)

        blurController.attachToWindow(window, blurRadiusDp = 32)
        blurController.updateBlurRadius(window, blurRadiusDp = 48)
        blurController.detachFromWindow(window)
    }

    // --- ActiveDisplayLaunchHelper Tests ---

    @Test
    fun activeDisplayLaunchHelperBuildsIntentsAndOptions() {
        val displayId = ActiveDisplayLaunchHelper.getActiveDisplayId(context)
        assertThat(displayId).isEqualTo(android.view.Display.DEFAULT_DISPLAY)

        val intent = ActiveDisplayLaunchHelper.createLaunchIntent(context)
        assertThat(intent.action).isEqualTo(ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK)
        assertThat(intent.component?.className).isEqualTo(ActiveDisplayLaunchHelper.DECK_ACTIVITY_CLASS_NAME)

        val options = ActiveDisplayLaunchHelper.createLaunchOptions(context, displayId)
        assertThat(options).isNotNull()

        val pendingIntent = ActiveDisplayLaunchHelper.createLaunchPendingIntent(
            context = context,
            action = ActiveDisplayLaunchHelper.ACTION_OPEN_WIDGET_PICKER,
            requestCode = 100
        )
        assertThat(pendingIntent).isNotNull()
    }

    // --- WidgetLeafApp Application Container Tests ---

    @Test
    fun widgetLeafAppInitializesContainer() {
        val app = WidgetLeafApp()
        val attachBaseContext = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attachBaseContext.isAccessible = true
        attachBaseContext.invoke(app, context)
        app.onCreate()
        assertThat(app.appContainer).isNotNull()
        assertThat(app.appContainer.repository).isNotNull()
        assertThat(app.appContainer.widgetHostGateway).isNotNull()
    }
}
