package com.screentouch.touchblocker

import android.graphics.Color
import android.graphics.Rect
import com.screentouch.touchblocker.data.BlockZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockZoneTest {

    @Test
    fun testBlockZoneToRect() {
        val zone = BlockZone(
            id = "test_zone",
            x = 100,
            y = 200,
            width = 300,
            height = 400
        )
        val rect = zone.toRect()
        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(400, rect.right)
        assertEquals(600, rect.bottom)
        assertEquals(300, rect.width())
        assertEquals(400, rect.height())
    }

    @Test
    fun testBlockZoneFromRect() {
        val rect = Rect(50, 100, 250, 400)
        val zone = BlockZone.fromRect("zone_1", rect, enabled = true, color = Color.BLUE, alpha = 0.5f)

        assertEquals("zone_1", zone.id)
        assertEquals(50, zone.x)
        assertEquals(100, zone.y)
        assertEquals(200, zone.width)
        assertEquals(300, zone.height)
        assertTrue(zone.enabled)
        assertEquals(Color.BLUE, zone.color)
        assertEquals(0.5f, zone.alpha, 0.001f)
    }

    @Test
    fun testBlockZoneJsonSerialization() {
        val originalZone = BlockZone(
            id = "preset_test",
            x = 120,
            y = 240,
            width = 360,
            height = 480,
            enabled = false,
            color = Color.RED,
            alpha = 0.25f
        )

        val json = originalZone.toJson()
        val deserialized = BlockZone.fromJson(json)

        assertEquals(originalZone.id, deserialized.id)
        assertEquals(originalZone.x, deserialized.x)
        assertEquals(originalZone.y, deserialized.y)
        assertEquals(originalZone.width, deserialized.width)
        assertEquals(originalZone.height, deserialized.height)
        assertFalse(deserialized.enabled)
        assertEquals(originalZone.color, deserialized.color)
        assertEquals(originalZone.alpha, deserialized.alpha, 0.001f)
    }
}
