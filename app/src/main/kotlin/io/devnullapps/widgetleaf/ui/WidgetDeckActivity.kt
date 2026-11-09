package io.devnullapps.widgetleaf.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
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
import io.devnullapps.widgetleaf.domain.model.PlacedWidget
import io.devnullapps.widgetleaf.services.ActiveDisplayLaunchHelper
import io.devnullapps.widgetleaf.services.WidgetDeckTileService
import io.devnullapps.widgetleaf.ui.components.DefaultFramelessWidgetContent
import io.devnullapps.widgetleaf.ui.components.WidgetDeckOverlayScreen
import io.devnullapps.widgetleaf.ui.hostview.DeckAppWidgetHostView
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
    private var lastPinTaskbarLeftState: Boolean = false
    private var lastPinTopBarRightState: Boolean = false
    private var hasRequestedNotificationPermission: Boolean = false
    private var lastSecondaryClickUptimeMs: Long = -10_000L
    private var lastWidgetMutationOrFlowUptimeMs: Long = -10_000L

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && ::viewModel.isInitialized) {
            container.statusBarTriggerManager.syncStatusBarTrigger(
                viewModel.uiState.value.settings.pinTopBarLeftStatusIcon
            )
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
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
        viewModel.onOverlayShown()
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
                            finishWithoutAnimation()
                        }
                    } else {
                        finishWithoutAnimation()
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

            LaunchedEffect(uiState.settings) {
                val settings = uiState.settings
                container.blurController.updateBlurRadius(window, settings.blurRadiusDp)
                if (settings.pinToTopBar &&
                    !hasRequestedNotificationPermission &&
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
                container.statusBarTriggerManager.syncStatusBarTrigger(settings.pinToTopBar)
                container.taskbarPlacementManager.syncRunningTaskbarPresence(
                    this@WidgetDeckActivity,
                    settings.effectiveShowInRunningTaskbar
                )

                val updatedProfile = container.gridProfileResolver.resolve(
                    context = this@WidgetDeckActivity,
                    useCenteredBentoCanvas = settings.useCenteredBentoCanvas
                )
                viewModel.updateGridProfile(updatedProfile)

                if (settings.pinTaskbarLeft && !lastPinTaskbarLeftState) {
                    container.taskbarPlacementManager.requestPinToTaskbarLeft(this@WidgetDeckActivity)
                }
                lastPinTaskbarLeftState = settings.pinTaskbarLeft

                if (settings.pinTopBarRightQsTile && !lastPinTopBarRightState) {
                    WidgetDeckTileService.requestPinQsTile(this@WidgetDeckActivity)
                }
                lastPinTopBarRightState = settings.pinTopBarRightQsTile
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
                        launchWidgetConfigurationActivity(action.appWidgetId)
                    }
                    null -> Unit
                }
            }

            val effectiveScrim = container.blurController.effectiveScrimAlpha(
                configuredAlpha = uiState.settings.scrimAlpha,
                isBlurActive = isBlurActive
            )
            val displayState = uiState.copy(
                settings = uiState.settings.copy(scrimAlpha = effectiveScrim)
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
                        finishWithoutAnimation()
                    }
                }
            }

            WidgetDeckOverlayScreen(
                uiState = displayState,
                onDismissOverlay = triggerExitAnimation,
                onSelectPage = viewModel::selectPage,
                onPreviousPage = viewModel::navigatePreviousPage,
                onNextPage = viewModel::navigateNextPage,
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
                    val w = uiState.widgets.find { it.appWidgetId == id }
                    if (w != null) {
                        viewModel.moveOrResizeWidget(id, x, y, w.spanX, w.spanY)
                    }
                },
                onResizeWidget = { id, x, y, sx, sy ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    viewModel.moveOrResizeWidget(id, x, y, sx, sy)
                },
                onDragAcrossEdge = viewModel::dragWidgetAcrossEdge,
                onRemoveWidget = { id ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    viewModel.removeWidget(id)
                },
                onReconfigureWidget = { appWidgetId ->
                    lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
                    launchWidgetConfigurationActivity(appWidgetId)
                },
                onOpenContextMenu = viewModel::openContextMenu,
                onCloseContextMenu = viewModel::closeContextMenu,
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
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                container.widgetDeckHost.createHostView(
                    context = ctx,
                    appWidgetId = placedWidget.appWidgetId,
                    appWidget = providerInfo
                ).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    onEnterEditModeListener = { id, _, _ ->
                        viewModel.enterWidgetEditMode(id)
                    }
                    onHorizontalPageScrollListener = { delta ->
                        if (delta > 0f) {
                            viewModel.navigatePreviousPage()
                        } else if (delta < 0f) {
                            viewModel.navigateNextPage()
                        }
                    }
                    onErrorRemoveOrReloadListener = { id ->
                        viewModel.removeWidget(id)
                    }
                }
            },
            update = { hostView ->
                hostView.interactionMode = if (isEditing) {
                    DeckAppWidgetHostView.HostInteractionMode.EDIT_MODE
                } else {
                    DeckAppWidgetHostView.HostInteractionMode.INTERACTIVE
                }
            }
        )
    }

    private fun launchWidgetConfigurationActivity(appWidgetId: Int) {
        lastWidgetMutationOrFlowUptimeMs = SystemClock.uptimeMillis()
        viewModel.markLaunchingInternalFlow(true)
        try {
            container.widgetDeckHost.startAppWidgetConfigureActivityForResult(
                this,
                appWidgetId,
                0,
                REQUEST_CODE_CONFIGURE_WIDGET,
                null
            )
        } catch (t: Throwable) {
            viewModel.markLaunchingInternalFlow(false)
            viewModel.onConfigureWidgetResult(appWidgetId, resultOk = true)
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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, isNewIntentWhileVisible = wasAlreadyResumed)
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
                    finishWithoutAnimation()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.widgetHostGateway.startListening()
    }

    override fun onResume() {
        super.onResume()
        wasAlreadyResumed = true
        if (::viewModel.isInitialized && viewModel.uiState.value.pendingWidgetAction == null) {
            viewModel.markLaunchingInternalFlow(false)
        }
    }

    override fun onPause() {
        wasAlreadyResumed = false
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val now = SystemClock.uptimeMillis()
        if (now - lastSecondaryClickUptimeMs < 600L || now - lastWidgetMutationOrFlowUptimeMs < 1500L) {
            return
        }
        if (::viewModel.isInitialized && viewModel.shouldAutoDismissOnUserLeave()) {
            finishWithoutAnimation()
        }
    }

    override fun onStop() {
        container.widgetHostGateway.stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        container.blurController.detachFromWindow(window)
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
        val profile = state.gridProfile
        val density = resources.displayMetrics.density
        val screenWidthDp = resources.configuration.screenWidthDp.toFloat()
        val gridWidthDp = if (state.settings.useCenteredBentoCanvas) {
            minOf(screenWidthDp, profile.maxCanvasWidthDp.toFloat())
        } else {
            screenWidthDp
        }
        val gridOffsetXDp = if (state.settings.useCenteredBentoCanvas && screenWidthDp > gridWidthDp) {
            (screenWidthDp - gridWidthDp) / 2f
        } else {
            0f
        }
        val localXDp = (screenX / density) - gridOffsetXDp
        val localYDp = screenY / density

        return state.activePageWidgets.firstOrNull { w ->
            val leftDp = profile.marginDp + w.cellX * (profile.cellWidthDp + profile.gutterDp)
            val topDp = profile.marginDp + w.cellY * (profile.cellHeightDp + profile.gutterDp)
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
                    val screenHeightPx = resources.displayMetrics.heightPixels
                    val density = resources.displayMetrics.density
                    val inBottomHudBand = ev.y >= (screenHeightPx - 88 * density)
                    if (!inBottomHudBand) {
                        viewModel.openContextMenu(Pair(ev.x, ev.y))
                    }
                }
            }
            // Always return true for secondary mouse/trackpad button events so Android ViewRootImpl
            // does not convert an unhandled right-click / two-finger click into KEYCODE_BACK!
            return true
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
                    finishWithoutAnimation()
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && ::viewModel.isInitialized) {
            val state = viewModel.uiState.value
            val isTypingText = state.editingPageLabelIndex != null || state.isWidgetPickerOpen

            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                val consumed = viewModel.handleEscapeKey()
                if (!consumed) {
                    finishWithoutAnimation()
                }
                return true
            }

            if (!isTypingText && !state.isOverlaySettingsOpen && state.settings.matchesKeyEvent(event)) {
                finishWithoutAnimation()
                return true
            }

            if (!isTypingText && !state.isOverlaySettingsOpen) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_PAGE_UP -> {
                        viewModel.navigatePreviousPage()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_PAGE_DOWN -> {
                        viewModel.navigateNextPage()
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
                    finishWithoutAnimation()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
        private const val REQUEST_CODE_CONFIGURE_WIDGET = 4042
        var testContainerOverride: AppContainer? = null
    }
}
