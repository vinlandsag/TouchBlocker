package com.screentouch.touchblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screentouch.touchblocker.GhostTouchBlockerService
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.data.TouchBlockerPreferences
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered on system startup (ACTION_BOOT_COMPLETED).
 * Automatically checks whether touch protection was active prior to shutdown
 * and restores GhostTouchBlockerService and active zones accordingly.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Device booted (${intent.action}). Checking touch protection state...")

            val pendingResult = goAsync()
            val repository = BlockZoneRepository.getInstance(context)
            val preferences = TouchBlockerPreferences(context)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val wasProtectionActive = repository.isProtectionActive.first() ||
                            preferences.isBlockerEnabled.first()
                    val hasOverlayPermission = PermissionUtils.hasOverlayPermission(context)

                    Log.d(TAG, "wasProtectionActive=$wasProtectionActive, hasOverlayPermission=$hasOverlayPermission")

                    if (wasProtectionActive && hasOverlayPermission) {
                        Log.i(TAG, "Restoring GhostTouchBlockerService after reboot.")

                        // Start foreground service
                        GhostTouchBlockerService.start(context.applicationContext)

                        // Restore saved enabled block zones
                        val savedZones = repository.getEnabledZones()
                        if (savedZones.isNotEmpty()) {
                            for (zone in savedZones) {
                                GhostTouchBlockerService.addZone(
                                    context = context.applicationContext,
                                    id = zone.id,
                                    rect = zone.toRect()
                                )
                            }
                            Log.i(TAG, "Restored ${savedZones.size} block zones after boot.")
                        }
                    } else if (wasProtectionActive && !hasOverlayPermission) {
                        Log.w(TAG, "Cannot restore touch blocker: SYSTEM_ALERT_WINDOW overlay permission revoked.")
                        repository.setProtectionActive(false)
                        preferences.setBlockerEnabled(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error restoring touch blocker on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
