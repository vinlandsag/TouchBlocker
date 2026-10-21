package com.screentouch.touchblocker.data

import android.content.Context
import android.graphics.Rect
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "touch_blocker_preferences")

class BlockZoneRepository private constructor(private val context: Context) {

    companion object {
        private val KEY_ACTIVE        = booleanPreferencesKey("is_protection_active_state")
        private val KEY_BOOT          = booleanPreferencesKey("start_on_boot_setting")
        private val KEY_LEFT          = intPreferencesKey("zone_rect_left")
        private val KEY_TOP           = intPreferencesKey("zone_rect_top")
        private val KEY_RIGHT         = intPreferencesKey("zone_rect_right")
        private val KEY_BOTTOM        = intPreferencesKey("zone_rect_bottom")

        @Volatile private var INSTANCE: BlockZoneRepository? = null

        fun getInstance(context: Context): BlockZoneRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlockZoneRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private fun <T> flow(transform: (Preferences) -> T): Flow<T> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(transform)

    val isProtectionActive: Flow<Boolean> = flow { it[KEY_ACTIVE] ?: false }
    val startOnBoot: Flow<Boolean>         = flow { it[KEY_BOOT] ?: false }
    val savedBoundsFlow: Flow<Rect?>       = flow { prefs ->
        val l = prefs[KEY_LEFT]; val t = prefs[KEY_TOP]
        val r = prefs[KEY_RIGHT]; val b = prefs[KEY_BOTTOM]
        if (l != null && t != null && r != null && b != null && r > l && b > t) Rect(l, t, r, b) else null
    }
    val hasConfiguredZone: Flow<Boolean>   = flow { prefs ->
        val l = prefs[KEY_LEFT]; val r = prefs[KEY_RIGHT]
        val t = prefs[KEY_TOP];  val b = prefs[KEY_BOTTOM]
        l != null && r != null && t != null && b != null && r > l && b > t
    }

    suspend fun setProtectionActive(active: Boolean) =
        context.dataStore.edit { it[KEY_ACTIVE] = active }

    suspend fun setStartOnBoot(enabled: Boolean) =
        context.dataStore.edit { it[KEY_BOOT] = enabled }

    suspend fun saveZoneBounds(bounds: Rect) = context.dataStore.edit {
        it[KEY_LEFT] = bounds.left; it[KEY_TOP] = bounds.top
        it[KEY_RIGHT] = bounds.right; it[KEY_BOTTOM] = bounds.bottom
    }

    suspend fun getSavedZone(): Rect? = savedBoundsFlow.first()

    suspend fun clearSavedZone() = context.dataStore.edit {
        it.remove(KEY_LEFT); it.remove(KEY_TOP); it.remove(KEY_RIGHT); it.remove(KEY_BOTTOM)
    }
}
