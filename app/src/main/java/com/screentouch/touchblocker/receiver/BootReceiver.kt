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

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val result = goAsync()
        val repository = BlockZoneRepository.getInstance(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!repository.startOnBoot.first()) return@launch
                if (!repository.isProtectionActive.first()) return@launch
                if (!PermissionUtils.hasOverlayPermission(context)) {
                    Log.w("BootReceiver", "Overlay permission revoked; clearing active state.")
                    repository.setProtectionActive(false)
                    return@launch
                }
                val saved = repository.getSavedZone()
                if (saved != null) {
                    GhostTouchBlockerService.applyZone(context.applicationContext, saved, isEditMode = false)
                } else {
                    GhostTouchBlockerService.start(context.applicationContext)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error restoring on boot", e)
            } finally {
                result.finish()
            }
        }
    }
}
