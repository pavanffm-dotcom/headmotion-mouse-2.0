package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.action.ActionResult
import com.assistive.headmouse.agent.jarvis.autonomous.ReplanningEngine
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationResult
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PHASE 11 VERIFICATION SUITE — REAL REPLANNING ENGINE
 *
 * Validates:
 * 1. Blocking popup/dialog recovery (dismiss via button or Cancel).
 * 2. Soft keyboard obstruction recovery (press BACK).
 * 3. Loading state / spinner recovery (WAIT 2000ms).
 * 4. Unexpected launcher drop recovery (RECENTS / LAUNCH_APP).
 * 5. Target not found recovery (SCROLL DOWN, SCROLL UP, BACK).
 * 6. Action failure / TYPE_TEXT without focus recovery (focus editable node).
 * 7. Timeout / unsettled UI recovery (WAIT 1500ms).
 * 8. Consecutive UNCHANGED threshold recovery (SCROLL or BACK).
 * 9. Transient error / retry button recovery (TAP retry).
 * 10. Infinite recovery loop prevention (returns null after max attempts).
 * 11. Backward compatibility with Phase 5/6 4-parameter overload.
 */
class JarvisPhase11ReplanningEngineTest {

    private lateinit var replanningEngine: ReplanningEngine

    @Before
    fun setUp() {
        replanningEngine = ReplanningEngine()
    }

