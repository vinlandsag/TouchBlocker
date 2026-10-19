package com.screentouch.touchblocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

import android.graphics.Rect
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "touch_blocker_preferences")

class TouchBlockerPreferences(private val context: Context) {

    companion object {
        val KEY_IS_BLOCKER_ENABLED = booleanPreferencesKey("is_blocker_enabled")
        val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val KEY_PRESET_LEFT = intPreferencesKey("preset_rect_left")
        val KEY_PRESET_TOP = intPreferencesKey("preset_rect_top")
        val KEY_PRESET_RIGHT = intPreferencesKey("preset_rect_right")
        val KEY_PRESET_BOTTOM = intPreferencesKey("preset_rect_bottom")
        val KEY_OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
    }

    val isBlockerEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_IS_BLOCKER_ENABLED] ?: false
    }

    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_START_ON_BOOT] ?: false
    }

    val overlayOpacity: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[KEY_OVERLAY_OPACITY] ?: 0.35f
    }

    val savedPresetRect: Flow<Rect?> = context.dataStore.data.map { preferences ->
        val left = preferences[KEY_PRESET_LEFT]
        val top = preferences[KEY_PRESET_TOP]
        val right = preferences[KEY_PRESET_RIGHT]
        val bottom = preferences[KEY_PRESET_BOTTOM]
        if (left != null && top != null && right != null && bottom != null) {
            Rect(left, top, right, bottom)
        } else {
            null
        }
    }

    suspend fun setBlockerEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IS_BLOCKER_ENABLED] = enabled
        }
    }

    suspend fun setStartOnBoot(startOnBoot: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_START_ON_BOOT] = startOnBoot
        }
    }

    suspend fun savePresetZone(rect: Rect, opacity: Float) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PRESET_LEFT] = rect.left
            preferences[KEY_PRESET_TOP] = rect.top
            preferences[KEY_PRESET_RIGHT] = rect.right
            preferences[KEY_PRESET_BOTTOM] = rect.bottom
            preferences[KEY_OVERLAY_OPACITY] = opacity
        }
    }
}
