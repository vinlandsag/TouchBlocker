package com.screentouch.touchblocker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentouch.touchblocker.GhostTouchBlockerService
import com.screentouch.touchblocker.data.TouchBlockerPreferences
import com.screentouch.touchblocker.util.PermissionUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Screen for configuring and positioning the touch blocker zone.
 * Features an interactive canvas with top-left, bottom-right, and center drag handles,
 * accurate DP to screen hardware PX translation, and control panel for service activation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneSelectorScreen(
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preferences = remember { TouchBlockerPreferences(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val isBlockerEnabled by preferences.isBlockerEnabled.collectAsState(initial = false)
    val savedPresetRect by preferences.savedPresetRect.collectAsState(initial = null)
    val savedOpacity by preferences.overlayOpacity.collectAsState(initial = 0.35f)

    // Opacity slider state (0% to 50% visibility)
    var overlayOpacity by remember { mutableFloatStateOf(0.35f) }

    LaunchedEffect(savedOpacity) {
        overlayOpacity = savedOpacity.coerceIn(0f, 0.5f)
    }

    // Interactive Zone Coordinates in Container PX
    var rectLeft by remember { mutableFloatStateOf(100f) }
    var rectTop by remember { mutableFloatStateOf(100f) }
    var rectRight by remember { mutableFloatStateOf(600f) }
    var rectBottom by remember { mutableFloatStateOf(800f) }

    var containerWidthPx by remember { mutableFloatStateOf(1f) }
    var containerHeightPx by remember { mutableFloatStateOf(1f) }
    var isInitialized by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val displayMetrics = context.resources.displayMetrics
    val screenHardwareWidthPx = displayMetrics.widthPixels
    val screenHardwareHeightPx = displayMetrics.heightPixels

    // Scale factors mapping container preview coordinates to actual hardware screen pixels
    val scaleX = if (containerWidthPx > 0f) screenHardwareWidthPx.toFloat() / containerWidthPx else 1f
    val scaleY = if (containerHeightPx > 0f) screenHardwareHeightPx.toFloat() / containerHeightPx else 1f

    // Convert container bounds to hardware screen Rect
    fun getHardwareRect(): Rect {
        val hLeft = (rectLeft * scaleX).roundToInt().coerceIn(0, screenHardwareWidthPx)
        val hTop = (rectTop * scaleY).roundToInt().coerceIn(0, screenHardwareHeightPx)
        val hRight = (rectRight * scaleX).roundToInt().coerceIn(hLeft + 1, screenHardwareWidthPx)
        val hBottom = (rectBottom * scaleY).roundToInt().coerceIn(hTop + 1, screenHardwareHeightPx)
        return Rect(hLeft, hTop, hRight, hBottom)
    }

    // Convert hardware Rect back to container preview coordinates
    fun loadFromHardwareRect(hRect: Rect) {
        if (containerWidthPx > 0f && containerHeightPx > 0f) {
            val invScaleX = containerWidthPx / screenHardwareWidthPx.toFloat()
            val invScaleY = containerHeightPx / screenHardwareHeightPx.toFloat()
            rectLeft = (hRect.left * invScaleX).coerceIn(0f, containerWidthPx)
            rectTop = (hRect.top * invScaleY).coerceIn(0f, containerHeightPx)
            rectRight = (hRect.right * invScaleX).coerceIn(rectLeft + 20f, containerWidthPx)
            rectBottom = (hRect.bottom * invScaleY).coerceIn(rectTop + 20f, containerHeightPx)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Ghost Touch Zone Selector", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (savedPresetRect != null) {
                        OutlinedButton(
                            onClick = {
                                savedPresetRect?.let { loadFromHardwareRect(it) }
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Loaded saved preset zone")
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Load Preset", fontSize = 12.sp)
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
            // Zone Pixel Coordinates HUD
            val hardwareRect = getHardwareRect()
            val widthDp = with(density) { ((rectRight - rectLeft) / density.density).dp }
            val heightDp = with(density) { ((rectBottom - rectTop) / density.density).dp }

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
                            text = "Hardware Screen: ${screenHardwareWidthPx} × ${screenHardwareHeightPx} px",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Active Rect: [L:${hardwareRect.left}, T:${hardwareRect.top}, R:${hardwareRect.right}, B:${hardwareRect.bottom}] px",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "${widthDp.value.roundToInt()}dp × ${heightDp.value.roundToInt()}dp",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Interactive Screen Representation & Resizable Box
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .onSizeChanged { size ->
                        containerWidthPx = size.width.toFloat()
                        containerHeightPx = size.height.toFloat()
                        if (!isInitialized && size.width > 0 && size.height > 0) {
                            // Default box positioned in center-screen
                            val defaultLeft = size.width * 0.15f
                            val defaultTop = size.height * 0.2f
                            val defaultRight = size.width * 0.85f
                            val defaultBottom = size.height * 0.6f
                            rectLeft = defaultLeft
                            rectTop = defaultTop
                            rectRight = defaultRight
                            rectBottom = defaultBottom
                            isInitialized = true
                        }
                    }
            ) {
                val minSizePx = with(density) { 48.dp.toPx() }
                val handleRadiusDp = 14.dp
                val handleSizeDp = handleRadiusDp * 2

                // 1. Canvas overlay drawing grid lines and selection box
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Selection box background with user-configured opacity
                    drawRect(
                        color = Color.Red.copy(alpha = overlayOpacity),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectRight - rectLeft, rectBottom - rectTop)
                    )

                    // Dashed stroke border around selection
                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectRight - rectLeft, rectBottom - rectTop),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 16f), 0f)
                        )
                    )
                }

                // 2. Center Draggable Area (Moves the entire box)
                val boxWidthDp = with(density) { (rectRight - rectLeft).toDp() }
                val boxHeightDp = with(density) { (rectBottom - rectTop).toDp() }
                val boxLeftDp = with(density) { rectLeft.toDp() }
                val boxTopDp = with(density) { rectTop.toDp() }

                Box(
                    modifier = Modifier
                        .offset(x = boxLeftDp, y = boxTopDp)
                        .size(width = boxWidthDp, height = boxHeightDp)
                        .pointerInput(containerWidthPx, containerHeightPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val currentWidth = rectRight - rectLeft
                                val currentHeight = rectBottom - rectTop

                                var newLeft = rectLeft + dragAmount.x
                                var newTop = rectTop + dragAmount.y

                                if (newLeft < 0f) newLeft = 0f
                                if (newLeft + currentWidth > containerWidthPx) {
                                    newLeft = containerWidthPx - currentWidth
                                }

                                if (newTop < 0f) newTop = 0f
                                if (newTop + currentHeight > containerHeightPx) {
                                    newTop = containerHeightPx - currentHeight
                                }

                                rectLeft = newLeft
                                rectTop = newTop
                                rectRight = newLeft + currentWidth
                                rectBottom = newTop + currentHeight
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Touch Block Zone\n(Drag center to move)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // 3. Top-Left Drag Handle (Resizes from Top-Left)
                val handleSizePx = with(density) { handleSizeDp.toPx() }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (rectLeft - handleSizePx / 2f).roundToInt(),
                                (rectTop - handleSizePx / 2f).roundToInt()
                            )
                        }
                        .size(handleSizeDp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, Color.Red, CircleShape)
                        .pointerInput(containerWidthPx, containerHeightPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val newLeft = (rectLeft + dragAmount.x).coerceIn(0f, rectRight - minSizePx)
                                val newTop = (rectTop + dragAmount.y).coerceIn(0f, rectBottom - minSizePx)
                                rectLeft = newLeft
                                rectTop = newTop
                            }
                        }
                )

                // 4. Bottom-Right Drag Handle (Resizes from Bottom-Right)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (rectRight - handleSizePx / 2f).roundToInt(),
                                (rectBottom - handleSizePx / 2f).roundToInt()
                            )
                        }
                        .size(handleSizeDp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, Color.Red, CircleShape)
                        .pointerInput(containerWidthPx, containerHeightPx) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val newRight = (rectRight + dragAmount.x).coerceIn(rectLeft + minSizePx, containerWidthPx)
                                val newBottom = (rectBottom + dragAmount.y).coerceIn(rectTop + minSizePx, containerHeightPx)
                                rectRight = newRight
                                rectBottom = newBottom
                            }
                        }
                )
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Slider: Opacity (0% to 50% visibility)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Overlay Opacity",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(overlayOpacity * 100).roundToInt()}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = overlayOpacity,
                        onValueChange = { overlayOpacity = it },
                        valueRange = 0f..0.50f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Button: Activate Protection
                        Button(
                            onClick = {
                                if (context is Activity && !PermissionUtils.hasOverlayPermission(context)) {
                                    PermissionUtils.requestOverlayPermission(context)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Overlay permission required. Please enable it in Settings.")
                                    }
                                    return@Button
                                }

                                val finalRect = getHardwareRect()

                                val intent = Intent(context, GhostTouchBlockerService::class.java).apply {
                                    action = GhostTouchBlockerService.ACTION_ADD_ZONE
                                    putExtra(GhostTouchBlockerService.EXTRA_ZONE_ID, "zone_custom")
                                    putExtra(GhostTouchBlockerService.EXTRA_ZONE_RECT, finalRect)
                                    putExtra(GhostTouchBlockerService.EXTRA_ZONE_COLOR, AndroidColor.RED)
                                    putExtra(GhostTouchBlockerService.EXTRA_ZONE_ALPHA, overlayOpacity)
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }

                                coroutineScope.launch {
                                    preferences.setBlockerEnabled(true)
                                    snackbarHostState.showSnackbar("Protection activated for zone: ${finalRect.width()}×${finalRect.height()} px")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Activate Protection", fontSize = 13.sp)
                        }

                        // Button: Stop Protection
                        OutlinedButton(
                            onClick = {
                                GhostTouchBlockerService.stop(context)
                                coroutineScope.launch {
                                    preferences.setBlockerEnabled(false)
                                    snackbarHostState.showSnackbar("Protection stopped")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop Protection", fontSize = 13.sp)
                        }
                    }

                    // Button: Save Preset Zone
                    Button(
                        onClick = {
                            val finalRect = getHardwareRect()
                            coroutineScope.launch {
                                val zone = com.screentouch.touchblocker.data.BlockZone.fromRect(
                                    id = "zone_preset_${System.currentTimeMillis()}",
                                    rect = finalRect,
                                    color = AndroidColor.RED,
                                    alpha = overlayOpacity
                                )
                                com.screentouch.touchblocker.data.BlockZoneRepository.getInstance(context).saveZone(zone)
                                preferences.savePresetZone(finalRect, overlayOpacity)
                                snackbarHostState.showSnackbar("Preset zone saved (${finalRect.width()}×${finalRect.height()} px)")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Preset Zone", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
