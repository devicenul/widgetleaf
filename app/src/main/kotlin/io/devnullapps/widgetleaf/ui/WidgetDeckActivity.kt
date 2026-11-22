package io.devnullapps.widgetleaf.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.devnullapps.widgetleaf.WidgetLeafApp
import io.devnullapps.widgetleaf.di.AppContainer
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import java.lang.ref.WeakReference
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper
import io.devnullapps.widgetleaf.services.OverlayDiagnosticsLog
import io.devnullapps.widgetleaf.services.WidgetDeckTileService
import io.devnullapps.widgetleaf.ui.components.DefaultFramelessWidgetContent
import io.devnullapps.widgetleaf.ui.components.OverlayViewportMetrics
import io.devnullapps.widgetleaf.ui.components.WidgetDeckOverlayScreen
import io.devnullapps.widgetleaf.ui.gesture.TrackpadGestureClassifier
import io.devnullapps.widgetleaf.ui.gesture.TrackpadSwipeAccumulator
import io.devnullapps.widgetleaf.ui.hostview.DeckAppWidgetHostView
import io.devnullapps.widgetleaf.ui.hostview.DeckWidgetHostContainer
import io.devnullapps.widgetleaf.ui.theme.MotionSpec
import io.devnullapps.widgetleaf.ui.viewmodel.DeckViewModel
import io.devnullapps.widgetleaf.ui.viewmodel.PendingWidgetAction
import kotlinx.coroutines.launch

class WidgetDeckActivity : ComponentActivity() {

    private val container: AppContainer by lazy {
        testContainerOverride
            ?: (application as? WidgetLeafApp)?.appContainer
            ?: AppContainer(applicationContext, useInMemoryDb = true)
    }

    lateinit var viewModel: DeckViewModel
        private set

