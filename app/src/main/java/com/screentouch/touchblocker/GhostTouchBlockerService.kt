package com.screentouch.touchblocker

import android.annotation.SuppressLint
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
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Service that manages touch-blocking overlays using [WindowManager].
 *
 * Prevents accidental full-screen blocking by requiring configured zone bounds or explicit
 * user confirmation. Keeps persistent notification, Quick Settings tile, and app UI synchronized.
 */
class GhostTouchBlockerService : LifecycleService() {

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var repository: BlockZoneRepository

    // Active overlay views keyed by zoneId
    private val activeViews = ConcurrentHashMap<String, View>()

    companion object {
        private const val TAG = "GhostTouchBlocker"
        const val NOTIFICATION_CHANNEL_ID = "GHOST_BLOCKER_CHANNEL"
        private const val NOTIFICATION_ID = 1001

        // Intent Actions
        const val ACTION = "ACTION"
        const val ACTION_START = "START"
        const val ACTION_APPLY_ZONE = "APPLY_ZONE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_TOGGLE_PROTECTION = "TOGGLE_PROTECTION"
        const val ACTION_REMOVE_ZONE = "REMOVE_ZONE"

        // Intent Extras
        const val EXTRA_ZONE_ID = "ZONE_ID"
        const val RECT_LEFT = "RECT_LEFT"
        const val RECT_TOP = "RECT_TOP"
        const val RECT_RIGHT = "RECT_RIGHT"
        const val RECT_BOTTOM = "RECT_BOTTOM"
        const val IS_EDIT_MODE = "IS_EDIT_MODE"
        const val EXTRA_CONFIRM_FULLSCREEN = "CONFIRM_FULLSCREEN"

        const val DEFAULT_ZONE_ID = "primary_zone"

        /**
         * Applies or updates a block zone.
         */
        fun applyZone(
            context: Context,
            bounds: Rect,
            isEditMode: Boolean = false,
            zoneId: String = DEFAULT_ZONE_ID
        ) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_APPLY_ZONE)
                putExtra(EXTRA_ZONE_ID, zoneId)
                putExtra(RECT_LEFT, bounds.left)
                putExtra(RECT_TOP, bounds.top)
                putExtra(RECT_RIGHT, bounds.right)
                putExtra(RECT_BOTTOM, bounds.bottom)
                putExtra(IS_EDIT_MODE, isEditMode)
            }
            startServiceCompat(context, intent)
        }

        /**
         * Starts protection using saved zones or explicitly confirmed full screen.
         */
        fun start(context: Context, confirmFullScreen: Boolean = false) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_START)
                putExtra(EXTRA_CONFIRM_FULLSCREEN, confirmFullScreen)
            }
            startServiceCompat(context, intent)
        }

        fun startService(context: Context) = start(context)

        /**
         * Stops protection, removes overlays, persists inactive state, and shuts down service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_STOP_SERVICE)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) = stop(context)

        fun addZone(context: Context, id: String, rect: Rect, isEditMode: Boolean = false) {
            applyZone(context, rect, isEditMode, id)
        }

        fun removeZone(context: Context, id: String) {
            val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_REMOVE_ZONE)
                putExtra(EXTRA_ZONE_ID, id)
            }
            context.startService(intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
        repository = BlockZoneRepository.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.getStringExtra(ACTION) ?: intent.action

        when (action) {
            ACTION_STOP_SERVICE, "com.screentouch.touchblocker.ACTION_STOP" -> {
                shutdownProtection()
            }
            ACTION_TOGGLE_PROTECTION -> {
                lifecycleScope.launch {
                    val isActive = repository.isProtectionActiveFlowValue()
                    if (isActive) {
                        pauseProtectionFromNotification()
                    } else {
                        restoreOrStartProtection(confirmFullScreen = false)
                    }
                }
            }
            ACTION_APPLY_ZONE -> {
                val zoneId = intent.getStringExtra(EXTRA_ZONE_ID) ?: DEFAULT_ZONE_ID
                val left = intent.getIntExtra(RECT_LEFT, 0)
                val top = intent.getIntExtra(RECT_TOP, 0)
                val right = intent.getIntExtra(RECT_RIGHT, 0)
                val bottom = intent.getIntExtra(RECT_BOTTOM, 0)
                val isEditMode = intent.getBooleanExtra(IS_EDIT_MODE, false)

                if (right > left && bottom > top) {
                    val bounds = Rect(left, top, right, bottom)
                    applyBlockingZone(zoneId, bounds, isEditMode)
                } else {
                    Log.w(TAG, "Invalid bounds received for zone $zoneId: [$left, $top, $right, $bottom]")
                }
            }
            ACTION_REMOVE_ZONE -> {
                val zoneId = intent.getStringExtra(EXTRA_ZONE_ID) ?: DEFAULT_ZONE_ID
                removeZone(zoneId)
                if (activeViews.isEmpty()) {
                    shutdownProtection()
                }
            }
            ACTION_START, null -> {
                val confirmFullScreen = intent.getBooleanExtra(EXTRA_CONFIRM_FULLSCREEN, false)
                restoreOrStartProtection(confirmFullScreen)
            }
            else -> {
                restoreOrStartProtection(confirmFullScreen = false)
            }
        }
    }

    /**
     * Attempts to start protection using configured zones.
     * Prevents accidental full-screen blocking if no zone has been configured.
     */
    private fun restoreOrStartProtection(confirmFullScreen: Boolean) {
        lifecycleScope.launch {
            val savedZone = repository.getSavedZone()
            if (savedZone != null) {
                applyBlockingZone(DEFAULT_ZONE_ID, savedZone, isEditMode = false)
                return@launch
            }

            val enabledZones = repository.getEnabledZones()
            if (enabledZones.isNotEmpty()) {
                for (zone in enabledZones) {
                    applyBlockingZone(zone.id, zone.toRect(), isEditMode = false)
                }
                return@launch
            }

            if (confirmFullScreen) {
                Log.w(TAG, "Full screen blocking explicitly confirmed by user.")
                val displayMetrics = resources.displayMetrics
                val fullScreen = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
                applyBlockingZone("fullscreen_confirmed", fullScreen, isEditMode = false)
            } else {
                Log.w(TAG, "No block zone configured. Aborting startup to prevent accidental full-screen lockouts.")
                shutdownProtection()
            }
        }
    }

    /**
     * Adds or updates a touch-blocking overlay.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun applyBlockingZone(
        zoneId: String,
        bounds: Rect,
        isEditMode: Boolean = false
    ) {
        runOnMainThread {
            if (!PermissionUtils.hasOverlayPermission(this)) {
                Log.e(TAG, "Cannot apply blocking zone $zoneId: Overlay permission revoked.")
                shutdownProtection()
                return@runOnMainThread
            }

            // Ensure foreground notification is running and synchronized
            startForegroundNotification(activeZoneCount = activeViews.size + 1)

            val layoutParams = WindowManager.LayoutParams(
                bounds.width(),
                bounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds.left
                y = bounds.top
            }

            // Reddish highlight in edit mode (#44FF0000), Completely transparent during active blocking
            val backgroundColor = if (isEditMode) {
                Color.parseColor("#44FF0000")
            } else {
                Color.TRANSPARENT
            }

            if (activeViews.containsKey(zoneId)) {
                val existingView = activeViews[zoneId]!!
                existingView.setBackgroundColor(backgroundColor)
                try {
                    windowManager.updateViewLayout(existingView, layoutParams)
                    Log.d(TAG, "Updated block zone '$zoneId' at $bounds, editMode=$isEditMode")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update layout for zone '$zoneId'", e)
                }
            } else {
                val overlayView = View(this).apply {
                    setBackgroundColor(backgroundColor)
                    // Consume all touch events
                    setOnTouchListener { _, _ -> true }
                }
                try {
                    windowManager.addView(overlayView, layoutParams)
                    activeViews[zoneId] = overlayView
                    Log.d(TAG, "Added block zone '$zoneId' at $bounds, editMode=$isEditMode")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add block zone '$zoneId'", e)
                }
            }

            // Persist active state in repository
            lifecycleScope.launch {
                repository.setProtectionActive(true)
                if (!isEditMode) {
                    repository.saveZoneBounds(bounds, isEditMode = false)
                }
            }
        }
    }

    /**
     * Removes a specific block zone.
     */
    fun removeZone(zoneId: String) {
        runOnMainThread {
            activeViews[zoneId]?.let { view ->
                try {
                    windowManager.removeView(view)
                    activeViews.remove(zoneId)
                    Log.d(TAG, "Removed zone '$zoneId'")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove zone view '$zoneId'", e)
                }
            }
        }
    }

    /**
     * Removes all active overlays.
     */
    fun removeAllZones() {
        runOnMainThread {
            activeViews.forEach { (id, view) ->
                try {
                    windowManager.removeView(view)
                    Log.d(TAG, "Removed overlay for zone '$id'")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove view for zone '$id'", e)
                }
            }
            activeViews.clear()
        }
    }

    /**
     * Centralized stop path: persists inactive state, removes all overlays,
     * stops the foreground notification, and terminates the service.
     */
    private fun shutdownProtection() {
        runOnMainThread {
            lifecycleScope.launch {
                repository.setProtectionActive(false)
                removeAllZones()
                stopForegroundService()
            }
        }
    }

    /** Pauses overlays but leaves a dismissible notification with a resume action. */
    private fun pauseProtectionFromNotification() {
        runOnMainThread {
            lifecycleScope.launch {
                repository.setProtectionActive(false)
                removeAllZones()
                startForegroundNotification(activeZoneCount = 0, protectionActive = false)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun startForegroundNotification(activeZoneCount: Int, protectionActive: Boolean = true) {
        val notification = buildForegroundNotification(activeZoneCount, protectionActive)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }



    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Ghost Touch Blocker Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification indicating active touch blocker overlay"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    /**
     * Builds the persistent foreground notification with actions to pause and toggle protection.
     */
    private fun buildForegroundNotification(
        activeZoneCount: Int = 1,
        protectionActive: Boolean = true
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        // Action to pause/stop protection
        val stopIntent = Intent(this, GhostTouchBlockerService::class.java).apply {
            putExtra(ACTION, ACTION_STOP_SERVICE)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action to toggle protection on/off
        val toggleIntent = Intent(this, GhostTouchBlockerService::class.java).apply {
            putExtra(ACTION, ACTION_TOGGLE_PROTECTION)
        }
        val togglePendingIntent = PendingIntent.getForegroundService(
            this,
            2,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (!protectionActive) {
            "Protection is off. Tap Turn On to resume your saved zone."
        } else if (activeZoneCount > 1) {
            "Blocking touches in $activeZoneCount active zones."
        } else {
            "Blocking touches in configured zone."
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(if (protectionActive) "Touch Blocker Active" else "Touch Blocker Paused")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(protectionActive)
            .setContentIntent(launchIntent)
            .addAction(
                if (protectionActive) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (protectionActive) "Turn Off Protection" else "Turn On Protection",
                togglePendingIntent
            )
            .apply {
                if (protectionActive) {
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop and Dismiss", stopPendingIntent)
                }
            }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private suspend fun BlockZoneRepository.isProtectionActiveFlowValue(): Boolean {
        return isProtectionActive.first()
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    override fun onDestroy() {
        // Ensure state is marked inactive and overlays are cleared if service is destroyed
        removeAllZones()
        lifecycleScope.launch {
            repository.setProtectionActive(false)
        }
        super.onDestroy()
    }
}
