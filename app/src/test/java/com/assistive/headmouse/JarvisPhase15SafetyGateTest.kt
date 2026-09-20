package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.action.ActionStep
import com.assistive.headmouse.agent.jarvis.action.ActionTarget
import com.assistive.headmouse.agent.jarvis.action.AutonomousActionType
import com.assistive.headmouse.agent.jarvis.action.TargetType
import com.assistive.headmouse.agent.jarvis.autonomous.ActionRisk
import com.assistive.headmouse.agent.jarvis.autonomous.SafetyGate
import com.assistive.headmouse.agent.jarvis.autonomous.SafetyLevel
import com.assistive.headmouse.agent.jarvis.autonomous.SafetyPolicy
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 15 — J.A.R.V.I.S. Action Safety Gate Unit Tests.
 *
 * Verifies:
 *  1. LOW risk classification (tap, scroll, wait, navigation, open app, web search)
 *  2. MEDIUM risk classification (type text, send ordinary content, change settings)
 *  3. HIGH risk classification (purchase, delete, uninstall, submit forms, sensitive info, irreversible)
 *  4. Anti-bypass protection (model injected arguments cannot bypass safety)
 *  5. Malformed/unknown tool calls fail-safe to HIGH risk
 *  6. Confirmation required for HIGH risk actions
 *  7. Confirmation granted allows execution
 *  8. Confirmation denied blocks execution
 *  9. Auto-block when no confirmation listener attached
 * 10. Autonomous execution of LOW risk actions without human confirmation
 * 11. Configurable safety policies (medium-risk confirmation, custom keywords)
 * 12. Backward compatibility with ActionStep callers
 */
class JarvisPhase15SafetyGateTest {

    private lateinit var safetyGate: SafetyGate

    @Before
    fun setUp() {
        safetyGate = SafetyGate(isEnabled = true)
    }

    // =========================================================================
    // 1. LOW RISK Classification Tests
    // =========================================================================

    @Test
    fun `tap on benign element is classified as LOW risk`() {
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Home"))
        assertEquals(ActionRisk.LOW, safetyGate.classifyRisk(call))
    }

    @Test
    fun `tap coordinates is classified as LOW risk`() {
        val call = ToolCall(name = CanonicalTools.TAP_COORDINATES, arguments = mapOf("x" to 500f, "y" to 800f))
        assertEquals(ActionRisk.LOW, safetyGate.classifyRisk(call))
    }

    @Test
    fun `scroll is classified as LOW risk`() {
        val call = ToolCall(name = CanonicalTools.SCROLL, arguments = mapOf("direction" to "DOWN"))
        assertEquals(ActionRisk.LOW, safetyGate.classifyRisk(call))
    }

    @Test
    fun `wait is classified as LOW risk`() {
        val call = ToolCall(name = CanonicalTools.WAIT, arguments = mapOf("duration_ms" to 1000))
        assertEquals(ActionRisk.LOW, safetyGate.classifyRisk(call))
    }

    @Test
    fun `press navigation is classified as LOW risk`() {
        val backCall = ToolCall(name = CanonicalTools.PRESS_NAVIGATION, arguments = mapOf("action" to "BACK"))
        val homeCall = ToolCall(name = CanonicalTools.PRESS_NAVIGATION, arguments = mapOf("action" to "HOME"))
        assertEquals(ActionRisk.LOW, safetyGate.classifyRisk(backCall))
        assertEquals(ActionRisk.LOW, safetyGate.classifyRisk(homeCall))
    }

    @Test
    fun `launch app is classified as LOW risk`() {
        val call = ToolCall(name = CanonicalTools.LAUNCH_APP, arguments = mapOf("package_or_name" to "com.google.android.youtube"))
        assertEquals(ActionRisk.LOW, safetyGate.classifyRisk(call))
    }

    @Test
    fun `web search is classified as LOW risk`() {
        val call = ToolCall(name = CanonicalTools.WEB_SEARCH, arguments = mapOf("query" to "Kotlin Coroutines tutorial"))
        assertEquals(ActionRisk.LOW, safetyGate.classifyRisk(call))
    }

    // =========================================================================
    // 2. MEDIUM RISK Classification Tests
    // =========================================================================

    @Test
    fun `type ordinary text is classified as MEDIUM risk`() {
        val call = ToolCall(name = CanonicalTools.TYPE_TEXT, arguments = mapOf("text" to "Hello world"))
        assertEquals(ActionRisk.MEDIUM, safetyGate.classifyRisk(call))
    }

    @Test
    fun `tap on settings item is classified as MEDIUM risk`() {
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Network Settings"))
        assertEquals(ActionRisk.MEDIUM, safetyGate.classifyRisk(call))
    }

    @Test
    fun `long press is classified as MEDIUM risk`() {
        val call = ToolCall(name = CanonicalTools.LONG_PRESS, arguments = mapOf("label" to "Photo Item"))
        assertEquals(ActionRisk.MEDIUM, safetyGate.classifyRisk(call))
    }

    // =========================================================================
    // 3. HIGH RISK Classification Tests
    // =========================================================================

