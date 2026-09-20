package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.autonomous.ContextCompressor
import com.assistive.headmouse.agent.jarvis.autonomous.DynamicPlanner
import com.assistive.headmouse.agent.jarvis.autonomous.ToolRegistry
import com.assistive.headmouse.agent.jarvis.autonomous.model.*
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.preferences.AiProvider
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 8 Verification Test Suite: Real Screen Vision / Multimodal Eyes Pipeline.
 * Validates model vision capability gating, multimodal payload generation for Gemini and OpenAI,
 * DynamicPlanner screenshot forwarding, and non-crash resilience.
 */
class JarvisPhase8VisionPipelineTest {

    // =========================================================================
    // 1. Model Vision Capability Gating Tests
    // =========================================================================

    @Test
    fun test_ModelVisionCapabilities_geminiAlwaysSupportsVision() {
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.GEMINI, "gemini-1.5-flash"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.GEMINI, "gemini-1.5-pro"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.GEMINI, "gemini-2.0-flash"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.GEMINI, "gemini-2.5-pro"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.GEMINI, ""))
    }

    @Test
    fun test_ModelVisionCapabilities_openAiVisionGating() {
        // Vision-capable OpenAI models
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "gpt-4o"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "gpt-4o-mini"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "gpt-4-turbo"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "o1-preview"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "o3-mini"))

        // Text-only legacy OpenAI models
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "gpt-3.5-turbo"))
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "text-davinci-003"))
    }

    @Test
    fun test_ModelVisionCapabilities_customOpenRouterGating() {
        // Text-only LLMs MUST be false to avoid HTTP 400 Bad Request
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "deepseek/deepseek-chat"))
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "deepseek/deepseek-r1"))
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "mistralai/mistral-7b-instruct"))

        // Vision-capable endpoints MUST be true
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "qwen/qwen-2-vl-72b-instruct"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "anthropic/claude-3.5-sonnet"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "openai/gpt-4o-2024-11-20"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "google/gemini-flash-1.5"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "mistralai/pixtral-12b"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "meta-llama/llama-3.2-11b-vision-instruct"))
    }

    // =========================================================================
    // 2. Google Gemini Multimodal Payload Tests
    // =========================================================================

    @Test
    fun test_GoogleGeminiClient_buildPayloadWithScreenshot() {
        val client = GoogleGeminiClient(apiKey = "test_gemini_key", modelName = "gemini-1.5-flash")
        val fakeBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"

        val request = ModelDecisionRequest(
            systemInstruction = "You are JARVIS",
            originalUserGoal = "Open Settings",
            compressedScreenIndex = "=== ACTIVE DISPLAY ===\nAPP: com.android.settings",
            screenshotBase64 = fakeBase64
        )

        val payloadJson = client.buildGeminiPayloadJson(request)

        // Must contain inline_data with image/jpeg and the Base64 bytes
        assertTrue("Payload must contain inline_data", payloadJson.contains("\"inline_data\""))
        assertTrue("Payload must declare image/jpeg MIME type", payloadJson.contains("\"image/jpeg\""))
        assertTrue("Payload must contain the exact base64 data", payloadJson.contains(fakeBase64))
        assertTrue("Payload must contain compressed prompt text", payloadJson.contains("Open Settings"))
        assertTrue("Payload must contain system instruction", payloadJson.contains("You are JARVIS"))
    }

    @Test
    fun test_GoogleGeminiClient_buildPayloadWithoutScreenshot() {
        val client = GoogleGeminiClient(apiKey = "test_gemini_key", modelName = "gemini-1.5-flash")

        val request = ModelDecisionRequest(
            systemInstruction = "You are JARVIS",
            originalUserGoal = "Open Settings",
            compressedScreenIndex = "=== ACTIVE DISPLAY ===\nAPP: com.android.settings",
            screenshotBase64 = null
        )

        val payloadJson = client.buildGeminiPayloadJson(request)

        assertFalse("Payload without screenshot must NOT contain inline_data", payloadJson.contains("\"inline_data\""))
        assertTrue("Payload must still contain text prompt", payloadJson.contains("Open Settings"))
    }

    // =========================================================================
    // 3. OpenAI-Compatible Multimodal Payload Tests
    // =========================================================================

    @Test
    fun test_OpenAiCompatibleClient_buildPayloadWithScreenshot() {
        val client = OpenAiCompatibleClient(apiKey = "test_openai_key", modelName = "gpt-4o")
        val fakeBase64 = "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP"

        val request = ModelDecisionRequest(
            systemInstruction = "You are JARVIS",
            originalUserGoal = "Tap Shorts",
            compressedScreenIndex = "=== ACTIVE DISPLAY ===\nAPP: com.google.android.youtube",
            screenshotBase64 = fakeBase64
        )

        val payloadJson = client.buildRequestPayloadJson(request)

        assertTrue("Payload must contain image_url", payloadJson.contains("\"image_url\""))
        assertTrue("Payload must contain data URI", payloadJson.contains("data:image/jpeg;base64,$fakeBase64"))
        assertTrue("Payload must contain tools array", payloadJson.contains("\"tools\""))
    }

    @Test
    fun test_OpenAiCompatibleClient_buildPayloadWithoutScreenshot() {
        val client = OpenAiCompatibleClient(apiKey = "test_openai_key", modelName = "gpt-4o")

        val request = ModelDecisionRequest(
            systemInstruction = "You are JARVIS",
            originalUserGoal = "Tap Shorts",
            compressedScreenIndex = "=== ACTIVE DISPLAY ===\nAPP: com.google.android.youtube",
            screenshotBase64 = null
        )

        val payloadJson = client.buildRequestPayloadJson(request)

        assertFalse("Payload without screenshot must NOT contain image_url", payloadJson.contains("\"image_url\""))
    }

    // =========================================================================
    // 4. DynamicPlanner Screenshot Forwarding Tests
    // =========================================================================

    @Test
    fun test_DynamicPlanner_forwardsScreenshotToModelClient() {
        val fakeBase64 = "TEST_BASE64_FRAME_DATA_12345"
        var capturedRequest: ModelDecisionRequest? = null

        val mockClient = object : ModelClient {
            override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
                capturedRequest = request
                return StructuredModelResult(
                    decision = DecisionType.EXECUTE_TOOL,
                    tool = CanonicalTools.TAP_ELEMENT,
                    arguments = mapOf("label" to "Shorts"),
                    reason = "Tapping shorts visible on screen"
                )
            }
        }

        val toolRegistry = ToolRegistry()
        val planner = DynamicPlanner(toolRegistry)

        val worldState = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(
                SemanticNode(index = 1, text = "Shorts", isClickable = true)
            ),
            screenshotBase64 = fakeBase64
        )

        val missionState = MissionState(originalUserGoal = "Click Shorts on YouTube")
        missionState.currentObservation = worldState

        kotlinx.coroutines.runBlocking {
            val decision = planner.decideNextAction(missionState, mockClient)
            assertEquals(CanonicalTools.TAP_ELEMENT, decision.name)
            assertNotNull("ModelDecisionRequest must have captured request", capturedRequest)
            assertEquals("ModelDecisionRequest must carry screenshotBase64", fakeBase64, capturedRequest?.screenshotBase64)
            assertTrue("Semantic screen index must still be included", capturedRequest?.compressedScreenIndex?.contains("Shorts") == true)
        }
    }

    @Test
    fun test_DynamicPlanner_omitsScreenshotWhenWorldStateHasNone() {
        var capturedRequest: ModelDecisionRequest? = null

        val mockClient = object : ModelClient {
            override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
                capturedRequest = request
                return StructuredModelResult(
                    decision = DecisionType.FINISH_TASK,
                    tool = CanonicalTools.FINISH_TASK,
                    arguments = mapOf("success" to true)
                )
            }
        }

        val toolRegistry = ToolRegistry()
        val planner = DynamicPlanner(toolRegistry)

        val worldState = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(SemanticNode(index = 1, text = "Display", isClickable = true)),
            screenshotBase64 = null
        )

        val missionState = MissionState(originalUserGoal = "Inspect settings")
        missionState.currentObservation = worldState

        kotlinx.coroutines.runBlocking {
            planner.decideNextAction(missionState, mockClient)
            assertNotNull(capturedRequest)
            assertNull("screenshotBase64 must be null when not provided", capturedRequest?.screenshotBase64)
        }
    }

    // =========================================================================
    // 5. Tool Call Extraction from Multimodal Model Text Response
    // =========================================================================

    @Test
    fun test_extractToolCallFromModelText_jsonEmbedded() {
        val modelOutput = "I can see the YouTube Shorts button on the lower navigation bar. Let's tap it.\n" +
                "{\"tool\": \"tap_element\", \"arguments\": {\"label\": \"Shorts\", \"node_index\": 2}}"

        val toolCall = OpenAiCompatibleClient.extractToolCallFromText(modelOutput)
        assertNotNull("ToolCall should be successfully extracted", toolCall)
        assertEquals("tap_element", toolCall?.name)
        assertEquals("Shorts", toolCall?.arguments?.get("label"))
        assertEquals(2, (toolCall?.arguments?.get("node_index") as? Number)?.toInt())
    }

    @Test
    fun test_extractToolCallFromModelText_finishTask() {
        val modelOutput = "{\"action\": \"finish_task\", \"parameters\": {\"success\": true, \"spoken_summary\": \"All items found, Sir.\"}}"

        val toolCall = OpenAiCompatibleClient.extractToolCallFromText(modelOutput)
        assertNotNull("Finish task should be extracted", toolCall)
        assertEquals("finish_task", toolCall?.name)
        assertEquals(true, toolCall?.arguments?.get("success"))
        assertEquals("All items found, Sir.", toolCall?.arguments?.get("spoken_summary"))
    }

    // =========================================================================
    // 6. Resilient Error Handling (Non-Crash Guarantee)
    // =========================================================================

    @Test
    fun test_DynamicPlanner_resilientWhenModelClientThrows() {
        val throwingClient = object : ModelClient {
            override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
                throw RuntimeException("Simulated network/transport timeout")
            }
        }

        val toolRegistry = ToolRegistry()
        val planner = DynamicPlanner(toolRegistry)

        val worldState = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(SemanticNode(index = 1, text = "Shorts", isClickable = true)),
            screenshotBase64 = "SOME_BASE64"
        )

        val missionState = MissionState(originalUserGoal = "shorts ke upar click karo")
        missionState.currentObservation = worldState

        kotlinx.coroutines.runBlocking {
            // Must NOT throw exception; must fallback gracefully to local perception matching
            val decision = planner.decideNextAction(missionState, throwingClient)
            assertNotNull(decision)
            assertEquals(CanonicalTools.TAP_ELEMENT, decision.name)
            assertEquals("Shorts", decision.arguments["label"])
        }
    }

    // =========================================================================
    // 7. Dual Perception Tests: Accessibility Tree + Multimodal Visual Frame
    // =========================================================================

    @Test
    fun test_DualPerception_carriesBothAccessibilityTreeAndScreenshot() {
        val fakeFrame = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        var receivedRequest: ModelDecisionRequest? = null

        val capturingClient = object : ModelClient {
            override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
                receivedRequest = request
                return StructuredModelResult(
                    decision = DecisionType.EXECUTE_TOOL,
                    tool = CanonicalTools.TAP_ELEMENT,
                    arguments = mapOf("label" to "Subscriptions")
                )
            }
        }

        val planner = DynamicPlanner(ToolRegistry())
        val worldState = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(
                SemanticNode(index = 1, text = "Home", isClickable = true),
                SemanticNode(index = 2, text = "Shorts", isClickable = true),
                SemanticNode(index = 3, text = "Subscriptions", isClickable = true)
            ),
            screenshotBase64 = fakeFrame
        )

        val missionState = MissionState(originalUserGoal = "Go to subscriptions")
        missionState.currentObservation = worldState

        kotlinx.coroutines.runBlocking {
            planner.decideNextAction(missionState, capturingClient)
            assertNotNull("Model request must be captured", receivedRequest)
            // 1. Must carry visual frame
            assertEquals("Visual frame must match exactly", fakeFrame, receivedRequest?.screenshotBase64)
            // 2. Must carry full semantic hierarchy (Accessibility Tree)
            val index = receivedRequest?.compressedScreenIndex ?: ""
            assertTrue("Index must carry Home node", index.contains("Home"))
            assertTrue("Index must carry Shorts node", index.contains("Shorts"))
            assertTrue("Index must carry Subscriptions node", index.contains("Subscriptions"))
            assertTrue("Index must carry package name", index.contains("com.google.android.youtube"))
        }
    }

    @Test
    fun test_ModelVisionCapabilities_comprehensiveCoverage() {
        // Gemini family
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.GEMINI, "gemini-1.5-flash-latest"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.GEMINI, "gemini-2.0-flash-exp"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.GEMINI, "models/gemini-1.5-pro"))

        // OpenAI family
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "gpt-4o-2024-08-06"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "gpt-4o-mini-2024-07-18"))
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "gpt-3.5-turbo-0125"))
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.OPENAI, "text-davinci-002"))

        // Custom OpenRouter models
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "google/gemini-2.0-flash-001"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "anthropic/claude-3-5-sonnet-20241022"))
        assertTrue(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "qwen/qwen-2.5-vl-72b-instruct"))
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "deepseek/deepseek-chat"))
        assertFalse(ModelVisionCapabilities.supportsVision(AiProvider.CUSTOM_OPENROUTER, "meta-llama/llama-3.1-70b-instruct"))
    }
}
