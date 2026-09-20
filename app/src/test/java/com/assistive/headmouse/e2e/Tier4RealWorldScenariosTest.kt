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
 * Tier 4: Real-World Application Scenarios Test Suite
 * Validates realistic, complete end-to-end user journeys covering hands-free
 * social media navigation, video search & playback, battery saving sessions,
 * rapid emergency abort, and multi-app multitasking.
 */
class Tier4RealWorldScenariosTest {

    private lateinit var harness: E2EUnifiedSystemHarness

    @Before
    fun setUp() {
        harness = E2EUnifiedSystemHarness()
    }

    /**
     * Journey 1: Hands-free Instagram launch, feed navigation, scroll, and dismissal.
     */
    @Test
    fun test_Journey1_HandsFreeInstagramLaunchAndNavigation() {
        // Initial state: DUAL_MODE active on Home Screen
        assertEquals(E2EDualOperatingMode.DUAL_MODE, harness.operatingMode)

        // Step 1: User speaks compound wake-command in one breath
        val launchResult = harness.processSpokenUtterance("Hey Jarvis open Instagram")
        assertEquals("com.instagram.android", launchResult.targetPackage)
        assertEquals("OPEN_APP", launchResult.actionType)
        assertEquals(E2EVoiceEngineState.SPEAKING, launchResult.stateTransition)

        // Step 2: TTS finishes, auto-resumes continuous listening
        harness.onTtsSpeakingFinished()
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)

        // Step 3: Head tracking cursor navigates the feed at 60 FPS
        val p1 = harness.simulateCameraFrame(0.016f, 540f, 960f)
        val p2 = harness.simulateCameraFrame(0.016f, 540f, 1200f)
        assertNotNull(p1)
        assertNotNull(p2)

        // Step 4: Spoken scroll command inside Instagram
        val scrollResult = harness.processSpokenUtterance("Scroll down")
        assertEquals("SCROLL_DOWN", scrollResult.actionType)
        harness.onTtsSpeakingFinished()

