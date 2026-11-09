package io.devnullapps.widgetleaf.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.devnullapps.widgetleaf.domain.model.OverlaySettings
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
        val KEY_PIN_TASKBAR_LEFT = booleanPreferencesKey("pin_taskbar_left")
        val KEY_SHOW_IN_RUNNING_TASKBAR_RIGHT = booleanPreferencesKey("show_in_running_taskbar_right")
        val KEY_PIN_TOP_BAR_LEFT_STATUS_ICON = booleanPreferencesKey("pin_top_bar_left_status_icon")
        val KEY_PIN_TOP_BAR_RIGHT_QS_TILE = booleanPreferencesKey("pin_top_bar_right_qs_tile")
        val KEY_USE_CENTERED_BENTO_CANVAS = booleanPreferencesKey("use_centered_bento_canvas")

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
            return OverlaySettings(
                blurRadiusDp = preferences[KEY_BLUR_RADIUS_DP] ?: 36,
                scrimAlpha = preferences[KEY_SCRIM_ALPHA] ?: 0.72f,
                useDynamicColor = preferences[KEY_USE_DYNAMIC_COLOR] ?: false,
                openOnPageOne = preferences[KEY_OPEN_ON_PAGE_ONE] ?: false,
                lastViewedPageIndex = preferences[KEY_LAST_VIEWED_PAGE_INDEX] ?: 0,
                launchOnStartup = preferences[KEY_LAUNCH_ON_STARTUP] ?: false,
                pinTaskbarLeft = preferences[KEY_PIN_TASKBAR_LEFT] ?: false,
                showInRunningTaskbarRight = preferences[KEY_SHOW_IN_RUNNING_TASKBAR_RIGHT] ?: false,
                pinTopBarLeftStatusIcon = preferences[KEY_PIN_TOP_BAR_LEFT_STATUS_ICON] ?: false,
                pinTopBarRightQsTile = preferences[KEY_PIN_TOP_BAR_RIGHT_QS_TILE] ?: false,
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
            preferences[KEY_PIN_TASKBAR_LEFT] = updated.pinTaskbarLeft
            preferences[KEY_SHOW_IN_RUNNING_TASKBAR_RIGHT] = updated.showInRunningTaskbarRight
            preferences[KEY_PIN_TOP_BAR_LEFT_STATUS_ICON] = updated.pinTopBarLeftStatusIcon
            preferences[KEY_PIN_TOP_BAR_RIGHT_QS_TILE] = updated.pinTopBarRightQsTile
            preferences[KEY_USE_CENTERED_BENTO_CANVAS] = updated.useCenteredBentoCanvas
        }
    }
}
