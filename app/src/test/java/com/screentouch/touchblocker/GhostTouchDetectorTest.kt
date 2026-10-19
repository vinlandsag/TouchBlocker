package com.screentouch.touchblocker

import android.view.MotionEvent
import com.screentouch.touchblocker.detector.DetectionState
import com.screentouch.touchblocker.detector.GhostTouchDetector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GhostTouchDetectorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testGhostTouchDetectedOnRapidDownSpikes() = testScope.runTest {
        val detector = GhostTouchDetector(scope = this)
        val now = 10000L

        // Feed 12 rapid ACTION_DOWN events within a 20px area in 200ms without smooth moves
        for (i in 0..11) {
            detector.recordPoint(
                GhostTouchDetector.TouchPoint(
                    x = 100f + (i % 3) * 2f,
                    y = 200f + (i % 2) * 2f,
                    action = MotionEvent.ACTION_DOWN,
                    timestamp = now + (i * 15L)
                )
            )
        }

        advanceUntilIdle()

        val state = detector.detectionState.value
        assertTrue("Detector should emit GhostTouchDetected on > 10 downs in 1s", state is DetectionState.GhostTouchDetected)

        if (state is DetectionState.GhostTouchDetected) {
            // Verify 20px padding around cluster
            assertTrue("Rect left should include padding", state.rect.left <= 80)
            assertTrue("Rect top should include padding", state.rect.top <= 180)
            assertTrue("Rect right should include padding", state.rect.right >= 120)
            assertTrue("Rect bottom should include padding", state.rect.bottom >= 220)
            assertTrue("Confidence should be above 0.8", state.confidence >= 0.80f)
        }
    }

    @Test
    fun testSmoothHumanDragDoesNotTriggerGhostSpike() = testScope.runTest {
        val detector = GhostTouchDetector(scope = this)
        val now = 10000L

        // Record 1 down and progressive move events across screen
        detector.recordPoint(GhostTouchDetector.TouchPoint(x = 100f, y = 100f, action = MotionEvent.ACTION_DOWN, timestamp = now))

        for (i in 1..8) {
            detector.recordPoint(
                GhostTouchDetector.TouchPoint(
                    x = 100f + i * 20f, // progressive smooth displacement
                    y = 100f + i * 15f,
                    action = MotionEvent.ACTION_MOVE,
                    timestamp = now + i * 30L
                )
            )
        }

        advanceUntilIdle()

        val state = detector.detectionState.value
        assertEquals("Smooth drag gesture should remain in Idle state", DetectionState.Idle, state)
    }

    @Test
    fun testResetStateReturnsToIdle() = testScope.runTest {
        val detector = GhostTouchDetector(scope = this)
        val now = 10000L

        for (i in 0..11) {
            detector.recordPoint(
                GhostTouchDetector.TouchPoint(
                    x = 100f,
                    y = 200f,
                    action = MotionEvent.ACTION_DOWN,
                    timestamp = now + (i * 10L)
                )
            )
        }

        advanceUntilIdle()
        assertTrue(detector.detectionState.value is DetectionState.GhostTouchDetected)

        detector.resetState()
        assertEquals(DetectionState.Idle, detector.detectionState.value)
    }
}
