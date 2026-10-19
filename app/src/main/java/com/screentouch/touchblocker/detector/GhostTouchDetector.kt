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
import kotlin.math.sqrt

/**
 * Detection states emitted by [GhostTouchDetector].
 */
sealed interface DetectionState {
    data object Idle : DetectionState
    data class GhostTouchDetected(
        val rect: Rect,
        val confidence: Float,
        val detectedAt: Long = System.currentTimeMillis()
    ) : DetectionState
}

/**
 * Automated ghost touch analysis engine.
 *
 * Maintains a rolling 3-second buffer of touch events and analyzes them using:
 * 1. Spatial clustering with threshold d <= 50px.
 * 2. Spike frequency detection: > 10 ACTION_DOWN events within 1 second without smooth ACTION_MOVE vector.
 * 3. Bounding box computation with a 20px safety padding.
 */
class GhostTouchDetector(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    data class TouchPoint(
        val x: Float,
        val y: Float,
        val action: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val mutex = Mutex()
    private val eventBuffer = ArrayDeque<TouchPoint>()

    private val _detectionState = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val detectionState: StateFlow<DetectionState> = _detectionState.asStateFlow()

    companion object {
        private const val ROLLING_WINDOW_MS = 3000L // 3-second rolling window
        private const val FREQUENCY_WINDOW_MS = 1000L // 1-second spike window
        private const val MAX_CLUSTER_DISTANCE = 50f // 50px spatial radius
        private const val MIN_SPIKE_DOWNS = 10 // > 10 ACTION_DOWN events
        private const val BOUNDING_BOX_PADDING = 20 // 20px margin around cluster
    }

    /**
     * Records a raw Android [MotionEvent] into the rolling buffer and triggers analysis.
     */
    fun recordEvent(event: MotionEvent) {
        val action = event.actionMasked
        val x = event.x
        val y = event.y
        val timestamp = System.currentTimeMillis()

        recordPoint(TouchPoint(x, y, action, timestamp))
    }

    /**
     * Records a raw touch point into the rolling buffer.
     */
    fun recordPoint(point: TouchPoint) {
        scope.launch {
            mutex.withLock {
                val now = point.timestamp
                eventBuffer.addLast(point)

                // 1. Maintain rolling buffer over 3-second window
                val cutoff = now - ROLLING_WINDOW_MS
                while (eventBuffer.isNotEmpty() && eventBuffer.first().timestamp < cutoff) {
                    eventBuffer.removeFirst()
                }

                // Run analysis algorithm on current buffer snapshot
                analyzeBuffer(eventBuffer.toList())
            }
        }
    }

    /**
     * Core Analysis Algorithm:
     * - Groups events into spatial clusters where distance d <= 50px.
     * - Detects frequency spikes (> 10 ACTION_DOWN within 1 second without smooth move vector).
     * - Computes 20px padded bounding box and emits GhostTouchDetected state.
     */
    private fun analyzeBuffer(points: List<TouchPoint>) {
        if (points.size < MIN_SPIKE_DOWNS) {
            return
        }

        // Group points into spatial clusters where d <= 50px
        val clusters = clusterPoints(points, MAX_CLUSTER_DISTANCE)

        for (cluster in clusters) {
            // Filter ACTION_DOWN events in this cluster
            val downEvents = cluster
                .filter { it.action == MotionEvent.ACTION_DOWN }
                .sortedBy { it.timestamp }

            if (downEvents.size <= MIN_SPIKE_DOWNS) continue

            // Check if > 10 ACTION_DOWN events occurred within any 1-second sliding window
            val maxDownsInOneSecond = countMaxEventsInWindow(downEvents, FREQUENCY_WINDOW_MS)

            if (maxDownsInOneSecond > MIN_SPIKE_DOWNS) {
                // Check whether there is a corresponding smooth ACTION_MOVE vector
                val moveEvents = cluster.filter { it.action == MotionEvent.ACTION_MOVE }
                val hasSmoothMoveVector = hasSmoothMoveVector(moveEvents)

                if (!hasSmoothMoveVector) {
                    // Flag as a Hardware Ghost Spike!
                    val boundingBox = calculateBoundingBox(cluster, BOUNDING_BOX_PADDING)

                    // Calculate confidence based on spike frequency (11 downs = 0.82 .. 15+ downs = 0.99)
                    val confidence = (0.75f + (maxDownsInOneSecond - MIN_SPIKE_DOWNS) * 0.05f).coerceIn(0.80f, 0.99f)

                    _detectionState.value = DetectionState.GhostTouchDetected(
                        rect = boundingBox,
                        confidence = confidence
                    )
                    return
                }
            }
        }
    }

    /**
     * Groups points into clusters using spatial proximity:
     * Two points belong to the same cluster if distance <= [maxDistance].
     */
    private fun clusterPoints(points: List<TouchPoint>, maxDistance: Float): List<List<TouchPoint>> {
        val visited = BooleanArray(points.size)
        val clusters = mutableListOf<List<TouchPoint>>()

        for (i in points.indices) {
            if (visited[i]) continue
            val cluster = mutableListOf<TouchPoint>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            visited[i] = true

            while (queue.isNotEmpty()) {
                val currIdx = queue.removeFirst()
                val curr = points[currIdx]
                cluster.add(curr)

                for (j in points.indices) {
                    if (!visited[j]) {
                        val other = points[j]
                        val dist = hypot(curr.x - other.x, curr.y - other.y)
                        if (dist <= maxDistance) {
                            visited[j] = true
                            queue.add(j)
                        }
                    }
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    /**
     * Counts the maximum number of events occurring inside any sliding [windowMs] interval.
     */
    private fun countMaxEventsInWindow(sortedEvents: List<TouchPoint>, windowMs: Long): Int {
        var maxCount = 0
        var windowStart = 0

        for (windowEnd in sortedEvents.indices) {
            val endTime = sortedEvents[windowEnd].timestamp
            while (sortedEvents[windowStart].timestamp < endTime - windowMs) {
                windowStart++
            }
            val currentCount = windowEnd - windowStart + 1
            if (currentCount > maxCount) {
                maxCount = currentCount
            }
        }
        return maxCount
    }

    /**
     * Evaluates whether a set of ACTION_MOVE events forms a coherent, smooth human drag vector.
     * True if moves show progressive displacement and consistent direction;
     * False if moves are non-existent, static jitter, or erratic hardware noise.
     */
    private fun hasSmoothMoveVector(moveEvents: List<TouchPoint>): Boolean {
        if (moveEvents.size < 4) return false

        var totalDisplacement = 0f
        var directionalConsistency = 0

        for (i in 1 until moveEvents.size) {
            val p0 = moveEvents[i - 1]
            val p1 = moveEvents[i]
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val stepDist = hypot(dx, dy)
            totalDisplacement += stepDist

            if (stepDist > 4f) {
                directionalConsistency++
            }
        }

        // A smooth human drag has noticeable cumulative displacement and progressive trajectory
        return totalDisplacement > 40f && directionalConsistency >= 3
    }

    /**
     * Calculates the bounding box around a cluster with [padding] margin.
     */
    private fun calculateBoundingBox(cluster: List<TouchPoint>, padding: Int): Rect {
        val minX = cluster.minOf { it.x }.toInt()
        val minY = cluster.minOf { it.y }.toInt()
        val maxX = cluster.maxOf { it.x }.toInt()
        val maxY = cluster.maxOf { it.y }.toInt()

        return Rect(
            (minX - padding).coerceAtLeast(0),
            (minY - padding).coerceAtLeast(0),
            maxX + padding,
            maxY + padding
        )
    }

    /**
     * Resets the detection state back to [DetectionState.Idle].
     */
    fun resetState() {
        _detectionState.value = DetectionState.Idle
    }

    /**
     * Clears all recorded touch events from the rolling buffer.
     */
    fun clearBuffer() {
        scope.launch {
            mutex.withLock {
                eventBuffer.clear()
            }
        }
    }
}
