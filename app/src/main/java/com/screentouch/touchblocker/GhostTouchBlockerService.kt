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
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.launch

class GhostTouchBlockerService : LifecycleService() {

    // ── WindowManager & state ────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private lateinit var repository: BlockZoneRepository
    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Per-zone state: physical bounds (always in ROTATION_0 portrait space)
     * and the live View added to WindowManager.
     */
    private data class ZoneState(val physicalBounds: Rect, val editMode: Boolean, val view: View)
    private val zones = mutableMapOf<String, ZoneState>()

    /**
     * Native portrait display dimensions — captured once in onCreate() and
     * used as the fixed reference frame for all rotation math.
     */
    private var nativeWidth  = 0
    private var nativeHeight = 0

    // ── DisplayListener ──────────────────────────────────────────────────────
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onRotationChanged()
        }
    }

    // ── Companion / static API ───────────────────────────────────────────────
    companion object {
        private const val TAG = "GhostTouchBlocker"
        const val NOTIFICATION_CHANNEL_ID = "GHOST_BLOCKER_CHANNEL"
        private const val NOTIFICATION_ID = 1001

        const val ACTION             = "ACTION"
        const val ACTION_START       = "START"
        const val ACTION_APPLY_ZONE  = "APPLY_ZONE"
        const val ACTION_STOP        = "STOP_SERVICE"
        const val ACTION_REMOVE_ZONE = "REMOVE_ZONE"
        const val EXTRA_ZONE_ID      = "ZONE_ID"
        const val RECT_LEFT          = "RECT_LEFT"
        const val RECT_TOP           = "RECT_TOP"
        const val RECT_RIGHT         = "RECT_RIGHT"
        const val RECT_BOTTOM        = "RECT_BOTTOM"
        const val IS_EDIT_MODE       = "IS_EDIT_MODE"
        const val DEFAULT_ZONE_ID    = "primary_zone"

        fun applyZone(
            context: Context,
            bounds: Rect,
            isEditMode: Boolean = false,
            zoneId: String = DEFAULT_ZONE_ID
        ) = launch(context, Intent(context, GhostTouchBlockerService::class.java).apply {
            putExtra(ACTION, ACTION_APPLY_ZONE)
            putExtra(EXTRA_ZONE_ID, zoneId)
            putExtra(RECT_LEFT, bounds.left); putExtra(RECT_TOP, bounds.top)
            putExtra(RECT_RIGHT, bounds.right); putExtra(RECT_BOTTOM, bounds.bottom)
            putExtra(IS_EDIT_MODE, isEditMode)
        })

        fun start(context: Context, confirmFullScreen: Boolean = false) =
            launch(context, Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_START)
                putExtra("CONFIRM_FULLSCREEN", confirmFullScreen)
            })

        fun stop(context: Context) =
            context.startService(Intent(context, GhostTouchBlockerService::class.java).apply {
                putExtra(ACTION, ACTION_STOP)
            })

        private fun launch(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /**
         * Transforms a physical Rect (portrait/ROTATION_0 space) into the logical
         * WindowManager Rect for the given display rotation.
         *
         * The physical coordinates describe a fixed spot on the glass digitizer.
         * When the device rotates, the OS remaps that spot to a different logical
         * position, so we must recompute x/y/w/h accordingly.
         *
         * @param phys    Zone bounds in native portrait pixel space.
         * @param nativeW Native portrait display width  (short edge in ROTATION_0).
         * @param nativeH Native portrait display height (long  edge in ROTATION_0).
         * @param rotation Current Surface rotation constant (ROTATION_0..270) from Display.rotation.
         */
        fun physicalToLogical(phys: Rect, nativeW: Int, nativeH: Int, rotation: Int): Rect =
            when (rotation) {
                Surface.ROTATION_0   -> Rect(
                    phys.left,
                    phys.top,
                    phys.right,
                    phys.bottom
                )
                Surface.ROTATION_90  -> Rect(
                    nativeH - phys.bottom,          // x = H_native − (Y_phys + H_phys)
                    phys.left,                      // y = X_phys
                    nativeH - phys.top,             // right  = x + H_phys
                    phys.right                      // bottom = y + W_phys
                )
                Surface.ROTATION_180 -> Rect(
                    nativeW - phys.right,           // x = W_native − (X_phys + W_phys)
                    nativeH - phys.bottom,          // y = H_native − (Y_phys + H_phys)
                    nativeW - phys.left,
                    nativeH - phys.top
                )
                Surface.ROTATION_270 -> Rect(
                    phys.top,                       // x = Y_phys
                    nativeW - phys.right,           // y = W_native − (X_phys + W_phys)
                    phys.bottom,                    // right  = x + H_phys
                    nativeW - phys.left             // bottom = y + W_phys
                )
                else -> Rect(phys)
            }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    override fun onCreate() {
        super.onCreate()
        windowManager  = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository     = BlockZoneRepository.getInstance(applicationContext)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // Capture native portrait dimensions from the default display.
        // getRealSize() returns the full physical panel size regardless of rotation.
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            // currentWindowMetrics rotates with the device; to get the portrait frame
            // we always take min→W and max→H so ROTATION_0 is always the reference.
            nativeWidth  = minOf(bounds.width(), bounds.height())
            nativeHeight = maxOf(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display?.getRealSize(point)
            nativeWidth  = minOf(point.x, point.y)
            nativeHeight = maxOf(point.x, point.y)
        }
        Log.d(TAG, "Native portrait frame: ${nativeWidth}×${nativeHeight}")

        // Register DisplayListener on main thread so callbacks arrive synchronously.
        displayManager.registerDisplayListener(displayListener, mainHandler)

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

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        removeAllZones()
        lifecycleScope.launch { repository.setProtectionActive(false) }
        super.onDestroy()
    }

    // ── Intent routing ───────────────────────────────────────────────────────

    private fun handleIntent(intent: Intent) {
        when (intent.getStringExtra(ACTION) ?: intent.action) {
            ACTION_STOP, "com.screentouch.touchblocker.ACTION_STOP" -> shutdown()

            ACTION_APPLY_ZONE -> {
                val id = intent.getStringExtra(EXTRA_ZONE_ID) ?: DEFAULT_ZONE_ID
                val l  = intent.getIntExtra(RECT_LEFT, 0);  val t = intent.getIntExtra(RECT_TOP, 0)
                val r  = intent.getIntExtra(RECT_RIGHT, 0); val b = intent.getIntExtra(RECT_BOTTOM, 0)
                val em = intent.getBooleanExtra(IS_EDIT_MODE, false)
                if (r > l && b > t) applyZone(id, Rect(l, t, r, b), em)
                else Log.w(TAG, "Invalid bounds for zone $id: [$l,$t,$r,$b]")
            }

            ACTION_REMOVE_ZONE -> {
                removeZone(intent.getStringExtra(EXTRA_ZONE_ID) ?: DEFAULT_ZONE_ID)
                if (zones.isEmpty()) shutdown()
            }

            else -> lifecycleScope.launch {
                val confirmFullScreen = intent.getBooleanExtra("CONFIRM_FULLSCREEN", false)
                val saved = repository.getSavedZone()
                when {
                    saved != null    -> applyZone(DEFAULT_ZONE_ID, saved, isEditMode = false)
                    confirmFullScreen -> {
                        // Full-screen zone in portrait physical space
                        applyZone("fullscreen", Rect(0, 0, nativeWidth, nativeHeight), isEditMode = false)
                    }
                    else -> { Log.w(TAG, "No zone configured; shutting down."); shutdown() }
                }
            }
        }
    }

    // ── Overlay management ───────────────────────────────────────────────────

    /**
     * Adds or updates a touch-blocking overlay for [zoneId].
     *
     * [physicalBounds] must be in ROTATION_0 portrait pixel space — the same
     * coordinate frame that [ZoneSelectorScreen] captures via DisplayMetrics
     * when the device is in portrait. The service transforms them to the current
     * logical position on every rotation event.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun applyZone(zoneId: String, physicalBounds: Rect, isEditMode: Boolean = false) {
        runOnMain {
            if (!PermissionUtils.hasOverlayPermission(this)) { shutdown(); return@runOnMain }

            startForeground()

            val rotation = currentRotation()
            val logical  = physicalToLogical(physicalBounds, nativeWidth, nativeHeight, rotation)
            val lp       = buildLayoutParams(logical)
            val bgColor  = if (isEditMode) Color.parseColor("#44FF0000") else Color.TRANSPARENT

            val existing = zones[zoneId]
            if (existing != null) {
                existing.view.setBackgroundColor(bgColor)
                try { windowManager.updateViewLayout(existing.view, lp) }
                catch (e: Exception) { Log.e(TAG, "updateViewLayout '$zoneId'", e) }
                zones[zoneId] = existing.copy(physicalBounds = physicalBounds, editMode = isEditMode)
            } else {
                val view = View(this).apply {
                    setBackgroundColor(bgColor)
                    setOnTouchListener { _, _ -> true }
                }
                try {
                    windowManager.addView(view, lp)
                    zones[zoneId] = ZoneState(physicalBounds, isEditMode, view)
                    Log.d(TAG, "Added zone '$zoneId' physical=$physicalBounds logical=$logical rotation=$rotation")
                } catch (e: Exception) { Log.e(TAG, "addView '$zoneId'", e) }
            }

            lifecycleScope.launch {
                repository.setProtectionActive(true)
                if (!isEditMode) repository.saveZoneBounds(physicalBounds)
            }
        }
    }

    fun removeZone(zoneId: String) = runOnMain {
        zones.remove(zoneId)?.let {
            try { windowManager.removeView(it.view) }
            catch (e: Exception) { Log.e(TAG, "removeView '$zoneId'", e) }
        }
    }

    private fun removeAllZones() = runOnMain {
        zones.forEach { (id, state) ->
            try { windowManager.removeView(state.view) }
            catch (e: Exception) { Log.e(TAG, "removeView '$id'", e) }
        }
        zones.clear()
    }

    /**
     * Called by [displayListener] on every display change (rotation, density, etc.).
     * Re-pins all active overlays to their physical hardware positions.
     */
    private fun onRotationChanged() {
        if (!PermissionUtils.hasOverlayPermission(this)) return
        val rotation = currentRotation()
        Log.d(TAG, "Display changed — rotation=$rotation, re-pinning ${zones.size} zone(s)")
        zones.forEach { (id, state) ->
            val logical = physicalToLogical(state.physicalBounds, nativeWidth, nativeHeight, rotation)
            val lp      = buildLayoutParams(logical)
            try { windowManager.updateViewLayout(state.view, lp) }
            catch (e: Exception) { Log.e(TAG, "Rotation update failed for '$id'", e) }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            display?.rotation ?: Surface.ROTATION_0
        else
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation

    private fun buildLayoutParams(logical: Rect) =
        WindowManager.LayoutParams(
            logical.width(), logical.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = logical.left; y = logical.top }

    private fun shutdown() = runOnMain {
        removeAllZones()
        lifecycleScope.launch { repository.setProtectionActive(false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForeground() {
        val launchPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, GhostTouchBlockerService::class.java).apply { putExtra(ACTION, ACTION_STOP) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Touch Blocker Active")
            .setContentText("Blocking touches · pinned to hardware coordinates.")
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
}
