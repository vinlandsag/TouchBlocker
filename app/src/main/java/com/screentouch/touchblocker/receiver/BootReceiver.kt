package com.screentouch.touchblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screentouch.touchblocker.GhostTouchBlockerService
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered on system startup (ACTION_BOOT_COMPLETED).
 * Automatically checks whether touch protection was active prior to shutdown
 * and restores GhostTouchBlockerService with transparent blocking zones.
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

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Check boot preference before restoring protection
                    val startOnBoot = repository.startOnBoot.first()
                    if (!startOnBoot) {
                        Log.i(TAG, "Start on boot disabled. Skipping restoration.")
                        return@launch
                    }
                    val wasProtectionActive = repository.isProtectionActive.first()
                    val hasOverlayPermission = PermissionUtils.hasOverlayPermission(context)

                    Log.d(TAG, "wasProtectionActive=$wasProtectionActive, hasOverlayPermission=$hasOverlayPermission")

                    if (wasProtectionActive && hasOverlayPermission) {
                        Log.i(TAG, "Restoring GhostTouchBlockerService with transparent overlay after reboot.")

                        val savedZone = repository.getSavedZone()
                        if (savedZone != null) {
                            // Re-apply primary saved zone in transparent mode (isEditMode = false)
                            GhostTouchBlockerService.applyZone(
                                context = context.applicationContext,
                                bounds = savedZone,
                                isEditMode = false,
                                zoneId = GhostTouchBlockerService.DEFAULT_ZONE_ID
                            )
                        } else {
                            val savedZones = repository.getEnabledZones()
                            if (savedZones.isNotEmpty()) {
                                for (zone in savedZones) {
                                    GhostTouchBlockerService.applyZone(
                                        context = context.applicationContext,
                                        bounds = zone.toRect(),
                                        isEditMode = false,
                                        zoneId = zone.id
                                    )
                                }
                            } else {
                                GhostTouchBlockerService.start(context.applicationContext)
                            }
                        }
                    } else if (wasProtectionActive && !hasOverlayPermission) {
                        Log.w(TAG, "Cannot restore touch blocker: Overlay permission revoked.")
                        repository.setProtectionActive(false)
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
