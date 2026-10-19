package com.screentouch.touchblocker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.detector.GhostTouchDetector
import com.screentouch.touchblocker.ui.GhostTouchAlertPopup
import com.screentouch.touchblocker.ui.ZoneSelectorScreen
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    val detector = GhostTouchDetector()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission result handled */ }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Stream in-app motion events to heuristic detector
        detector.recordEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS permission for Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                var currentScreen by remember { mutableStateOf("home") }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Popup alert displayed when in-app ghost touch anomaly is detected
                    GhostTouchAlertPopup(detector = detector)

                    if (currentScreen == "zone_selector") {
                        ZoneSelectorScreen(
                            onNavigateBack = { currentScreen = "home" }
                        )
                    } else {
                        TouchBlockerScreen(
                            activity = this@MainActivity,
                            detector = detector,
                            onOpenZoneSelector = { currentScreen = "zone_selector" }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchBlockerScreen(
    activity: MainActivity,
    detector: GhostTouchDetector,
    onOpenZoneSelector: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { BlockZoneRepository.getInstance(context) }

    val isBlockerActive by repository.isProtectionActive.collectAsState(initial = false)
    val startOnBoot by repository.startOnBoot.collectAsState(initial = false)
    val hasConfiguredZone by repository.hasConfiguredZone.collectAsState(initial = false)
    val savedBounds by repository.savedBoundsFlow.collectAsState(initial = null)

    var hasOverlayPermission by remember {
        mutableStateOf(PermissionUtils.hasOverlayPermission(context))
    }

    var showNoZoneDialog by remember { mutableStateOf(false) }

    // Refresh overlay permission status when resuming the activity
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = PermissionUtils.hasOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Confirmation dialog when attempting to activate protection with no zone configured
    if (showNoZoneDialog) {
        AlertDialog(
            onDismissRequest = { showNoZoneDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("No Block Zone Configured") },
            text = {
                Text(
                    "To prevent accidental screen lockouts, you should configure a specific touch block zone first.\n\n" +
                            "Would you like to configure a zone now, or explicitly block the entire screen? (An emergency stop action remains accessible in your notifications)."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNoZoneDialog = false
                        onOpenZoneSelector()
                    }
                ) {
                    Text("Configure Zone")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showNoZoneDialog = false
                            // User explicitly confirmed full-screen touch blocking
                            GhostTouchBlockerService.start(context, confirmFullScreen = true)
                            coroutineScope.launch { repository.setProtectionActive(true) }
                        }
                    ) {
                        Text("Block Full Screen", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showNoZoneDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Touch Blocker", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasOverlayPermission)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (hasOverlayPermission) "Overlay Permission: Granted" else "Overlay Permission: Required",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (hasOverlayPermission)
                            "The app has permission to render the touch-blocking overlay."
                        else
                            "Overlay permission (SYSTEM_ALERT_WINDOW) is mandatory to block ghost and accidental touches.",
                        fontSize = 13.sp
                    )
                    if (!hasOverlayPermission) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = { PermissionUtils.requestOverlayPermission(activity) }) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            // Blocker Switch Card (Single Source of Truth)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Touch Blocker Service",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isBlockerActive) "Active (Blocking screen touches)" else "Disabled",
                            fontSize = 13.sp,
                            color = if (isBlockerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (savedBounds != null) {
                            Text(
                                text = "Zone: [${savedBounds!!.left}, ${savedBounds!!.top}, ${savedBounds!!.right}, ${savedBounds!!.bottom}] px",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Switch(
                        checked = isBlockerActive,
                        enabled = hasOverlayPermission,
                        onCheckedChange = { enable ->
                            if (enable) {
                                if (!hasConfiguredZone) {
                                    // Prevent accidental full screen blocking
                                    showNoZoneDialog = true
                                } else {
                                    GhostTouchBlockerService.start(context)
                                    coroutineScope.launch { repository.setProtectionActive(true) }
                                }
                            } else {
                                GhostTouchBlockerService.stop(context)
                                coroutineScope.launch { repository.setProtectionActive(false) }
                            }
                        }
                    )
                }
            }

            // Start on Device Boot Setting Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Start on Device Boot",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Restore saved protection zones automatically after system reboot",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = startOnBoot,
                        onCheckedChange = { enable ->
                            coroutineScope.launch {
                                repository.setStartOnBoot(enable)
                            }
                        }
                    )
                }
            }

            // In-App Touch Heuristic Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "In-App Ghost Touch Diagnostic",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Analyzes touch input patterns while interacting within this app to detect hardware digitizer jitter and water-droplet anomalies. Touch monitoring is an in-app heuristic; Android security prevents apps from capturing touches globally outside their own windows.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Configure Custom Block Zone Button
            Button(
                onClick = onOpenZoneSelector,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Configure Block Zone Coordinates")
            }

            // DEBUG ONLY: Ghost Touch Simulation Button
            if (BuildConfig.DEBUG) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val baseX = 350f
                            val baseY = 650f
                            val now = System.currentTimeMillis()
                            // Generates 12 rapid ACTION_DOWN pulses within 40px radius over 300ms
                            for (i in 0..11) {
                                val jitterX = baseX + ((-15..15).random())
                                val jitterY = baseY + ((-15..15).random())
                                detector.recordPoint(
                                    GhostTouchDetector.TouchPoint(
                                        x = jitterX,
                                        y = jitterY,
                                        action = MotionEvent.ACTION_DOWN,
                                        timestamp = now + (i * 25L)
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("[Debug Build] Simulate Ghost Touch Spike")
                }
            }

            // Emergency Stop Blocker
            if (isBlockerActive) {
                Button(
                    onClick = {
                        GhostTouchBlockerService.stop(context)
                        coroutineScope.launch { repository.setProtectionActive(false) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Emergency Stop Blocker")
                }
            }
        }
    }
}
