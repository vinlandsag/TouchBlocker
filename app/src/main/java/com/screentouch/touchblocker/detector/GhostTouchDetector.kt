package com.screentouch.touchblocker.detector

import android.graphics.Rect
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.hypot

sealed interface DetectionState {
    data object Idle : DetectionState
    data class GhostTouchDetected(
        val rect: Rect,
        val confidence: Float,
        val detectedAt: Long = System.currentTimeMillis()
    ) : DetectionState
}

/**
 * Analyzes a rolling 3-second touch buffer for hardware ghost-touch spikes using:
 *  1. Spatial clustering (d ≤ 50 px)
 *  2. Frequency detection (> 10 ACTION_DOWN within 1 s without a smooth move vector)
 *  3. 20 px padded bounding box output
 */
class GhostTouchDetector(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    data class TouchPoint(val x: Float, val y: Float, val action: Int, val timestamp: Long = System.currentTimeMillis())

    private val mutex = Mutex()
    private val buffer = ArrayDeque<TouchPoint>()

    private val _state = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val detectionState: StateFlow<DetectionState> = _state.asStateFlow()

    companion object {
        private const val WINDOW_MS   = 3000L
        private const val SPIKE_MS    = 1000L
        private const val CLUSTER_PX  = 50f
        private const val MIN_DOWNS   = 10
        private const val BOX_PADDING = 20
    }

    fun recordEvent(event: MotionEvent) =
        recordPoint(TouchPoint(event.x, event.y, event.actionMasked))

    fun recordPoint(point: TouchPoint) {
        scope.launch {
            mutex.withLock {
                buffer.addLast(point)
                val cutoff = point.timestamp - WINDOW_MS
                while (buffer.isNotEmpty() && buffer.first().timestamp < cutoff) buffer.removeFirst()
                analyze(buffer.toList())
            }
        }
    }

    private fun analyze(points: List<TouchPoint>) {
        if (points.size < MIN_DOWNS) return
        for (cluster in clusterPoints(points)) {
            val downs = cluster.filter { it.action == MotionEvent.ACTION_DOWN }.sortedBy { it.timestamp }
            if (downs.size <= MIN_DOWNS) continue
            if (maxInWindow(downs, SPIKE_MS) > MIN_DOWNS && !hasSmoothMove(cluster.filter { it.action == MotionEvent.ACTION_MOVE })) {
                val confidence = (0.75f + (maxInWindow(downs, SPIKE_MS) - MIN_DOWNS) * 0.05f).coerceIn(0.80f, 0.99f)
                _state.value = DetectionState.GhostTouchDetected(boundingBox(cluster), confidence)
                return
            }
        }
    }

    private fun clusterPoints(points: List<TouchPoint>): List<List<TouchPoint>> {
        val visited = BooleanArray(points.size)
        val clusters = mutableListOf<List<TouchPoint>>()
        for (i in points.indices) {
            if (visited[i]) continue
            val cluster = mutableListOf<TouchPoint>()
            val queue = ArrayDeque<Int>().also { it.add(i) }
            visited[i] = true
            while (queue.isNotEmpty()) {
                val curr = queue.removeFirst().also { cluster.add(points[it]) }
                for (j in points.indices) {
                    if (!visited[j] && hypot(points[curr].x - points[j].x, points[curr].y - points[j].y) <= CLUSTER_PX) {
                        visited[j] = true; queue.add(j)
                    }
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    private fun maxInWindow(sorted: List<TouchPoint>, windowMs: Long): Int {
        var max = 0; var start = 0
        for (end in sorted.indices) {
            while (sorted[start].timestamp < sorted[end].timestamp - windowMs) start++
            if (end - start + 1 > max) max = end - start + 1
        }
        return max
    }

    private fun hasSmoothMove(moves: List<TouchPoint>): Boolean {
        if (moves.size < 4) return false
        var total = 0f; var consistent = 0
        for (i in 1 until moves.size) {
            val d = hypot(moves[i].x - moves[i-1].x, moves[i].y - moves[i-1].y)
            total += d; if (d > 4f) consistent++
        }
        return total > 40f && consistent >= 3
    }

    private fun boundingBox(cluster: List<TouchPoint>): Rect {
        val minX = cluster.minOf { it.x }.toInt(); val minY = cluster.minOf { it.y }.toInt()
        val maxX = cluster.maxOf { it.x }.toInt(); val maxY = cluster.maxOf { it.y }.toInt()
        return Rect((minX - BOX_PADDING).coerceAtLeast(0), (minY - BOX_PADDING).coerceAtLeast(0), maxX + BOX_PADDING, maxY + BOX_PADDING)
    }

    fun resetState() { _state.value = DetectionState.Idle }
}
