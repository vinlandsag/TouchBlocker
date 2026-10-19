package com.screentouch.touchblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.screentouch.touchblocker.data.BlockZone
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.overlay.TouchOverlayView
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Service responsible for managing touch-blocking overlay windows.
 * Extends [LifecycleService] and operates as an Android 14 (API 34) specialUse service.
 */
class GhostTouchBlockerService : LifecycleService() {

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    /**
     * Data class holding an active overlay view and its associated WindowManager configuration.
     */
    data class BlockZoneEntry(
        val id: String,
        val view: TouchOverlayView,
        val layoutParams: WindowManager.LayoutParams,
        var rect: Rect
    )

    // Map maintaining active overlay views added to WindowManager keyed by zone ID
    private val activeZones = ConcurrentHashMap<String, BlockZoneEntry>()

    companion object {
        private const val TAG = "GhostTouchBlocker"
        const val NOTIFICATION_CHANNEL_ID = "GHOST_BLOCKER_CHANNEL"
        private const val NOTIFICATION_ID = 1001

        // Intent Actions
        const val ACTION_START = "com.screentouch.touchblocker.ACTION_START"
        const val ACTION_STOP = "com.screentouch.touchblocker.ACTION_STOP"
        const val ACTION_ADD_ZONE = "com.screentouch.touchblocker.ACTION_ADD_ZONE"
        const val ACTION_UPDATE_ZONE = "com.screentouch.touchblocker.ACTION_UPDATE_ZONE"
        const val ACTION_REMOVE_ZONE = "com.screentouch.touchblocker.ACTION_REMOVE_ZONE"
        const val ACTION_REMOVE_ALL_ZONES = "com.screentouch.touchblocker.ACTION_REMOVE_ALL_ZONES"

        // Intent Extras
        const val EXTRA_ZONE_ID = "extra_zone_id"
        const val EXTRA_ZONE_RECT = "extra_zone_rect"
        const val EXTRA_ZONE_COLOR = "extra_zone_color"
        const val EXTRA_ZONE_ALPHA = "extra_zone_alpha"

        /**
         * Starts the GhostTouchBlockerService in the foreground.
         */
        fun start(context: Context) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startService(context: Context) = start(context)

        /**
         * Stops the GhostTouchBlockerService and dismisses all overlays.
         */
        fun stop(context: Context) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun stopService(context: Context) = stop(context)

        /**
         * Sends an intent to add a block zone.
         */
        fun addZone(context: Context, id: String, rect: Rect) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                action = ACTION_ADD_ZONE
                putExtra(EXTRA_ZONE_ID, id)
                putExtra(EXTRA_ZONE_RECT, rect)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Sends an intent to remove a specific block zone.
         */
        fun removeZone(context: Context, id: String) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                action = ACTION_REMOVE_ZONE
                putExtra(EXTRA_ZONE_ID, id)
            }
            context.startService(intent)
        }

        /**
         * Sends an intent to remove all block zones.
         */
        fun removeAll(context: Context) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                action = ACTION_REMOVE_ALL_ZONES
            }
            context.startService(intent)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): GhostTouchBlockerService = this@GhostTouchBlockerService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Construct and start persistent foreground notification using "GHOST_BLOCKER_CHANNEL"
        startForegroundNotification()

        intent?.let { handleIntent(it) }

        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                lifecycleScope.launch {
                    val repository = BlockZoneRepository.getInstance(applicationContext)
                    repository.setProtectionActive(true)
                    val savedZones = repository.getEnabledZones()
                    if (savedZones.isNotEmpty()) {
                        for (zone in savedZones) {
                            addBlockZone(zone.id, zone.toRect(), zone.color, zone.alpha)
                        }
                    } else if (activeZones.isEmpty()) {
                        val displayMetrics = resources.displayMetrics
                        val fullScreenRect = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
                        addBlockZone("default_fullscreen", fullScreenRect)
                    }
                }
            }
            ACTION_STOP -> {
                removeAllZones()
                stopForegroundService()
            }
            ACTION_ADD_ZONE -> {
                val id = intent.getStringExtra(EXTRA_ZONE_ID)
                @Suppress("DEPRECATION")
                val rect = intent.getParcelableExtra<Rect>(EXTRA_ZONE_RECT)
                val color = intent.getIntExtra(EXTRA_ZONE_COLOR, Color.RED)
                val alpha = intent.getFloatExtra(EXTRA_ZONE_ALPHA, 0.35f)
                if (id != null && rect != null) {
                    addBlockZone(id, rect, color, alpha)
                }
            }
            ACTION_UPDATE_ZONE -> {
                val id = intent.getStringExtra(EXTRA_ZONE_ID)
                @Suppress("DEPRECATION")
                val rect = intent.getParcelableExtra<Rect>(EXTRA_ZONE_RECT)
                if (id != null && rect != null) {
                    updateBlockZone(id, rect)
                }
            }
            ACTION_REMOVE_ZONE -> {
                val id = intent.getStringExtra(EXTRA_ZONE_ID)
                if (id != null) {
                    removeBlockZone(id)
                }
            }
            ACTION_REMOVE_ALL_ZONES -> {
                removeAllZones()
            }
        }
    }

    /**
     * Adds a touch block zone overlay at the specified [rect] coordinates.
     *
     * WindowManager layout params:
     * - width = rect.width()
     * - height = rect.height()
     * - x = rect.left
     * - y = rect.top
     * - type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
     * - flags = FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN
     * - format = PixelFormat.TRANSLUCENT
     * - gravity = Gravity.TOP or Gravity.LEFT
     *
     * @param id Unique identifier for the block zone.
     * @param rect Bounding rectangle on the screen for the block zone.
     * @param color Optional visual debug color (default: Color.RED).
     * @param alpha Optional transparency level (default: 0.35f).
     */
    fun addBlockZone(
        id: String,
        rect: Rect,
        color: Int = Color.RED,
        alpha: Float = 0.35f
    ) {
        runOnMainThread {
            if (!PermissionUtils.hasOverlayPermission(this)) {
                Log.e(TAG, "Cannot add block zone $id: Overlay permission not granted.")
                return@runOnMainThread
            }

            // If a zone with this id already exists, update its position and size
            if (activeZones.containsKey(id)) {
                updateBlockZone(id, rect)
                return@runOnMainThread
            }

            val overlayView = TouchOverlayView(this).apply {
                overlayColor = color
                overlayAlpha = alpha
            }

            val layoutParams = WindowManager.LayoutParams().apply {
                width = rect.width()
                height = rect.height()
                x = rect.left
                y = rect.top
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.LEFT
            }

            try {
                windowManager?.addView(overlayView, layoutParams)
                activeZones[id] = BlockZoneEntry(
                    id = id,
                    view = overlayView,
                    layoutParams = layoutParams,
                    rect = Rect(rect)
                )
                Log.d(TAG, "Added block zone '$id' at $rect (total active: ${activeZones.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add block zone '$id' to WindowManager", e)
            }
        }
    }

    /**
     * Updates an existing block zone's bounds to [rect].
     *
     * @param id Unique identifier of the block zone to update.
     * @param rect New rectangle coordinates and dimensions.
     */
    fun updateBlockZone(id: String, rect: Rect) {
        runOnMainThread {
            val entry = activeZones[id]
            if (entry == null) {
                // If zone does not exist yet, add it
                addBlockZone(id, rect)
                return@runOnMainThread
            }

            entry.layoutParams.apply {
                width = rect.width()
                height = rect.height()
                x = rect.left
                y = rect.top
            }
            entry.rect = Rect(rect)

            try {
                windowManager?.updateViewLayout(entry.view, entry.layoutParams)
                Log.d(TAG, "Updated block zone '$id' to $rect")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update block zone '$id'", e)
            }
        }
    }

    /**
     * Removes an active block zone overlay from WindowManager by its [id].
     *
     * @param id Unique identifier of the block zone to remove.
     */
    fun removeBlockZone(id: String) {
        runOnMainThread {
            val entry = activeZones.remove(id)
            if (entry != null) {
                try {
                    windowManager?.removeView(entry.view)
                    Log.d(TAG, "Removed block zone '$id' (remaining active: ${activeZones.size})")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove block zone '$id' from WindowManager", e)
                }
            } else {
                Log.w(TAG, "Block zone '$id' not found in active zones.")
            }
        }
    }

    /**
     * Removes all active block zone overlay views from WindowManager and clears the registry.
     */
    fun removeAllZones() {
        runOnMainThread {
            activeZones.forEach { (id, entry) ->
                try {
                    windowManager?.removeView(entry.view)
                    Log.d(TAG, "Removed block zone '$id'")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove view for zone '$id'", e)
                }
            }
            activeZones.clear()
            Log.d(TAG, "All block zones removed.")
        }
    }

    /**
     * Returns a snapshot list of all currently active block zone entries.
     */
    fun getActiveZones(): List<BlockZoneEntry> = activeZones.values.toList()

    /**
     * Constructs and initiates the persistent foreground notification.
     */
    private fun startForegroundNotification() {
        val notification = buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 (API 34) Special Use foreground service type
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val stopIntent = Intent(this, GhostTouchBlockerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ghost Touch Blocker Active")
            .setContentText("Screen touch blocking overlays are active.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(launchIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop All", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Ghost Touch Blocker Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification channel for Ghost Touch Blocker overlay service"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        lifecycleScope.launch {
            BlockZoneRepository.getInstance(applicationContext).setProtectionActive(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeAllZones()
    }
}
