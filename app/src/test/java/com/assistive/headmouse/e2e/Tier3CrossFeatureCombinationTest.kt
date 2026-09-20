package com.assistive.headmouse.e2e

import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.e2e.harness.E2EUnifiedSystemHarness
import com.assistive.headmouse.e2e.model.E2EDualOperatingMode
import com.assistive.headmouse.e2e.model.E2EVoiceEngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 3: Cross-Feature Combination Test Suite
 * Covers pairwise interactions, concurrent operations, mode transitions while actions are in-flight,
 * and multi-subsystem coordination across Concept 1 and Concept 2.
 */
class Tier3CrossFeatureCombinationTest {

    private lateinit var harness: E2EUnifiedSystemHarness

    @Before
    fun setUp() {
        harness = E2EUnifiedSystemHarness()
    }

    @Test
    fun test_T3_1_wakeWordDuringActiveMouseMovementAndDwell() {
        // Concept 1: Mouse movement at 60 FPS
        val p1 = harness.simulateCameraFrame(0.016f, 300f, 400f)
        val p2 = harness.simulateCameraFrame(0.016f, 310f, 410f)
        assertNotNull(p1)
        assertNotNull(p2)

        // Concurrent Concept 2: User speaks wake word while moving head
        val res = harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, res.stateTransition)

        // Mouse continues moving without disruption
        val p3 = harness.simulateCameraFrame(0.016f, 320f, 420f)
        assertTrue(harness.isCursorOverlayVisible)
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
    }

    @Test
    fun test_T3_2_cameraUnbindWhileActiveVoiceMissionExecuting() {
        // Start autonomous voice action
        val res = harness.processSpokenUtterance("Hey Jarvis search funny videos")
        assertEquals(E2EVoiceEngineState.SPEAKING, res.stateTransition)

        // User toggles Head Mouse OFF from HUD during speech
        harness.toggleHeadMouseFromHud()

        // Camera must immediately unbind to save power
        assertFalse("Camera must unbind", harness.isCameraBound)
        assertFalse("Privacy LED must turn off", harness.isPrivacyLedOn)

        // Voice mission continues and delivers results cleanly
        assertEquals(E2EDualOperatingMode.JARVIS_ONLY, harness.operatingMode)
        assertTrue("HUD remains visible", harness.isFloatingHudVisible)
    }

    @Test
    fun test_T3_3_modeTransitionWhileDraggingOnScreenElement() {
        // Cursor tracking
        harness.simulateCameraFrame(0.016f, 100f, 100f)

        // Switch to JARVIS_ONLY
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)

        // Cursor overlay hides, camera unbinds
        assertFalse(harness.isCursorOverlayVisible)
        assertFalse(harness.isCameraBound)

        // Voice engine continues in wake-word standby
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
        assertTrue(harness.isFloatingHudVisible)
    }

    @Test
    fun test_T3_4_compoundCommandWithEmbeddedSleepTrigger() {
        // User says "Hey Jarvis stop" in one single breath
        val parse = harness.parseWakeWordUtterance("Hey Jarvis stop")
        assertTrue(parse.isWakeWordDetected)
        assertTrue(parse.isImmediateSleepTrigger)

        val res = harness.processSpokenUtterance("Hey Jarvis stop")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
        assertEquals("AMBER", harness.lastHudPulseColor)
    }

    @Test
    fun test_T3_5_headMouseToggleFromHudWhileCameraProcessingFrame() {
        // Process camera frame
        harness.simulateCameraFrame(0.016f, 500f, 500f)

        // Quick toggle from HUD
        harness.expandHud()
        harness.toggleHeadMouseFromHud()

        // Verify camera safely stopped without deadlock
        assertFalse(harness.isCameraBound)
        assertFalse(harness.isCameraSensorPowered)
    }

    @Test
    fun test_T3_6_emergencyAbortWhileTtsSpeakingAndRecognizerStarting() {
        // Voice query triggers speaking
        harness.processSpokenUtterance("Hey Jarvis who are you?")
        assertEquals(E2EVoiceEngineState.SPEAKING, harness.voiceEngineState)

        // User hits emergency abort on HUD
        harness.triggerEmergencyAbort()

        // State immediately halts to standby, HUD shows RED
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
        assertEquals("RED", harness.lastHudPulseColor)
        assertEquals("MISSION ABORTED", harness.hudStatusText)
    }

    @Test
    fun test_T3_7_wakeWordTriggerWhileCursorMagneticallySnapped() {
        // Snap to node
        val node = ScreenNode(id = "target_btn", left = 200f, top = 200f, right = 300f, bottom = 260f, text = "Submit")
        harness.spatialCache.updateNodes(listOf(node))

        val snapped = harness.spatialCache.findNearestNode(220f, 220f, 50f)
        assertNotNull(snapped)

        // Voice wake word spoken while hovering over button
        val res = harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, res.stateTransition)

        // Snapped node remains intact in spatial index
        val verifiedNode = harness.spatialCache.getNodes().find { it.id == "target_btn" }
        assertEquals("Submit", verifiedNode?.text)
    }

    @Test
    fun test_T3_8_switchingTargetAppsWhileContinuousVoiceActive() {
        // Launch Instagram
        val res1 = harness.processSpokenUtterance("Hey Jarvis open Instagram")
        assertEquals("com.instagram.android", res1.targetPackage)
        harness.onTtsSpeakingFinished()

        // Next turn: Launch YouTube
        val res2 = harness.processSpokenUtterance("Open YouTube")
        assertEquals("com.google.android.youtube", res2.targetPackage)
        harness.onTtsSpeakingFinished()

        // Head tracking mouse and continuous voice both remain active
        assertTrue(harness.isCameraBound)
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
    }

    @Test
    fun test_T3_9_rapidDualModeTogglesUnderHighTrackingLoad() {
        for (i in 0 until 10) {
            harness.simulateCameraFrame(0.016f, 100f + i, 200f + i)
            if (i % 2 == 0) {
                harness.setOperatingMode(E2EDualOperatingMode.HEAD_MOUSE_ONLY)
            } else {
                harness.setOperatingMode(E2EDualOperatingMode.DUAL_MODE)
            }
        }
        assertEquals(E2EDualOperatingMode.DUAL_MODE, harness.operatingMode)
        assertTrue(harness.isCameraBound)
        assertTrue(harness.isMicrophoneActive)
    }

    @Test
    fun test_T3_10_sleepTriggerWhileMissionLockIsHeld() {
        // Voice mission begins
        harness.processSpokenUtterance("Hey Jarvis search tutorials")
        assertEquals(E2EVoiceEngineState.SPEAKING, harness.voiceEngineState)

        // User says sleep trigger
        val res = harness.processSpokenUtterance("Jarvis ruk ja")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
        assertFalse(harness.activeMissionLock)
        assertEquals("AMBER", harness.lastHudPulseColor)
    }
}
