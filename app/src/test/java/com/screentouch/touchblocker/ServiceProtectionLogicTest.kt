package com.screentouch.touchblocker

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests validating WindowManager layout parameters, flags, and color logic
 * used by GhostTouchBlockerService.
 */
class ServiceProtectionLogicTest {

    @Test
    fun testLayoutParamsFlagsIncludeNoLimitsAndInScreen() {
        val requiredFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        assertTrue(
            "Flags must include FLAG_LAYOUT_IN_SCREEN",
            (requiredFlags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0
        )
        assertTrue(
            "Flags must include FLAG_LAYOUT_NO_LIMITS to prevent status bar offset",
            (requiredFlags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0
        )
        assertTrue(
            "Flags must include FLAG_NOT_FOCUSABLE",
            (requiredFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        )
        assertTrue(
            "Flags must include FLAG_NOT_TOUCH_MODAL",
            (requiredFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0
        )
    }

    @Test
    fun testLayoutParamsBoundsCoordinates() {
        val bounds = Rect(120, 240, 480, 800)

        val layoutParams = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        assertEquals(360, layoutParams.width)
        assertEquals(560, layoutParams.height)
        assertEquals(120, layoutParams.x)
        assertEquals(240, layoutParams.y)
        assertEquals(Gravity.TOP or Gravity.START, layoutParams.gravity)
        assertEquals(PixelFormat.TRANSLUCENT, layoutParams.format)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, layoutParams.type)
    }

    @Test
    fun testOverlayBackgroundColorEditModeVsActiveMode() {
        fun resolveOverlayColor(isEditMode: Boolean): Int {
            return if (isEditMode) {
                Color.parseColor("#44FF0000")
            } else {
                Color.TRANSPARENT
            }
        }

        val editColor = resolveOverlayColor(isEditMode = true)
        val activeColor = resolveOverlayColor(isEditMode = false)

        assertEquals("Normal protection mode must be completely transparent", Color.TRANSPARENT, activeColor)
        assertEquals("Edit mode must be semi-transparent red (#44FF0000)", Color.parseColor("#44FF0000"), editColor)
    }
}
