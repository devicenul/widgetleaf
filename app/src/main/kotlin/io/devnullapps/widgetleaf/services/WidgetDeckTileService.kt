package io.devnullapps.widgetleaf.services

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.devnullapps.widgetleaf.R
import java.util.function.Consumer

/**
 * Top Bar Right — Quick Settings / BentoBar TileService.
 *
 * Provides a Quick Settings tile that toggles the WidgetLeaf overlay.
 * On Android 13+ (API 33+), supports one-click pinning via [StatusBarManager.requestAddTileService].
 */
class WidgetDeckTileService : TileService() {

    internal var lastLaunchedIntent: Intent? = null
    internal var lastLaunchedPendingIntent: PendingIntent? = null

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = getString(R.string.tile_widget_deck_label)
            subtitle = getString(R.string.tile_widget_deck_subtitle)
            icon = Icon.createWithResource(this@WidgetDeckTileService, R.drawable.ic_qs_tile_leaf)
            updateTile()
        }
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = ActiveDisplayLaunchHelper.createLaunchIntent(
            context = this,
            action = ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
        )
        val pendingIntent = ActiveDisplayLaunchHelper.createLaunchPendingIntent(
            context = this,
            action = ActiveDisplayLaunchHelper.ACTION_TOGGLE_DECK
        )
        lastLaunchedIntent = intent
        lastLaunchedPendingIntent = pendingIntent

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startActivityAndCollapse(pendingIntent)
            } catch (_: Throwable) {
                try {
                    startActivity(intent)
                } catch (_: Throwable) {
                    // Ignored in test environment
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            } catch (_: Throwable) {
                startActivity(intent)
            }
        }
    }

    companion object {
        /**
         * Requests the system to pin the WidgetDeck Quick Settings tile to the user's QS panel.
         * Supported on Android 13+ (API 33+).
         */
        fun requestPinQsTile(context: Context, onResult: (Int) -> Unit = {}) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                if (statusBarManager != null) {
                    statusBarManager.requestAddTileService(
                        ComponentName(context, WidgetDeckTileService::class.java),
                        context.getString(R.string.tile_widget_deck_label),
                        Icon.createWithResource(context, R.drawable.ic_qs_tile_leaf),
                        ContextCompat.getMainExecutor(context),
                        Consumer { result -> onResult(result) }
                    )
                } else {
                    onResult(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)
                }
            } else {
                onResult(-1)
            }
        }
    }
}
