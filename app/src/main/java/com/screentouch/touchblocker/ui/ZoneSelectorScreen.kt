package com.screentouch.touchblocker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentouch.touchblocker.GhostTouchBlockerService
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneSelectorScreen(
    onNavigateBack: (() -> Unit)? = null,
    onSaveZone: ((Rect, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { BlockZoneRepository.getInstance(context) }
    val snackbar = remember { SnackbarHostState() }

    val savedBounds by repository.savedBoundsFlow.collectAsState(initial = null)
    val isProtectionActive by repository.isProtectionActive.collectAsState(initial = false)

    var leftF   by remember { mutableStateOf(0.1f) }
    var topF    by remember { mutableStateOf(0.38f) }
    var widthF  by remember { mutableStateOf(0.8f) }
    var heightF by remember { mutableStateOf(0.22f) }
    var isEditMode by remember { mutableStateOf(true) }

    val sw = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val sh = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)

    LaunchedEffect(savedBounds) {
        savedBounds?.let { r ->
            leftF   = (r.left.toFloat()  / sw).coerceIn(0f, 0.95f)
            topF    = (r.top.toFloat()   / sh).coerceIn(0f, 0.95f)
            widthF  = (r.width().toFloat()  / sw).coerceIn(0.05f, 1f - leftF)
            heightF = (r.height().toFloat() / sh).coerceIn(0.05f, 1f - topF)
        }
    }

    fun pixelRect() = Rect(
        (leftF * sw).roundToInt().coerceIn(0, sw - 1),
        (topF  * sh).roundToInt().coerceIn(0, sh - 1),
        ((leftF + widthF) * sw).roundToInt().coerceIn((leftF * sw).roundToInt() + 1, sw),
        ((topF + heightF) * sh).roundToInt().coerceIn((topF * sh).roundToInt() + 1, sh)
    )

    fun sendToService(rect: Rect, editMode: Boolean) {
        if (context is Activity && !PermissionUtils.hasOverlayPermission(context)) {
            PermissionUtils.requestOverlayPermission(context)
            scope.launch { snackbar.showSnackbar("Overlay permission required.") }
            return
        }
        val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
            putExtra(GhostTouchBlockerService.ACTION, GhostTouchBlockerService.ACTION_APPLY_ZONE)
            putExtra(GhostTouchBlockerService.EXTRA_ZONE_ID, GhostTouchBlockerService.DEFAULT_ZONE_ID)
            putExtra(GhostTouchBlockerService.RECT_LEFT, rect.left)
            putExtra(GhostTouchBlockerService.RECT_TOP, rect.top)
            putExtra(GhostTouchBlockerService.RECT_RIGHT, rect.right)
            putExtra(GhostTouchBlockerService.RECT_BOTTOM, rect.bottom)
            putExtra(GhostTouchBlockerService.IS_EDIT_MODE, editMode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        onSaveZone?.invoke(rect, editMode)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Configure Block Zone", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    onNavigateBack?.let {
                        IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Pixel coordinates HUD
            val rect = pixelRect()
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Whole display preview", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                        Text("Drag to move · drag handle to resize", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Rect(L:${rect.left}, T:${rect.top}, R:${rect.right}, B:${rect.bottom}) px",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        with(density) { "${rect.width().toDp().value.roundToInt()}dp × ${rect.height().toDp().value.roundToInt()}dp" },
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Full-screen preview canvas
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF222222))
            ) {
                val aw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val ah = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val aspect = sw.toFloat() / sh.toFloat()
                val pw = min(aw, ah * aspect)
                val ph = pw / aspect
                val pl = (aw - pw) / 2f
                val pt = (ah - ph) / 2f
                val minFrac = with(density) { 16.dp.toPx() }
                val handleR = 14.dp

                // Screen frame
                Box(
                    Modifier.offset { IntOffset(pl.roundToInt(), pt.roundToInt()) }
                        .size(with(density) { pw.toDp() }, with(density) { ph.toDp() })
                        .background(Color(0xFF151515))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val g = Color.White.copy(alpha = 0.16f)
                        for (d in 1..2) {
                            drawLine(g, Offset(size.width * d / 3f, 0f), Offset(size.width * d / 3f, size.height), 1.dp.toPx())
                            drawLine(g, Offset(0f, size.height * d / 3f), Offset(size.width, size.height * d / 3f), 1.dp.toPx())
                        }
                    }
                    Text("TOP · STATUS BAR", Modifier.align(Alignment.TopCenter).padding(top = 4.dp), Color.White.copy(0.65f), fontSize = 8.sp)
                    Text("BOTTOM · NAVIGATION BAR", Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), Color.White.copy(0.65f), fontSize = 8.sp)
                }

                // Draggable / resizable zone selector
                Box(
                    Modifier
                        .offset { IntOffset((pl + leftF * pw).roundToInt(), (pt + topF * ph).roundToInt()) }
                        .size(with(density) { (pw * widthF).toDp() }, with(density) { (ph * heightF).toDp() })
                        .background(if (isEditMode) Color.Red.copy(alpha = 0.27f) else Color.Transparent)
                        .border(2.dp, if (isEditMode) Color.Red else Color.Green, RoundedCornerShape(4.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                leftF = (leftF + drag.x / pw).coerceIn(0f, 1f - widthF)
                                topF  = (topF  + drag.y / ph).coerceIn(0f, 1f - heightF)
                            }
                        }
                ) {
                    Text(
                        if (isEditMode) "Editing Zone\n(Drag to move)" else "Blocking Active\n(Transparent)",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                            .background(Color.Black.copy(0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    // Resize handle (bottom-right)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = handleR / 2, y = handleR / 2)
                            .size(handleR * 2)
                            .shadow(4.dp, CircleShape).clip(CircleShape)
                            .background(Color.White)
                            .border(3.dp, if (isEditMode) Color.Red else Color.Green, CircleShape)
                            .pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    widthF  = (widthF  + drag.x / pw).coerceIn(minFrac / sw, 1f - leftF)
                                    heightF = (heightF + drag.y / ph).coerceIn(minFrac / sh, 1f - topF)
                                }
                            }
                    )
                }
            }

            // Control panel
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                    // Protection toggle
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Touch Blocking", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(if (isProtectionActive) "Protection is on" else "Turn protection on or off",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isProtectionActive,
                            enabled = PermissionUtils.hasOverlayPermission(context),
                            onCheckedChange = { on ->
                                if (on) sendToService(pixelRect(), false)
                                else GhostTouchBlockerService.stop(context)
                                scope.launch { repository.setProtectionActive(on) }
                            }
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // Edit mode toggle
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Edit Mode Preview (Red Highlight)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(if (isEditMode) "Semi-transparent red (#44FF0000)" else "Fully transparent (invisible)",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = isEditMode, onCheckedChange = { checked ->
                            isEditMode = checked
                            if (isProtectionActive) sendToService(pixelRect(), checked)
                        })
                    }

                    Spacer(Modifier.height(4.dp))

                    // Apply
                    Button(
                        onClick = {
                            val r = pixelRect()
                            sendToService(r, isEditMode)
                            scope.launch {
                                repository.setProtectionActive(true)
                                snackbar.showSnackbar("Applied: [${r.left}, ${r.top}, ${r.right}, ${r.bottom}] px")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Apply Protection", fontSize = 14.sp)
                    }

                    // Save & hide highlight
                    Button(
                        onClick = {
                            isEditMode = false
                            val r = pixelRect()
                            sendToService(r, editMode = false)
                            scope.launch {
                                repository.saveZoneBounds(r)
                                repository.setProtectionActive(true)
                                snackbar.showSnackbar("Saved! Overlay is now 100% transparent.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save & Hide Highlight (Invisible Mode)", fontSize = 14.sp)
                    }

                    // Stop
                    if (isProtectionActive) {
                        OutlinedButton(
                            onClick = {
                                GhostTouchBlockerService.stop(context)
                                scope.launch {
                                    repository.setProtectionActive(false)
                                    snackbar.showSnackbar("Touch protection stopped.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop Protection", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
