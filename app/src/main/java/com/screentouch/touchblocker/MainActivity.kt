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
    ) { }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.recordEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf("home") }
                Column(Modifier.fillMaxSize()) {
                    GhostTouchAlertPopup(detector = detector)
                    if (screen == "zone_selector") {
                        ZoneSelectorScreen(onNavigateBack = { screen = "home" })
                    } else {
                        TouchBlockerScreen(
                            activity = this@MainActivity,
                            detector = detector,
                            onOpenZoneSelector = { screen = "zone_selector" }
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
    val scope = rememberCoroutineScope()
    val repository = remember { BlockZoneRepository.getInstance(context) }

    val isActive by repository.isProtectionActive.collectAsState(initial = false)
    val startOnBoot by repository.startOnBoot.collectAsState(initial = false)
    val hasZone by repository.hasConfiguredZone.collectAsState(initial = false)
    val savedBounds by repository.savedBoundsFlow.collectAsState(initial = null)

    var hasOverlay by remember { mutableStateOf(PermissionUtils.hasOverlayPermission(context)) }
    var showNoZoneDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasOverlay = PermissionUtils.hasOverlayPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showNoZoneDialog) {
        AlertDialog(
            onDismissRequest = { showNoZoneDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("No Block Zone Configured") },
            text = {
                Text(
                    "To prevent accidental screen lockouts, configure a specific touch block zone first.\n\n" +
                    "Configure a zone now, or explicitly block the entire screen? " +
                    "(An emergency stop remains in your notifications.)"
                )
            },
            confirmButton = {
                Button(onClick = { showNoZoneDialog = false; onOpenZoneSelector() }) { Text("Configure Zone") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showNoZoneDialog = false
                        GhostTouchBlockerService.start(context, confirmFullScreen = true)
                        scope.launch { repository.setProtectionActive(true) }
                    }) { Text("Block Full Screen", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showNoZoneDialog = false }) { Text("Cancel") }
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
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission card
            Card(
                Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                CardDefaults.cardColors(
                    containerColor = if (hasOverlay) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (hasOverlay) "Overlay Permission: Granted" else "Overlay Permission: Required",
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasOverlay) "The app can render the touch-blocking overlay."
                        else "SYSTEM_ALERT_WINDOW is required to block ghost and accidental touches.",
                        fontSize = 13.sp
                    )
                    if (!hasOverlay) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { PermissionUtils.requestOverlayPermission(activity) }) { Text("Grant Permission") }
                    }
                }
            }

            // Blocker toggle card
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Touch Blocker Service", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isActive) "Active (Blocking screen touches)" else "Disabled",
                            fontSize = 13.sp,
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        savedBounds?.let {
                            Text("Zone: [${it.left}, ${it.top}, ${it.right}, ${it.bottom}] px",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Switch(
                        checked = isActive,
                        enabled = hasOverlay,
                        onCheckedChange = { on ->
                            if (on) {
                                if (!hasZone) showNoZoneDialog = true
                                else { GhostTouchBlockerService.start(context); scope.launch { repository.setProtectionActive(true) } }
                            } else {
                                GhostTouchBlockerService.stop(context)
                                scope.launch { repository.setProtectionActive(false) }
                            }
                        }
                    )
                }
            }

            // Boot toggle card
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start on Device Boot", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Restore saved zone automatically after reboot",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = startOnBoot, onCheckedChange = { scope.launch { repository.setStartOnBoot(it) } })
                }
            }

            // Ghost touch info card
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("In-App Ghost Touch Diagnostic", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Analyzes touch patterns while interacting within this app to detect digitizer jitter and water-droplet anomalies. " +
                            "Note: Android security prevents apps from capturing touches outside their own windows.",
                            fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(onClick = onOpenZoneSelector, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Configure Block Zone Coordinates")
            }

            if (BuildConfig.DEBUG) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val now = System.currentTimeMillis()
                            repeat(12) { i ->
                                detector.recordPoint(GhostTouchDetector.TouchPoint(
                                    x = 350f + (-15..15).random(),
                                    y = 650f + (-15..15).random(),
                                    action = android.view.MotionEvent.ACTION_DOWN,
                                    timestamp = now + i * 25L
                                ))
                            }
                        }
                    },
                    Modifier.fillMaxWidth()
                ) { Text("[Debug] Simulate Ghost Touch Spike") }
            }

            if (isActive) {
                Button(
                    onClick = { GhostTouchBlockerService.stop(context); scope.launch { repository.setProtectionActive(false) } },
                    Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Emergency Stop Blocker") }
            }
        }
    }
}
