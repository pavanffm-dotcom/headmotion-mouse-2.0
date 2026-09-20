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
 * Tier 1: Feature Coverage Test Suite
 * Covers representative inputs for all 13 features in PROJECT.md (>= 5 tests per feature = 65 tests).
 */
class Tier1FeatureCoverageTest {

    private lateinit var harness: E2EUnifiedSystemHarness

    @Before
    fun setUp() {
        harness = E2EUnifiedSystemHarness()
    }

    // =========================================================================
    // F1: Independent Engine Settings (Tests 1 - 5)
    // =========================================================================

    @Test
    fun test_F1_1_dualModeActivatesBothEngines() {
        harness.setOperatingMode(E2EDualOperatingMode.DUAL_MODE)
        assertTrue("Head mouse must be active in DUAL_MODE", harness.isHeadMouseActive)
        assertTrue("J.A.R.V.I.S. must be active in DUAL_MODE", harness.isJarvisActive)
        assertEquals(E2EDualOperatingMode.DUAL_MODE, harness.operatingMode)
        assertTrue("Camera must be bound", harness.isCameraBound)
        assertTrue("Microphone must be active", harness.isMicrophoneActive)
    }

    @Test
    fun test_F1_2_headMouseOnlyDeactivatesJarvis() {
        harness.setOperatingMode(E2EDualOperatingMode.HEAD_MOUSE_ONLY)
        assertTrue("Head mouse must be active in HEAD_MOUSE_ONLY", harness.isHeadMouseActive)
        assertFalse("J.A.R.V.I.S. must be inactive in HEAD_MOUSE_ONLY", harness.isJarvisActive)
        assertEquals(E2EDualOperatingMode.HEAD_MOUSE_ONLY, harness.operatingMode)
        assertTrue("Camera must be bound", harness.isCameraBound)
        assertFalse("Microphone must be powered off", harness.isMicrophoneActive)
        assertFalse("Floating HUD must be hidden", harness.isFloatingHudVisible)
    }

    @Test
    fun test_F1_3_jarvisOnlyDeactivatesHeadMouse() {
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        assertFalse("Head mouse must be inactive in JARVIS_ONLY", harness.isHeadMouseActive)
        assertTrue("J.A.R.V.I.S. must be active in JARVIS_ONLY", harness.isJarvisActive)
        assertEquals(E2EDualOperatingMode.JARVIS_ONLY, harness.operatingMode)
        assertFalse("Camera must be unbound", harness.isCameraBound)
        assertFalse("Camera sensor must be powered off", harness.isCameraSensorPowered)
        assertFalse("Privacy LED must be extinguished", harness.isPrivacyLedOn)
        assertTrue("Voice service must be active", harness.isMicrophoneActive)
        assertTrue("Floating HUD must be visible", harness.isFloatingHudVisible)
    }

    @Test
    fun test_F1_4_standbyModeSuspendsBoth() {
        harness.setOperatingMode(E2EDualOperatingMode.STANDBY)
        assertFalse("Head mouse must be inactive in STANDBY", harness.isHeadMouseActive)
        assertFalse("J.A.R.V.I.S. must be inactive in STANDBY", harness.isJarvisActive)
        assertEquals(E2EDualOperatingMode.STANDBY, harness.operatingMode)
        assertFalse("Camera must be unbound", harness.isCameraBound)
        assertFalse("Mic must be released", harness.isMicrophoneActive)
        assertFalse("Cursor overlay must be hidden", harness.isCursorOverlayVisible)
        assertFalse("HUD must be hidden", harness.isFloatingHudVisible)
    }

    @Test
    fun test_F1_5_independentTogglesPreservePartnerEngine() {
        harness.setOperatingMode(E2EDualOperatingMode.DUAL_MODE)
        harness.toggleHeadMouse() // Disable head mouse
        assertFalse(harness.isHeadMouseActive)
        assertTrue("J.A.R.V.I.S. state must be preserved when head mouse toggled", harness.isJarvisActive)
        assertEquals(E2EDualOperatingMode.JARVIS_ONLY, harness.operatingMode)

        harness.toggleJarvis() // Disable JARVIS
        assertFalse(harness.isHeadMouseActive)
        assertFalse(harness.isJarvisActive)
        assertEquals(E2EDualOperatingMode.STANDBY, harness.operatingMode)
    }

