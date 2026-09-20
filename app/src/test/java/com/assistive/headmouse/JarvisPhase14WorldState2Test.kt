package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.autonomous.state.ScrollState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.VerificationResult
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ActionResult
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 14 — WorldState 2.0 unit tests.
 *
 * Covers:
 *  1. observationId uniqueness per snapshot
 *  2. screenId stability across observations (same app+activity)
 *  3. screenId differs when activity changes
 *  4. ScrollState captured and surfaced
 *  5. lastAction / lastActionResult / verificationResult default null
 *  6. withActionResult() attaches context without altering identity fields
 *  7. isSameScreen() logic
 *  8. isLastActionVerified convenience accessor
 *  9. hasActionContext convenience accessor
 * 10. lastActionSummary() formatting
 * 11. toCompressedSemanticIndex() includes observationId, screenId, scroll, action context
 * 12. Backwards-compatibility: existing callers with only foregroundPackage still compile
 * 13. hasStateChanged() still works as before
 * 14. VerificationResult carries observationId link
 */
class JarvisPhase14WorldState2Test {

    private fun baseState(
        pkg: String = "com.example.app",
        activity: String? = "com.example.app.MainActivity"
    ) = WorldState(foregroundPackage = pkg, foregroundActivity = activity)

    private fun makeToolCall(name: String = "tap_element") = ToolCall(
        name = name,
        arguments = mapOf("label" to "Submit")
    )

    private fun makeActionResult(success: Boolean = true) = ActionResult(
        success = success,
        tool = "tap_element",
        arguments = mapOf("label" to "Submit"),
        errorMessage = if (!success) "Node not found" else null
    )

    private fun makeVerification(obs: WorldState, success: Boolean = true) = VerificationResult(
        success = success,
        message = if (success) "Button tapped, dialog dismissed" else "Screen unchanged after tap",
        observationId = obs.observationId,
        confidence = if (success) 0.97f else 0.60f
    )

    @Test
    fun `observationId is unique per WorldState instance`() {
        val s1 = baseState()
        val s2 = baseState()
        assertNotEquals(
            "Two separate WorldState instances must have different observationIds",
            s1.observationId, s2.observationId
        )
    }

    @Test
    fun `screenId is identical for same package and activity`() {
        val s1 = baseState()
        val s2 = baseState()
        assertEquals(
            "Same package+activity must produce the same screenId",
            s1.screenId, s2.screenId
        )
    }

    @Test
    fun `screenId differs when activity changes`() {
        val home = baseState(activity = "com.example.app.MainActivity")
        val settings = baseState(activity = "com.example.app.SettingsActivity")
        assertNotEquals(
            "Different activities must produce different screenIds",
            home.screenId, settings.screenId
        )
    }

    @Test
    fun `ScrollState is captured and accessible`() {
        val scroll = ScrollState(isScrollable = true, scrollOffsetY = 320, totalItems = 50, firstVisibleItem = 5)
        val state = WorldState(
            foregroundPackage = "com.example",
            scrollState = scroll
        )
        assertNotNull(state.scrollState)
        assertEquals(320, state.scrollState!!.scrollOffsetY)
        assertEquals(50, state.scrollState!!.totalItems)
        assertEquals(5, state.scrollState!!.firstVisibleItem)
        assertTrue(state.scrollState!!.isScrollable)
    }

    @Test
    fun `action context fields are null by default`() {
        val state = baseState()
        assertNull("lastAction should default to null", state.lastAction)
        assertNull("lastActionResult should default to null", state.lastActionResult)
        assertNull("verificationResult should default to null", state.verificationResult)
        assertFalse(state.hasActionContext)
        assertFalse(state.isLastActionVerified)
        assertNull(state.lastActionSummary())
    }

    @Test
    fun `withActionResult attaches context preserving identity fields`() {
        val pre = baseState()
        val call = makeToolCall()
        val result = makeActionResult(success = true)
        val verification = makeVerification(pre, success = true)

        val post = pre.withActionResult(call, result, verification)

        assertEquals(pre.observationId, post.observationId)
        assertEquals(pre.screenId, post.screenId)
        assertEquals(pre.foregroundPackage, post.foregroundPackage)
        assertEquals(pre.timestamp, post.timestamp)

        assertEquals(call, post.lastAction)
        assertEquals(result, post.lastActionResult)
        assertEquals(verification, post.verificationResult)
        assertTrue(post.hasActionContext)
        assertTrue(post.isLastActionVerified)
    }

    @Test
    fun `isSameScreen returns true for same package and activity`() {
        val s1 = baseState()
        val s2 = baseState()
        assertTrue(s1.isSameScreen(s2))
    }

    @Test
    fun `isSameScreen returns false after navigation to different activity`() {
        val home = baseState(activity = "com.example.app.MainActivity")
        val settings = baseState(activity = "com.example.app.SettingsActivity")
        assertFalse(home.isSameScreen(settings))
    }

