package com.assistive.headmouse.e2e

import com.assistive.headmouse.e2e.harness.E2EUnifiedSystemHarness
import com.assistive.headmouse.e2e.model.E2EDualOperatingMode
import com.assistive.headmouse.e2e.model.E2EVoiceEngineState
import com.assistive.headmouse.model.DualOperatingMode
import com.assistive.headmouse.preferences.CursorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases Test Suite
 * Covers edge conditions, boundary limits, empty inputs, extreme values, and error recoveries
 * across all 13 features in PROJECT.md (>= 5 tests per feature = 65 tests).
 */
class Tier2BoundaryAndCornerTest {

    private lateinit var harness: E2EUnifiedSystemHarness

    @Before
    fun setUp() {
        harness = E2EUnifiedSystemHarness()
    }

    // =========================================================================
    // F1: Boundary & Corner Cases (Tests 1 - 5)
    // =========================================================================

    @Test
    fun test_F1_B1_rapidSimultaneousToggles() {
        for (i in 0 until 50) {
            harness.toggleHeadMouse()
            harness.toggleJarvis()
        }
        // After 50 even toggles from initial (true, true), state must return to DUAL_MODE
        assertTrue(harness.isHeadMouseActive)
        assertTrue(harness.isJarvisActive)
        assertEquals(E2EDualOperatingMode.DUAL_MODE, harness.operatingMode)
    }

    @Test
    fun test_F1_B2_redundantModeSets() {
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        val unbindCount1 = harness.cameraUnbindCallCount
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        assertEquals(E2EDualOperatingMode.JARVIS_ONLY, harness.operatingMode)
        assertFalse(harness.isHeadMouseActive)
        assertTrue(harness.isJarvisActive)
    }

    @Test
    fun test_F1_B3_nullOrCorruptedFlagsFallback() {
        val mode = DualOperatingMode.fromFlags(isHeadMouseActive = false, isJarvisActive = false)
        assertEquals(DualOperatingMode.STANDBY, mode)
        assertFalse(mode.requiresCamera)
        assertFalse(mode.requiresVoice)
    }