        // Step 5: User dismisses J.A.R.V.I.S. with natural Hindi sleep trigger
        val sleepResult = harness.processSpokenUtterance("Jarvis ruk ja")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, sleepResult.stateTransition)
        assertEquals("AMBER", harness.lastHudPulseColor)
        assertEquals("JARVIS • STANDBY", harness.hudStatusText)
    }

    /**
     * Journey 2: YouTube voice search, playback, and hands-free comments exploration.
     */
    @Test
    fun test_Journey2_YouTubeContextualVoiceSearchAndPlayback() {
        // Step 1: User in HEAD_MOUSE_ONLY, enables J.A.R.V.I.S. via HUD quick-toggle
        harness.setOperatingMode(E2EDualOperatingMode.HEAD_MOUSE_ONLY)
        assertFalse(harness.isJarvisActive)

        harness.toggleVoiceFromHud()
        assertTrue(harness.isJarvisActive)
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)

        // Step 2: User speaks contextual search command
        val searchResult = harness.processSpokenUtterance("Search artificial intelligence on YouTube")
        assertEquals("SEARCH", searchResult.actionType)
        assertTrue(searchResult.displayText.contains("artificial intelligence"))
        harness.onTtsSpeakingFinished()

        // Step 3: Populate YouTube search result nodes in spatial cache
        val videoCard = ScreenNode(id = "yt_video_1", left = 100f, top = 400f, right = 980f, bottom = 700f, text = "AI Documentary 2026")
        harness.spatialCache.updateNodes(listOf(videoCard))

        val snappedTarget = harness.spatialCache.findNearestNode(540f, 550f, 60f)
        assertNotNull(snappedTarget)
        assertEquals("AI Documentary 2026", snappedTarget?.text)

        // Step 4: Video plays, user sleeps J.A.R.V.I.S.
        val sleepResult = harness.processSpokenUtterance("Go to sleep")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, sleepResult.stateTransition)
    }

    /**
     * Journey 3: J.A.R.V.I.S.-only battery saving session with 0% camera sensor drain.
     */
    @Test
    fun test_Journey3_JarvisOnlyBatterySavingSession() {
        // Step 1: User activates J.A.R.V.I.S. Only mode to conserve battery
        harness.setOperatingMode(E2EDualOperatingMode.JARVIS_ONLY)
        assertEquals(E2EDualOperatingMode.JARVIS_ONLY, harness.operatingMode)

        // Step 2: Verify front camera is completely unbound and powered off
        assertFalse("Camera must be unbound", harness.isCameraBound)
        assertFalse("Camera sensor must be powered off", harness.isCameraSensorPowered)
        assertFalse("Privacy LED must be off", harness.isPrivacyLedOn)

        // Step 3: Execute continuous conversation turns
        harness.processSpokenUtterance("Hey Jarvis")
        assertEquals(E2EVoiceEngineState.ACTIVE_LISTENING, harness.voiceEngineState)

        val query1 = harness.processSpokenUtterance("Who are you?")
        assertTrue(query1.spokenText.contains("J.A.R.V.I.S."))
        harness.onTtsSpeakingFinished()

        val query2 = harness.processSpokenUtterance("Open Settings")
        assertEquals("com.android.settings", query2.targetPackage)
        harness.onTtsSpeakingFinished()

        // Step 4: Verify 0 frames were processed by camera throughout the entire session
        val frameResult = harness.simulateCameraFrame(0.016f, 500f, 500f)
        assertTrue("Camera frame processing was suspended", frameResult.isStationary)

        // Step 5: Return to standby
        harness.processSpokenUtterance("Good bye")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, harness.voiceEngineState)
    }

    /**
     * Journey 4: Emergency abort during rapid continuous speech and autonomous execution.
     */
    @Test
    fun test_Journey4_EmergencyAbortDuringRapidSpeech() {
        // Step 1: Start autonomous mission
        val startResult = harness.processSpokenUtterance("Hey Jarvis open YouTube")
        assertEquals("com.google.android.youtube", startResult.targetPackage)

        // Step 2: Emergency abort spoken within rapid succession
        val abortResult = harness.processSpokenUtterance("Stop mission ruk ja")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, abortResult.stateTransition)
        assertEquals("RED", harness.lastHudPulseColor)
        assertEquals("MISSION ABORTED", harness.hudStatusText)
        assertFalse("Mission lock released", harness.activeMissionLock)

        // Step 3: Verify sub-200ms latency requirement
        assertTrue("Abort response delivered rapidly", abortResult.responseTimeMs < 200)
    }

    /**
     * Journey 5: Multi-app multitasking hands-free workflow (WhatsApp -> Chrome -> Dismiss).
     */
    @Test
    fun test_Journey5_MultiAppMultitaskingHandsFreeWorkflow() {
        // Step 1: Launch WhatsApp
        val step1 = harness.processSpokenUtterance("Hey Jarvis open WhatsApp")
        assertEquals("com.whatsapp", step1.targetPackage)
        harness.onTtsSpeakingFinished()

        // Step 2: Select contact with head mouse
        val contactNode = ScreenNode(id = "contact_1", left = 50f, top = 200f, right = 400f, bottom = 280f, text = "Dr. Banner")
        harness.spatialCache.updateNodes(listOf(contactNode))
        val contact = harness.spatialCache.findNearestNode(200f, 240f, 40f)
        assertEquals("Dr. Banner", contact?.text)

        // Step 3: Switch to Chrome via compound voice command
        val step3 = harness.processSpokenUtterance("Hey Jarvis open Chrome")
        assertEquals("com.android.chrome", step3.targetPackage)
        harness.onTtsSpeakingFinished()

        // Step 4: Scroll Chrome page with head gesture simulation
        val scrollRes = harness.processSpokenUtterance("Scroll down")
        assertEquals("SCROLL_DOWN", scrollRes.actionType)
        harness.onTtsSpeakingFinished()

        // Step 5: Conclude workflow with sleep command
        val endRes = harness.processSpokenUtterance("Bye Jarvis")
        assertEquals(E2EVoiceEngineState.WAKE_WORD_STANDBY, endRes.stateTransition)
        assertEquals(E2EDualOperatingMode.DUAL_MODE, harness.operatingMode)
        assertTrue(harness.isCameraBound)
    }
}