    @Test
    fun `purchase action is classified as HIGH risk`() {
        val buyCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Buy Now"))
        val checkoutCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Proceed to Checkout"))
        val payCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Pay $19.99"))

        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(buyCall))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(checkoutCall))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(payCall))
    }

    @Test
    fun `delete action is classified as HIGH risk`() {
        val deleteCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Delete Message"))
        val eraseCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Erase All Contacts"))

        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(deleteCall))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(eraseCall))
    }

    @Test
    fun `uninstall action is classified as HIGH risk`() {
        val uninstallCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Uninstall App"))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(uninstallCall))
    }

    @Test
    fun `submit important form is classified as HIGH risk`() {
        val transferCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Transfer Funds"))
        val confirmOrderCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Confirm Order"))

        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(transferCall))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(confirmOrderCall))
    }

    @Test
    fun `send sensitive information is classified as HIGH risk`() {
        val otpCall = ToolCall(name = CanonicalTools.TYPE_TEXT, arguments = mapOf("text" to "Your OTP is 482910"))
        val passwordCall = ToolCall(name = CanonicalTools.TYPE_TEXT, arguments = mapOf("text" to "mySecretPassword123"))
        val cardCall = ToolCall(name = CanonicalTools.TYPE_TEXT, arguments = mapOf("text" to "Credit Card Number 4111222233334444"))

        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(otpCall))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(passwordCall))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(cardCall))
    }

    @Test
    fun `irreversible action is classified as HIGH risk`() {
        val factoryReset = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Factory Reset"))
        val wipe = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Wipe All Data"))

        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(factoryReset))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(wipe))
    }

    // =========================================================================
    // 4. Anti-Bypass & Malformed Input Tests
    // =========================================================================

    @Test
    fun `model injected bypass arguments cannot bypass safety classification`() {
        val sneakyCall = ToolCall(
            name = CanonicalTools.TAP_ELEMENT,
            arguments = mapOf(
                "label" to "Delete Account",
                "bypass_safety" to true,
                "is_safe" to true,
                "confirmed" to true
            )
        )
        // Must still be classified as HIGH risk regardless of injected parameters
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(sneakyCall))
    }

    @Test
    fun `unknown or malformed tool call fails-safe to HIGH risk`() {
        val unknownCall = ToolCall(
            name = "unknown_destructive_call",
            arguments = mapOf("action" to "do_something")
        )
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(unknownCall))
    }

    // =========================================================================
    // 5. Confirmation Workflow Tests
    // =========================================================================

    @Test
    fun `low risk action proceeds immediately without confirmation`() {
        var decisionResult: Boolean? = null
        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Next Page"))

        val safe = safetyGate.checkSafety(call) { approved ->
            decisionResult = approved
        }

        assertTrue("Low-risk action must proceed immediately", safe)
        assertEquals(true, decisionResult)
    }

    @Test
    fun `high risk action requires confirmation and allows execution when granted`() {
        var confirmationRequested = false
        var decisionResult: Boolean? = null

        safetyGate.onConfirmToolCall = { tool, risk, prompt, onDecision ->
            confirmationRequested = true
            assertEquals(ActionRisk.HIGH, risk)
            onDecision(true) // User grants confirmation
        }

        val deleteCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Delete Photo"))
        val safe = safetyGate.checkSafety(deleteCall) { approved ->
            decisionResult = approved
        }

        assertFalse("High-risk action must be intercepted for confirmation", safe)
        assertTrue("Confirmation callback must be triggered", confirmationRequested)
        assertEquals(true, decisionResult)
    }

    @Test
    fun `high risk action is blocked when user denies confirmation`() {
        var decisionResult: Boolean? = null

        safetyGate.onConfirmToolCall = { _, _, _, onDecision ->
            onDecision(false) // User denies confirmation
        }

        val buyCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Confirm Purchase"))
        val safe = safetyGate.checkSafety(buyCall) { approved ->
            decisionResult = approved
        }

        assertFalse("High-risk action must be intercepted", safe)
        assertEquals(false, decisionResult)
    }

    @Test
    fun `high risk action is blocked automatically when no listener is attached`() {
        var decisionResult: Boolean? = null
        safetyGate.onConfirmToolCall = null
        safetyGate.onRequestUserConfirmation = null

        val eraseCall = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Erase All Data"))
        val safe = safetyGate.checkSafety(eraseCall) { approved ->
            decisionResult = approved
        }

        assertFalse("High-risk action must be intercepted", safe)
        assertEquals("Action must be blocked when no listener attached", false, decisionResult)
    }

    // =========================================================================
    // 6. Configurable Policy Tests
    // =========================================================================

    @Test
    fun `custom high-risk keywords escalate risk`() {
        safetyGate.policy = SafetyPolicy(customHighRiskKeywords = listOf("confidential_project"))

        val call = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Open confidential_project"))
        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(call))
    }

    @Test
    fun `medium risk action requires confirmation when policy is enabled`() {
        safetyGate.policy = SafetyPolicy(requireConfirmationForMediumRisk = true)
        var confirmed = false

        safetyGate.onConfirmToolCall = { _, risk, _, onDecision ->
            assertEquals(ActionRisk.MEDIUM, risk)
            confirmed = true
            onDecision(true)
        }

        val typeCall = ToolCall(name = CanonicalTools.TYPE_TEXT, arguments = mapOf("text" to "Ordinary message"))
        val safe = safetyGate.checkSafety(typeCall) { }

        assertFalse(safe)
        assertTrue(confirmed)
    }

    // =========================================================================
    // 7. Backward Compatibility Tests (ActionStep)
    // =========================================================================

    @Test
    fun `ActionStep classification and checkSafety work seamlessly`() {
        val deleteStep = ActionStep(
            id = 1,
            action = AutonomousActionType.TAP,
            target = ActionTarget(TargetType.TEXT, "Delete Database"),
            text = null
        )

        assertEquals(ActionRisk.HIGH, safetyGate.classifyRisk(deleteStep))
        assertEquals(SafetyLevel.DESTRUCTIVE, safetyGate.classifySafety(deleteStep))

        var confirmationTriggered = false
        safetyGate.onRequestUserConfirmation = { _, _, onDecision ->
            confirmationTriggered = true
            onDecision(true)
        }

        val safe = safetyGate.checkSafety(deleteStep) { }
        assertFalse(safe)
        assertTrue(confirmationTriggered)
    }
}
