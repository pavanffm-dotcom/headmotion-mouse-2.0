package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.autonomous.AccessibilityEventBus
import com.assistive.headmouse.agent.jarvis.autonomous.ReplanningEngine
import com.assistive.headmouse.agent.jarvis.autonomous.ScreenDiffEngine
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationEngine
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 5: JarvisPerceptionSettlingTest
 *
 * 14 unit tests verifying:
 *  - AccessibilityEventBus signal dispatch
 *  - ScreenDiffEngine WorldState diff
 *  - VerificationEngine VerificationState correctness
 *  - Recovery strategy selection
 */
class JarvisPerceptionSettlingTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeNode(
        index: Int,
        text: String? = null,
        resourceId: String? = null,
        isClickable: Boolean = true,
        isFocused: Boolean = false,
        isScrollable: Boolean = false
    ) = SemanticNode(
        index = index,
        text = text,
        resourceId = resourceId,
        isClickable = isClickable,
        isFocused = isFocused,
        isScrollable = isScrollable
    )

    private fun makeWorldState(
        pkg: String = "com.example.app",
        nodes: List<SemanticNode> = emptyList(),
        isKeyboard: Boolean = false,
        isDialog: Boolean = false,
        isLoading: Boolean = false
    ) = WorldState(
        foregroundPackage = pkg,
        nodes = nodes,
        isKeyboardVisible = isKeyboard,
        isDialogBlocking = isDialog,
        isLoadingIndicatorPresent = isLoading
    )

    private val verifyEngine = VerificationEngine()

    // =========================================================================
    // Test 1: AccessibilityEventBus emits WINDOW_STATE_CHANGED signal
    // =========================================================================
    @Test
    fun test1_EventBus_EmitsWindowStateChangedSignal() = runBlocking {
        val signal = AccessibilityEventBus.AccessibilityEventSignal(
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            packageName = "com.example.app"
        )
        AccessibilityEventBus.emit(signal)
        val received = AccessibilityEventBus.events.first()

        assertNotNull("Event bus should emit signal", received)
        assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, received.eventType)
        assertTrue("Window transition flag should be true", received.isWindowTransition)
    }

    // =========================================================================
    // Test 2: AccessibilityEventBus isWindowTransition for WINDOWS_CHANGED
    // =========================================================================
    @Test
    fun test2_EventBus_WindowsChangedIsWindowTransition() {
        val signal = AccessibilityEventBus.AccessibilityEventSignal(
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            packageName = "com.example.app"
        )
        assertTrue("WINDOWS_CHANGED should be window transition", signal.isWindowTransition)
        assertFalse("WINDOWS_CHANGED should NOT be content change", signal.isContentChange)
    }

    // =========================================================================
    // Test 3: AccessibilityEventBus isContentChange for VIEW_SCROLLED
    // =========================================================================
    @Test
    fun test3_EventBus_ViewScrolledIsContentChange() {
        val signal = AccessibilityEventBus.AccessibilityEventSignal(
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED,
            packageName = "com.example.app"
        )
        assertTrue("VIEW_SCROLLED should be content change", signal.isContentChange)
        assertFalse("VIEW_SCROLLED should NOT be window transition", signal.isWindowTransition)
    }

    // =========================================================================
    // Test 4: ScreenDiffEngine detects package change between WorldStates
    // =========================================================================
    @Test
    fun test4_ScreenDiffEngine_DetectsPackageChange() {
        val pre = makeWorldState(pkg = "com.android.launcher")
        val post = makeWorldState(pkg = "com.google.android.youtube")
        val diff = ScreenDiffEngine.computeDiff(pre, post)
        assertTrue("Package change should be detected", diff.packageChanged)
        assertTrue("Diff should have meaningful change", diff.hasMeaningfulChange)
    }

    // =========================================================================
    // Test 5: ScreenDiffEngine detects node addition
    // =========================================================================
    @Test
    fun test5_ScreenDiffEngine_DetectsNodeAddition() {
        val pre = makeWorldState(nodes = emptyList())
        val post = makeWorldState(nodes = listOf(makeNode(1, text = "Search", resourceId = "search_btn")))
        val diff = ScreenDiffEngine.computeDiff(pre, post)
        assertTrue("Added nodes should be detected", diff.nodesAdded.isNotEmpty())
        assertTrue("Diff should have meaningful change", diff.hasMeaningfulChange)
    }

    // =========================================================================
    // Test 6: ScreenDiffEngine detects text change within same node
    // =========================================================================
    @Test
    fun test6_ScreenDiffEngine_DetectsTextChange() {
        val pre = makeWorldState(nodes = listOf(makeNode(1, text = "Loading", resourceId = "status")))
        val post = makeWorldState(nodes = listOf(makeNode(1, text = "Done", resourceId = "status")))
        val diff = ScreenDiffEngine.computeDiff(pre, post)
        assertTrue("Text change should be detected", diff.textChanged.isNotEmpty())
        assertEquals("Loading", diff.textChanged.first().first)
        assertEquals("Done", diff.textChanged.first().second)
    }

    // =========================================================================
    // Test 7: VerificationEngine returns SUCCESS when screen changes after tap
    // =========================================================================
    @Test
    fun test7_Verification_SuccessWhenScreenChangesAfterTap() {
        val pre = makeWorldState(pkg = "com.example.app", nodes = listOf(makeNode(1, text = "Open")))
        val post = makeWorldState(pkg = "com.example.detail") // package changed
        val toolCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Open"))
        val result = verifyEngine.verify(toolCall, pre, post)
        assertEquals(VerificationState.SUCCESS, result.state)
        assertTrue(result.verified)
    }

    // =========================================================================
    // Test 8: VerificationEngine returns UNCHANGED when pre/post hash identical
    // =========================================================================
    @Test
    fun test8_Verification_UnchangedWhenHashIdentical() {
        val nodes = listOf(makeNode(1, text = "Button"))
        val pre = makeWorldState(nodes = nodes)
        // Same package, same nodes -> same hash
        val post = WorldState(
            foregroundPackage = pre.foregroundPackage,
            nodes = nodes,
            screenHash = pre.screenHash,
            accessibilityHash = pre.accessibilityHash
        )
        val toolCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Button"))
        val result = verifyEngine.verify(toolCall, pre, post)
        assertEquals(VerificationState.UNCHANGED, result.state)
        assertFalse(result.verified)
        assertTrue(result.isRecoverable)
    }

    // =========================================================================
    // Test 9: VerificationEngine returns REPLAN_REQUIRED when wrong package
    // =========================================================================
    @Test
    fun test9_Verification_ReplanRequiredWhenWrongPackage() {
        val pre = makeWorldState(pkg = "com.android.launcher")
        // Expected to launch YouTube but got Chrome instead
        val post = makeWorldState(pkg = "com.android.chrome")
        val toolCall = ToolCall(name = CanonicalTools.LAUNCH_APP, arguments = mapOf("package_or_name" to "youtube"))
        val result = verifyEngine.verify(toolCall, pre, post)
        assertEquals(VerificationState.REPLAN_REQUIRED, result.state)
        assertFalse(result.verified)
        assertTrue(result.state.requiresReplan)
    }

    // =========================================================================
    // Test 10: VerificationEngine returns PARTIAL when screen changed but text not confirmed
    // =========================================================================
    @Test
    fun test10_Verification_PartialWhenScreenChangedButTextMissing() {
        val pre = makeWorldState(pkg = "com.example.app", nodes = emptyList(), isKeyboard = false)
        // Screen changed (keyboard appeared) but typed text not in nodes
        val post = makeWorldState(pkg = "com.example.app", nodes = emptyList(), isKeyboard = true)
        val toolCall = ToolCall(name = CanonicalTools.TYPE_TEXT, arguments = mapOf("text" to "hello world"))
        val result = verifyEngine.verify(toolCall, pre, post)
        assertEquals(VerificationState.PARTIAL, result.state)
    }

    // =========================================================================
    // Test 11: VerificationEngine returns FAILED on type_text with no text, no state change
    // =========================================================================
    @Test
    fun test11_Verification_FailedOnTypeTextNoChange() {
        val nodes = listOf(makeNode(1, text = "placeholder", resourceId = "input_field", isFocused = false))
        val pre = makeWorldState(nodes = nodes)
        val post = WorldState(
            foregroundPackage = pre.foregroundPackage,
            nodes = nodes,
            screenHash = pre.screenHash,
            accessibilityHash = pre.accessibilityHash
        )
        val toolCall = ToolCall(name = CanonicalTools.TYPE_TEXT, arguments = mapOf("text" to "hello"))
        val result = verifyEngine.verify(toolCall, pre, post)
        assertEquals(VerificationState.FAILED, result.state)
        assertFalse(result.verified)
    }

    // =========================================================================
    // Test 12: Recovery - 3x UNCHANGED triggers scroll strategy
    // =========================================================================
    @Test
    fun test12_Recovery_3xUnchangedTriggersScroll() {
        val engine = ReplanningEngine()
        val world = makeWorldState(
            pkg = "com.example.app",
            nodes = listOf(makeNode(1, text = "Item", isScrollable = true))
        )
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Missing Item"))
        val recovery = engine.recoverFromState(
            failedTool = failedTool,
            verificationState = VerificationState.UNCHANGED,
            currentWorld = world,
            consecutiveUnchanged = 3
        )
        assertNotNull("Recovery action should be provided", recovery)
        assertEquals("Should suggest scroll when scrollable", CanonicalTools.SCROLL, recovery?.name)
    }

    // =========================================================================
    // Test 13: Recovery - keyboard visible triggers BACK navigation
    // =========================================================================
    @Test
    fun test13_Recovery_KeyboardBlockingTriggersBack() {
        val engine = ReplanningEngine()
        val world = makeWorldState(pkg = "com.example.app", isKeyboard = true)
        val failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Button"))
        val recovery = engine.recoverFromState(
            failedTool = failedTool,
            verificationState = VerificationState.UNCHANGED,
            currentWorld = world,
            consecutiveUnchanged = 1
        )
        assertNotNull("Recovery should be suggested for keyboard obstruction", recovery)
        assertEquals("Should press BACK to hide keyboard", CanonicalTools.PRESS_NAVIGATION, recovery?.name)
        assertEquals("BACK", recovery?.arguments?.get("action"))
    }

    // =========================================================================
    // Test 14: VerificationEngine returns SUCCESS for FINISH_TASK
    // =========================================================================
    @Test
    fun test14_Verification_FinishTaskAlwaysSuccess() {
        val pre = makeWorldState()
        val post = makeWorldState()
        val toolCall = ToolCall(name = CanonicalTools.FINISH_TASK, arguments = mapOf("success" to true, "spoken_summary" to "Done."))
        val result = verifyEngine.verify(toolCall, pre, post)
        assertEquals(VerificationState.SUCCESS, result.state)
        assertTrue(result.verified)
    }
}
