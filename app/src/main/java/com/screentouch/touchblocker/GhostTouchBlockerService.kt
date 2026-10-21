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

class GhostTouchBlockerService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: BlockZoneRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeViews = mutableMapOf<String, View>()

    companion object {
        private const val TAG = "GhostTouchBlocker"
        const val NOTIFICATION_CHANNEL_ID = "GHOST_BLOCKER_CHANNEL"
        private const val NOTIFICATION_ID = 1001

        const val ACTION            = "ACTION"
        const val ACTION_START      = "START"
        const val ACTION_APPLY_ZONE = "APPLY_ZONE"
        const val ACTION_STOP       = "STOP_SERVICE"
        const val ACTION_REMOVE_ZONE = "REMOVE_ZONE"
        const val EXTRA_ZONE_ID     = "ZONE_ID"
        const val RECT_LEFT         = "RECT_LEFT"
        const val RECT_TOP          = "RECT_TOP"
        const val RECT_RIGHT        = "RECT_RIGHT"
        const val RECT_BOTTOM       = "RECT_BOTTOM"
        const val IS_EDIT_MODE      = "IS_EDIT_MODE"
        const val DEFAULT_ZONE_ID   = "primary_zone"

        fun applyZone(context: Context, bounds: Rect, isEditMode: Boolean = false, zoneId: String = DEFAULT_ZONE_ID) =
            start(context, Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_APPLY_ZONE)
                putExtra(EXTRA_ZONE_ID, zoneId)
                putExtra(RECT_LEFT, bounds.left); putExtra(RECT_TOP, bounds.top)
                putExtra(RECT_RIGHT, bounds.right); putExtra(RECT_BOTTOM, bounds.bottom)
                putExtra(IS_EDIT_MODE, isEditMode)
            })

        fun start(context: Context, confirmFullScreen: Boolean = false) =
            start(context, Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_START)
                putExtra("CONFIRM_FULLSCREEN", confirmFullScreen)
            })

        fun stop(context: Context) =
            context.startService(Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_STOP)
            })

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = BlockZoneRepository.getInstance(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "Ghost Touch Blocker", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.getStringExtra(ACTION) ?: intent.action) {
            ACTION_STOP, "com.screentouch.touchblocker.ACTION_STOP" -> shutdown()

            ACTION_APPLY_ZONE -> {
                val id = intent.getStringExtra(EXTRA_ZONE_ID) ?: DEFAULT_ZONE_ID
                val l = intent.getIntExtra(RECT_LEFT, 0); val t = intent.getIntExtra(RECT_TOP, 0)
                val r = intent.getIntExtra(RECT_RIGHT, 0); val b = intent.getIntExtra(RECT_BOTTOM, 0)
                if (r > l && b > t) applyZone(id, Rect(l, t, r, b), intent.getBooleanExtra(IS_EDIT_MODE, false))
                else Log.w(TAG, "Invalid bounds for zone $id: [$l,$t,$r,$b]")
            }

            ACTION_REMOVE_ZONE -> {
                removeZone(intent.getStringExtra(EXTRA_ZONE_ID) ?: DEFAULT_ZONE_ID)
                if (activeViews.isEmpty()) shutdown()
            }

            else -> lifecycleScope.launch {
                val confirmFullScreen = intent.getBooleanExtra("CONFIRM_FULLSCREEN", false)
                val saved = repository.getSavedZone()
                when {
                    saved != null -> applyZone(DEFAULT_ZONE_ID, saved, isEditMode = false)
                    confirmFullScreen -> {
                        val dm = resources.displayMetrics
                        applyZone("fullscreen", Rect(0, 0, dm.widthPixels, dm.heightPixels), isEditMode = false)
                    }
                    else -> { Log.w(TAG, "No zone configured; shutting down."); shutdown() }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun applyZone(zoneId: String, bounds: Rect, isEditMode: Boolean = false) {
        runOnMain {
            if (!PermissionUtils.hasOverlayPermission(this)) { shutdown(); return@runOnMain }

            startForeground()

            val lp = WindowManager.LayoutParams(
                bounds.width(), bounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = bounds.left; y = bounds.top }

            val bgColor = if (isEditMode) Color.parseColor("#44FF0000") else Color.TRANSPARENT

            val existing = activeViews[zoneId]
            if (existing != null) {
                existing.setBackgroundColor(bgColor)
                try { windowManager.updateViewLayout(existing, lp) }
                catch (e: Exception) { Log.e(TAG, "updateViewLayout '$zoneId'", e) }
            } else {
                val view = View(this).apply {
                    setBackgroundColor(bgColor)
                    setOnTouchListener { _, _ -> true }
                }
                try { windowManager.addView(view, lp); activeViews[zoneId] = view }
                catch (e: Exception) { Log.e(TAG, "addView '$zoneId'", e) }
            }

            lifecycleScope.launch {
                repository.setProtectionActive(true)
                if (!isEditMode) repository.saveZoneBounds(bounds)
            }
        }
    }

    fun removeZone(zoneId: String) = runOnMain {
        activeViews.remove(zoneId)?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.e(TAG, "removeView '$zoneId'", e) }
        }
    }

    private fun removeAllZones() = runOnMain {
        activeViews.forEach { (id, view) ->
            try { windowManager.removeView(view) } catch (e: Exception) { Log.e(TAG, "removeView '$id'", e) }
        }
        activeViews.clear()
    }

    private fun shutdown() = runOnMain {
        removeAllZones()
        lifecycleScope.launch { repository.setProtectionActive(false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForeground() {
        val launchPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, GhostTouchBlockerService::class.java).apply { putExtra(ACTION, ACTION_STOP) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Touch Blocker Active")
            .setContentText("Blocking touches in configured zone.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(launchPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIFICATION_ID, notification)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun onDestroy() {
        removeAllZones()
        lifecycleScope.launch { repository.setProtectionActive(false) }
        super.onDestroy()
    }
}