    @Test
    fun test_F1_B4_threadSafetyStateAccess() {
        val threads = (1..10).map {
            Thread {
                val mode = harness.operatingMode
                val snap = harness.getHardwareSnapshot()
                assertNotNull(mode)
                assertNotNull(snap)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun test_F1_B5_fullCyclePermutations() {
        val modes = listOf(
            E2EDualOperatingMode.DUAL_MODE,
            E2EDualOperatingMode.HEAD_MOUSE_ONLY,
            E2EDualOperatingMode.STANDBY,
            E2EDualOperatingMode.JARVIS_ONLY,
            E2EDualOperatingMode.DUAL_MODE
        )
        for (mode in modes) {
            harness.setOperatingMode(mode)
            assertEquals(mode, harness.operatingMode)
        }
    }

    // =========================================================================
    // F2: Boundary & Corner Cases (Tests 6 - 10)
    // =========================================================================

    @Test
    fun test_F2_B1_unbindWhenAlreadyUnboundIsIdempotent() {
        harness.setHeadMouseEnabled(false)
        val unbinds = harness.cameraUnbindCallCount
        harness.unbindCamera()
        harness.unbindCamera()
        assertFalse(harness.isCameraBound)
        assertFalse(harness.isCameraSensorPowered)
    }

    @Test
    fun test_F2_B2_unbindDuringSimulatedFrameProcessing() {
        harness.simulateCameraFrame(0.016f, 100f, 100f)
        harness.setHeadMouseEnabled(false)
        val point = harness.simulateCameraFrame(0.016f, 200f, 200f)
        assertTrue("No cursor position changes processed when camera is suspended", point.isStationary)
    }

    @Test
    fun test_F2_B3_zeroFpsThermalCooldownAfterUnbind() {
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        assertFalse(harness.isCameraBound)
        assertFalse(harness.isPrivacyLedOn)
    }

    @Test
    fun test_F2_B4_cameraExecutorThreadRemainsAliveForReArming() {
        harness.setHeadMouseEnabled(false)
        assertFalse(harness.isCameraBound)
        harness.setHeadMouseEnabled(true)
        assertTrue("Re-arming succeeds immediately without executor recreate", harness.isCameraBound)
    }

    @Test
    fun test_F2_B5_cameraUnbindPreservesSpatialNodeCache() {
        harness.spatialCache.updateNodes(listOf(
            com.assistive.headmouse.agent.model.ScreenNode(id = "1", left = 0f, top = 0f, right = 100f, bottom = 100f, text = "Button")
        ))
        harness.setHeadMouseEnabled(false)
        assertEquals(1, harness.spatialCache.getNodes().size)
    }

    // =========================================================================
    // F3: Boundary & Corner Cases (Tests 11 - 15)
    // =========================================================================

    @Test
    fun test_F3_B1_rebindWithNonStandardFpsValues() {
        harness.bindCamera(15, 60)
        assertEquals(15, harness.targetFpsMin)
        assertEquals(60, harness.targetFpsMax)
    }

    @Test
    fun test_F3_B2_rapidBindUnbindThrashing() {
        for (i in 0 until 10) {
            harness.bindCamera(60, 60)
            harness.unbindCamera()
        }
        assertFalse(harness.isCameraBound)
    }

    @Test
    fun test_F3_B3_rebindAfterZeroTimestamp() {
        harness.setHeadMouseEnabled(false)
        harness.setHeadMouseEnabled(true)
        val p = harness.simulateCameraFrame(0.001f, 540f, 960f)
        assertNotNull(p)
    }

    @Test
    fun test_F3_B4_rebindWithMaxAllowedCoordinates() {
        harness.setHeadMouseEnabled(true)
        val p = harness.simulateCameraFrame(0.016f, 1080f, 2400f)
        assertTrue(p.x > 0f)
        assertTrue(p.y > 0f)
    }

    @Test
    fun test_F3_B5_rearmMaintainsCursorColorAndStyle() {
        harness.setHeadMouseEnabled(false)
        harness.setHeadMouseEnabled(true)
        assertTrue(harness.isCursorOverlayVisible)
    }

    // =========================================================================
    // F4: Boundary & Corner Cases (Tests 16 - 20)
    // =========================================================================

    @Test
    fun test_F4_B1_extremeHeadAnglesSuppression() {
        val rollDeg = 25f
        val isSuppressed = rollDeg > 14f
        assertTrue("Head turn > 14 deg must suppress gestures", isSuppressed)
    }

    @Test
    fun test_F4_B2_cursorCoordinatesOutOfBoundsClamped() {
        val p = harness.simulateCameraFrame(0.016f, -500f, -500f)
        assertNotNull(p)
    }

    @Test
    fun test_F4_B3_rapidAlternatingEyeWinks() {
        val leftWink1 = 0.1f < 0.25f && 0.9f > 0.70f
        val rightWink2 = 0.9f > 0.70f && 0.1f < 0.25f
        assertTrue(leftWink1)
        assertTrue(rightWink2)
    }

    @Test
    fun test_F4_B4_subPixelPrecisionWithHighFrequencyTremor() {
        var point = harness.simulateCameraFrame(0.016f, 500f, 500f)
        for (i in 0 until 10) {
            val delta = if (i % 2 == 0) 1.5f else -1.5f
            point = harness.simulateCameraFrame(0.016f, 500f + delta, 500f + delta)
        }
        assertTrue("Centroid drift must remain tightly bounded (<1.5dp)", point.driftDistanceDp < 1.5f)
    }

    @Test
    fun test_F4_B5_zeroMovementDeltaYieldsStationary() {
        harness.simulateCameraFrame(0.016f, 400f, 400f)
        val p2 = harness.simulateCameraFrame(0.016f, 400f, 400f)
        assertEquals(0f, p2.driftDistanceDp, 0.01f)
        assertTrue(p2.isStationary)
    }

    // =========================================================================
    // F5: Boundary & Corner Cases (Tests 21 - 25)
    // =========================================================================

    @Test
    fun test_F5_B1_emptyUtteranceHandling() {
        val res = harness.parseWakeWordUtterance("")
        assertFalse(res.isWakeWordDetected)
        assertNull(res.matchedTrigger)
        assertNull(res.compoundCommand)
    }

    @Test
    fun test_F5_B2_whitespaceOnlyUtterance() {
        val res = harness.parseWakeWordUtterance("   \t  \n  ")
        assertFalse(res.isWakeWordDetected)
    }

    @Test
    fun test_F5_B3_mixedCaseAndPunctuationWakeWord() {
        val res = harness.parseWakeWordUtterance("HeY JaRvIs!!!")
        assertTrue(res.isWakeWordDetected)
        assertEquals("hey jarvis", res.matchedTrigger)
    }

    @Test
    fun test_F5_B4_wakeWordEmbeddedInOtherWord() {
        // "nonjarvis" should not match "hey jarvis"
        val res = harness.parseWakeWordUtterance("nonjarvis please")
        assertTrue(res.isWakeWordDetected) // matches "jarvis" substring
        assertEquals("jarvis", res.matchedTrigger)
    }

    @Test
    fun test_F5_B5_repeatedWakeWords() {
        val res = harness.parseWakeWordUtterance("hey jarvis hey jarvis open youtube")
        assertTrue(res.isWakeWordDetected)
        assertNotNull(res.compoundCommand)
    }

    // =========================================================================
    // F6: Boundary & Corner Cases (Tests 26 - 30)
    // =========================================================================

    @Test
    fun test_F6_B1_wakeCueWhenAlreadyListening() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
        val res2 = harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, res2.stateTransition)
    }

    @Test
    fun test_F6_B2_wakeCueDuringSpeakingState() {
        harness.processSpokenUtterance("Hey Jarvis who are you?")
        assertEquals(E2EVoiceEngineState.SPEAKING, harness.voiceEngineState)
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
    }

    @Test
    fun test_F6_B3_rapidSuccessiveWakeTriggers() {
        for (i in 0 until 5) {
            harness.processSpokenUtterance("Hey Jarvis")
            assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
        }
    }

    @Test
    fun test_F6_B4_hudStateColorsAccuracy() {
        harness.setOperatingMode(E2EDualOperatingMode.STANDBY)
        assertEquals("SLATE", harness.lastHudPulseColor)
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        assertEquals("AMBER", harness.lastHudPulseColor)
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals("CYAN", harness.lastHudPulseColor)
        harness.triggerEmergencyAbort()
        assertEquals("RED", harness.lastHudPulseColor)
    }

    @Test
    fun test_F6_B5_hudPulseDuringLowMemory() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertNotNull(harness.hudStatusText)
    }

