package com.screentouch.touchblocker.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Utility helper for managing system overlay permissions required to display
 * touch blocking overlay windows across the system.
 */
object PermissionUtils {

    /**
     * Checks if the application currently has permission to draw on top of other apps (SYSTEM_ALERT_WINDOW).
     *
     * @param context The application or activity context.
     * @return True if overlay permission is granted via Settings.canDrawOverlays, false otherwise.
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Launches the system settings screen prompting the user to grant overlay permission (canDrawOverlays).
     * Attempts to deep link directly to the app's overlay settings page first, falling back to the
     * global overlay management page if the package-specific intent fails.
     *
     * @param activity The calling activity.
     */
    fun requestOverlayPermission(activity: Activity) {
        if (!hasOverlayPermission(activity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            try {
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // Fallback for custom OEM ROMs that do not support package URI scheme for this action
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                activity.startActivity(fallbackIntent)
            }
        }
    }
}