    @Test
    fun `isSameScreen returns false for null`() {
        assertFalse(baseState().isSameScreen(null))
    }

    @Test
    fun `isLastActionVerified false when verification failed`() {
        val pre = baseState()
        val post = pre.withActionResult(
            action = makeToolCall(),
            result = makeActionResult(success = false),
            verification = makeVerification(pre, success = false)
        )
        assertFalse(post.isLastActionVerified)
    }

    @Test
    fun `isLastActionVerified false when no verification attached`() {
        val pre = baseState()
        val post = pre.withActionResult(makeToolCall(), makeActionResult())
        assertFalse(post.isLastActionVerified)
    }

    @Test
    fun `lastActionSummary contains tool name and OK for success`() {
        val pre = baseState()
        val post = pre.withActionResult(
            action = makeToolCall("tap_element"),
            result = makeActionResult(success = true),
            verification = makeVerification(pre, success = true)
        )
        val summary = post.lastActionSummary()
        assertNotNull(summary)
        assertTrue("Summary should contain tool name", summary!!.contains("tap_element"))
        assertTrue("Summary should indicate OK", summary.contains("OK"))
        assertTrue("Summary should indicate verified=true", summary.contains("verified=true"))
    }

    @Test
    fun `lastActionSummary contains FAIL and error fragment`() {
        val pre = baseState()
        val post = pre.withActionResult(
            action = makeToolCall("tap_element"),
            result = makeActionResult(success = false),
            verification = makeVerification(pre, success = false)
        )
        val summary = post.lastActionSummary()
        assertNotNull(summary)
        assertTrue("Summary should indicate FAIL", summary!!.contains("FAIL"))
        assertTrue("Summary should contain error snippet", summary.contains("Node not found"))
        assertTrue("Summary should indicate verified=false", summary.contains("verified=false"))
    }

    @Test
    fun `toCompressedSemanticIndex includes observationId and screenId`() {
        val state = baseState()
        val index = state.toCompressedSemanticIndex()
        assertTrue(
            "Compressed index must include observationId",
            index.contains(state.observationId)
        )
        assertTrue(
            "Compressed index must include screenId",
            index.contains(state.screenId)
        )
    }

    @Test
    fun `toCompressedSemanticIndex includes scroll info when scrollable`() {
        val state = WorldState(
            foregroundPackage = "com.example",
            scrollState = ScrollState(isScrollable = true, scrollOffsetY = 200, totalItems = 30, firstVisibleItem = 3)
        )
        val index = state.toCompressedSemanticIndex()
        assertTrue("Should include scroll offset", index.contains("offsetY=200"))
        assertTrue("Should include item counts", index.contains("items=3/30"))
    }

    @Test
    fun `toCompressedSemanticIndex omits scroll section when not scrollable`() {
        val state = WorldState(
            foregroundPackage = "com.example",
            scrollState = ScrollState(isScrollable = false)
        )
        val index = state.toCompressedSemanticIndex()
        assertFalse("Non-scrollable should not emit SCROLL line", index.contains("SCROLL:"))
    }

    @Test
    fun `toCompressedSemanticIndex includes action context when present`() {
        val pre = baseState()
        val post = pre.withActionResult(makeToolCall("scroll"), makeActionResult())
        val index = post.toCompressedSemanticIndex()
        assertTrue("Index should include ACTION_CTX header", index.contains("ACTION_CTX:"))
        assertTrue("Index should include tool name in action context", index.contains("scroll"))
    }

    @Test
    fun `WorldState can be created with only foregroundPackage`() {
        val state = WorldState(foregroundPackage = "com.example")
        assertNotNull(state.observationId)
        assertNotNull(state.screenId)
        assertTrue(state.nodes.isEmpty())
        assertNull(state.scrollState)
        assertNull(state.lastAction)
    }

    @Test
    fun `hasStateChanged returns false for identical states`() {
        val nodes = listOf(
            SemanticNode(index = 1, text = "OK", isClickable = true, left = 0f, top = 0f, right = 100f, bottom = 50f)
        )
        val s1 = WorldState(foregroundPackage = "com.example", nodes = nodes)
        val s2 = WorldState(foregroundPackage = "com.example", nodes = nodes)
        assertFalse(
            "Identical structural states must not report change",
            s1.hasStateChanged(s2)
        )
    }

    @Test
    fun `hasStateChanged returns true when package changes`() {
        val s1 = WorldState(foregroundPackage = "com.a")
        val s2 = WorldState(foregroundPackage = "com.b")
        assertTrue(s1.hasStateChanged(s2))
    }

    @Test
    fun `VerificationResult observationId matches the WorldState it was based on`() {
        val obs = baseState()
        val verification = makeVerification(obs, success = true)
        assertEquals(
            "VerificationResult.observationId must reference the source observation",
            obs.observationId, verification.observationId
        )
    }
}