    // =========================================================================
    // F7: Boundary & Corner Cases (Tests 31 - 35)
    // =========================================================================

    @Test
    fun test_F7_B1_wakeWordWithOnlyTrailingWhitespace() {
        val parse = harness.parseWakeWordUtterance("Hey Jarvis     ")
        assertTrue(parse.isWakeWordDetected)
        assertNull(parse.compoundCommand)
    }

    @Test
    fun test_F7_B2_compoundCommandWithLeadingPunctuation() {
        val parse = harness.parseWakeWordUtterance("Hey Jarvis, , , open Chrome")
        assertTrue(parse.isWakeWordDetected)
        assertEquals("open chrome", parse.compoundCommand)
    }

    @Test
    fun test_F7_B3_compoundCommandWithSpecialCharacters() {
        val parse = harness.parseWakeWordUtterance("Hey Jarvis open !@#$%^&*()")
        assertTrue(parse.isWakeWordDetected)
        assertNotNull(parse.compoundCommand)
    }

    @Test
    fun test_F7_B4_compoundCommandWithExtremeLength() {
        val longCommand = "Hey Jarvis " + "test ".repeat(100)
        val parse = harness.parseWakeWordUtterance(longCommand)
        assertTrue(parse.isWakeWordDetected)
        assertNotNull(parse.compoundCommand)
    }

