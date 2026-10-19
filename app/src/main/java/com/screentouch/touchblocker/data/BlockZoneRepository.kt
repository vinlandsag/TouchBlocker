package com.screentouch.touchblocker.data

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Data model representing a configured touch blocking zone.
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
    /**
     * Converts zone coordinates into an Android [Rect].
     */
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
 * Repository for managing block zone persistence using DataStore Preferences.
 * Serializes block zones into a JSON array and exposes real-time flows to
 * UI and background services.
 */
class BlockZoneRepository(private val context: Context) {

    companion object {
        private val KEY_ZONES_JSON = stringPreferencesKey("block_zones_json_array")
        private val KEY_PROTECTION_ACTIVE = booleanPreferencesKey("is_protection_active_state")

        @Volatile
        private var INSTANCE: BlockZoneRepository? = null

        fun getInstance(context: Context): BlockZoneRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlockZoneRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Flow emitting the list of all configured block zones.
     */
    val zonesFlow: Flow<List<BlockZone>> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val jsonString = preferences[KEY_ZONES_JSON] ?: "[]"
            deserializeZones(jsonString)
        }

    /**
     * Flow tracking whether touch protection was actively running.
     */
    val isProtectionActive: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_PROTECTION_ACTIVE] ?: false
        }

    /**
     * Persists a block zone (inserts new or updates existing).
     */
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

    /**
     * Saves or replaces the full list of block zones.
     */
    suspend fun saveAllZones(zones: List<BlockZone>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ZONES_JSON] = serializeZones(zones)
        }
    }

    /**
     * Removes a block zone by its unique [id].
     */
    suspend fun removeZone(id: String) {
        context.dataStore.edit { preferences ->
            val currentZones = deserializeZones(preferences[KEY_ZONES_JSON] ?: "[]").toMutableList()
            currentZones.removeAll { it.id == id }
            preferences[KEY_ZONES_JSON] = serializeZones(currentZones)
        }
    }

    /**
     * Toggles the enabled/disabled state of a specific block zone.
     */
    suspend fun toggleZone(id: String, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            val currentZones = deserializeZones(preferences[KEY_ZONES_JSON] ?: "[]").toMutableList()
            val index = currentZones.indexOfFirst { it.id == id }
            if (index >= 0) {
                currentZones[index] = currentZones[index].copy(enabled = enabled)
                preferences[KEY_ZONES_JSON] = serializeZones(currentZones)
            }
        }
    }

    /**
     * Clears all configured zones from DataStore.
     */
    suspend fun clearAllZones() {
        context.dataStore.edit { preferences ->
            preferences[KEY_ZONES_JSON] = "[]"
        }
    }

    /**
     * Sets whether touch protection is actively running.
     */
    suspend fun setProtectionActive(active: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PROTECTION_ACTIVE] = active
        }
    }

    /**
     * Retrieves the current snapshot list of enabled active block zones.
     */
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
                val obj = jsonArray.getJSONObject(i)
                list.add(BlockZone.fromJson(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
