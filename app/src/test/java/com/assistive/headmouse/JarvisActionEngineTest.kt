package com.assistive.headmouse

import android.graphics.RectF
import com.assistive.headmouse.agent.jarvis.AppLauncher
import com.assistive.headmouse.agent.jarvis.JarvisVoiceEngine
import com.assistive.headmouse.agent.jarvis.action.*
import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.preferences.AppSettings
import com.assistive.headmouse.tracking.model.HeadPoseData
import com.assistive.headmouse.ui.calibration.CalibrationManager
import org.junit.Assert.*
import org.junit.Test

class JarvisActionEngineTest {

    @Test
    fun testTargetResolverPriorityOrder() {
        val node1 = ScreenNode(
            id = "node1",
            left = 100f, top = 200f, right = 300f, bottom = 250f,
            text = "Search YouTube",
            contentDescription = "Search button",
            viewIdResourceName = "com.google.android.youtube:id/search_icon"
        )
        val node2 = ScreenNode(
            id = "node2",
            left = 400f, top = 200f, right = 600f, bottom = 250f,
            text = null,
            contentDescription = "Voice Search",
            viewIdResourceName = "com.google.android.youtube:id/btn_mic"
        )

        // Priority 1: Accessibility Text Match
        val resText = TargetResolver.resolveTarget(
            ActionTarget(TargetType.TEXT, "Search YouTube"),
            listOf(node1, node2)
        )
        assertNotNull("Should resolve via accessibility text", resText)
        assertEquals("ACCESSIBILITY_TEXT", resText!!.matchType)
        assertEquals(200f, resText.x, 0.1f)
        assertEquals(225f, resText.y, 0.1f)

        // Priority 2: Content Description Match
        val resDesc = TargetResolver.resolveTarget(
            ActionTarget(TargetType.CONTENT_DESCRIPTION, "Voice Search"),
            listOf(node1, node2)
        )
        assertNotNull("Should resolve via content description", resDesc)
        assertEquals("CONTENT_DESCRIPTION", resDesc!!.matchType)
        assertEquals(500f, resDesc.x, 0.1f)

        // Priority 3: Resource ID Match
        val resId = TargetResolver.resolveTarget(
            ActionTarget(TargetType.RESOURCE_ID, "btn_mic"),
            listOf(node1, node2)
        )
        assertNotNull("Should resolve via resource id", resId)
        assertEquals("RESOURCE_ID", resId!!.matchType)

        // Priority 5: Fuzzy Text Similarity
        val resFuzzy = TargetResolver.resolveTarget(
            ActionTarget(TargetType.TEXT, "Seurch YouTube"), // Typo (e instead of a), not a substring
            listOf(node1, node2)
        )
        assertNotNull("Should resolve via text similarity", resFuzzy)
        assertEquals("TEXT_SIMILARITY", resFuzzy!!.matchType)

        // Priority 7: Vision Fallback
        val resVision = TargetResolver.resolveTarget(
            ActionTarget(TargetType.TEXT, "NonExistentElement"),
            listOf(node1, node2),
            visionCoordinates = Pair(540f, 960f)
        )
        assertNotNull("Should resolve via vision fallback", resVision)
        assertEquals("VISION_FALLBACK", resVision!!.matchType)
        assertEquals(540f, resVision.x, 0.1f)
        assertEquals(960f, resVision.y, 0.1f)
    }

    @Test
    fun testSpeechSanitization() {
        // Verify sanitization static logic
        val rawSpeech = "**Hello Sir!** [ACTION: OPEN_APP:com.google.android.youtube] # Status: ```json {\"step\": 1} ``` Opening YouTube."
        val sanitized = rawSpeech
            .replace(Regex("""\[ACTION:[^\]]+\]"""), "")
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""\{[\s\S]*?\}"""), "")
            .replace(Regex("""[*#_~`>|\\]"""), "")
            .replace(Regex("""[\[\](){}<>]"""), "")
            .replace("\"", "")
            .replace("'", "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        assertFalse("Sanitized speech should not contain asterisks", sanitized.contains("*"))
        assertFalse("Sanitized speech should not contain action tags", sanitized.contains("[ACTION:"))
        assertFalse("Sanitized speech should not contain JSON blocks", sanitized.contains("json"))
        assertFalse("Sanitized speech should not contain curly braces", sanitized.contains("{"))
        assertTrue("Sanitized speech should contain plain message", sanitized.contains("Hello Sir!"))
        assertTrue("Sanitized speech should contain Opening YouTube", sanitized.contains("Opening YouTube."))
    }

    @Test
    fun testActionProtocolAndContextLifecycle() {
        val step1 = ActionStep(
            id = 1,
            action = AutonomousActionType.OPEN_APP,
            target = ActionTarget(TargetType.TEXT, "YouTube"),
            waitAfterMs = 1200L
        )
        val step2 = ActionStep(
            id = 2,
            action = AutonomousActionType.TAP,
            target = ActionTarget(TargetType.CONTENT_DESCRIPTION, "Search"),
            waitAfterMs = 600L
        )

        val plan = ActionPlan(
            taskId = "task_test_101",
            taskGoal = "Open YouTube and Search",
            steps = listOf(step1, step2)
        )

        val context = TaskContext(
            taskId = plan.taskId,
            originalCommand = plan.taskGoal,
            totalSteps = plan.steps.size,
            steps = plan.steps.toMutableList()
        )

        assertFalse("Context should not be complete initially", context.isComplete())
        assertEquals(1, context.currentStep()?.id)

        // Advance step 1
        context.completedSteps.add(context.currentStep()!!)
        context.currentStepIndex++

        assertFalse("Context should still have step 2 remaining", context.isComplete())
        assertEquals(2, context.currentStep()?.id)

        // Advance step 2
        context.completedSteps.add(context.currentStep()!!)
        context.currentStepIndex++

        assertTrue("Context should now be complete", context.isComplete())
        assertEquals(2, context.completedSteps.size)
    }

    @Test
    fun testActionResultContract() {
        val result = ActionResult(
            success = true,
            action = "TAP",
            target = "Search",
            reason = "VERIFIED_ON_SCREEN",
            verified = true,
            retryable = false,
            durationMs = 320L
        )

        assertTrue(result.success)
        assertTrue(result.verified)
        assertEquals("TAP", result.action)
        assertEquals("Search", result.target)
        assertEquals(320L, result.durationMs)
    }
}