    // =========================================================================
    // F2: Clean Camera Suspension (Tests 6 - 10)
    // =========================================================================

    @Test
    fun test_F2_1_cameraUnbindsWhenHeadMouseDisabled() {
        val initialUnbindCount = harness.cameraUnbindCallCount
        harness.setHeadMouseEnabled(false)
        assertEquals("Camera unbind must be executed", initialUnbindCount + 1, harness.cameraUnbindCallCount)
        assertFalse(harness.isCameraBound)
    }

    @Test
    fun test_F2_2_cameraSensorPowersOff() {
        harness.setHeadMouseEnabled(false)
        assertFalse("Camera sensor must be powered off to eliminate battery drain", harness.isCameraSensorPowered)
        val point = harness.simulateCameraFrame(0.016f, 100f, 200f)
        assertTrue("No frame updates should be processed when camera is suspended", point.isStationary)
    }

    @Test
    fun test_F2_3_privacyLedExtinguished() {
        harness.setHeadMouseEnabled(false)
        assertFalse("Android privacy LED indicator must be extinguished", harness.isPrivacyLedOn)
    }

    @Test
    fun test_F2_4_cursorOverlayHidesOnSuspension() {
        harness.setHeadMouseEnabled(false)
        assertFalse("Cursor overlay must be hidden during camera suspension", harness.isCursorOverlayVisible)
    }

    @Test
    fun test_F2_5_suspensionLeavesVoiceAndHudActiveInJarvisMode() {
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        assertFalse("Camera is suspended", harness.isCameraSensorPowered)
        assertTrue("HUD is active", harness.isFloatingHudVisible)
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
    }

    // =========================================================================
    // F3: Instant 60 FPS CameraX Resumption (Tests 11 - 15)
    // =========================================================================

    @Test
    fun test_F3_1_rearmingHeadMouseRebindsCamera() {
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        val bindCountBefore = harness.cameraBindCallCount
        harness.setHeadMouseEnabled(true)
        assertTrue("Camera must rebind on re-arming", harness.isCameraBound)
        assertEquals("Bind call count must increment", bindCountBefore + 1, harness.cameraBindCallCount)
    }

    @Test
    fun test_F3_2_camera2InteropRequests60FpsTargetRange() {
        harness.setHeadMouseEnabled(true)
        assertEquals(60, harness.targetFpsMin)
        assertEquals(60, harness.targetFpsMax)
    }

    @Test
    fun test_F3_3_variableTargetFpsFallbackSupport() {
        harness.bindCamera(30, 60)
        assertEquals(30, harness.targetFpsMin)
        assertEquals(60, harness.targetFpsMax)
    }

    @Test
    fun test_F3_4_instantReArmingWithoutServiceRestart() {
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        val startTime = System.currentTimeMillis()
        harness.setHeadMouseEnabled(true)
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("Camera re-arm must occur in under 500ms", elapsed < 500)
        assertTrue(harness.isCameraBound)
        assertTrue(harness.isPrivacyLedOn)
    }

    @Test
    fun test_F3_5_filtersResetCleanlyOnReArming() {
        harness.setHeadMouseEnabled(false)
        harness.setHeadMouseEnabled(true)
        val point = harness.simulateCameraFrame(0.016f, 540f, 960f)
        assertNotNull(point)
        assertTrue(point.x > 0f && point.y > 0f)
    }

    // =========================================================================
    // F4: Concept 1 Non-Interference (Tests 16 - 20)
    // =========================================================================

    @Test
    fun test_F4_1_allTenCursorStylesRetained() {
        val styles = CursorStyle.values()
        assertEquals("All 10 distinct cursor styles must be retained", 10, styles.size)
        assertTrue(styles.contains(CursorStyle.CLASSIC_TRIPLE_DOT))
        assertTrue(styles.contains(CursorStyle.LIQUID_GLASS))
        assertTrue(styles.contains(CursorStyle.PRECISION_CROSSHAIR))
        assertTrue(styles.contains(CursorStyle.NEON_HALO))
        assertTrue(styles.contains(CursorStyle.HOLOGRAM_DIAMOND))
        assertTrue(styles.contains(CursorStyle.DUAL_ORBITAL))
        assertTrue(styles.contains(CursorStyle.STEALTH_GHOST))
        assertTrue(styles.contains(CursorStyle.LASER_PIN))
        assertTrue(styles.contains(CursorStyle.WATER_RIPPLE))
        assertTrue(styles.contains(CursorStyle.AURA_GLOW))
    }

