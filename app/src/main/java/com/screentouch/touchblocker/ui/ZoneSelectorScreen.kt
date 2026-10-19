package com.screentouch.touchblocker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentouch.touchblocker.GhostTouchBlockerService
import com.screentouch.touchblocker.data.BlockZoneRepository
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive Screen for configuring touch blocking regions.
 *
 * Provides a resizable and draggable rectangle on screen, converting Compose DP
 * dimensions to raw screen hardware pixels (px) via [LocalDensity] to eliminate
 * coordinate mismatch bugs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneSelectorScreen(
    onNavigateBack: (() -> Unit)? = null,
    onSaveZone: ((Rect, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { BlockZoneRepository.getInstance(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val savedBounds by repository.savedBoundsFlow.collectAsState(initial = null)
    val isProtectionActive by repository.isProtectionActive.collectAsState(initial = false)

    // The preview is a scaled map of the entire display. Fractions keep the selected
    // region stable when the editor's controls or available layout size change.
    var leftFraction by remember { mutableStateOf(0.1f) }
    var topFraction by remember { mutableStateOf(0.38f) }
    var widthFraction by remember { mutableStateOf(0.8f) }
    var heightFraction by remember { mutableStateOf(0.22f) }

    // Toggle: Show red highlight during setup (#44FF0000), or test invisible mode
    var isEditMode by remember { mutableStateOf(true) }

    val screenWidthPx = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val screenHeightPx = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)

    // Load saved bounds on first launch if present
    LaunchedEffect(savedBounds, screenWidthPx, screenHeightPx) {
        savedBounds?.let { rect ->
            leftFraction = (rect.left.toFloat() / screenWidthPx).coerceIn(0f, 0.95f)
            topFraction = (rect.top.toFloat() / screenHeightPx).coerceIn(0f, 0.95f)
            widthFraction = (rect.width().toFloat() / screenWidthPx).coerceIn(0.05f, 1f - leftFraction)
            heightFraction = (rect.height().toFloat() / screenHeightPx).coerceIn(0.05f, 1f - topFraction)
        }
    }

    // Function to calculate exact screen hardware pixel Rect
    fun calculateScreenPixelRect(): Rect {
        val leftPx = (leftFraction * screenWidthPx).roundToInt().coerceIn(0, screenWidthPx - 1)
        val topPx = (topFraction * screenHeightPx).roundToInt().coerceIn(0, screenHeightPx - 1)
        val rightPx = ((leftFraction + widthFraction) * screenWidthPx).roundToInt().coerceIn(leftPx + 1, screenWidthPx)
        val bottomPx = ((topFraction + heightFraction) * screenHeightPx).roundToInt().coerceIn(topPx + 1, screenHeightPx)
        return Rect(leftPx, topPx, rightPx, bottomPx)
    }

    fun sendZoneToService(rectPx: Rect, editMode: Boolean) {
        if (context is Activity && !PermissionUtils.hasOverlayPermission(context)) {
            PermissionUtils.requestOverlayPermission(context)
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Overlay permission is required to activate touch blocking.")
            }
            return
        }

        val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
            putExtra(GhostTouchBlockerService.ACTION, GhostTouchBlockerService.ACTION_APPLY_ZONE)
            putExtra(GhostTouchBlockerService.EXTRA_ZONE_ID, GhostTouchBlockerService.DEFAULT_ZONE_ID)
            putExtra(GhostTouchBlockerService.RECT_LEFT, rectPx.left)
            putExtra(GhostTouchBlockerService.RECT_TOP, rectPx.top)
            putExtra(GhostTouchBlockerService.RECT_RIGHT, rectPx.right)
            putExtra(GhostTouchBlockerService.RECT_BOTTOM, rectPx.bottom)
            putExtra(GhostTouchBlockerService.IS_EDIT_MODE, editMode)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        onSaveZone?.invoke(rectPx, editMode)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Configure Block Zone", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Real-Time Hardware Pixels HUD
            val currentRectPx = calculateScreenPixelRect()
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Whole display preview",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Drag the box to move it; drag the round handle to resize",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Raw Pixel Bounds (WindowManager):",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Rect(L:${currentRectPx.left}, T:${currentRectPx.top}, R:${currentRectPx.right}, B:${currentRectPx.bottom}) px",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = with(density) {
                            "${currentRectPx.width().toDp().value.roundToInt()}dp × ${currentRectPx.height().toDp().value.roundToInt()}dp"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // The preview maps edge-to-edge to the device display; its top and bottom
            // correspond to the physical screen edges, despite the editor controls.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF222222))
            ) {
                val availableWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val availableHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val screenAspectRatio = screenWidthPx.toFloat() / screenHeightPx.toFloat()
                val previewWidthPx = min(availableWidthPx, availableHeightPx * screenAspectRatio)
                val previewHeightPx = previewWidthPx / screenAspectRatio
                val previewLeftPx = (availableWidthPx - previewWidthPx) / 2f
                val previewTopPx = (availableHeightPx - previewHeightPx) / 2f
                val minSizeDp = 16.dp
                val handleRadiusDp = 14.dp
                val boxWidth = with(density) { (previewWidthPx * widthFraction).toDp() }
                val boxHeight = with(density) { (previewHeightPx * heightFraction).toDp() }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(previewLeftPx.roundToInt(), previewTopPx.roundToInt())
                        }
                        .size(
                            with(density) { previewWidthPx.toDp() },
                            with(density) { previewHeightPx.toDp() }
                        )
                        .background(Color(0xFF151515))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val guideColor = Color.White.copy(alpha = 0.16f)
                        for (division in 1..2) {
                            val x = size.width * division / 3f
                            val y = size.height * division / 3f
                            drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                            drawLine(guideColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                    }
                    Text(
                        text = "TOP · STATUS BAR",
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 8.sp
                    )
                    Text(
                        text = "BOTTOM · NAVIGATION BAR",
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 8.sp
                    )
                }

                // Interactive Selector Box (Draggable & Resizable)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (previewLeftPx + leftFraction * previewWidthPx).roundToInt(),
                                (previewTopPx + topFraction * previewHeightPx).roundToInt()
                            )
                        }
                        .size(boxWidth, boxHeight)
                        .background(
                            if (isEditMode) Color.Red.copy(alpha = 0.27f) // #44FF0000 preview
                            else Color.Transparent
                        )
                        .border(
                            width = 2.dp,
                            color = if (isEditMode) Color.Red else Color.Green,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .pointerInput(Unit) {
                            // Dragging moves the zone as a fraction of the full screen map.
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                leftFraction = (leftFraction + dragAmount.x / previewWidthPx)
                                    .coerceIn(0f, 1f - widthFraction)
                                topFraction = (topFraction + dragAmount.y / previewHeightPx)
                                    .coerceIn(0f, 1f - heightFraction)
                            }
                        }
                ) {
                    Text(
                        text = if (isEditMode) "Editing Zone\n(Drag center to move)" else "Blocking Active\n(Transparent)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )

                    // Bottom-Right Drag Handle (Resizes Width & Height)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = handleRadiusDp / 2, y = handleRadiusDp / 2)
                            .size(handleRadiusDp * 2)
                            .shadow(4.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(3.dp, if (isEditMode) Color.Red else Color.Green, CircleShape)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    with(density) {
                                        // Fractions are relative to the real display, so use
                                        // display dimensions here rather than the scaled preview.
                                        val minWidthFraction = minSizeDp.toPx() / screenWidthPx
                                        val minHeightFraction = minSizeDp.toPx() / screenHeightPx
                                        widthFraction = (widthFraction + dragAmount.x / previewWidthPx)
                                            .coerceIn(minWidthFraction, 1f - leftFraction)
                                        heightFraction = (heightFraction + dragAmount.y / previewHeightPx)
                                            .coerceIn(minHeightFraction, 1f - topFraction)
                                    }
                                }
                            }
                    )
                }
            }

            // Bottom UI Control Panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Touch Blocking", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                if (isProtectionActive) "Protection is on for this zone"
                                else "Turn protection on or off",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isProtectionActive,
                            enabled = PermissionUtils.hasOverlayPermission(context),
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val rect = calculateScreenPixelRect()
                                    sendZoneToService(rect, editMode = false)
                                    coroutineScope.launch { repository.setProtectionActive(true) }
                                } else {
                                    GhostTouchBlockerService.stop(context)
                                    coroutineScope.launch { repository.setProtectionActive(false) }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Switch Control: "Edit Mode Preview (Red Highlight)"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Edit Mode Preview (Red Highlight)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (isEditMode) "Shows #44FF0000 semi-transparent red overlay"
                                else "Overlay is completely transparent (Color.TRANSPARENT)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isEditMode,
                            onCheckedChange = { checked ->
                                isEditMode = checked
                                // If protection is active, update the service overlay appearance immediately
                                if (isProtectionActive) {
                                    sendZoneToService(calculateScreenPixelRect(), editMode = checked)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Button: "Apply Protection"
                    Button(
                        onClick = {
                            val rectPx = calculateScreenPixelRect()
                            sendZoneToService(rectPx, isEditMode)
                            coroutineScope.launch {
                                repository.setProtectionActive(true)
                                snackbarHostState.showSnackbar(
                                    "Protection applied: [${rectPx.left}, ${rectPx.top}, ${rectPx.right}, ${rectPx.bottom}] px (Edit Mode: $isEditMode)"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Apply Protection", fontSize = 14.sp)
                    }

                    // Button: "Save & Hide Highlight"
                    Button(
                        onClick = {
                            isEditMode = false
                            val rectPx = calculateScreenPixelRect()
                            // Send intent to service with isEditMode = false (completely transparent overlay)
                            sendZoneToService(rectPx, editMode = false)
                            coroutineScope.launch {
                                repository.saveZoneBounds(rectPx, isEditMode = false)
                                repository.setProtectionActive(true)
                                snackbarHostState.showSnackbar("Saved! Overlay is now 100% transparent while blocking all touches.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save & Hide Highlight (Invisible Mode)", fontSize = 14.sp)
                    }

                    // Button: "Stop Protection"
                    if (isProtectionActive) {
                        OutlinedButton(
                            onClick = {
                                GhostTouchBlockerService.stop(context)
                                coroutineScope.launch {
                                    repository.setProtectionActive(false)
                                    snackbarHostState.showSnackbar("Touch protection stopped.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop Protection", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