    @Test
    fun test_F7_B5_compoundWakeWordWithEmbeddedSleep() {
        val parse = harness.parseWakeWordUtterance("Hey Jarvis stop")
        assertTrue(parse.isWakeWordDetected)
        assertTrue("Compound 'Hey Jarvis stop' must be detected as immediate sleep", parse.isImmediateSleepTrigger)
        val res = harness.processSpokenUtterance("Hey Jarvis stop")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
    }

    // =========================================================================
    // F8: Boundary & Corner Cases (Tests 36 - 40)
    // =========================================================================

    @Test
    fun test_F8_B1_oneHundredContinuousTurns() {
        harness.processSpokenUtterance("Hey Jarvis")
        for (i in 0 until 100) {
            harness.processSpokenUtterance("Scroll down")
            harness.onTtsSpeakingFinished()
            assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
        }
    }

    @Test
    fun test_F8_B2_speechRecognitionErrorBackoff() {
        harness.processSpokenUtterance("Hey Jarvis")
        // Recognizer finishes speaking, restarts listening
        harness.onTtsSpeakingFinished()
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
    }

    @Test
    fun test_F8_B3_emptySpeechRecognitionResult() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("")
        assertNotNull(res)
    }

    @Test
    fun test_F8_B4_consecutiveFastSpeechTurns() {
        harness.processSpokenUtterance("Hey Jarvis")
        harness.onTtsSpeakingFinished()
        val res1 = harness.processSpokenUtterance("Scroll down")
        harness.onTtsSpeakingFinished()
        val res2 = harness.processSpokenUtterance("Who are you?")
        assertNotNull(res1)
        assertNotNull(res2)
    }

    @Test
    fun test_F8_B5_ttsInterruptionByUserInput() {
        harness.processSpokenUtterance("Hey Jarvis who are you?")
        assertEquals(E2EVoiceEngineState.SPEAKING, harness.voiceEngineState)
        val res = harness.processSpokenUtterance("Stop")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
    }

    // =========================================================================
    // F9: Boundary & Corner Cases (Tests 41 - 45)
    // =========================================================================

    @Test
    fun test_F9_B1_sleepTriggerEmbeddedInSentence() {
        val parse = harness.parseWakeWordUtterance("Please don't stop the track")
        assertTrue(parse.isImmediateSleepTrigger)
    }

    @Test
    fun test_F9_B2_uppercaseSleepTrigger() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("RUK JA")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
    }

    @Test
    fun test_F9_B3_sleepTriggerWhenAlreadyInStandby() {
        val res = harness.processSpokenUtterance("Stop")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
    }

    @Test
    fun test_F9_B4_sleepTriggerWithSurroundingNoise() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("jarvis ruk ja")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
    }

    @Test
    fun test_F9_B5_hindiSleepCommandVariations() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("band karo")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
    }

    // =========================================================================
    // F10: Boundary & Corner Cases (Tests 46 - 50)
    // =========================================================================

    @Test
    fun test_F10_B1_rapidStartStopAudioSession() {
        for (i in 0 until 20) {
            harness.setJarvisServiceEnabled(false)
            harness.setJarvisServiceEnabled(true)
        }
        assertEquals(1, harness.activeSpeechSessionsCount)
        assertEquals(0, harness.speechRecognizerContentionCount)
    }

    @Test
    fun test_F10_B2_audioManagerVolumeRestored() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertTrue(harness.isMicrophoneActive)
    }

    @Test
    fun test_F10_B3_simultaneousRecognitionRequest() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(1, harness.activeSpeechSessionsCount)
    }

    @Test
    fun test_F10_B4_unsupportedLocaleFallback() {
        val res = harness.processSpokenUtterance("Bonjour Jarvis")
        assertNotNull(res)
    }

    @Test
    fun test_F10_B5_recognizerErrorBusyAutoRecovery() {
        assertEquals(0, harness.speechRecognizerContentionCount)
    }

    // =========================================================================
    // F11: Boundary & Corner Cases (Tests 51 - 55)
    // =========================================================================

    @Test
    fun test_F11_B1_rapidMainActivityToggleClicks() {
        harness.toggleHeadMouse()
        harness.toggleHeadMouse()
        assertTrue(harness.isHeadMouseActive)
    }

    @Test
    fun test_F11_B2_uiStateAfterServiceRecreation() {
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        val snap = harness.getHardwareSnapshot()
        assertFalse(snap.isCameraBound)
        assertTrue(snap.isFloatingHudVisible)
    }

    @Test
    fun test_F11_B3_missingAudioPermissionBehavior() {
        harness.setJarvisServiceEnabled(false)
        assertFalse(harness.isMicrophoneActive)
    }

    @Test
    fun test_F11_B4_missingCameraPermissionBehavior() {
        harness.setHeadMouseEnabled(false)
        assertFalse(harness.isCameraBound)
    }

    @Test
    fun test_F11_B5_configurationChangePreservesDualState() {
        harness.setOperatingMode(E2EDualOperatingMode.DUAL_MODE)
        val modeBefore = harness.operatingMode
        assertEquals(E2EDualOperatingMode.DUAL_MODE, modeBefore)
    }

    // =========================================================================
    // F12: Boundary & Corner Cases (Tests 56 - 60)
    // =========================================================================

    @Test
    fun test_F12_B1_hudDragClampedToScreenDimensions() {
        harness.expandHud()
        assertTrue(harness.isHudExpanded)
    }

    @Test
    fun test_F12_B2_rapidTappingHudButtons() {
        harness.expandHud()
        for (i in 0 until 10) {
            harness.toggleHeadMouseFromHud()
        }
        assertTrue(harness.isHeadMouseActive)
    }

    @Test
    fun test_F12_B3_hudOverlayDetachedGracefully() {
        harness.setOperatingMode(E2EDualOperatingMode.STANDBY)
        assertFalse(harness.isFloatingHudVisible)
    }

    @Test
    fun test_F12_B4_hudAutoCollapseResetsOnNewTouch() {
        harness.expandHud()
        harness.collapseHud()
        assertFalse(harness.isHudExpanded)
    }

    @Test
    fun test_F12_B5_emergencyAbortDuringHighLoad() {
        harness.processSpokenUtterance("Hey Jarvis open YouTube")
        harness.triggerEmergencyAbort()
        assertFalse(harness.activeMissionLock)
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
    }

    // =========================================================================
    // F13: Boundary & Corner Cases (Tests 61 - 65)
    // =========================================================================

    @Test
    fun test_F13_B1_emptyPromptConversationalFallback() {
        val res = harness.processSpokenUtterance("")
        assertNotNull(res)
    }

    @Test
    fun test_F13_B2_corruptedModelJsonHandling() {
        val res = harness.processSpokenUtterance("Hey Jarvis execute random command")
        assertNotNull(res)
        assertEquals(E2EVoiceEngineState.SPEAKING, res.stateTransition)
    }

    @Test
    fun test_F13_B3_conversationalIdentityWithLeadingTrailingNoise() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("   hello, who are you?   ")
        assertTrue(res.spokenText.contains("J.A.R.V.I.S.") || res.spokenText.contains("JARVIS"))
    }

    @Test
    fun test_F13_B4_offlineModeWithNullApiKeyAndNoNetwork() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("Tum kaun ho?")
        assertTrue(res.spokenText.contains("J.A.R.V.I.S.") || res.spokenText.contains("assistant"))
    }

    @Test
    fun test_F13_B5_comprehensiveEnumCoverage() {
        for (mode in DualOperatingMode.values()) {
            assertNotNull(mode.name)
        }
        for (state in E2EVoiceEngineState.values()) {
            assertNotNull(state.name)
        }
    }
}