    private var wasAlreadyResumed: Boolean = false
    private var lastAppliedSettings: OverlaySettings? = null
    private var isAwaitingInternalSystemPrompt: Boolean = false
    private var hasRequestedNotificationPermission: Boolean = false
    private var lastSecondaryClickUptimeMs: Long = -10_000L
    private var lastWidgetMutationOrFlowUptimeMs: Long = -10_000L
    private var lastInteractiveWidgetClickUptimeMs: Long = -10_000L
    private var lastInteractiveWidgetClickId: Int = -1
    private var isActivityPaused: Boolean = false
    private var isActivityTopResumed: Boolean = true
    private var hasActivityWindowFocus: Boolean = true
    private var creationUptimeMs: Long = 0L
    private var lastHotkeyToggleUptimeMs: Long = -10_000L
    private var overlayContainerHeightPx: Float = 0f
    private var overlayBottomInsetPx: Float = 0f
    private var latestViewportMetrics: OverlayViewportMetrics? = null
    private val trackpadSwipeAccumulator = TrackpadSwipeAccumulator()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDeferredDismissRunnable: Runnable? = null
    private var pendingDeferredDismissTrigger: String? = null
    private var pendingDeferredDismissRequirePauseOrFocusLoss: Boolean = true
    private var pendingTemporaryHideRunnable: Runnable? = null
    private var isTemporarilyHiddenForDeferredDismiss: Boolean = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && ::viewModel.isInitialized) {
            container.statusBarTriggerManager.syncStatusBarTrigger(true)
        }
    }

    private val bindWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = viewModel.uiState.value.pendingWidgetAction
        if (pending is PendingWidgetAction.RequestBind) {
            viewModel.onBindWidgetResult(
                appWidgetId = pending.appWidgetId,
                granted = result.resultCode == Activity.RESULT_OK
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        creationUptimeMs = SystemClock.uptimeMillis()
        activeInstance = this
        OverlayDiagnosticsLog.record(
            "onCreate",
            "action=${intent?.action} displayId=${ActiveDisplayLaunchHelper.getActiveDisplayId(this)}"
        )
        refreshDensityThresholds()
        reassertOverlayPresentation()

        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DeckViewModel(
                        repository = container.repository,
                        widgetHostGateway = container.widgetHostGateway,
                        reconcileOrphanWidgetsUseCase = container.reconcileOrphanWidgetsUseCase,
                        calculateResponsiveSizesUseCase = container.calculateResponsiveSizesUseCase,
                        edgePageDragTransferUseCase = container.edgePageDragTransferUseCase,
                        cascadeDisplacementResolver = container.cascadeDisplacementResolver,
                        gridCollisionResolver = container.gridCollisionResolver
                    ) as T
                }
            }
        )[DeckViewModel::class.java]

        val initialSettings = viewModel.uiState.value.settings
        container.blurController.attachToWindow(window, initialSettings.blurRadiusDp)
        container.taskbarPlacementManager.syncDynamicShortcuts(this)

        val resolvedGridProfile = container.gridProfileResolver.resolve(
            context = this,
            useCenteredBentoCanvas = initialSettings.useCenteredBentoCanvas
        )
        viewModel.updateGridProfile(resolvedGridProfile)
        val targetPageOverride = intent.getIntExtra(ActiveDisplayLaunchHelper.EXTRA_TARGET_PAGE_INDEX, -1).takeIf { it >= 0 }
        intent.removeExtra(ActiveDisplayLaunchHelper.EXTRA_TARGET_PAGE_INDEX)
        viewModel.onOverlayShown(targetPageOverride = targetPageOverride)
        handleIncomingIntent(intent, isNewIntentWhileVisible = false)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (SystemClock.uptimeMillis() - lastSecondaryClickUptimeMs < 600L) {
                        return
                    }
                    if (::viewModel.isInitialized) {
                        val consumed = viewModel.handleEscapeKey()
                        if (!consumed) {
                            finishWithoutAnimation("back_key")
                        }
                    } else {
                        finishWithoutAnimation("back_key_early")
                    }
                }
            }
        )

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val isBlurActive by container.blurController.isBlurSupportedAndEnabled.collectAsState()
            val coroutineScope = rememberCoroutineScope()

            val overlayAlpha = remember { Animatable(0f) }
            val overlayScale = remember { Animatable(0.96f) }
            var isExiting by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                launch {
                    overlayAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = MotionSpec.OVERLAY_ENTRY_DURATION_MS,
                            easing = MotionSpec.OverlayEntryEasing
                        )
                    )
                }
                launch {
                    overlayScale.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = MotionSpec.OVERLAY_ENTRY_DURATION_MS,
                            easing = MotionSpec.OverlayEntryEasing
                        )
                    )
                }
            }

            LaunchedEffect(uiState.isSettingsLoaded, uiState.settings) {
                val settings = uiState.settings
                container.blurController.updateBlurRadius(window, settings.blurRadiusDp)
                if (!hasRequestedNotificationPermission &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    hasRequestedNotificationPermission = true
                    try {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } catch (_: Throwable) {
                        // Ignored in headless test environments
                    }
                }

                val metrics = latestViewportMetrics
                if (metrics != null && metrics.widthDp > 10f && metrics.heightDp > 10f) {
                    val updatedProfile = container.gridProfileResolver.resolve(
                        screenWidthDp = metrics.widthDp,
                        screenHeightDp = metrics.heightDp,
                        useCenteredBentoCanvas = settings.useCenteredBentoCanvas,
                        leftInsetDp = metrics.leftInsetDp,
                        topInsetDp = metrics.topInsetDp,
                        rightInsetDp = metrics.rightInsetDp,
                        bottomInsetDp = metrics.bottomInsetDp
                    )
                    viewModel.updateGridProfile(updatedProfile)
                } else {
                    val updatedProfile = container.gridProfileResolver.resolve(
                        context = this@WidgetDeckActivity,
                        useCenteredBentoCanvas = settings.useCenteredBentoCanvas
                    )
                    viewModel.updateGridProfile(updatedProfile)
                }

                if (uiState.isSettingsLoaded) {
                    val prev = lastAppliedSettings
                    container.settingsSideEffectController.applySettings(
                        settings = settings,
                        previousSettings = prev,
                        promptContext = this@WidgetDeckActivity,
                        onPinPromptStarted = {
                            isAwaitingInternalSystemPrompt = true
                            lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                        },
                        onPinPromptFinished = {
                            isAwaitingInternalSystemPrompt = false
                        }
                    )
                    lastAppliedSettings = settings
                } else {
                    container.statusBarTriggerManager.syncStatusBarTrigger(true)
                    container.taskbarPlacementManager.syncRunningTaskbarPresence(this@WidgetDeckActivity)
                }
            }

            LaunchedEffect(uiState.pendingWidgetAction) {
                when (val action = uiState.pendingWidgetAction) {
                    is PendingWidgetAction.RequestBind -> {
                        lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                        val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, action.appWidgetId)
                            putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
                                ComponentName(action.providerPackage, action.providerClass)
                            )
                        }
                        try {
                            bindWidgetLauncher.launch(bindIntent)
                        } catch (t: Throwable) {
                            viewModel.onBindWidgetResult(action.appWidgetId, granted = false)
                        }
                    }
                    is PendingWidgetAction.RequestConfigure -> {
                        lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                        launchWidgetConfigurationTrampoline(
                            appWidgetId = action.appWidgetId,
                            candidateWidget = action.candidateWidget,
                            isReconfigure = false,
                            originPageIndex = viewModel.uiState.value.activePageIndex
                        )
                    }
                    null -> Unit
                }
            }

            val effectiveScrim = container.blurController.effectiveScrimAlpha(
                configuredAlpha = uiState.settings.scrimAlpha,
                isBlurActive = isBlurActive
            )

            val triggerExitAnimation: () -> Unit = {
                if (!isExiting) {
                    isExiting = true
                    coroutineScope.launch {
                        launch {
                            overlayAlpha.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(
                                    durationMillis = MotionSpec.OVERLAY_EXIT_DURATION_MS,
                                    easing = MotionSpec.OverlayExitEasing
                                )
                            )
                        }
                        overlayScale.animateTo(
                            targetValue = 0.98f,
                            animationSpec = tween(
                                durationMillis = MotionSpec.OVERLAY_EXIT_DURATION_MS,
                                easing = MotionSpec.OverlayExitEasing
                            )
                        )
                        finishWithoutAnimation("ui_exit_animation")
                    }
                }
            }

            WidgetDeckOverlayScreen(
                uiState = uiState,
                effectiveScrimAlpha = effectiveScrim,
                onDismissOverlay = triggerExitAnimation,
                onSelectPage = viewModel::selectPage,
                onPreviousPage = viewModel::navigatePreviousPage,
                onNextPage = viewModel::navigateNextPage,
                onSwipePrevious = viewModel::swipePreviousPage,
                onSwipeNext = viewModel::swipeNextPage,
                canWidgetScroll = { x, y, dirX, dirY ->
                    val hit = findWidgetAtScreenPoint(x, y)
                    val hostView = hit?.let { container.widgetDeckHost.getHostView(it.appWidgetId) }
                    if (hostView != null) {
                        val canH = if (dirX != 0) hostView.canChildScrollHorizontally(dirX) else false
                        val canV = if (dirY != 0) hostView.canChildScrollVertically(dirY) else false
                        canH || canV
                    } else {
                        false
                    }
                },
                onCancelChildTouches = { x, y ->
                    val hit = findWidgetAtScreenPoint(x, y)
                    hit?.let { container.widgetDeckHost.getHostView(it.appWidgetId)?.cancelChildTouches() }
                },
                onOpenWidgetPicker = viewModel::openWidgetPicker,
                onCloseWidgetPicker = viewModel::closeWidgetPicker,
                onSearchQueryChange = viewModel::updateWidgetSearchQuery,
                onAddWidgetAutoPlace = { item ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    viewModel.addWidgetAutoPlace(item)
                },
                onDragOutCatalogItem = { item, cellX, cellY ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    viewModel.dropCatalogWidgetAtCell(item, uiState.activePageIndex, cellX, cellY)
                },
                onOpenOverlaySettings = viewModel::openOverlaySettings,
                onCloseOverlaySettings = viewModel::closeOverlaySettings,
                onUpdateSettings = viewModel::updateSettings,
                onStartEditPageLabel = viewModel::startEditingPageLabel,
                onDraftChange = viewModel::updateEditingPageLabelDraft,
                onCommitPageLabel = viewModel::commitPageLabel,
                onCancelEditPageLabel = viewModel::cancelEditingPageLabel,
                onEnterEditMode = viewModel::enterWidgetEditMode,
                onExitEditMode = viewModel::exitWidgetEditMode,
                onMoveWidget = { id, x, y ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    viewModel.moveWidget(id, x, y)
                },
                onResizeWidget = { id, x, y, sx, sy ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    viewModel.resizeWidget(id, x, y, sx, sy)
                },
                onStartWidgetDrag = viewModel::startWidgetDrag,
                onWidgetHoverCellChanged = viewModel::onWidgetHoverCellChanged,
                onCommitWidgetDrag = viewModel::commitWidgetDrag,
                onCancelWidgetDrag = viewModel::cancelWidgetDrag,
                onDragAcrossEdge = viewModel::dragWidgetAcrossEdge,
                onEdgeDwellTriggered = viewModel::onEdgeDwellTriggered,
                onRemoveWidget = { id ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    viewModel.removeWidget(id)
                },
                onReconfigureWidget = { appWidgetId ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    val placedWidget = viewModel.uiState.value.widgets.find { it.appWidgetId == appWidgetId }
                    val originPage = placedWidget?.pageIndex ?: viewModel.uiState.value.activePageIndex
                    launchWidgetConfigurationTrampoline(
                        appWidgetId = appWidgetId,
                        candidateWidget = placedWidget,
                        isReconfigure = true,
                        originPageIndex = originPage
                    )
                },
                onOpenContextMenu = viewModel::openContextMenu,
                onCloseContextMenu = viewModel::closeContextMenu,
                onReady = viewModel::enablePageAnimations,
                onViewportMetricsChanged = { metrics ->
                    overlayContainerHeightPx = metrics.heightPx
                    overlayBottomInsetPx = metrics.bottomInsetPx
                    if (metrics.widthDp > 10f && metrics.heightDp > 10f) {
                        latestViewportMetrics = metrics
                        val updatedProfile = container.gridProfileResolver.resolve(
                            screenWidthDp = metrics.widthDp,
                            screenHeightDp = metrics.heightDp,
                            useCenteredBentoCanvas = uiState.settings.useCenteredBentoCanvas,
                            leftInsetDp = metrics.leftInsetDp,
                            topInsetDp = metrics.topInsetDp,
                            rightInsetDp = metrics.rightInsetDp,
                            bottomInsetDp = metrics.bottomInsetDp
                        )
                        viewModel.updateGridProfile(updatedProfile)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = overlayAlpha.value
                        scaleX = overlayScale.value
                        scaleY = overlayScale.value
                    },
                widgetContent = { placedWidget ->
                    HostedAppWidgetContent(placedWidget = placedWidget)
                }
            )
        }
    }

    private fun refreshDensityThresholds() {
        val density = resources.displayMetrics.density
        trackpadSwipeAccumulator.pageThresholdPx = 48f * density
        trackpadSwipeAccumulator.dismissThresholdPx = 72f * density
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDensityThresholds()
        latestViewportMetrics = null
        if (::viewModel.isInitialized) {
            val settings = viewModel.uiState.value.settings
            container.blurController.updateBlurRadius(window, settings.blurRadiusDp)
            val previousProfile = viewModel.uiState.value.gridProfile
            val updatedProfile = container.gridProfileResolver.resolve(
                context = this,
                useCenteredBentoCanvas = settings.useCenteredBentoCanvas
            )
            viewModel.updateGridProfile(updatedProfile)
            if (updatedProfile == previousProfile) {
                viewModel.resyncWidgetSizes()
            }
        }
    }

    @Composable
    private fun HostedAppWidgetContent(placedWidget: PlacedWidget) {
        val uiState by viewModel.uiState.collectAsState()
        val providerInfo = remember(placedWidget.appWidgetId) {
            try {
                container.appWidgetManager.getAppWidgetInfo(placedWidget.appWidgetId)
            } catch (t: Throwable) {
                null
            }
        }

        if (providerInfo == null) {
            DefaultFramelessWidgetContent(placedWidget)
            return
        }

        val isEditing = uiState.editingWidgetId == placedWidget.appWidgetId
        DeckWidgetHostContainer(
            placedWidget = placedWidget,
            isEditing = isEditing,
            createHostView = { ctx ->
                container.widgetDeckHost.createHostView(
                    context = ctx,
                    appWidgetId = placedWidget.appWidgetId,
                    appWidget = providerInfo
                )
            },
            onEnterEditMode = { id ->
                viewModel.enterWidgetEditMode(id)
            },
            onHorizontalPageScroll = { delta ->
                if (delta > 0f) {
                    viewModel.navigatePreviousPage()
                } else if (delta < 0f) {
                    viewModel.navigateNextPage()
                }
            },
            onErrorRemoveOrReload = { id ->
                viewModel.removeWidget(id)
            },
            onWidgetClicked = ::onInteractiveWidgetClicked
        )
    }

    /**
     * Records an interactive widget touch interaction without dismissing the overlay.
     * Broadcast/service PendingIntents, CompoundButton responses, list fill-in updates, and
     * short activity trampolines keep the pane open; foreground-loss lifecycle callbacks only
     * schedule a deferred dismissal that cancels if focus/resume returns quickly (INV-23).
     */
    internal fun onInteractiveWidgetClicked(appWidgetId: Int) {
        if (::viewModel.isInitialized && (viewModel.uiState.value.editingWidgetId != null || hasOverlayModalOpen())) {
            return
        }
        val now = SystemClock.uptimeMillis()
        val isDuplicateForSameUp = lastInteractiveWidgetClickId == appWidgetId &&
            (now - lastInteractiveWidgetClickUptimeMs) in 0L..5L
        lastInteractiveWidgetClickUptimeMs = now
        lastInteractiveWidgetClickId = appWidgetId
        if (!isDuplicateForSameUp) {
            OverlayDiagnosticsLog.record("widget_interaction", "appWidgetId=$appWidgetId")
        }
    }

    private fun isRecentInteractiveWidgetClick(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        val elapsed = nowMs - lastInteractiveWidgetClickUptimeMs
        return elapsed in 0L..WIDGET_ACTIVITY_LAUNCH_WINDOW_MS
    }

    private fun isInternalFlowOrSystemPromptActive(): Boolean {
        if (isAwaitingInternalSystemPrompt) return true
        if (::viewModel.isInitialized) {
            val state = viewModel.uiState.value
            if (state.isLaunchingInternalFlow || state.pendingWidgetAction != null) {
                return true
            }
        }
        return false
    }

    private fun hasOverlayModalOpen(): Boolean {
        if (!::viewModel.isInitialized) return false
        val state = viewModel.uiState.value
        return state.isOverlaySettingsOpen ||
            state.isWidgetPickerOpen ||
            state.contextMenuPosition != null ||
            state.editingWidgetId != null ||
            state.editingPageLabelIndex != null
    }

    private fun evaluateForegroundLossDismissalAfterWidgetClick() {
        if (isFinishing || isDestroyed || isChangingConfigurations) return
        if (!isActivityPaused) return
        if (hasActivityWindowFocus && isActivityTopResumed) return
        if (!isRecentInteractiveWidgetClick()) return
        if (isInternalFlowOrSystemPromptActive() || hasOverlayModalOpen()) return
        scheduleDeferredDismiss(
            reason = "widget_click_foreground_loss",
            hideImmediately = false,
            requirePauseOrFocusLoss = true
        )
    }

    private fun scheduleDeferredDismiss(
        reason: String,
        hideImmediately: Boolean,
        requirePauseOrFocusLoss: Boolean = true
    ) {
        if (isFinishing || isDestroyed || isChangingConfigurations) return
        if (isInternalFlowOrSystemPromptActive() || hasOverlayModalOpen()) return

        if (hideImmediately) {
            cancelPendingTemporaryHideRunnable()
            applyTemporaryHideForDeferredDismiss()
        } else if (pendingTemporaryHideRunnable == null && !isTemporarilyHiddenForDeferredDismiss) {
            val hideRunnable = Runnable {
                pendingTemporaryHideRunnable = null
                if (!isFinishing &&
                    !isDestroyed &&
                    !isChangingConfigurations &&
                    pendingDeferredDismissRunnable != null &&
                    (isActivityPaused || !hasActivityWindowFocus || !isActivityTopResumed)
                ) {
                    applyTemporaryHideForDeferredDismiss()
                }
            }
            pendingTemporaryHideRunnable = hideRunnable
            mainHandler.postDelayed(hideRunnable, TEMPORARY_HIDE_GRACE_MS)
        }

        if (pendingDeferredDismissRunnable != null) {
            if (!requirePauseOrFocusLoss) {
                pendingDeferredDismissRequirePauseOrFocusLoss = false
            }
            return
        }

        pendingDeferredDismissTrigger = reason
        pendingDeferredDismissRequirePauseOrFocusLoss = requirePauseOrFocusLoss
        OverlayDiagnosticsLog.record(
            "deferred_dismiss_scheduled",
            "trigger=$reason delayMs=$DEFERRED_DISMISS_DELAY_MS recentWidgetClick=${isRecentInteractiveWidgetClick()}"
        )

        val runnable = Runnable {
            pendingDeferredDismissRunnable = null
            val trigger = pendingDeferredDismissTrigger ?: reason
            val requireLoss = pendingDeferredDismissRequirePauseOrFocusLoss
            pendingDeferredDismissTrigger = null
            pendingDeferredDismissRequirePauseOrFocusLoss = true
            cancelPendingTemporaryHideRunnable()
            if (isFinishing || isDestroyed || isChangingConfigurations) return@Runnable
            if (isInternalFlowOrSystemPromptActive() || hasOverlayModalOpen()) {
                restoreFromTemporaryHide()
                OverlayDiagnosticsLog.record("deferred_dismiss_aborted", "modal_or_internal trigger=$trigger")
                return@Runnable
            }
            val pausedOrUnfocused = isActivityPaused || !hasActivityWindowFocus || !isActivityTopResumed
            if (requireLoss && !pausedOrUnfocused) {
                restoreFromTemporaryHide()
                OverlayDiagnosticsLog.record("deferred_dismiss_aborted", "regained_foreground trigger=$trigger")
                return@Runnable
            }
            finishWithoutAnimation("deferred_$trigger")
        }
        pendingDeferredDismissRunnable = runnable
        mainHandler.postDelayed(runnable, DEFERRED_DISMISS_DELAY_MS)
    }

    private fun cancelDeferredDismissal(reason: String) {
        cancelPendingTemporaryHideRunnable()
        val hadPending = pendingDeferredDismissRunnable != null
        pendingDeferredDismissRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingDeferredDismissRunnable = null
        }
        val prevTrigger = pendingDeferredDismissTrigger
        pendingDeferredDismissTrigger = null
        pendingDeferredDismissRequirePauseOrFocusLoss = true
        if (hadPending) {
            OverlayDiagnosticsLog.record(
                "deferred_dismiss_cancelled",
                "reason=$reason prevTrigger=$prevTrigger"
            )
        }
        restoreFromTemporaryHide()
    }

    private fun cancelPendingTemporaryHideRunnable() {
        pendingTemporaryHideRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingTemporaryHideRunnable = null
        }
    }

    private fun applyTemporaryHideForDeferredDismiss() {
        if (isTemporarilyHiddenForDeferredDismiss) return
        isTemporarilyHiddenForDeferredDismiss = true
        try {
            window?.decorView?.alpha = 0f
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            container.blurController.detachFromWindow(window)
        } catch (_: Throwable) {
        }
        OverlayDiagnosticsLog.record("temporary_hide", "applied=true")
    }

    private fun restoreFromTemporaryHide() {
        if (!isTemporarilyHiddenForDeferredDismiss) return
        isTemporarilyHiddenForDeferredDismiss = false
        try {
            window?.decorView?.alpha = 1f
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            val blurRadius = if (::viewModel.isInitialized) {
                viewModel.uiState.value.settings.blurRadiusDp
            } else {
                36
            }
            container.blurController.attachToWindow(window, blurRadius)
        } catch (_: Throwable) {
        }
        OverlayDiagnosticsLog.record("temporary_hide", "applied=false")
    }

    internal fun dismissFromExternalTrigger(reason: String) {
        cancelDeferredDismissal("external_trigger_$reason")
        finishWithoutAnimation(reason)
    }

    private fun launchWidgetConfigurationTrampoline(
        appWidgetId: Int,
        candidateWidget: PlacedWidget?,
        isReconfigure: Boolean
    ) {
        launchWidgetConfigurationTrampoline(
            appWidgetId = appWidgetId,
            candidateWidget = candidateWidget,
            isReconfigure = isReconfigure,
            originPageIndex = viewModel.uiState.value.activePageIndex
        )
    }

    private fun launchWidgetConfigurationTrampoline(
        appWidgetId: Int,
        candidateWidget: PlacedWidget?,
        isReconfigure: Boolean,
        originPageIndex: Int
    ) {
        lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
        viewModel.markLaunchingInternalFlow(true)
        val intent = Intent(this, WidgetConfigureTrampolineActivity::class.java).apply {
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_IS_RECONFIGURE, isReconfigure)
            putExtra(WidgetConfigureTrampolineActivity.EXTRA_ORIGIN_PAGE_INDEX, originPageIndex)
            candidateWidget?.let {
                putExtra(WidgetConfigureTrampolineActivity.EXTRA_CANDIDATE_WIDGET, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val options = createConfigureLaunchOptions()
        startActivity(intent, options.toBundle())
        finishWithoutAnimation("launch_configure_trampoline")
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

    internal fun reassertOverlayPresentation() {
        try {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

            val blurRadius = if (::viewModel.isInitialized) {
                viewModel.uiState.value.settings.blurRadiusDp
            } else {
                36
            }
            container.blurController.attachToWindow(window, blurRadius)
            container.taskbarPlacementManager.syncRunningTaskbarPresence(this)
        } catch (_: Throwable) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CONFIGURE_WIDGET) {
            lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
            viewModel.markLaunchingInternalFlow(false)
            val pending = viewModel.uiState.value.pendingWidgetAction
            val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                ?.takeIf { it != -1 }
                ?: (pending as? PendingWidgetAction.RequestConfigure)?.appWidgetId
            if (widgetId != null) {
                viewModel.onConfigureWidgetResult(
                    appWidgetId = widgetId,
                    resultOk = resultCode == Activity.RESULT_OK
                )
            }
            reassertOverlayPresentation()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OverlayDiagnosticsLog.record(
            "onNewIntent",
            "action=${intent.action} wasResumed=$wasAlreadyResumed visible=$isDeckVisible paused=$isActivityPaused"
        )
        val shouldToggleClosed = wasAlreadyResumed && isDeckVisible && !isActivityPaused
        cancelDeferredDismissal("onNewIntent:${intent.action}")
        val targetPageOverride = intent.getIntExtra(ActiveDisplayLaunchHelper.EXTRA_TARGET_PAGE_INDEX, -1).takeIf { it >= 0 }
        if (targetPageOverride != null) {
            intent.removeExtra(ActiveDisplayLaunchHelper.EXTRA_TARGET_PAGE_INDEX)
            viewModel.onOverlayShown(targetPageOverride = targetPageOverride)
        }
        val now = SystemClock.uptimeMillis()
        if (intent.action == ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK ||
            intent.action == Intent.ACTION_ASSIST
        ) {
            if (shouldToggleClosed && now - lastHotkeyToggleUptimeMs >= 350L) {
                lastHotkeyToggleUptimeMs = now
                finishWithoutAnimation("new_intent_toggle")
            }
            return
        }
        handleIncomingIntent(intent, isNewIntentWhileVisible = shouldToggleClosed)
    }

    private fun handleIncomingIntent(intent: Intent?, isNewIntentWhileVisible: Boolean) {
        when (intent?.action) {
            ActiveDisplayLaunchHelper.ACTION_OPEN_WIDGET_PICKER -> {
                viewModel.openWidgetPicker()
            }
            ActiveDisplayLaunchHelper.ACTION_OPEN_OVERLAY_SETTINGS,
            TileService.ACTION_QS_TILE_PREFERENCES -> {
                viewModel.openOverlaySettings()
            }
            ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK,
            Intent.ACTION_ASSIST -> {
                if (isNewIntentWhileVisible) {
                    finishWithoutAnimation("incoming_intent_toggle")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activeInstance = this
        container.widgetHostGateway.startListening()
        OverlayDiagnosticsLog.record("onStart")
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        isDeckVisible = true
        wasAlreadyResumed = true
        isActivityPaused = false
        isActivityTopResumed = true
        isAwaitingInternalSystemPrompt = false
        lastInteractiveWidgetClickUptimeMs = -10_000L
        lastInteractiveWidgetClickId = -1
        cancelDeferredDismissal("onResume")
        OverlayDiagnosticsLog.record(
            "onResume",
            "displayId=${ActiveDisplayLaunchHelper.getActiveDisplayId(this)}"
        )
        if (::viewModel.isInitialized && viewModel.uiState.value.pendingWidgetAction == null) {
            viewModel.markLaunchingInternalFlow(false)
        }
        reassertOverlayPresentation()

        if (io.devnullapps.widgetleaf.BuildConfig.DEBUG) {
            try {
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                val inMultiWindow = isInMultiWindowMode
                val density = resources.displayMetrics.density
                val widthDp = bounds.width() / density
                val heightDp = bounds.height() / density
                val displayMetrics = resources.displayMetrics
                val displayWidthDp = displayMetrics.widthPixels / density
                val displayHeightDp = displayMetrics.heightPixels / density
                android.util.Log.d(
                    TAG,
                    "Overlay Diagnostics: isInMultiWindowMode=$inMultiWindow, windowBoundsPx=$bounds (${widthDp.toInt()}x${heightDp.toInt()}dp), displayPx=${displayMetrics.widthPixels}x${displayMetrics.heightPixels} (${displayWidthDp.toInt()}x${displayHeightDp.toInt()}dp)"
                )
            } catch (_: Throwable) {
            }
        }
    }

    override fun onPause() {
        isActivityPaused = true
        isDeckVisible = false
        wasAlreadyResumed = false
        super.onPause()
        OverlayDiagnosticsLog.record(
            "onPause",
            "finishing=$isFinishing focus=$hasActivityWindowFocus topResumed=$isActivityTopResumed recentWidgetClick=${isRecentInteractiveWidgetClick()}"
        )
        evaluateForegroundLossDismissalAfterWidgetClick()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasActivityWindowFocus = hasFocus
        OverlayDiagnosticsLog.record(
            "onWindowFocusChanged",
            "hasFocus=$hasFocus paused=$isActivityPaused topResumed=$isActivityTopResumed"
        )
        if (hasFocus) {
            cancelDeferredDismissal("window_focus_regained")
        } else {
            evaluateForegroundLossDismissalAfterWidgetClick()
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        isActivityTopResumed = isTopResumedActivity
        OverlayDiagnosticsLog.record(
            "onTopResumedActivityChanged",
            "isTop=$isTopResumedActivity paused=$isActivityPaused focus=$hasActivityWindowFocus"
        )
        if (isTopResumedActivity && !isActivityPaused) {
            cancelDeferredDismissal("top_resumed_regained")
        } else if (!isTopResumedActivity) {
            evaluateForegroundLossDismissalAfterWidgetClick()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val now = SystemClock.uptimeMillis()
        val recentSecondary = now - lastSecondaryClickUptimeMs < 600L
        val recentMutation = now - lastWidgetMutationOrFlowUptimeMs < 1500L
        val internalActive = isInternalFlowOrSystemPromptActive()
        val modalOpen = hasOverlayModalOpen()
        val recentWidgetClick = isRecentInteractiveWidgetClick(now)
        OverlayDiagnosticsLog.record(
            "onUserLeaveHint",
            "recentWidgetClick=$recentWidgetClick recentSecondary=$recentSecondary recentMutation=$recentMutation internal=$internalActive modal=$modalOpen"
        )
        if (recentSecondary || recentMutation || internalActive || modalOpen) {
            return
        }
        if (::viewModel.isInitialized && viewModel.shouldAutoDismissOnUserLeave()) {
            scheduleDeferredDismiss(
                reason = if (recentWidgetClick) "user_leave_after_widget_click" else "user_leave",
                hideImmediately = !recentWidgetClick,
                requirePauseOrFocusLoss = recentWidgetClick
            )
        }
    }

    override fun onStop() {
        val recentWidgetClick = isRecentInteractiveWidgetClick()
        val internalActive = isInternalFlowOrSystemPromptActive()
        val modalOpen = hasOverlayModalOpen()
        val shouldDeferOnStop = !isFinishing &&
            !isDestroyed &&
            !isChangingConfigurations &&
            !internalActive &&
            !modalOpen &&
            (recentWidgetClick || pendingDeferredDismissRunnable != null)
        OverlayDiagnosticsLog.record(
            "onStop",
            "recentWidgetClick=$recentWidgetClick internal=$internalActive modal=$modalOpen defer=$shouldDeferOnStop"
        )
        container.widgetHostGateway.stopListening()
        if (activeInstance === this) {
            activeInstance = null
        }
        isDeckVisible = false
        super.onStop()
        if (shouldDeferOnStop) {
            scheduleDeferredDismiss(
                reason = "stop_after_widget_click",
                hideImmediately = true,
                requirePauseOrFocusLoss = true
            )
        }
    }

    override fun onDestroy() {
        OverlayDiagnosticsLog.record("onDestroy", "finishing=$isFinishing")
        pendingDeferredDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDeferredDismissRunnable = null
        pendingDeferredDismissTrigger = null
        cancelPendingTemporaryHideRunnable()
        mainHandler.removeCallbacksAndMessages(null)
        container.widgetDeckHost.releaseViewsForContext(this)
        container.blurController.detachFromWindow(window)
        if (activeInstance === this) {
            activeInstance = null
        }
        isDeckVisible = false
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val isSecondaryButton = (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
            (ev.actionButton == MotionEvent.BUTTON_SECONDARY)
        if (isSecondaryButton || ev.pointerCount >= 2) {
            lastSecondaryClickUptimeMs = SystemClock.uptimeMillis()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun findWidgetAtScreenPoint(screenX: Float, screenY: Float): PlacedWidget? {
        if (!::viewModel.isInitialized) return null
        val state = viewModel.uiState.value
        val activeWidgets = state.activePageWidgets
        val decorView = window?.decorView
        val hasDecor = decorView != null && decorView.isAttachedToWindow
        val windowCoords = IntArray(2)
        if (hasDecor) {
            decorView!!.getLocationOnScreen(windowCoords)
        }
        val viewCoords = IntArray(2)
        for (w in activeWidgets) {
            val hostView = container.widgetDeckHost.getHostView(w.appWidgetId)
            if (hostView != null && hostView.isAttachedToWindow && hostView.width > 0 && hostView.height > 0) {
                hostView.getLocationOnScreen(viewCoords)
                val relX = (viewCoords[0] - if (hasDecor) windowCoords[0] else 0).toFloat()
                val relY = (viewCoords[1] - if (hasDecor) windowCoords[1] else 0).toFloat()
                if (screenX >= relX &&
                    screenX <= (relX + hostView.width) &&
                    screenY >= relY &&
                    screenY <= (relY + hostView.height)
                ) {
                    return w
                }
            }
        }

        val profile = state.gridProfile
        val density = resources.displayMetrics.density
        val localXDp = screenX / density
        val localYDp = screenY / density

        return activeWidgets.firstOrNull { w ->
            val leftDp = profile.marginDp + w.cellX * (profile.cellWidthDp + profile.gutterDp)
            val topDp = profile.topMarginDp + w.cellY * (profile.cellHeightDp + profile.gutterDp)
            val widthDp = profile.widthDpForSpan(w.spanX)
            val heightDp = profile.heightDpForSpan(w.spanY)
            localXDp >= leftDp &&
                localXDp <= (leftDp + widthDp) &&
                localYDp >= topDp &&
                localYDp <= (topDp + heightDp)
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val isSecondaryButton = (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
            (ev.actionButton == MotionEvent.BUTTON_SECONDARY)
        if (isSecondaryButton) {
            lastSecondaryClickUptimeMs = SystemClock.uptimeMillis()
            val wasEditingBefore = ::viewModel.isInitialized &&
                viewModel.uiState.value.editingWidgetId != null
            super.dispatchGenericMotionEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ::viewModel.isInitialized) {
                val state = viewModel.uiState.value
                val hitWidget = findWidgetAtScreenPoint(ev.x, ev.y)
                if (hitWidget != null) {
                    viewModel.enterWidgetEditMode(hitWidget.appWidgetId)
                } else if (!wasEditingBefore &&
                    !state.isWidgetPickerOpen &&
                    !state.isOverlaySettingsOpen &&
                    state.contextMenuPosition == null &&
                    state.editingWidgetId == null
                ) {
                    // Also ignore secondary clicks in the bottom pagination pill band
                    val density = resources.displayMetrics.density
                    val windowMetrics = windowManager.currentWindowMetrics
                    val containerHeightPx = overlayContainerHeightPx.takeIf { it > 0f }
                        ?: windowMetrics.bounds.height().toFloat().takeIf { it > 0f }
                        ?: resources.displayMetrics.heightPixels.toFloat()
                    val bottomInsetPx = if (overlayContainerHeightPx > 0f) {
                        overlayBottomInsetPx
                    } else {
                        windowMetrics.windowInsets.getInsets(
                            android.view.WindowInsets.Type.navigationBars() or
                                android.view.WindowInsets.Type.displayCutout()
                        ).bottom.toFloat()
                    }
                    val inBottomHudBand = ev.y >= (containerHeightPx - bottomInsetPx - 88f * density)
                    if (!inBottomHudBand) {
                        viewModel.openContextMenu(Pair(ev.x, ev.y))
                    }
                }
            }
            // Always return true for secondary mouse/trackpad button events so Android ViewRootImpl
            // does not convert an unhandled right-click / two-finger click into KEYCODE_BACK!
            return true
        }

        if (::viewModel.isInitialized) {
            val state = viewModel.uiState.value
            // Feature 4: All swipe gestures DISABLED while a widget is in edit mode
            if (state.editingWidgetId == null &&
                !state.isWidgetPickerOpen &&
                !state.isOverlaySettingsOpen &&
                state.contextMenuPosition == null
            ) {
                if (TrackpadGestureClassifier.isTrackpadSwipe(ev)) {
                    val deltas = TrackpadGestureClassifier.extractScrollDeltas(ev)
                    val hitWidget = findWidgetAtScreenPoint(ev.x, ev.y)
                    val hostView = hitWidget?.let { container.widgetDeckHost.getHostView(it.appWidgetId) }

                    val result = trackpadSwipeAccumulator.onTrackpadEvent(
                        dx = deltas.dx,
                        dy = deltas.dy,
                        uptimeMs = ev.eventTime,
                        canScrollX = { dir -> hostView?.canChildScrollHorizontally(dir) == true },
                        canScrollY = { dir -> hostView?.canChildScrollVertically(dir) == true }
                    )

                    when (result) {
                        TrackpadSwipeAccumulator.SwipeResult.PAGE_NEXT -> {
                            viewModel.swipeNextPage()
                            return true
                        }
                        TrackpadSwipeAccumulator.SwipeResult.PAGE_PREVIOUS -> {
                            viewModel.swipePreviousPage()
                            return true
                        }
                        TrackpadSwipeAccumulator.SwipeResult.DISMISS -> {
                            OverlayDiagnosticsLog.record("dismiss_trigger", "swipe_dismiss_trackpad")
                            finishWithoutAnimation("swipe_dismiss_trackpad")
                            return true
                        }
                        TrackpadSwipeAccumulator.SwipeResult.NONE -> {
                            if (deltas.dx != 0f || deltas.dy != 0f) {
                                if (hostView != null) {
                                    return super.dispatchGenericMotionEvent(ev)
                                }
                                return true
                            }
                        }
                    }
                }
            }
        }

        return super.dispatchGenericMotionEvent(ev)
    }

    private fun isNonDismissingHardwareOrFocusKey(keyCode: Int): Boolean {
        if (OverlaySettings.isModifierKeyCode(keyCode)) return true
        return keyCode in setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_BRIGHTNESS_UP,
            KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_SYSRQ,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER
        )
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (SystemClock.uptimeMillis() - lastSecondaryClickUptimeMs < 600L) {
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && ::viewModel.isInitialized) {
                val consumed = viewModel.handleEscapeKey()
                if (!consumed) {
                    finishWithoutAnimation("key_back")
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && ::viewModel.isInitialized) {
            val state = viewModel.uiState.value
            val isTypingText = state.editingPageLabelIndex != null || state.isWidgetPickerOpen

            if (isTypingText || state.isOverlaySettingsOpen) {
                if (super.dispatchKeyEvent(event)) {
                    return true
                }
            }

            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                val consumed = viewModel.handleEscapeKey()
                if (!consumed) {
                    finishWithoutAnimation("key_escape")
                }
                return true
            }

            if (!isTypingText && !state.isOverlaySettingsOpen) {
                if (state.settings.matchesKeyEvent(event)) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastHotkeyToggleUptimeMs >= 350L) {
                        lastHotkeyToggleUptimeMs = now
                        finishWithoutAnimation("key_hotkey")
                    }
                    return true
                }

                // Key repeats or releases of the hotkey must never fall through to dismiss on other keys
                if (event.keyCode == state.settings.hotkeyKeyCode) {
                    return true
                }

                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_PAGE_UP -> {
                        val editingId = state.editingWidgetId
                        val editingWidget = if (editingId != null && event.isShiftPressed) {
                            state.widgets.find { it.appWidgetId == editingId }
                        } else {
                            null
                        }
                        if (editingWidget != null) {
                            val landingX = (state.gridProfile.columns - editingWidget.spanX).coerceAtLeast(0)
                            viewModel.dragWidgetAcrossEdge(
                                editingWidget.appWidgetId,
                                io.devnullapps.widgetleaf.domain.usecase.EdgeDirection.LEFT,
                                landingX,
                                editingWidget.cellY
                            )
                        } else {
                            viewModel.navigatePreviousPage()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_PAGE_DOWN -> {
                        val editingId = state.editingWidgetId
                        val editingWidget = if (editingId != null && event.isShiftPressed) {
                            state.widgets.find { it.appWidgetId == editingId }
                        } else {
                            null
                        }
                        if (editingWidget != null) {
                            viewModel.dragWidgetAcrossEdge(
                                editingWidget.appWidgetId,
                                io.devnullapps.widgetleaf.domain.usecase.EdgeDirection.RIGHT,
                                0,
                                editingWidget.cellY
                            )
                        } else {
                            viewModel.navigateNextPage()
                        }
                        return true
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        if (!event.isCtrlPressed && !event.isAltPressed) {
                            val digit = event.keyCode - KeyEvent.KEYCODE_0
                            viewModel.jumpToPageByNumberKey(digit)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_N -> {
                        if (event.isCtrlPressed) {
                            viewModel.openWidgetPicker()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_PLUS,
                    KeyEvent.KEYCODE_NUMPAD_ADD -> {
                        viewModel.openWidgetPicker()
                        return true
                    }
                    KeyEvent.KEYCODE_EQUALS -> {
                        if (event.isShiftPressed) {
                            viewModel.openWidgetPicker()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_FORWARD_DEL -> {
                        if (state.editingWidgetId != null) {
                            viewModel.deleteSelectedWidget()
                            return true
                        }
                    }
                }

                // Dismiss the overlay when pressing any other non-WidgetLeaf key (e.g. Space, A..Z, =, etc.)
                if (!isNonDismissingHardwareOrFocusKey(event.keyCode)) {
                    val sinceCreatedMs = SystemClock.uptimeMillis() - creationUptimeMs
                    if (sinceCreatedMs >= 300L) {
                        OverlayDiagnosticsLog.record("key_dismiss", "keyCode=${event.keyCode}")
                        finishWithoutAnimation("key_${event.keyCode}")
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun finishWithoutAnimation(reason: String = "unspecified") {
        cancelPendingTemporaryHideRunnable()
        pendingDeferredDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDeferredDismissRunnable = null
        pendingDeferredDismissTrigger = null
        OverlayDiagnosticsLog.record("finish", "reason=$reason")
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val TAG = "WidgetDeckActivity"
        private const val REQUEST_CODE_CONFIGURE_WIDGET = 4042
        internal const val WIDGET_ACTIVITY_LAUNCH_WINDOW_MS = 2500L
        internal const val DEFERRED_DISMISS_DELAY_MS = 850L
        internal const val TEMPORARY_HIDE_GRACE_MS = 120L
        var testContainerOverride: AppContainer? = null

        private var activeInstanceRef: WeakReference<WidgetDeckActivity>? = null

        var activeInstance: WidgetDeckActivity?
            get() = activeInstanceRef?.get()
            internal set(value) {
                activeInstanceRef = if (value != null) WeakReference(value) else null
            }
        var isDeckVisible: Boolean = false
            internal set
    }
}