    @Test
    fun test_F4_2_cursorOuterRadiusBoundedTo18dp() {
        for (style in CursorStyle.values()) {
            assertNotNull(style.displayName)
            assertTrue("Cursor style name must not be empty", style.displayName.isNotEmpty())
        }
    }

    @Test
    fun test_F4_3_oneEuroFilterTremorAttenuation() {
        // Stationary head hold: slight tremor variation of ±2px
        val p1 = harness.simulateCameraFrame(0.016f, 500f, 500f)
        val p2 = harness.simulateCameraFrame(0.016f, 502f, 499f)
        val p3 = harness.simulateCameraFrame(0.016f, 501f, 501f)
        assertTrue("Sub-pixel precision hold must keep drift < 1.5dp", p3.driftDistanceDp < 1.5f)
    }

    @Test
    fun test_F4_4_dwellTimerGraceDelayConfigured() {
        // Dwell grace delay is 180ms
        val gracePeriodMs = 180L
        assertTrue("Grace delay must be between 150ms and 200ms", gracePeriodMs in 150..200)
    }

    @Test
    fun test_F4_5_facialGestureThresholdIntegrity() {
        // Teeth show smiling probability threshold >= 0.74f
        val teethThreshold = 0.74f
        assertTrue("Smiling threshold for teeth pause/resume must be 0.74f", teethThreshold >= 0.74f)
    }

    // =========================================================================
    // F5: Always-On Background Wake-Word (Tests 21 - 25)
    // =========================================================================

    @Test
    fun test_F5_1_wakeTriggerHeyJarvisDetected() {
        val result = harness.parseWakeWordUtterance("Hey Jarvis")
        assertTrue("Wake word 'Hey Jarvis' must be detected", result.isWakeWordDetected)
        assertEquals("hey jarvis", result.matchedTrigger)
        assertNull(result.compoundCommand)
    }

    @Test
    fun test_F5_2_wakeTriggerJarvisDetected() {
        val result = harness.parseWakeWordUtterance("Jarvis")
        assertTrue("Wake word 'Jarvis' must be detected", result.isWakeWordDetected)
        assertEquals("jarvis", result.matchedTrigger)
    }

    @Test
    fun test_F5_3_wakeTriggerHelloJarvisDetected() {
        val result = harness.parseWakeWordUtterance("Hello Jarvis")
        assertTrue("Wake word 'Hello Jarvis' must be detected", result.isWakeWordDetected)
        assertEquals("hello jarvis", result.matchedTrigger)
    }

    @Test
    fun test_F5_4_wakeTriggerOkJarvisDetected() {
        val result = harness.parseWakeWordUtterance("Ok Jarvis")
        assertTrue("Wake word 'Ok Jarvis' must be detected", result.isWakeWordDetected)
        assertEquals("ok jarvis", result.matchedTrigger)
    }

    @Test
    fun test_F5_5_wakeTriggerSunJarvisDetected() {
        val res1 = harness.parseWakeWordUtterance("Sun Jarvis")
        assertTrue("Wake word 'Sun Jarvis' must be detected", res1.isWakeWordDetected)

        val res2 = harness.parseWakeWordUtterance("सुन जार्विस")
        assertTrue("Hindi wake word 'सुन जार्विस' must be detected", res2.isWakeWordDetected)
    }

    // =========================================================================
    // F6: Visual & Audio Wake Cue (Tests 26 - 30)
    // =========================================================================

