package com.screentouch.touchblocker.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screentouch.touchblocker.GhostTouchBlockerService
import com.screentouch.touchblocker.detector.DetectionState
import com.screentouch.touchblocker.detector.GhostTouchDetector
import com.screentouch.touchblocker.util.PermissionUtils

/**
 * Jetpack Compose Popup Alert for Ghost Touch Detections.
 *
 * Listens to [GhostTouchDetector.detectionState] and renders an animated popup notification:
 * "Ghost touch detected at area [X, Y, W, H]. Tap to block this zone automatically."
 *
 * Tapping automatically activates the Ghost Touch Blocker Service for the flagged region.
 */
@Composable
fun GhostTouchAlertPopup(
    detector: GhostTouchDetector,
    modifier: Modifier = Modifier,
    onZoneBlocked: ((Rect) -> Unit)? = null
) {
    val context = LocalContext.current
    val state by detector.detectionState.collectAsState()

    AnimatedVisibility(
        visible = state is DetectionState.GhostTouchDetected,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        if (state is DetectionState.GhostTouchDetected) {
            val detected = state as DetectionState.GhostTouchDetected
            val rect = detected.rect
            val x = rect.left
            val y = rect.top
            val w = rect.width()
            val h = rect.height()
            val confidencePct = (detected.confidence * 100).toInt()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .clickable {
                        // Check overlay permission
                        if (context is Activity && !PermissionUtils.hasOverlayPermission(context)) {
                            PermissionUtils.requestOverlayPermission(context)
                            return@clickable
                        }

                        // Automatically add zone to GhostTouchBlockerService
                        val zoneId = "ghost_spike_${System.currentTimeMillis()}"
                        GhostTouchBlockerService.addZone(context, zoneId, rect)

                        detector.resetState()
                        onZoneBlocked?.invoke(rect)
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Hardware Ghost Spike!",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "$confidencePct% confidence",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Ghost touch detected at area [$x, $y, $w, $h]. Tap to block this zone automatically.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Bounds: Rect(L:$x, T:$y, R:${rect.right}, B:${rect.bottom})",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    IconButton(
                        onClick = { detector.resetState() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