    // ========================================================================
    // 1. POPUP / DIALOG RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_dialogBlocking_dismissesWithCancel() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube",
            isDialogBlocking = true,
            nodes = listOf(
                SemanticNode(1, text = "Rate this app", isClickable = false),
                SemanticNode(2, text = "Cancel", isClickable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Shorts"))
        val verification = VerificationResult(
            state = VerificationState.UNCHANGED,
            stateChanged = false,
            explanation = "Screen unchanged after tap."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull("Recovery action must be generated for blocking dialog", recovery)
        assertEquals(CanonicalTools.TAP_ELEMENT, recovery?.name)
        assertEquals("Cancel", recovery?.arguments?.get("label"))
        assertTrue(recovery?.thought?.contains("popup", ignoreCase = true) == true)
    }

    @Test
    fun testReplanning_dialogWithNotNowButton_dismissesWithNotNow() {
        val world = WorldState(
            foregroundPackage = "com.android.vending",
            nodes = listOf(
                SemanticNode(1, text = "Update available"),
                SemanticNode(2, text = "Not now", isClickable = true),
                SemanticNode(3, text = "Update", isClickable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Search"))
        val verification = VerificationResult(
            state = VerificationState.UNCHANGED,
            stateChanged = false,
            explanation = "Screen unchanged."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.TAP_ELEMENT, recovery?.name)
        assertEquals("Not now", recovery?.arguments?.get("label"))
    }

    // ========================================================================
    // 2. KEYBOARD OBSTRUCTION RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_keyboardObstructing_pressesBack() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube",
            isKeyboardVisible = true,
            nodes = listOf(
                SemanticNode(1, text = "Search query", isEditable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Video result"))
        val verification = VerificationResult(
            state = VerificationState.UNCHANGED,
            stateChanged = false,
            explanation = "Element obscured."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.PRESS_NAVIGATION, recovery?.name)
        assertEquals("BACK", recovery?.arguments?.get("action"))
        assertTrue(recovery?.thought?.contains("keyboard", ignoreCase = true) == true)
    }

    // ========================================================================
    // 3. LOADING STATE RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_loadingIndicatorPresent_waitsToSettle() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube",
            isLoadingIndicatorPresent = true
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Play"))
        val verification = VerificationResult(
            state = VerificationState.PARTIAL,
            stateChanged = false,
            explanation = "Screen is loading."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.WAIT, recovery?.name)
        assertEquals(2000, recovery?.arguments?.get("duration_ms"))
    }

    // ========================================================================
    // 4. UNEXPECTED SCREEN / LAUNCHER DROP RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_unexpectedLauncher_switchesViaRecents() {
        val world = WorldState(
            foregroundPackage = "com.google.android.apps.nexuslauncher"
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Settings"))
        val verification = VerificationResult(
            state = VerificationState.REPLAN_REQUIRED,
            stateChanged = true,
            explanation = "Unexpected navigation to launcher."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.PRESS_NAVIGATION, recovery?.name)
        assertEquals("RECENTS", recovery?.arguments?.get("action"))
    }

    @Test
    fun testReplanning_unexpectedLauncher_relaunchApp_ifRecentsAlreadyTried() {
        val currentWorld = WorldState(
            foregroundPackage = "com.google.android.apps.nexuslauncher"
        )
        val prevWorld = WorldState(
            foregroundPackage = "com.google.android.youtube"
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Library"))
        val verification = VerificationResult(
            state = VerificationState.REPLAN_REQUIRED,
            stateChanged = true,
            explanation = "Unexpected navigation."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = currentWorld,
            previousWorld = prevWorld,
            recentHistory = listOf("PRESS_NAVIGATION on 'RECENTS'")
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.LAUNCH_APP, recovery?.name)
        assertEquals("com.google.android.youtube", recovery?.arguments?.get("package_or_name"))
    }

    // ========================================================================
    // 5. TARGET NOT FOUND / VIEWPORT SCROLL RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_targetNotFound_scrollable_scrollsDown() {
        val world = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(1, text = "Network & internet", isClickable = true),
                SemanticNode(2, text = "Connected devices", isClickable = true),
                SemanticNode(3, text = "List", isScrollable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "About phone"))
        val verification = VerificationResult(
            state = VerificationState.UNCHANGED,
            stateChanged = false,
            explanation = "Target 'About phone' not found in viewport."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.SCROLL, recovery?.name)
        assertEquals("DOWN", recovery?.arguments?.get("direction"))
    }

    @Test
    fun testReplanning_targetNotFound_scrollable_alreadyScrolledDown_scrollsUp() {
        val world = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(1, text = "System", isClickable = true),
                SemanticNode(2, text = "List", isScrollable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Wi-Fi"))
        val verification = VerificationResult(
            state = VerificationState.UNCHANGED,
            stateChanged = false,
            explanation = "Target not found."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world,
            recentHistory = listOf("SCROLL on 'DOWN'")
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.SCROLL, recovery?.name)
        assertEquals("UP", recovery?.arguments?.get("direction"))
    }

    @Test
    fun testReplanning_targetNotFound_notScrollable_stepsBack() {
        val world = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(1, text = "Display settings", isClickable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "NonExistent"))
        val verification = VerificationResult(
            state = VerificationState.UNCHANGED,
            stateChanged = false,
            explanation = "Target not found."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.PRESS_NAVIGATION, recovery?.name)
        assertEquals("BACK", recovery?.arguments?.get("action"))
    }

    // ========================================================================
    // 6. ACTION FAILURE / UNEXPECTED INPUT FIELD RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_typeTextUnfocusedField_tapsToFocusField() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(
                SemanticNode(1, text = "Search YouTube", isEditable = true, isFocused = false)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TYPE_TEXT, arguments = mapOf("text" to "lofi hip hop"))
        val verification = VerificationResult(
            state = VerificationState.FAILED,
            stateChanged = false,
            explanation = "Text 'lofi hip hop' not found in focused field after typing."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.TAP_ELEMENT, recovery?.name)
        assertEquals("Search YouTube", recovery?.arguments?.get("label"))
    }

    // ========================================================================
    // 7. TIMEOUT / UNSETTLED UI RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_timeout_waitsToSettle() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube"
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Play"))
        val actionResult = ActionResult(
            success = false,
            action = "TAP_ELEMENT",
            reason = "Operation timeout waiting for UI"
        )
        val verification = VerificationResult(
            state = VerificationState.UNKNOWN,
            stateChanged = false,
            explanation = "Operation timeout."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world,
            actionResult = actionResult
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.WAIT, recovery?.name)
        assertEquals(1500, recovery?.arguments?.get("duration_ms"))
    }

    // ========================================================================
    // 8. 3x CONSECUTIVE UNCHANGED THRESHOLD RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_3xConsecutiveUnchanged_scrollsDown() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(
                SemanticNode(1, isScrollable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Video"))
        val verification = VerificationResult(
            state = VerificationState.UNCHANGED,
            stateChanged = false,
            explanation = "Screen unchanged."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world,
            consecutiveUnchanged = 3
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.SCROLL, recovery?.name)
        assertEquals("DOWN", recovery?.arguments?.get("direction"))
    }

    // ========================================================================
    // 9. TRANSIENT ERROR / RETRY BUTTON RECOVERY TESTS
    // ========================================================================

    @Test
    fun testReplanning_retryButtonPresent_tapsRetry() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(
                SemanticNode(1, text = "Something went wrong"),
                SemanticNode(2, text = "Retry", isClickable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Video"))
        val verification = VerificationResult(
            state = VerificationState.FAILED,
            stateChanged = false,
            explanation = "Playback failed."
        )

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world
        )

        assertNotNull(recovery)
        assertEquals(CanonicalTools.TAP_ELEMENT, recovery?.name)
        assertEquals("Retry", recovery?.arguments?.get("label"))
    }

    // ========================================================================
    // 10. INFINITE RECOVERY LOOP PREVENTION TESTS
    // ========================================================================

    @Test
    fun testReplanning_maxRecoveryAttemptsExceeded_escalatesToModelClient() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube",
            isDialogBlocking = true,
            nodes = listOf(
                SemanticNode(1, text = "Cancel", isClickable = true)
            )
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Play"))
        val verification = VerificationResult(
            state = VerificationState.UNCHANGED,
            stateChanged = false,
            explanation = "Dialog still blocking."
        )

        // Attempt 3 is the limit (MAX_RECOVERY_ATTEMPTS = 3)
        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationResult = verification,
            currentWorld = world,
            consecutiveRecoveryAttempts = 3
        )

        assertNull("Local recovery must escalate to ModelClient after reaching MAX_RECOVERY_ATTEMPTS", recovery)
    }

    // ========================================================================
    // 11. BACKWARD COMPATIBILITY TESTS
    // ========================================================================

    @Test
    fun testReplanning_backwardCompatible4ParamOverload_works() {
        val world = WorldState(
            foregroundPackage = "com.google.android.youtube",
            isKeyboardVisible = true
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Channel"))

        val recovery = replanningEngine.recoverFromState(
            failedTool = failedTool,
            verificationState = VerificationState.UNCHANGED,
            currentWorld = world,
            consecutiveUnchanged = 0
        )

        assertNotNull("Backward-compatible overload must produce recovery ToolCall", recovery)
        assertEquals(CanonicalTools.PRESS_NAVIGATION, recovery?.name)
        assertEquals("BACK", recovery?.arguments?.get("action"))
    }
}
