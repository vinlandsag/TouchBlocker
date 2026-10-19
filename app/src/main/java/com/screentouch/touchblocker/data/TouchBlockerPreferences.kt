package com.screentouch.touchblocker.data

import android.content.Context
import android.graphics.Rect
import kotlinx.coroutines.flow.Flow

/**
 * Facade providing compatibility with legacy calls while delegating
 * directly to the unified [BlockZoneRepository].
 */
class TouchBlockerPreferences(context: Context) {

    private val repository = BlockZoneRepository.getInstance(context)

    val isBlockerEnabled: Flow<Boolean> = repository.isProtectionActive
    val startOnBoot: Flow<Boolean> = repository.startOnBoot
    val savedPresetRect: Flow<Rect?> = repository.savedBoundsFlow

    suspend fun setBlockerEnabled(enabled: Boolean) {
        repository.setProtectionActive(enabled)
    }

    suspend fun setStartOnBoot(startOnBoot: Boolean) {
        repository.setStartOnBoot(startOnBoot)
    }

    suspend fun savePresetZone(rect: Rect, opacity: Float = 0.35f) {
        repository.saveZoneBounds(rect, isEditMode = false)
    }
}
