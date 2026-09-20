package com.assistive.headmouse

import android.content.Context
import android.content.ContextWrapper
import com.assistive.headmouse.agent.jarvis.action.TargetQuery
import com.assistive.headmouse.agent.jarvis.action.TargetResolution
import com.assistive.headmouse.agent.jarvis.action.TargetResolver
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDispatcher
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolValidator
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JarvisToolSystemTest {

    private lateinit var context: Context
    private lateinit var toolDispatcher: ToolDispatcher
    private lateinit var verificationEngine: VerificationEngine

    @Before
    fun setUp() {
        context = object : ContextWrapper(null) {}
        // Provide null service for pure headless unit testing of dispatch, resolution, and validation logic
        toolDispatcher = ToolDispatcher(context, serviceProvider = { null })
        verificationEngine = VerificationEngine()
    }

    // =========================================================================
    // SECTION 1: Verification of All 12 Canonical Tools
    // =========================================================================

    @Test
    fun testTool1_ObserveScreen() = runBlocking {
        val state = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(index = 1, text = "Wi-Fi", left = 100f, top = 200f, right = 400f, bottom = 300f, isClickable = true)
            )
        )
        val call = ToolCall(name = CanonicalTools.OBSERVE_SCREEN, arguments = emptyMap())
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.OBSERVE_SCREEN, result.tool)
        assertNotNull(result.verification)
        assertNull(result.errorCode)
    }

    @Test
    fun testTool2_TapElement() = runBlocking {
        val state = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(index = 1, text = "Display", left = 100f, top = 400f, right = 500f, bottom = 500f, isClickable = true)
            )
        )
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Display"))
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.TAP_ELEMENT, result.tool)
        assertFalse("Execution success must not falsely claim verification before observe", result.verified)
    }

    @Test
    fun testTool3_TapCoordinates() = runBlocking {
        val state = WorldState(foregroundPackage = "com.android.vending")
        val call = ToolCall(name = CanonicalTools.TAP_COORDINATES, arguments = mapOf("x" to 540f, "y" to 960f))
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.TAP_COORDINATES, result.tool)
    }

    @Test
    fun testTool4_TypeText() = runBlocking {
        val state = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(
                SemanticNode(index = 1, text = "Search YouTube", isEditable = true, isClickable = true)
            )
        )
        val call = ToolCall(
            name = CanonicalTools.TYPE_TEXT,
            arguments = mapOf("text" to "Kotlin Coroutines", "node_index" to 1, "press_enter" to true)
        )
        val result = toolDispatcher.dispatch(call, state)

        assertEquals(CanonicalTools.TYPE_TEXT, result.tool)
        assertEquals("Kotlin Coroutines", result.arguments["text"])
    }

    @Test
    fun testTool5_Scroll() = runBlocking {
        val state = WorldState(foregroundPackage = "com.android.settings")
        val call = ToolCall(name = CanonicalTools.SCROLL, arguments = mapOf("direction" to "DOWN"))
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.SCROLL, result.tool)
        assertEquals("DOWN", result.arguments["direction"])
    }

    @Test
    fun testTool6_Swipe() = runBlocking {
        val state = WorldState(foregroundPackage = "com.instagram.android")
        val call = ToolCall(name = CanonicalTools.SWIPE, arguments = mapOf("direction" to "LEFT"))
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.SWIPE, result.tool)
        assertEquals("LEFT", result.arguments["direction"])
    }

    @Test
    fun testTool7_LongPress() = runBlocking {
        val state = WorldState(
            foregroundPackage = "com.android.launcher",
            nodes = listOf(
                SemanticNode(index = 1, text = "Chrome", left = 100f, top = 200f, right = 300f, bottom = 400f, isClickable = true)
            )
        )
        val call = ToolCall(name = CanonicalTools.LONG_PRESS, arguments = mapOf("label" to "Chrome"))
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.LONG_PRESS, result.tool)
    }

    @Test
    fun testTool8_PressNavigation() = runBlocking {
        val state = WorldState(foregroundPackage = "com.android.vending")
        for (action in listOf("BACK", "HOME", "RECENTS")) {
            val call = ToolCall(name = CanonicalTools.PRESS_NAVIGATION, arguments = mapOf("action" to action))
            val result = toolDispatcher.dispatch(call, state)

            assertTrue(result.success)
            assertEquals(CanonicalTools.PRESS_NAVIGATION, result.tool)
        }
    }

    @Test
    fun testTool9_LaunchApp() = runBlocking {
        val state = WorldState(foregroundPackage = "com.android.launcher")
        val call = ToolCall(name = CanonicalTools.LAUNCH_APP, arguments = mapOf("package_or_name" to "com.android.settings"))
        val result = toolDispatcher.dispatch(call, state)

        assertEquals(CanonicalTools.LAUNCH_APP, result.tool)
        assertEquals("com.android.settings", result.arguments["package_or_name"])
    }

    @Test
    fun testTool10_Wait() = runBlocking {
        val state = WorldState(foregroundPackage = "com.android.settings")
        val call = ToolCall(name = CanonicalTools.WAIT, arguments = mapOf("duration_ms" to 150))
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.WAIT, result.tool)
        assertTrue(result.verification?.contains("150ms") == true)
    }

    @Test
    fun testTool11_TakeScreenshot() = runBlocking {
        val state = WorldState(foregroundPackage = "com.android.settings")
        val call = ToolCall(name = CanonicalTools.TAKE_SCREENSHOT, arguments = emptyMap())
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.TAKE_SCREENSHOT, result.tool)
    }

    @Test
    fun testTool12_FinishTask() = runBlocking {
        val state = WorldState(foregroundPackage = "com.android.settings")
        val call = ToolCall(
            name = CanonicalTools.FINISH_TASK,
            arguments = mapOf("success" to true, "spoken_summary" to "Mission accomplished, Sir.")
        )
        val result = toolDispatcher.dispatch(call, state)

        assertTrue(result.success)
        assertEquals(CanonicalTools.FINISH_TASK, result.tool)
        assertEquals("Mission accomplished, Sir.", result.verification)
        assertFalse(result.recoverable)
    }

    // =========================================================================
    // SECTION 2: Edge Cases & Robustness
    // =========================================================================

    @Test
    fun testEdgeCase_MissingTarget() = runBlocking {
        val state = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(index = 1, text = "Display", left = 100f, top = 200f, right = 400f, bottom = 300f, isClickable = true)
            )
        )
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Battery"))
        val result = toolDispatcher.dispatch(call, state)

        assertFalse("Missing target must fail", result.success)
        assertEquals("NODE_NOT_FOUND", result.errorCode)
        assertTrue(result.recoverable)
    }

    @Test
    fun testEdgeCase_AmbiguousTarget() = runBlocking {
        // Two identical clickable "Delete" buttons on screen
        val state = WorldState(
            foregroundPackage = "com.example.app",
            nodes = listOf(
                SemanticNode(index = 1, text = "Delete", left = 100f, top = 200f, right = 300f, bottom = 300f, isClickable = true),
                SemanticNode(index = 2, text = "Delete", left = 100f, top = 400f, right = 300f, bottom = 500f, isClickable = true)
            )
        )
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Delete"))
        val result = toolDispatcher.dispatch(call, state)

        assertFalse("Ambiguous target must NOT be clicked blindly", result.success)
        assertEquals("AMBIGUOUS_TARGET", result.errorCode)
        assertTrue(result.recoverable)
        assertTrue(result.errorMessage?.contains("indices") == true)
    }

    @Test
    fun testEdgeCase_ScrollRequired() = runBlocking {
        // Screen height 2400. Element is off-screen below viewport at top=2800f
        val state = WorldState(
            foregroundPackage = "com.android.settings",
            screenshotHeight = 2400,
            nodes = listOf(
                SemanticNode(index = 1, text = "About Phone", left = 100f, top = 2800f, right = 600f, bottom = 2950f, isClickable = true)
            )
        )
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "About Phone"))
        val result = toolDispatcher.dispatch(call, state)

        assertFalse("Off-screen element cannot be directly tapped", result.success)
        assertEquals("SCROLL_REQUIRED", result.errorCode)
        assertTrue(result.errorMessage?.contains("DOWN") == true)
        assertTrue(result.recoverable)
    }

    @Test
    fun testEdgeCase_DisabledNode() = runBlocking {
        // Disabled button (e.g. greyed out Save button)
        val state = WorldState(
            foregroundPackage = "com.example.app",
            nodes = listOf(
                SemanticNode(index = 1, text = "Save", left = 100f, top = 200f, right = 400f, bottom = 300f, isClickable = true, isEnabled = false)
            )
        )
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Save"))
        val result = toolDispatcher.dispatch(call, state)

        assertFalse("Disabled element cannot be tapped", result.success)
        assertEquals("NODE_DISABLED", result.errorCode)
        assertFalse("Disabled node failure is non-recoverable until state changes", result.recoverable)
    }

    @Test
    fun testEdgeCase_KeyboardOccludedTarget() = runBlocking {
        // Keyboard is active, covering bottom 45% of screen. Target is at top=1900f
        val state = WorldState(
            foregroundPackage = "com.example.chat",
            screenshotHeight = 2400,
            isKeyboardVisible = true,
            nodes = listOf(
                SemanticNode(index = 1, text = "Send Message", left = 100f, top = 1900f, right = 400f, bottom = 2050f, isClickable = true, isFocused = false)
            )
        )
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Send Message"))
        val result = toolDispatcher.dispatch(call, state)

        assertFalse("Keyboard-occluded element cannot be tapped blindly", result.success)
        assertEquals("OCCLUDED_BY_KEYBOARD", result.errorCode)
        assertTrue(result.recoverable)
    }

    @Test
    fun testEdgeCase_PopupDialogTarget() = runBlocking {
        // A modal popup appears with "Allow" and "Don't allow"
        val state = WorldState(
            foregroundPackage = "com.google.android.permissioncontroller",
            isDialogBlocking = true,
            nodes = listOf(
                SemanticNode(index = 1, text = "Allow this app to access photos?", left = 100f, top = 800f, right = 980f, bottom = 1000f),
                SemanticNode(index = 2, text = "Allow", left = 600f, top = 1200f, right = 900f, bottom = 1350f, isClickable = true),
                SemanticNode(index = 3, text = "Don't allow", left = 200f, top = 1200f, right = 500f, bottom = 1350f, isClickable = true)
            )
        )
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Allow"))
        val result = toolDispatcher.dispatch(call, state)

        assertTrue("Target on popup dialog resolves and executes cleanly", result.success)
        assertEquals(CanonicalTools.TAP_ELEMENT, result.tool)
    }

    @Test
    fun testEdgeCase_ValidationRejectsInvalidArguments() = runBlocking {
        // Missing required argument for LAUNCH_APP
        val badCall1 = ToolCall(name = CanonicalTools.LAUNCH_APP, arguments = emptyMap())
        val res1 = toolDispatcher.dispatch(badCall1, null)
        assertFalse(res1.success)
        assertEquals("MISSING_ARGUMENT", res1.errorCode)

        // Invalid direction enum for SCROLL
        val badCall2 = ToolCall(name = CanonicalTools.SCROLL, arguments = mapOf("direction" to "SIDEWAYS"))
        val res2 = toolDispatcher.dispatch(badCall2, null)
        assertFalse(res2.success)
        assertEquals("INVALID_ARGUMENT", res2.errorCode)

        // Negative coordinate for TAP_COORDINATES
        val badCall3 = ToolCall(name = CanonicalTools.TAP_COORDINATES, arguments = mapOf("x" to -50f, "y" to 100f))
        val res3 = toolDispatcher.dispatch(badCall3, null)
        assertFalse(res3.success)
        assertEquals("INVALID_ARGUMENT", res3.errorCode)
    }

    @Test
    fun testNoFalseSuccessInvariant() = runBlocking {
        // When an action is dispatched, execution success does NOT imply verification success
        val preState = WorldState(
            foregroundPackage = "com.example.app",
            nodes = listOf(
                SemanticNode(index = 1, text = "Unresponsive Button", left = 100f, top = 200f, right = 400f, bottom = 300f, isClickable = true)
            )
        )
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("node_index" to 1))
        val executionResult = toolDispatcher.dispatch(call, preState)

        // Step 1: Execution claims dispatch success
        assertTrue("Dispatch itself succeeded", executionResult.success)
        assertFalse("Execution result MUST NOT claim verified state change on its own", executionResult.verified)

        // Step 2: Post-state is identical (frozen UI / dead click)
        val postStateIdentical = preState.copy()
        val verification = verificationEngine.verify(call, preState, postStateIdentical)

        // Invariant: VerificationEngine refuses false success!
        assertFalse("Unchanged screen state must NOT be verified as success", verification.verified)
        assertFalse("stateChanged must be false when screen hash is unchanged", verification.stateChanged)
        assertTrue(verification.explanation.contains("unchanged"))
    }
}
