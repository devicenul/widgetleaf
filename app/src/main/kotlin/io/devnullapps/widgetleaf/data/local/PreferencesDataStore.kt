package io.devnullapps.widgetleaf.data.local

import android.content.Context
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
import io.devnullapps.widgetleaf.domain.model.TopBarPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "widgetleaf_settings")

class PreferencesDataStore(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        val KEY_BLUR_RADIUS_DP = intPreferencesKey("blur_radius_dp")
        val KEY_SCRIM_ALPHA = floatPreferencesKey("scrim_alpha")
        val KEY_USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val KEY_OPEN_ON_PAGE_ONE = booleanPreferencesKey("open_on_page_one")
        val KEY_LAST_VIEWED_PAGE_INDEX = intPreferencesKey("last_viewed_page_index")
        val KEY_LAUNCH_ON_STARTUP = booleanPreferencesKey("launch_on_startup")
        val KEY_PIN_TOP_BAR_RIGHT_QS_TILE = booleanPreferencesKey("pin_top_bar_right_qs_tile")
        val KEY_TOP_BAR_POSITION = stringPreferencesKey("top_bar_position")
        val KEY_HOTKEY_KEY_CODE = intPreferencesKey("hotkey_key_code")
        val KEY_HOTKEY_CTRL = booleanPreferencesKey("hotkey_ctrl")
        val KEY_HOTKEY_ALT = booleanPreferencesKey("hotkey_alt")
        val KEY_HOTKEY_SHIFT = booleanPreferencesKey("hotkey_shift")
        val KEY_HOTKEY_META = booleanPreferencesKey("hotkey_meta")
        val KEY_USE_CENTERED_BENTO_CANVAS = booleanPreferencesKey("use_centered_bento_canvas")

        // Legacy keys migrated and removed gracefully
        private val KEY_LEGACY_PIN_TASKBAR_LEFT = booleanPreferencesKey("pin_taskbar_left")
        private val KEY_LEGACY_SHOW_IN_RUNNING_TASKBAR_RIGHT = booleanPreferencesKey("show_in_running_taskbar_right")
        private val KEY_LEGACY_PIN_TOP_BAR_LEFT_STATUS_ICON = booleanPreferencesKey("pin_top_bar_left_status_icon")
        private val KEY_LEGACY_PIN_TO_TOP_BAR = booleanPreferencesKey("pin_to_top_bar")

        @Volatile
        private var INSTANCE: PreferencesDataStore? = null

        fun createDataStore(context: Context): PreferencesDataStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesDataStore(context.applicationContext.dataStore).also {
                    INSTANCE = it
                }
            }
        }

        private fun toOverlaySettings(preferences: Preferences): OverlaySettings {
            val topBarPosition = when (preferences[KEY_TOP_BAR_POSITION]) {
                TopBarPosition.RIGHT.name -> TopBarPosition.RIGHT
                else -> TopBarPosition.LEFT
            }
            return OverlaySettings(
                blurRadiusDp = preferences[KEY_BLUR_RADIUS_DP] ?: 36,
                scrimAlpha = preferences[KEY_SCRIM_ALPHA] ?: 0.72f,
                useDynamicColor = preferences[KEY_USE_DYNAMIC_COLOR] ?: false,
                openOnPageOne = preferences[KEY_OPEN_ON_PAGE_ONE] ?: false,
                lastViewedPageIndex = preferences[KEY_LAST_VIEWED_PAGE_INDEX] ?: 0,
                launchOnStartup = preferences[KEY_LAUNCH_ON_STARTUP] ?: false,
                topBarPosition = topBarPosition,
                pinTopBarRightQsTile = preferences[KEY_PIN_TOP_BAR_RIGHT_QS_TILE] ?: false,
                hotkeyKeyCode = preferences[KEY_HOTKEY_KEY_CODE] ?: KeyEvent.KEYCODE_W,
                hotkeyCtrl = preferences[KEY_HOTKEY_CTRL] ?: false,
                hotkeyAlt = preferences[KEY_HOTKEY_ALT] ?: true,
                hotkeyShift = preferences[KEY_HOTKEY_SHIFT] ?: false,
                hotkeyMeta = preferences[KEY_HOTKEY_META] ?: false,
                useCenteredBentoCanvas = preferences[KEY_USE_CENTERED_BENTO_CANVAS] ?: true
            )
        }
    }

    val settingsFlow: Flow<OverlaySettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            toOverlaySettings(preferences)
        }

    suspend fun updateSettings(transform: (OverlaySettings) -> OverlaySettings) {
        dataStore.edit { preferences ->
            val current = toOverlaySettings(preferences)
            val updated = transform(current)
            preferences[KEY_BLUR_RADIUS_DP] = updated.blurRadiusDp
            preferences[KEY_SCRIM_ALPHA] = updated.scrimAlpha
            preferences[KEY_USE_DYNAMIC_COLOR] = updated.useDynamicColor
            preferences[KEY_OPEN_ON_PAGE_ONE] = updated.openOnPageOne
            preferences[KEY_LAST_VIEWED_PAGE_INDEX] = updated.lastViewedPageIndex
            preferences[KEY_LAUNCH_ON_STARTUP] = updated.launchOnStartup
            preferences[KEY_PIN_TOP_BAR_RIGHT_QS_TILE] = updated.pinTopBarRightQsTile
            preferences[KEY_TOP_BAR_POSITION] = updated.topBarPosition.name
            preferences[KEY_HOTKEY_KEY_CODE] = updated.hotkeyKeyCode
            preferences[KEY_HOTKEY_CTRL] = updated.hotkeyCtrl
            preferences[KEY_HOTKEY_ALT] = updated.hotkeyAlt
            preferences[KEY_HOTKEY_SHIFT] = updated.hotkeyShift
            preferences[KEY_HOTKEY_META] = updated.hotkeyMeta
            preferences[KEY_USE_CENTERED_BENTO_CANVAS] = updated.useCenteredBentoCanvas

            // Clean up legacy keys
            preferences.remove(KEY_LEGACY_PIN_TASKBAR_LEFT)
            preferences.remove(KEY_LEGACY_SHOW_IN_RUNNING_TASKBAR_RIGHT)
            preferences.remove(KEY_LEGACY_PIN_TOP_BAR_LEFT_STATUS_ICON)
            preferences.remove(KEY_LEGACY_PIN_TO_TOP_BAR)
        }
    }
}
