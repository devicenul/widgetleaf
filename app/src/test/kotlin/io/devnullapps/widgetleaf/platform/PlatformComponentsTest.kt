package io.devnullapps.widgetleaf.platform

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Window
import android.view.WindowManager
import org.robolectric.shadows.ShadowDisplayManager
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
    fun desktopGridProfileResolverResolvesStandard1440x900To8x6FittedCanvas() {
        val profile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 1440f,
            screenHeightDp = 900f,
            useCenteredBentoCanvas = true
        )

        assertThat(profile.columns).isEqualTo(8)
        assertThat(profile.rows).isEqualTo(6)
        assertThat(profile.gutterDp).isEqualTo(16f)
        assertThat(profile.marginDp).isEqualTo(36f)
        assertThat(profile.availableWidthDp).isEqualTo(1440f)
        assertThat(profile.availableHeightDp).isEqualTo(900f)

        // Symmetric margins: left margin == right margin
        val totalGridWidth = profile.widthDpForSpan(profile.columns)
        val rightMargin = profile.availableWidthDp - (profile.marginDp + totalGridWidth)
        assertThat(rightMargin).isWithin(0.01f).of(profile.marginDp)

        // Spanning all 6 rows fills usableHeightDp (900 - 36 - 84 = 780dp)
        assertThat(profile.heightDpForSpan(profile.rows)).isWithin(0.5f).of(780f)
    }

    @Test
    fun desktopGridProfileResolverSpansFullWidthSymmetricallyOn1024x640() {
        val profile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 1024f,
            screenHeightDp = 640f,
            useCenteredBentoCanvas = true
        )

        assertThat(profile.columns).isEqualTo(6)
        assertThat(profile.rows).isEqualTo(4)
        assertThat(profile.marginDp).isEqualTo(36f)

        val totalGridWidth = profile.widthDpForSpan(profile.columns)
        val rightMargin = profile.availableWidthDp - (profile.marginDp + totalGridWidth)
        assertThat(rightMargin).isWithin(0.01f).of(profile.marginDp)
        assertThat(totalGridWidth + 2 * profile.marginDp).isWithin(0.01f).of(1024f)
    }

    @Test
    fun desktopGridProfileResolverSpansFullWidthSymmetricallyOn1280x800() {
        val profile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 1280f,
            screenHeightDp = 800f,
            useCenteredBentoCanvas = true
        )

        assertThat(profile.columns).isEqualTo(7)
        assertThat(profile.rows).isEqualTo(6)
        assertThat(profile.marginDp).isEqualTo(36f)

        val totalGridWidth = profile.widthDpForSpan(profile.columns)
        val rightMargin = profile.availableWidthDp - (profile.marginDp + totalGridWidth)
        assertThat(rightMargin).isWithin(0.01f).of(profile.marginDp)
        assertThat(totalGridWidth + 2 * profile.marginDp).isWithin(0.01f).of(1280f)
    }

    @Test
    fun desktopGridProfileResolverCentersWithin1440DpAndFillsFullWidthWhenDisabledOn1920x1080() {
        val centeredProfile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 1920f,
            screenHeightDp = 1080f,
            useCenteredBentoCanvas = true
        )

        assertThat(centeredProfile.columns).isEqualTo(8)
        assertThat(centeredProfile.rows).isEqualTo(8)
        val centeredGridWidth = centeredProfile.widthDpForSpan(centeredProfile.columns)
        assertThat(centeredGridWidth).isAtMost(DesktopGridProfileResolver.MAX_CANVAS_WIDTH_DP)
        assertThat(centeredProfile.marginDp).isWithin(0.01f).of((1920f - centeredGridWidth) / 2f)
        val centeredRightMargin = centeredProfile.availableWidthDp - (centeredProfile.marginDp + centeredGridWidth)
        assertThat(centeredRightMargin).isWithin(0.01f).of(centeredProfile.marginDp)

        val fullWidthProfile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 1920f,
            screenHeightDp = 1080f,
            useCenteredBentoCanvas = false
        )
        assertThat(fullWidthProfile.columns).isEqualTo(11)
        assertThat(fullWidthProfile.rows).isEqualTo(8)
        assertThat(fullWidthProfile.marginDp).isEqualTo(36f)
        val fullGridWidth = fullWidthProfile.widthDpForSpan(fullWidthProfile.columns)
        val fullRightMargin = fullWidthProfile.availableWidthDp - (fullWidthProfile.marginDp + fullGridWidth)
        assertThat(fullRightMargin).isWithin(0.01f).of(fullWidthProfile.marginDp)
        assertThat(fullGridWidth + 2 * fullWidthProfile.marginDp).isWithin(0.01f).of(1920f)
    }

    @Test
    fun desktopGridProfileResolverHandlesUltraWideCenteredOnAndOffAcross2560And3440() {
        for (widthDp in listOf(2560f, 3440f)) {
            val centered = DesktopGridProfileResolver.resolve(
                screenWidthDp = widthDp,
                screenHeightDp = 1440f,
                useCenteredBentoCanvas = true
            )
            assertThat(centered.columns).isEqualTo(8)
            assertThat(centered.rows).isEqualTo(11)
            val centeredGridWidth = centered.widthDpForSpan(centered.columns)
            assertThat(centeredGridWidth).isAtMost(DesktopGridProfileResolver.MAX_CANVAS_WIDTH_DP)
            val centeredRightMargin = centered.availableWidthDp - (centered.marginDp + centeredGridWidth)
            assertThat(centeredRightMargin).isWithin(0.01f).of(centered.marginDp)

            val fullWidth = DesktopGridProfileResolver.resolve(
                screenWidthDp = widthDp,
                screenHeightDp = 1440f,
                useCenteredBentoCanvas = false
            )
            val expectedCols = if (widthDp == 2560f) 16 else 21
            assertThat(fullWidth.columns).isEqualTo(expectedCols)
            assertThat(fullWidth.rows).isEqualTo(11)
            val fullGridWidth = fullWidth.widthDpForSpan(fullWidth.columns)
            val fullRightMargin = fullWidth.availableWidthDp - (fullWidth.marginDp + fullGridWidth)
            assertThat(fullRightMargin).isWithin(0.01f).of(fullWidth.marginDp)
            assertThat(fullGridWidth + 2 * fullWidth.marginDp).isWithin(0.01f).of(widthDp)
        }
    }

    @Test
    fun desktopGridProfileResolverMatrixRespectsInsetsHudClearanceAndSmallWindows() {
        data class MatrixCase(
            val label: String,
            val w: Float,
            val h: Float,
            val centered: Boolean,
            val leftInset: Float = 0f,
            val topInset: Float = 0f,
            val rightInset: Float = 0f,
            val bottomInset: Float = 0f
        )

        val cases = listOf(
            MatrixCase("1366x768", 1366f, 768f, true),
            MatrixCase("1280x800", 1280f, 800f, true),
            MatrixCase("1333x889", 1333f, 889f, true),
            MatrixCase("1920x1080_centered", 1920f, 1080f, true),
            MatrixCase("1920x1080_full", 1920f, 1080f, false),
            MatrixCase("2560x1440_centered", 2560f, 1440f, true),
            MatrixCase("2560x1440_full", 2560f, 1440f, false),
            MatrixCase("3440x1440_centered", 3440f, 1440f, true),
            MatrixCase("3440x1440_full", 3440f, 1440f, false),
            MatrixCase("800x1280_portrait_taskbar", 800f, 1280f, true, topInset = 48f, bottomInset = 64f),
            MatrixCase("1440x900_cutout_and_bars", 1440f, 900f, true, leftInset = 48f, topInset = 32f, rightInset = 0f, bottomInset = 64f),
            MatrixCase("320x480_small", 320f, 480f, true),
            MatrixCase("500x360_short", 500f, 360f, true)
        )

        for (c in cases) {
            val p = DesktopGridProfileResolver.resolve(
                screenWidthDp = c.w,
                screenHeightDp = c.h,
                useCenteredBentoCanvas = c.centered,
                leftInsetDp = c.leftInset,
                topInsetDp = c.topInset,
                rightInsetDp = c.rightInset,
                bottomInsetDp = c.bottomInset
            )
            assertThat(p.columns).isAtLeast(DesktopGridProfileResolver.MIN_COLUMNS)
            assertThat(p.rows).isAtLeast(DesktopGridProfileResolver.MIN_ROWS)

            val totalGridWidth = p.widthDpForSpan(p.columns)
            val totalGridHeight = p.heightDpForSpan(p.rows)

            // Safe area horizontal checks
            assertThat(p.marginDp).isAtLeast(c.leftInset)
            val rightEdge = p.marginDp + totalGridWidth
            assertThat(rightEdge).isAtMost(c.w - c.rightInset + 0.05f)
            val leftSafeMargin = p.marginDp - c.leftInset
            val rightSafeMargin = (c.w - c.rightInset) - rightEdge
            assertThat(rightSafeMargin).isWithin(0.05f).of(leftSafeMargin)

            // Centered cap check
            if (c.centered) {
                assertThat(totalGridWidth).isAtMost(DesktopGridProfileResolver.MAX_CANVAS_WIDTH_DP + 0.05f)
                assertThat(p.columns).isAtMost(8)
            }

            // Top inset & HUD clearance checks
            if (c.topInset > 0f) {
                assertThat(p.topMarginDp).isAtLeast(c.topInset + 8f)
            }
            val gridBottom = p.topMarginDp + totalGridHeight
            val hudTopBoundary = c.h - (DesktopGridProfileResolver.VERTICAL_RESERVED_DP + c.bottomInset)
            assertThat(gridBottom).isAtMost(hudTopBoundary + 0.05f)
        }
    }

    @Test
    fun desktopGridProfileResolverReadsWindowMetricsAndSystemBarCutoutInsets() {
        val bounds = android.graphics.Rect(0, 0, 1600, 2560) // 800x1280dp @ 2x
        val insets = android.view.WindowInsets.Builder()
            .setInsets(
                android.view.WindowInsets.Type.systemBars(),
                android.graphics.Insets.of(0, 96, 0, 128) // top=48dp, bottom=64dp @ 2x
            )
            .build()
        val windowMetrics = android.view.WindowMetrics(bounds, insets)

        val profile = DesktopGridProfileResolver.resolve(
            windowMetrics = windowMetrics,
            density = 2f,
            useCenteredBentoCanvas = true
        )

        assertThat(profile.availableWidthDp).isEqualTo(800f)
        assertThat(profile.availableHeightDp).isEqualTo(1280f)
        assertThat(profile.topInsetDp).isEqualTo(48f)
        assertThat(profile.bottomInsetDp).isEqualTo(64f)
        assertThat(profile.topMarginDp).isEqualTo(60f) // 48dp + 12dp gap
        val gridBottom = profile.topMarginDp + profile.heightDpForSpan(profile.rows)
        assertThat(gridBottom).isAtMost(1280f - (84f + 64f) + 0.05f)
    }

    @Test
    fun desktopGridProfileResolverEnsuresSymmetricMarginsOnPortrait() {
        val portraitProfile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 412f,
            screenHeightDp = 915f,
            useCenteredBentoCanvas = true
        )

        assertThat(portraitProfile.columns).isEqualTo(4)
        assertThat(portraitProfile.rows).isEqualTo(6)

        val totalGridWidth = portraitProfile.widthDpForSpan(portraitProfile.columns)
        val rightMargin = portraitProfile.availableWidthDp - (portraitProfile.marginDp + totalGridWidth)
        assertThat(rightMargin).isWithin(0.01f).of(portraitProfile.marginDp)
        assertThat(totalGridWidth + 2 * portraitProfile.marginDp).isWithin(0.01f).of(412f)
    }

    @Test
    fun desktopGridProfileResolverEnsuresMinimum4ColumnsAnd4RowsOnSmallerWindows() {
        val smallProfile = DesktopGridProfileResolver.resolve(
            screenWidthDp = 500f,
            screenHeightDp = 400f,
            useCenteredBentoCanvas = true
        )

        assertThat(smallProfile.columns).isEqualTo(4)
        assertThat(smallProfile.rows).isEqualTo(4)

        val totalGridWidth = smallProfile.widthDpForSpan(smallProfile.columns)
        val rightMargin = smallProfile.availableWidthDp - (smallProfile.marginDp + totalGridWidth)
        assertThat(rightMargin).isWithin(0.01f).of(smallProfile.marginDp)
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

    @Test
    fun widgetDeckHostDetachesFromExistingParentOnCreateHostView() {
        val host = WidgetDeckHost(context)
        val hostView = host.createHostView(context, 88, null)
        val parent1 = android.widget.FrameLayout(context)
        parent1.addView(hostView)
        assertThat(hostView.parent).isEqualTo(parent1)

        // When createHostView is called again for the same id, it automatically detaches from parent1
        val reusedView = host.createHostView(context, 88, null)
        assertThat(reusedView).isSameInstanceAs(hostView)
        assertThat(reusedView.parent).isNull()

        // And can be added to parent2 without IllegalStateException
        val parent2 = android.widget.FrameLayout(context)
        parent2.addView(reusedView)
        assertThat(reusedView.parent).isEqualTo(parent2)
    }

    @Test
    fun widgetDeckHostRekeysOnDensityUiModeAndDisplayChange() {
        val host = WidgetDeckHost(context)
        val baseConfig = Configuration(context.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
            uiMode = Configuration.UI_MODE_NIGHT_NO
        }
        val ctxMdpi = context.createConfigurationContext(baseConfig)
        val view1 = host.createHostView(ctxMdpi, 101, null)

        val xxhdpiConfig = Configuration(baseConfig).apply {
            densityDpi = DisplayMetrics.DENSITY_XXHIGH
        }
        val ctxXxhdpi = context.createConfigurationContext(xxhdpiConfig)
        val view2 = host.createHostView(ctxXxhdpi, 101, null)
        assertThat(view2).isNotSameInstanceAs(view1)
        assertThat(host.cachedEnvironment(101)?.densityDpi).isEqualTo(DisplayMetrics.DENSITY_XXHIGH)

        val nightConfig = Configuration(xxhdpiConfig).apply {
            uiMode = Configuration.UI_MODE_NIGHT_YES
        }
        val ctxNight = context.createConfigurationContext(nightConfig)
        val view3 = host.createHostView(ctxNight, 101, null)
        assertThat(view3).isNotSameInstanceAs(view2)
        assertThat(host.cachedEnvironment(101)?.uiMode).isEqualTo(Configuration.UI_MODE_NIGHT_YES)

        val secDisplayId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp-mdpi")
        val dm = context.getSystemService(DisplayManager::class.java)
        val secDisplay = dm.getDisplay(secDisplayId)!!
        val secContext = context.createDisplayContext(secDisplay)
        val view4 = host.createHostView(secContext, 101, null)
        assertThat(view4).isNotSameInstanceAs(view3)
        assertThat(host.cachedEnvironment(101)?.displayId).isEqualTo(secDisplayId)

        ShadowDisplayManager.removeDisplay(secDisplayId)
        host.clearViews()
    }

    @Test
    fun widgetDeckHostReleasesOwnedActivityViewsWithoutClobberingNewActivity() {
        val host = WidgetDeckHost(context)
        val controller1 = Robolectric.buildActivity(Activity::class.java).setup()
        val controller2 = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val act1 = controller1.get()
            val act2 = controller2.get()

            val view1 = host.createHostView(act1, 11, null).apply {
                onWidgetClickedListener = {}
            }
            val parent1 = android.widget.FrameLayout(act1)
            parent1.addView(view1)

            val view2 = host.createHostView(act2, 22, null)
            assertThat(host.cacheSize()).isEqualTo(2)

            // Releasing act1 cleans up widget 11 only, leaving widget 22 intact for act2
            host.releaseViewsForContext(act1)
            assertThat(host.getHostView(11)).isNull()
            assertThat(view1.parent).isNull()
            assertThat(view1.onWidgetClickedListener).isNull()
            assertThat(host.getHostView(22)).isSameInstanceAs(view2)

            // Releasing act2 empties cache
            host.releaseViewsForContext(act2)
            assertThat(host.cacheSize()).isEqualTo(0)
        } finally {
            controller1.pause().stop().destroy()
            controller2.pause().stop().destroy()
        }
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
