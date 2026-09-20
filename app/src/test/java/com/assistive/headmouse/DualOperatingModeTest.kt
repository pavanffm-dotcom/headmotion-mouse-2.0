package com.assistive.headmouse

import com.assistive.headmouse.model.DualModeController
import com.assistive.headmouse.model.DualOperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests covering DualOperatingMode enum properties, state transitions,
 * flag reconstruction, and the DualModeController contract.
 */
class DualOperatingModeTest {

    @Test
    fun testEnumPropertiesAndRequirements() {
        // 1. DUAL_MODE: Both active
        val dual = DualOperatingMode.DUAL_MODE
        assertTrue("DUAL_MODE must have isHeadMouseActive = true", dual.isHeadMouseActive)
        assertTrue("DUAL_MODE must have isJarvisActive = true", dual.isJarvisActive)
        assertTrue("DUAL_MODE must require camera", dual.requiresCamera)
        assertTrue("DUAL_MODE must require voice", dual.requiresVoice)

        // 2. HEAD_MOUSE_ONLY: Only camera mouse active
        val headOnly = DualOperatingMode.HEAD_MOUSE_ONLY
        assertTrue("HEAD_MOUSE_ONLY must have isHeadMouseActive = true", headOnly.isHeadMouseActive)
        assertFalse("HEAD_MOUSE_ONLY must have isJarvisActive = false", headOnly.isJarvisActive)
        assertTrue("HEAD_MOUSE_ONLY must require camera", headOnly.requiresCamera)
        assertFalse("HEAD_MOUSE_ONLY must not require voice", headOnly.requiresVoice)

        // 3. JARVIS_ONLY: Camera powered off, voice active
        val jarvisOnly = DualOperatingMode.JARVIS_ONLY
        assertFalse("JARVIS_ONLY must have isHeadMouseActive = false", jarvisOnly.isHeadMouseActive)
        assertTrue("JARVIS_ONLY must have isJarvisActive = true", jarvisOnly.isJarvisActive)
        assertFalse("JARVIS_ONLY must not require camera (sensor powered off)", jarvisOnly.requiresCamera)
        assertTrue("JARVIS_ONLY must require voice", jarvisOnly.requiresVoice)

        // 4. STANDBY: Both inactive
        val standby = DualOperatingMode.STANDBY
        assertFalse("STANDBY must have isHeadMouseActive = false", standby.isHeadMouseActive)
        assertFalse("STANDBY must have isJarvisActive = false", standby.isJarvisActive)
        assertFalse("STANDBY must not require camera", standby.requiresCamera)
        assertFalse("STANDBY must not require voice", standby.requiresVoice)
    }

    @Test
    fun testFromFlagsFactory() {
        assertEquals(DualOperatingMode.DUAL_MODE, DualOperatingMode.fromFlags(isHeadMouseActive = true, isJarvisActive = true))
        assertEquals(DualOperatingMode.HEAD_MOUSE_ONLY, DualOperatingMode.fromFlags(isHeadMouseActive = true, isJarvisActive = false))
        assertEquals(DualOperatingMode.JARVIS_ONLY, DualOperatingMode.fromFlags(isHeadMouseActive = false, isJarvisActive = true))
        assertEquals(DualOperatingMode.STANDBY, DualOperatingMode.fromFlags(isHeadMouseActive = false, isJarvisActive = false))
    }

    @Test
    fun testEveryModeRoundTripsViaFromFlags() {
        for (mode in DualOperatingMode.values()) {
            val reconstructed = DualOperatingMode.fromFlags(mode.isHeadMouseActive, mode.isJarvisActive)
            assertEquals("Roundtrip failed for mode: $mode", mode, reconstructed)
            assertEquals("requiresCamera mismatch for $mode", mode.isHeadMouseActive, mode.requiresCamera)
            assertEquals("requiresVoice mismatch for $mode", mode.isJarvisActive, mode.requiresVoice)
        }
    }

    @Test
    fun testStateTransitionsLifecycle() {
        // Initial state: Head Mouse Only (default for HeadMotionMouse)
        var currentMode = DualOperatingMode.HEAD_MOUSE_ONLY
        assertTrue(currentMode.requiresCamera)
        assertFalse(currentMode.requiresVoice)

        // Step 1: User enables J.A.R.V.I.S. -> Transitions to DUAL_MODE
        currentMode = DualOperatingMode.fromFlags(
            isHeadMouseActive = currentMode.isHeadMouseActive,
            isJarvisActive = true
        )
        assertEquals(DualOperatingMode.DUAL_MODE, currentMode)
        assertTrue(currentMode.requiresCamera)
        assertTrue(currentMode.requiresVoice)

        // Step 2: User disables Head Mouse to save battery and stop camera sensor -> Transitions to JARVIS_ONLY
        currentMode = DualOperatingMode.fromFlags(
            isHeadMouseActive = false,
            isJarvisActive = currentMode.isJarvisActive
        )
        assertEquals(DualOperatingMode.JARVIS_ONLY, currentMode)
        assertFalse("Camera must be released in JARVIS_ONLY", currentMode.requiresCamera)
        assertTrue("Voice must remain active in JARVIS_ONLY", currentMode.requiresVoice)

        // Step 3: User says 'Go to sleep' or puts system in standby -> Transitions to STANDBY
        currentMode = DualOperatingMode.fromFlags(
            isHeadMouseActive = currentMode.isHeadMouseActive,
            isJarvisActive = false
        )
        assertEquals(DualOperatingMode.STANDBY, currentMode)
        assertFalse(currentMode.requiresCamera)
        assertFalse(currentMode.requiresVoice)

        // Step 4: User re-enables Head Mouse from standby -> Transitions to HEAD_MOUSE_ONLY
        currentMode = DualOperatingMode.fromFlags(
            isHeadMouseActive = true,
            isJarvisActive = currentMode.isJarvisActive
        )
        assertEquals(DualOperatingMode.HEAD_MOUSE_ONLY, currentMode)
        assertTrue(currentMode.requiresCamera)
        assertFalse(currentMode.requiresVoice)
    }

    @Test
    fun testDualModeControllerImplementation() {
        // Mock controller tracking state mutations
        class TestDualModeController : DualModeController {
            private var headMouse = true
            private var jarvis = false

            override fun setOperatingMode(mode: DualOperatingMode) {
                headMouse = mode.isHeadMouseActive
                jarvis = mode.isJarvisActive
            }

            override fun setHeadMouseEnabled(enabled: Boolean) {
                headMouse = enabled
            }

            override fun setJarvisServiceEnabled(enabled: Boolean) {
                jarvis = enabled
            }

            override fun getOperatingMode(): DualOperatingMode {
                return DualOperatingMode.fromFlags(headMouse, jarvis)
            }
        }

        val controller = TestDualModeController()
        assertEquals(DualOperatingMode.HEAD_MOUSE_ONLY, controller.getOperatingMode())

        controller.setJarvisServiceEnabled(true)
        assertEquals(DualOperatingMode.DUAL_MODE, controller.getOperatingMode())

        controller.setHeadMouseEnabled(false)
        assertEquals(DualOperatingMode.JARVIS_ONLY, controller.getOperatingMode())

        controller.setOperatingMode(DualOperatingMode.STANDBY)
        assertEquals(DualOperatingMode.STANDBY, controller.getOperatingMode())

        controller.setOperatingMode(DualOperatingMode.DUAL_MODE)
        assertEquals(DualOperatingMode.DUAL_MODE, controller.getOperatingMode())
    }
}
