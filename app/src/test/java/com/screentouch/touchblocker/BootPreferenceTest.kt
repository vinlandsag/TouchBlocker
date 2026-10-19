package com.screentouch.touchblocker

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying boot restoration decision matrix according to user preferences.
 */
class BootPreferenceTest {

    data class BootRecoveryDecision(
        val shouldRestore: Boolean,
        val restoredZone: Rect?,
        val shouldDisableActiveFlag: Boolean
    )

    /**
     * Evaluates whether touch blocker service should be restored on device boot.
     */
    private fun evaluateBootRecovery(
        startOnBoot: Boolean,
        wasProtectionActive: Boolean,
        hasOverlayPermission: Boolean,
        savedZone: Rect?,
        hasEnabledMultiZones: Boolean
    ): BootRecoveryDecision {
        if (!startOnBoot) {
            return BootRecoveryDecision(
                shouldRestore = false,
                restoredZone = null,
                shouldDisableActiveFlag = true
            )
        }

        if (!wasProtectionActive) {
            return BootRecoveryDecision(
                shouldRestore = false,
                restoredZone = null,
                shouldDisableActiveFlag = false
            )
        }

        if (!hasOverlayPermission) {
            return BootRecoveryDecision(
                shouldRestore = false,
                restoredZone = null,
                shouldDisableActiveFlag = true
            )
        }

        if (savedZone != null) {
            return BootRecoveryDecision(
                shouldRestore = true,
                restoredZone = savedZone,
                shouldDisableActiveFlag = false
            )
        }

        if (hasEnabledMultiZones) {
            return BootRecoveryDecision(
                shouldRestore = true,
                restoredZone = null, // Will restore multi-zones
                shouldDisableActiveFlag = false
            )
        }

        // Accidental full-screen prevention: No zone configured, do not create full-screen overlay on boot
        return BootRecoveryDecision(
            shouldRestore = false,
            restoredZone = null,
            shouldDisableActiveFlag = true
        )
    }

    @Test
    fun testBootRestorationHonorsStartOnBootDisabled() {
        val decision = evaluateBootRecovery(
            startOnBoot = false,
            wasProtectionActive = true,
            hasOverlayPermission = true,
            savedZone = Rect(100, 100, 300, 300),
            hasEnabledMultiZones = false
        )
        assertFalse("Should not restore when startOnBoot is disabled", decision.shouldRestore)
        assertTrue(decision.shouldDisableActiveFlag)
    }

    @Test
    fun testBootRestorationHonorsProtectionWasInactive() {
        val decision = evaluateBootRecovery(
            startOnBoot = true,
            wasProtectionActive = false,
            hasOverlayPermission = true,
            savedZone = Rect(100, 100, 300, 300),
            hasEnabledMultiZones = false
        )
        assertFalse("Should not restore when protection was not active prior to shutdown", decision.shouldRestore)
        assertFalse(decision.shouldDisableActiveFlag)
    }

    @Test
    fun testBootRestorationWithOverlayPermissionRevoked() {
        val decision = evaluateBootRecovery(
            startOnBoot = true,
            wasProtectionActive = true,
            hasOverlayPermission = false,
            savedZone = Rect(100, 100, 300, 300),
            hasEnabledMultiZones = false
        )
        assertFalse("Should not restore when overlay permission is revoked", decision.shouldRestore)
        assertTrue("Should disable active flag when permission is revoked", decision.shouldDisableActiveFlag)
    }

    @Test
    fun testBootRestorationPreventsAccidentalFullScreenWhenNoZonesConfigured() {
        val decision = evaluateBootRecovery(
            startOnBoot = true,
            wasProtectionActive = true,
            hasOverlayPermission = true,
            savedZone = null,
            hasEnabledMultiZones = false
        )
        assertFalse("Should not create full-screen overlay when no zones were configured", decision.shouldRestore)
        assertTrue(decision.shouldDisableActiveFlag)
    }

    @Test
    fun testBootRestorationSucceedsWhenAllConditionsMet() {
        val targetZone = Rect(50, 100, 350, 500)
        val decision = evaluateBootRecovery(
            startOnBoot = true,
            wasProtectionActive = true,
            hasOverlayPermission = true,
            savedZone = targetZone,
            hasEnabledMultiZones = false
        )
        assertTrue("Should restore when startOnBoot, protectionActive, permission, and zone are all present", decision.shouldRestore)
        assertEquals(targetZone, decision.restoredZone)
        assertFalse(decision.shouldDisableActiveFlag)
    }
}