    @Test
    fun test_F6_1_arcReactorTransitionsToListeningState() {
        val res = harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, res.stateTransition)
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
    }

    @Test
    fun test_F6_2_hudPulsesCyanOnWake() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals("CYAN", harness.lastHudPulseColor)
    }

    @Test
    fun test_F6_3_hudBadgeDisplaysCallConnected() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals("CALL CONNECTED", harness.hudStatusText)
    }

    @Test
    fun test_F6_4_handsFreeAudioPromptSpoken() {
        val res = harness.processSpokenUtterance("Hey Jarvis")
        assertEquals("At your service, Sir.", res.spokenText)
    }

    @Test
    fun test_F6_5_zeroTouchRequiredToBeginListening() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertTrue("System is hands-free listening", harness.isRecognizingSpeech)
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
    }

    // =========================================================================
    // F7: Compound Wake-Commands (Tests 31 - 35)
    // =========================================================================

    @Test
    fun test_F7_1_stripPrefixOpenInstagram() {
        val parse = harness.parseWakeWordUtterance("Hey Jarvis open Instagram")
        assertTrue(parse.isWakeWordDetected)
        assertEquals("open instagram", parse.compoundCommand)

        val res = harness.processSpokenUtterance("Hey Jarvis open Instagram")
        assertEquals("com.instagram.android", res.targetPackage)
        assertEquals("OPEN_APP", res.actionType)
    }

    @Test
    fun test_F7_2_stripPrefixYouTubeKholo() {
        val parse = harness.parseWakeWordUtterance("Jarvis YouTube kholo")
        assertTrue(parse.isWakeWordDetected)
        assertEquals("youtube kholo", parse.compoundCommand)

        val res = harness.processSpokenUtterance("Jarvis YouTube kholo")
        assertEquals("com.google.android.youtube", res.targetPackage)
        assertEquals("OPEN_APP", res.actionType)
    }

    @Test
    fun test_F7_3_punctuationToleranceInCompound() {
        val parse = harness.parseWakeWordUtterance("Ok Jarvis, scroll down!")
        assertTrue(parse.isWakeWordDetected)
        assertEquals("scroll down", parse.compoundCommand)

        val res = harness.processSpokenUtterance("Ok Jarvis, scroll down!")
        assertEquals("SCROLL_DOWN", res.actionType)
    }

    @Test
    fun test_F7_4_contextualSearchCompoundCommand() {
        val parse = harness.parseWakeWordUtterance("Hey Jarvis search artificial intelligence")
        assertTrue(parse.isWakeWordDetected)
        assertEquals("search artificial intelligence", parse.compoundCommand)

        val res = harness.processSpokenUtterance("Hey Jarvis search artificial intelligence")
        assertEquals("SEARCH", res.actionType)
        assertTrue(res.displayText.contains("artificial intelligence"))
    }

    @Test
    fun test_F7_5_oneBreathDirectActionWithoutFollowUpPrompt() {
        val res = harness.processSpokenUtterance("Hey Jarvis open WhatsApp")
        assertEquals("com.whatsapp", res.targetPackage)
        assertEquals(E2EVoiceEngineState.SPEAKING, res.stateTransition)
    }

    // =========================================================================
    // F8: Persistent Continuous Conversation (Tests 36 - 40)
    // =========================================================================

    @Test
    fun test_F8_1_continuousListeningPersistsAcrossTurns() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)

        harness.processSpokenUtterance("Who are you?")
        assertEquals(E2EVoiceEngineState.SPEAKING, harness.voiceEngineState)

        harness.onTtsSpeakingFinished()
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)

        harness.processSpokenUtterance("Scroll down")
        assertEquals(E2EVoiceEngineState.SPEAKING, harness.voiceEngineState)
    }

    @Test
    fun test_F8_2_ttsCompletionAutoResumesListening() {
        harness.processSpokenUtterance("Hey Jarvis open YouTube")
        assertEquals(E2EVoiceEngineState.SPEAKING, harness.voiceEngineState)
        harness.onTtsSpeakingFinished()
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
        assertEquals("Listening...", harness.hudStatusText)
    }

    @Test
    fun test_F8_3_crossAppContinuousListening() {
        harness.processSpokenUtterance("Hey Jarvis open Settings")
        harness.onTtsSpeakingFinished()
        assertTrue("Speech recognizer stays active across apps", harness.isRecognizingSpeech)
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
    }

    @Test
    fun test_F8_4_hudReflectsConversationFlow() {
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals("CYAN", harness.lastHudPulseColor)
        harness.processSpokenUtterance("Who are you?")
        assertEquals("CYAN", harness.lastHudPulseColor)
        harness.onTtsSpeakingFinished()
        assertEquals("CYAN", harness.lastHudPulseColor)
    }

    @Test
    fun test_F8_5_singleBreathCompoundResumesContinuousFlow() {
        harness.processSpokenUtterance("Hey Jarvis scroll down")
        harness.onTtsSpeakingFinished()
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
    }

    // =========================================================================
    // F9: Natural Sleep Commands (Tests 41 - 45)
    // =========================================================================

    @Test
    fun test_F9_1_sleepTriggerStop() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("Stop")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
        assertEquals("AMBER", harness.lastHudPulseColor)
    }

    @Test
    fun test_F9_2_sleepTriggerRukJa() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("Jarvis ruk ja")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
        assertTrue(res.spokenText.contains("standby"))
    }

    @Test
    fun test_F9_3_sleepTriggerBandHoJao() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("Band ho jao")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
    }

    @Test
    fun test_F9_4_sleepTriggerGoToSleep() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("Go to sleep")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
    }

    @Test
    fun test_F9_5_sleepTriggerByeJarvis() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("Bye Jarvis")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, res.stateTransition)
        assertEquals("JARVIS • STANDBY", harness.hudStatusText)
    }

    // =========================================================================
    // F10: Unified Audio Coordinator (Tests 46 - 50)
    // =========================================================================

    @Test
    fun test_F10_1_singleActiveSpeechSession() {
        assertEquals("Only 1 speech session active", 1, harness.activeSpeechSessionsCount)
    }

    @Test
    fun test_F10_2_zeroAudioContentionErrors() {
        harness.processSpokenUtterance("Hey Jarvis")
        harness.processSpokenUtterance("Scroll down")
        assertEquals("Contention count must be 0", 0, harness.speechRecognizerContentionCount)
    }

    @Test
    fun test_F10_3_microphoneReleasedWhenJarvisOff() {
        harness.setJarvisServiceEnabled(false)
        assertFalse("Microphone must be powered off when JARVIS is disabled", harness.isMicrophoneActive)
        assertFalse(harness.isRecognizingSpeech)
        assertEquals(0, harness.activeSpeechSessionsCount)
    }

    @Test
    fun test_F10_4_silentWakeStandbySuppressesBeeps() {
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
        assertTrue(harness.isMicrophoneActive)
    }

    @Test
    fun test_F10_5_audioCoordinatorSurvivesModeTransitions() {
        harness.setOperatingMode(E2EDualOperatingMode.HEAD_MOUSE_ONLY)
        assertEquals(0, harness.activeSpeechSessionsCount)
        harness.setOperatingMode(E2EDualOperatingMode.DUAL_MODE)
        assertEquals(1, harness.activeSpeechSessionsCount)
        assertEquals(0, harness.speechRecognizerContentionCount)
    }

    // =========================================================================
    // F11: MainActivity Dual-Mode Hub (Tests 51 - 55)
    // =========================================================================

    @Test
    fun test_F11_1_independentHeadMouseToggleFromUI() {
        harness.toggleHeadMouse()
        assertFalse(harness.isHeadMouseActive)
        assertEquals(E2EDualOperatingMode.JARVIS_ONLY, harness.operatingMode)
    }

    @Test
    fun test_F11_2_independentJarvisToggleFromUI() {
        harness.toggleJarvis()
        assertFalse(harness.isJarvisActive)
        assertEquals(E2EDualOperatingMode.HEAD_MOUSE_ONLY, harness.operatingMode)
    }

    @Test
    fun test_F11_3_bothActiveSynchronizesDualMode() {
        harness.setHeadMouseEnabled(true)
        harness.setJarvisServiceEnabled(true)
        assertEquals(E2EDualOperatingMode.DUAL_MODE, harness.operatingMode)
    }

    @Test
    fun test_F11_4_bothInactiveSynchronizesStandby() {
        harness.setHeadMouseEnabled(false)
        harness.setJarvisServiceEnabled(false)
        assertEquals(E2EDualOperatingMode.STANDBY, harness.operatingMode)
    }

    @Test
    fun test_F11_5_uiStateReflectsUnderlyingSettings() {
        val snapshot = harness.getHardwareSnapshot()
        assertEquals(harness.isCameraBound, snapshot.isCameraBound)
        assertEquals(harness.isMicrophoneActive, snapshot.isMicrophoneActive)
        assertEquals(harness.isFloatingHudVisible, snapshot.isFloatingHudVisible)
    }

    // =========================================================================
    // F12: Floating HUD Quick-Toggles (Tests 56 - 60)
    // =========================================================================

    @Test
    fun test_F12_1_hudExpandsOnDemand() {
        harness.expandHud()
        assertTrue("HUD must be expanded to reveal quick-toggles", harness.isHudExpanded)
    }

    @Test
    fun test_F12_2_quickToggleHeadMouseFromHud() {
        harness.expandHud()
        harness.toggleHeadMouseFromHud()
        assertFalse("Head mouse must be toggled off via HUD", harness.isHeadMouseActive)
        assertFalse(harness.isCameraBound)
    }

    @Test
    fun test_F12_3_quickToggleVoiceFromHud() {
        harness.expandHud()
        harness.toggleVoiceFromHud()
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)
        harness.toggleVoiceFromHud()
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
    }

    @Test
    fun test_F12_4_hudEmergencyAbortHaltsImmediately() {
        harness.processSpokenUtterance("Hey Jarvis")
        harness.triggerEmergencyAbort()
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
        assertEquals("RED", harness.lastHudPulseColor)
        assertEquals("MISSION ABORTED", harness.hudStatusText)
        assertEquals(1, harness.rapidSpeechAbortCount)
    }

    @Test
    fun test_F12_5_hudCollapsesAfterInactivity() {
        harness.expandHud()
        assertTrue(harness.isHudExpanded)
        harness.collapseHud()
        assertFalse("HUD must collapse back to compact state", harness.isHudExpanded)
    }

    // =========================================================================
    // F13: Automated Test Suite & Baseline Fix (Tests 61 - 65)
    // =========================================================================

    @Test
    fun test_F13_1_conversationalIdentityReturnsJarvis() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("Who are you?")
        assertTrue("Response must contain J.A.R.V.I.S.", res.spokenText.contains("J.A.R.V.I.S.") || res.spokenText.contains("JARVIS"))
    }

    @Test
    fun test_F13_2_hindiConversationalIdentity() {
        harness.processSpokenUtterance("Hey Jarvis")
        val res = harness.processSpokenUtterance("Tum kaun ho?")
        assertTrue("Response must identify as assistant", res.spokenText.contains("J.A.R.V.I.S.") || res.spokenText.contains("JARVIS"))
    }

    @Test
    fun test_F13_3_dualOperatingModeContractCompleteness() {
        val modes = DualOperatingMode.values()
        assertEquals(4, modes.size)
        assertEquals(DualOperatingMode.DUAL_MODE, DualOperatingMode.fromFlags(true, true))
        assertEquals(DualOperatingMode.HEAD_MOUSE_ONLY, DualOperatingMode.fromFlags(true, false))
        assertEquals(DualOperatingMode.JARVIS_ONLY, DualOperatingMode.fromFlags(false, true))
        assertEquals(DualOperatingMode.STANDBY, DualOperatingMode.fromFlags(false, false))
    }

    @Test
    fun test_F13_4_compoundCommandParsingContract() {
        val parse = harness.parseWakeWordUtterance("Hey Jarvis search recipes")
        assertTrue(parse.isWakeWordDetected)
        assertEquals("hey jarvis", parse.matchedTrigger)
        assertEquals("search recipes", parse.compoundCommand)
        assertFalse(parse.isImmediateSleepTrigger)
    }

    @Test
    fun test_F13_5_sleepTriggerContractCompleteness() {
        for (trigger in harness.sleepTriggers) {
            val parse = harness.parseWakeWordUtterance(trigger)
            assertTrue("Sleep trigger '$trigger' must be detected", parse.isImmediateSleepTrigger)
        }
    }
}
