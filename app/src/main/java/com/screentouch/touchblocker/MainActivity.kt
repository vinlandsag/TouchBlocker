package com.screentouch.touchblocker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.screentouch.touchblocker.data.TouchBlockerPreferences
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.launch

import android.view.MotionEvent
import com.screentouch.touchblocker.detector.GhostTouchDetector
import com.screentouch.touchblocker.ui.GhostTouchAlertPopup
import com.screentouch.touchblocker.ui.ZoneSelectorScreen

class MainActivity : ComponentActivity() {

    val detector = GhostTouchDetector()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission result handled */ }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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
                    // Popup alert displayed when ghost touch is detected
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
    val preferences = remember { TouchBlockerPreferences(context) }

    val isBlockerActive by preferences.isBlockerEnabled.collectAsState(initial = false)
    val startOnBoot by preferences.startOnBoot.collectAsState(initial = false)

    var hasOverlayPermission by remember {
        mutableStateOf(PermissionUtils.hasOverlayPermission(context))
    }

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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Ghost Touch Blocker", fontWeight = FontWeight.Bold) },
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Card
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
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hasOverlayPermission)
                            "The app has permission to display floating blocker overlays."
                        else
                            "Overlay permission is mandatory to block ghost and accidental touches.",
                        fontSize = 14.sp
                    )
                    if (!hasOverlayPermission) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                PermissionUtils.requestOverlayPermission(activity)
                            }
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            // Blocker Switch Card
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
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isBlockerActive,
                        enabled = hasOverlayPermission,
                        onCheckedChange = { enable ->
                            if (enable) {
                                GhostTouchBlockerService.startService(context)
                            } else {
                                GhostTouchBlockerService.stopService(context)
                            }
                        }
                    )
                }
            }

            // Start on Boot Setting Card
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
                            text = "Automatically resume blocker after system reboot",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = startOnBoot,
                        onCheckedChange = { enable ->
                            coroutineScope.launch {
                                preferences.setStartOnBoot(enable)
                            }
                        }
                    )
                }
            }

            // Configure Custom Block Zone Button
            Button(
                onClick = onOpenZoneSelector,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Configure Custom Block Zone")
            }

            // Simulate Ghost Touch Spike (Testing & Demo)
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
                Text("Simulate Ghost Touch Spike (Test Engine)")
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isBlockerActive) {
                OutlinedButton(
                    onClick = { GhostTouchBlockerService.stopService(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Emergency Stop Blocker")
                }
            }
        }
    }
}
