package com.screentouch.touchblocker.data

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "touch_blocker_preferences")

/**
 * Data model for a configured block zone.
 */
data class BlockZone(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val enabled: Boolean = true,
    val color: Int = Color.RED,
    val alpha: Float = 0.35f
) {
    fun toRect(): Rect = Rect(x, y, x + width, y + height)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x)
        put("y", y)
        put("width", width)
        put("height", height)
        put("enabled", enabled)
        put("color", color)
        put("alpha", alpha.toDouble())
    }

    companion object {
        fun fromRect(
            id: String,
            rect: Rect,
            enabled: Boolean = true,
            color: Int = Color.RED,
            alpha: Float = 0.35f
        ): BlockZone = BlockZone(
            id = id,
            x = rect.left,
            y = rect.top,
            width = rect.width(),
            height = rect.height(),
            enabled = enabled,
            color = color,
            alpha = alpha
        )

        fun fromJson(json: JSONObject): BlockZone = BlockZone(
            id = json.getString("id"),
            x = json.getInt("x"),
            y = json.getInt("y"),
            width = json.getInt("width"),
            height = json.getInt("height"),
            enabled = json.optBoolean("enabled", true),
            color = json.optInt("color", Color.RED),
            alpha = json.optDouble("alpha", 0.35).toFloat()
        )
    }
}

/**
 * Single Source of Truth for touch blocker settings, protection state, and zone persistence.
 */
class BlockZoneRepository(private val context: Context) {

    companion object {
        // Coordinated keys
        val KEY_IS_PROTECTION_ACTIVE = booleanPreferencesKey("is_protection_active_state")
        val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot_setting")

        // Primary zone coordinates
        val KEY_RECT_LEFT = intPreferencesKey("zone_rect_left")
        val KEY_RECT_TOP = intPreferencesKey("zone_rect_top")
        val KEY_RECT_RIGHT = intPreferencesKey("zone_rect_right")
        val KEY_RECT_BOTTOM = intPreferencesKey("zone_rect_bottom")
        val KEY_IS_EDIT_MODE = booleanPreferencesKey("is_edit_mode_state")

        // JSON array key for multi-zone configurations
        val KEY_ZONES_JSON = stringPreferencesKey("block_zones_json_array")

        @Volatile
        private var INSTANCE: BlockZoneRepository? = null

        fun getInstance(context: Context): BlockZoneRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlockZoneRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Flow emitting whether touch protection is currently active.
     */
    val isProtectionActive: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_IS_PROTECTION_ACTIVE] ?: false }

    /**
     * Flow emitting whether protection should be restored upon device reboot.
     */
    val startOnBoot: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_START_ON_BOOT] ?: false }

    /**
     * Flow emitting the primary saved zone bounds as an Android [Rect].
     */
    val savedBoundsFlow: Flow<Rect?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            val left = preferences[KEY_RECT_LEFT]
            val top = preferences[KEY_RECT_TOP]
            val right = preferences[KEY_RECT_RIGHT]
            val bottom = preferences[KEY_RECT_BOTTOM]
            if (left != null && top != null && right != null && bottom != null && right > left && bottom > top) {
                Rect(left, top, right, bottom)
            } else {
                null
            }
        }

    /**
     * Flow emitting whether the zone is configured in visual edit mode.
     */
    val isEditModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_IS_EDIT_MODE] ?: false }

    /**
     * Flow emitting all configured zones.
     */
    val zonesFlow: Flow<List<BlockZone>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { deserializeZones(it[KEY_ZONES_JSON] ?: "[]") }

    /**
     * Flow indicating whether at least one valid block zone has been configured.
     */
    val hasConfiguredZone: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            val left = preferences[KEY_RECT_LEFT]
            val top = preferences[KEY_RECT_TOP]
            val right = preferences[KEY_RECT_RIGHT]
            val bottom = preferences[KEY_RECT_BOTTOM]
            val hasPrimary = left != null && top != null && right != null && bottom != null && right > left && bottom > top
            val multiZones = deserializeZones(preferences[KEY_ZONES_JSON] ?: "[]")
            hasPrimary || multiZones.any { it.enabled }
        }

    /**
     * Sets whether touch protection is actively running.
     * Persists across app lifecycle, service restarts, and reboots.
     */
    suspend fun setProtectionActive(active: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IS_PROTECTION_ACTIVE] = active
        }
    }

    /**
     * Sets whether protection should be restored on device reboot.
     */
    suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_START_ON_BOOT] = enabled
        }
    }

    /**
     * Persists the primary zone bounds (left, top, right, bottom).
     */
    suspend fun saveZoneBounds(bounds: Rect, isEditMode: Boolean = false) {
        context.dataStore.edit { preferences ->
            preferences[KEY_RECT_LEFT] = bounds.left
            preferences[KEY_RECT_TOP] = bounds.top
            preferences[KEY_RECT_RIGHT] = bounds.right
            preferences[KEY_RECT_BOTTOM] = bounds.bottom
            preferences[KEY_IS_EDIT_MODE] = isEditMode
        }
    }

    /**
     * Clears saved primary zone coordinates.
     */
    suspend fun clearSavedZone() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_RECT_LEFT)
            preferences.remove(KEY_RECT_TOP)
            preferences.remove(KEY_RECT_RIGHT)
            preferences.remove(KEY_RECT_BOTTOM)
            preferences.remove(KEY_IS_EDIT_MODE)
        }
    }

    /**
     * Synchronous snapshot retrieval of the primary saved zone bounds.
     */
    suspend fun getSavedZone(): Rect? {
        return savedBoundsFlow.first()
    }

    // --- Multi-Zone Support ---

    suspend fun saveZone(zone: BlockZone) {
        context.dataStore.edit { preferences ->
            val currentZones = deserializeZones(preferences[KEY_ZONES_JSON] ?: "[]").toMutableList()
            val existingIndex = currentZones.indexOfFirst { it.id == zone.id }
            if (existingIndex >= 0) {
                currentZones[existingIndex] = zone
            } else {
                currentZones.add(zone)
            }
            preferences[KEY_ZONES_JSON] = serializeZones(currentZones)
        }
    }

    suspend fun removeZone(id: String) {
        context.dataStore.edit { preferences ->
            val currentZones = deserializeZones(preferences[KEY_ZONES_JSON] ?: "[]").toMutableList()
            currentZones.removeAll { it.id == id }
            preferences[KEY_ZONES_JSON] = serializeZones(currentZones)
        }
    }

    suspend fun clearAllZones() {
        context.dataStore.edit { preferences ->
            preferences[KEY_ZONES_JSON] = "[]"
            preferences.remove(KEY_RECT_LEFT)
            preferences.remove(KEY_RECT_TOP)
            preferences.remove(KEY_RECT_RIGHT)
            preferences.remove(KEY_RECT_BOTTOM)
            preferences[KEY_IS_PROTECTION_ACTIVE] = false
        }
    }

    suspend fun getEnabledZones(): List<BlockZone> {
        return zonesFlow.first().filter { it.enabled }
    }

    private fun serializeZones(zones: List<BlockZone>): String {
        val jsonArray = JSONArray()
        zones.forEach { jsonArray.put(it.toJson()) }
        return jsonArray.toString()
    }

    private fun deserializeZones(jsonString: String): List<BlockZone> {
        val list = mutableListOf<BlockZone>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                list.add(BlockZone.fromJson(jsonArray.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
