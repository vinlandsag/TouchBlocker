package com.screentouch.touchblocker.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.screentouch.touchblocker.GhostTouchBlockerService
import com.screentouch.touchblocker.MainActivity
import com.screentouch.touchblocker.R
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings Tile Service allowing users to toggle Ghost Touch Blocker ON/OFF
 * directly from the Android notification shade / Quick Settings panel.
 */
class GhostBlockerTileService : TileService() {

    companion object {
        private const val TAG = "GhostBlockerTile"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var listeningJob: Job? = null
    private lateinit var repository: BlockZoneRepository

    override fun onCreate() {
        super.onCreate()
        repository = BlockZoneRepository.getInstance(applicationContext)
    }

    /**
     * Called when the tile becomes visible in the Quick Settings panel.
     * Starts observing the protection state flow and updates the tile visual state.
     */
    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            repository.isProtectionActive.collect { isActive ->
                updateTileState(isActive)
            }
        }
    }

    /**
     * Called when the tile is no longer visible in the Quick Settings panel.
     */
    override fun onStopListening() {
        super.onStopListening()
        listeningJob?.cancel()
        listeningJob = null
    }

    /**
     * Handles Quick Settings Tile clicks:
     * - If OFF: Validates overlay permission and starts [GhostTouchBlockerService].
     * - If ON: Stops [GhostTouchBlockerService].
     * - Updates tile state to [Tile.STATE_ACTIVE] or [Tile.STATE_INACTIVE].
     */
    override fun onClick() {
        super.onClick()

        serviceScope.launch {
            val isCurrentlyActive = repository.isProtectionActive.first()

            if (!isCurrentlyActive) {
                // Toggling Protection ON
                if (!PermissionUtils.hasOverlayPermission(this@GhostBlockerTileService)) {
                    Log.w(TAG, "Overlay permission not granted. Prompting user...")
                    launchOverlayPermissionScreen()
                    return@launch
                }

                Log.i(TAG, "Activating Ghost Touch Blocker from Quick Settings Tile.")
                GhostTouchBlockerService.start(applicationContext)
                repository.setProtectionActive(true)

                updateTileState(true)
            } else {
                // Toggling Protection OFF
                Log.i(TAG, "Deactivating Ghost Touch Blocker from Quick Settings Tile.")
                GhostTouchBlockerService.stop(applicationContext)
                repository.setProtectionActive(false)

                updateTileState(false)
            }
        }
    }

    /**
     * Updates the QS tile label, subtitle, and state.
     */
    private fun updateTileState(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) "Active" else "Off"
        }
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_lock)
        tile.updateTile()
    }

    /**
     * Opens overlay permission settings or main activity when permission is missing.
     * Uses Android 14 (API 34) compatible startActivityAndCollapse(PendingIntent).
     */
    private fun launchOverlayPermissionScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34) requires PendingIntent for startActivityAndCollapse
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listeningJob?.cancel()
    }
}
