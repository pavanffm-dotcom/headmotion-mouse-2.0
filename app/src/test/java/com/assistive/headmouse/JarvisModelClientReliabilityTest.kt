package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.autonomous.ContextCompressor
import com.assistive.headmouse.agent.jarvis.autonomous.model.DecisionType
import com.assistive.headmouse.agent.jarvis.autonomous.model.DefaultHttpTransport
import com.assistive.headmouse.agent.jarvis.autonomous.model.DisabledModelClient
import com.assistive.headmouse.agent.jarvis.autonomous.model.ErrorType
import com.assistive.headmouse.agent.jarvis.autonomous.model.HttpResponse
import com.assistive.headmouse.agent.jarvis.autonomous.model.HttpTransport
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelClientFactory
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelDecisionRequest
import com.assistive.headmouse.agent.jarvis.autonomous.model.OpenAiCompatibleClient
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.model.SimpleJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Comprehensive API Reliability & Model Adapter Test Suite (Phase 4).
 * Covers:
 * 1. Valid Response (Structured tool_calls parsing)
 * 2. Valid Response (Fallback JSON in content)
 * 3. Empty Response Handling
 * 4. Malformed JSON Response Handling (Parser Failure)
 * 5. Socket Read Timeout Distinction
 * 6. Connection Failure Distinction (DNS / ConnectException)
 * 7. HTTP 401 Unauthorized (Fail Fast, No Retry)
 * 8. HTTP 429 Rate Limit (Retry with Exponential Backoff)
 * 9. HTTP 5xx Server Error (Retry with Backoff)
 * 10. Large Context Compression (< 1,000 token budget)
 * 11. Tool Schemas Actually Serialized in Request Body
 * 12. Security & Credential Masking (Zero API key leakage)
 * 13. URL Normalization (/chat/completions auto-extension)
 * 14. Disabled Client Safeguard
 */
class JarvisModelClientReliabilityTest {

    /**
     * Programmable mock transport capturing calls and simulating exact network responses/errors.
     */
    class MockHttpTransport : HttpTransport {
        data class RecordedRequest(
            val url: String,
            val method: String,
            val headers: Map<String, String>,
            val body: String,
            val connectTimeoutMs: Int,
            val readTimeoutMs: Int
        )

        val recordedRequests = mutableListOf<RecordedRequest>()
        private val responseQueue = mutableListOf<Any>() // HttpResponse or Throwable

        fun enqueueResponse(response: HttpResponse) {
            responseQueue.add(response)
        }

        fun enqueueException(throwable: Throwable) {
            responseQueue.add(throwable)
        }

        override fun execute(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int
        ): HttpResponse {
            recordedRequests.add(
                RecordedRequest(url, method, headers, body, connectTimeoutMs, readTimeoutMs)
            )

            if (responseQueue.isEmpty()) {
                throw IllegalStateException("MockHttpTransport queue empty!")
            }

            when (val item = responseQueue.removeAt(0)) {
                is HttpResponse -> return item
                is Throwable -> throw item
                else -> throw IllegalStateException("Unexpected item in queue: $item")
            }
        }
    }

    private fun createStandardRequest(): ModelDecisionRequest {
        return ModelDecisionRequest(
            systemInstruction = "You are J.A.R.V.I.S.",
            originalUserGoal = "Open YouTube and search lo-fi beats",
            currentSubgoal = "Search for lo-fi beats",
            compressedScreenIndex = "[1] EditText | \"Search\" | bounds=[100,100,900,200]",
            actionHistory = listOf("Step 1: launch_app -> verified: true"),
            availableTools = CanonicalTools.ALL_DEFINITIONS
        )
    }

    @Test
    fun test1_ValidResponse_StructuredToolCall() = runBlocking {
        val mock = MockHttpTransport()
        val validJson = """
            {
              "id": "gen_123",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "I will tap the search field.",
                    "tool_calls": [
                      {
                        "id": "call_abc",
                        "type": "function",
                        "function": {
                          "name": "tap_element",
                          "arguments": "{\"element_id\": 1}"
                        }
                      }
                    ]
                  }
                }
              ],
              "usage": { "prompt_tokens": 120, "completion_tokens": 30 }
            }
        """.trimIndent()

        mock.enqueueResponse(HttpResponse(statusCode = 200, body = validJson, connectTimeMs = 15, readTimeMs = 80))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key-12345",
            baseUrl = "https://api.xkiro.com/v1",
            modelName = "deepseek/deepseek-chat",
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals("tap_element", result.tool)
        assertEquals(1, (result.arguments["element_id"] as? Number)?.toInt())
        assertEquals("I will tap the search field.", result.reason)
        assertNull(result.error)
        assertEquals(120, result.diagnostics.promptTokens)
        assertEquals(30, result.diagnostics.completionTokens)
        assertEquals(1, result.diagnostics.attempts)
    }

    @Test
    fun test2_ValidResponse_FallbackJsonInContent() = runBlocking {
        val mock = MockHttpTransport()
        val fallbackJson = """
            {
              "id": "gen_456",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Here is the tool to invoke:\n{\"tool\": \"type_text\", \"arguments\": {\"text\": \"lo-fi beats\", \"press_enter\": true}}"
                  }
                }
              ]
            }
        """.trimIndent()

        mock.enqueueResponse(HttpResponse(statusCode = 200, body = fallbackJson))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key",
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals("type_text", result.tool)
        assertEquals("lo-fi beats", result.arguments["text"])
        assertEquals(true, result.arguments["press_enter"])
    }

    @Test
    fun test3_EmptyResponseBody_HandledAsEmptyResponseError() = runBlocking {
        val mock = MockHttpTransport()
        // Return HTTP 200 with empty body across retries
        mock.enqueueResponse(HttpResponse(statusCode = 200, body = ""))
        mock.enqueueResponse(HttpResponse(statusCode = 200, body = "   "))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key",
            maxRetries = 1,
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.ERROR, result.decision)
        assertNotNull(result.error)
        assertEquals(ErrorType.EMPTY_RESPONSE, result.error?.type)
        assertEquals(2, result.diagnostics.attempts)
    }

    @Test
    fun test4_MalformedJson_HandledAsParserFailure() = runBlocking {
        val mock = MockHttpTransport()
        mock.enqueueResponse(HttpResponse(statusCode = 200, body = "{ incomplete json body..."))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key",
            maxRetries = 0,
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.ERROR, result.decision)
        assertEquals(ErrorType.PARSER_FAILURE, result.error?.type)
        assertFalse(result.error!!.isRecoverable)
    }

    @Test
    fun test5_SocketReadTimeout_DistinguishedAndRetried() = runBlocking {
        val mock = MockHttpTransport()
        mock.enqueueException(SocketTimeoutException("Read timed out"))
        // Second attempt succeeds
        val validJson = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Done",
                    "tool_calls": [{ "function": { "name": "finish_task", "arguments": "{\"status\":\"success\"}" } }]
                  }
                }
              ]
            }
        """.trimIndent()
        mock.enqueueResponse(HttpResponse(statusCode = 200, body = validJson))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key",
            maxRetries = 1,
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.FINISH_TASK, result.decision)
        assertEquals("finish_task", result.tool)
        assertEquals(2, result.diagnostics.attempts)
    }

    @Test
    fun test6_ConnectionFailure_UnknownHostAndConnectException() = runBlocking {
        val mock = MockHttpTransport()
        mock.enqueueException(UnknownHostException("api.nonexistent-domain.xyz"))
        mock.enqueueException(ConnectException("Connection refused"))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key",
            maxRetries = 1,
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.ERROR, result.decision)
        assertEquals(ErrorType.CONNECTION_FAILURE, result.error?.type)
        assertEquals(2, result.diagnostics.attempts)
    }

    @Test
    fun test7_Http401Unauthorized_FailsFastWithoutRetry() = runBlocking {
        val mock = MockHttpTransport()
        mock.enqueueResponse(HttpResponse(statusCode = 401, body = null, errorBody = "{\"error\":\"Invalid API key\"}"))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-expired-secret-key-9999",
            maxRetries = 2,
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.ERROR, result.decision)
        assertEquals(ErrorType.HTTP_UNAUTHORIZED, result.error?.type)
        assertEquals(401, result.error?.httpCode)
        assertFalse(result.error!!.isRecoverable)
        // MUST FAIL FAST: Exactly 1 attempt made, never retried!
        assertEquals(1, result.diagnostics.attempts)
        assertEquals(1, mock.recordedRequests.size)
    }

    @Test
    fun test8_Http429RateLimit_RetriesWithBackoff() = runBlocking {
        val mock = MockHttpTransport()
        mock.enqueueResponse(HttpResponse(statusCode = 429, body = null, errorBody = "Rate limited"))
        val validJson = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Retried successfully",
                    "tool_calls": [{ "function": { "name": "wait", "arguments": "{\"duration_ms\": 500}" } }]
                  }
                }
              ]
            }
        """.trimIndent()
        mock.enqueueResponse(HttpResponse(statusCode = 200, body = validJson))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key",
            maxRetries = 1,
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals("wait", result.tool)
        assertEquals(2, result.diagnostics.attempts)
        assertEquals(2, mock.recordedRequests.size)
    }

    @Test
    fun test9_Http502ServerError_Retried() = runBlocking {
        val mock = MockHttpTransport()
        mock.enqueueResponse(HttpResponse(statusCode = 502, body = null, errorBody = "Bad Gateway"))
        val validJson = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Recovered",
                    "tool_calls": [{ "function": { "name": "scroll", "arguments": "{\"direction\": \"DOWN\"}" } }]
                  }
                }
              ]
            }
        """.trimIndent()
        mock.enqueueResponse(HttpResponse(statusCode = 200, body = validJson))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key",
            maxRetries = 1,
            transport = mock
        )

        val result = client.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals("scroll", result.tool)
        assertEquals(2, result.diagnostics.attempts)
    }

    @Test
    fun test10_LargeContextCompression_StrictTokenBudgeting() {
        val hugeHistory = (1..50).map { step ->
            "Step $step: Very verbose past action with deep element dump [node #$step: class=android.widget.TextView text='Item $step' bounds=[0, $step, 1080, ${step+50}]] -> verified: true"
        }
        val hugeScreenIndex = (1..100).joinToString("\n") { i ->
            "[$i] TextView | \"Item $i\" | bounds=[0, $i, 1080, ${i + 40}] | clickable"
        }

        val compressedPrompt = ContextCompressor.buildCompressedDecisionPrompt(
            originalGoal = "Find and click settings item",
            currentSubgoal = "Scroll to item 50",
            compressedScreenIndex = hugeScreenIndex,
            actionHistory = hugeHistory,
            maxHistorySteps = 4,
            maxTokenBudget = 1000
        )

        val tokenEstimate = ContextCompressor.estimateTokenCount(compressedPrompt)

        // Verifies context budgeting: Token count must strictly stay below 1,000 tokens
        assertTrue("Expected tokens <= 1000, but was $tokenEstimate", tokenEstimate <= 1000)
        assertTrue(compressedPrompt.contains("ORIGINAL GOAL: \"Find and click settings item\""))
        assertTrue(compressedPrompt.contains("CURRENT SUBGOAL: \"Scroll to item 50\""))
        assertFalse(compressedPrompt.contains("Step 1:")) // Old historical turns must be pruned
    }

    @Test
    fun test11_ToolSchemasActuallySerializedInRequestBody() = runBlocking {
        val mock = MockHttpTransport()
        mock.enqueueResponse(HttpResponse(statusCode = 200, body = "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}"))

        val client = OpenAiCompatibleClient(
            apiKey = "sk-test-key",
            baseUrl = "https://api.xkiro.com/v1",
            transport = mock
        )

        client.decideNextActionStructured(createStandardRequest())

        assertEquals(1, mock.recordedRequests.size)
        val sentBody = mock.recordedRequests[0].body
        val jsonMap = SimpleJson.parseObject(sentBody)

        // REQUIREMENT 6 VERIFICATION: Tools array must be physically present in outgoing payload
        assertTrue("Request body must contain 'tools'", jsonMap.containsKey("tools"))
        @Suppress("UNCHECKED_CAST")
        val toolsList = jsonMap["tools"] as? List<Map<String, Any?>> ?: emptyList()
        assertEquals(12, toolsList.size) // All 12 canonical tools serialized

        // Verify tool schema structure (type=function, function.name, function.parameters)
        val firstTool = toolsList[0]
        assertEquals("function", firstTool["type"])
        @Suppress("UNCHECKED_CAST")
        val functionObj = firstTool["function"] as? Map<String, Any?> ?: emptyMap()
        assertTrue(functionObj.containsKey("name"))
        assertTrue(functionObj.containsKey("parameters"))
        assertEquals("auto", jsonMap["tool_choice"])
    }

    @Test
    fun test12_Security_ApiKeyNeverLoggedAndMasked() {
        val rawKey = "sk-or-v1-abcdef1234567890abcdef1234567890"
        val masked = OpenAiCompatibleClient.maskKey(rawKey)

        assertEquals("sk-o••••7890", masked)
        assertFalse("Masked key must not contain raw secret core", masked.contains("abcdef1234567890"))

        val emptyMasked = OpenAiCompatibleClient.maskKey("")
        assertEquals("NO_KEY", emptyMasked)

        val shortMasked = OpenAiCompatibleClient.maskKey("123456")
        assertEquals("••••••••", shortMasked)
    }

    @Test
    fun test13_Configuration_UrlNormalization() {
        // Base URLs without endpoint must be normalized
        assertEquals(
            "https://api.xkiro.com/v1/chat/completions",
            OpenAiCompatibleClient.normalizeChatCompletionsUrl("https://api.xkiro.com/v1")
        )
        // Base URLs with trailing slash
        assertEquals(
            "https://api.xkiro.com/v1/chat/completions",
            OpenAiCompatibleClient.normalizeChatCompletionsUrl("https://api.xkiro.com/v1/")
        )
        // Base URLs that already have /chat/completions
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenAiCompatibleClient.normalizeChatCompletionsUrl("https://openrouter.ai/api/v1/chat/completions")
        )
    }

    @Test
    fun test14_DisabledClientSafeguard() = runBlocking {
        val disabled = DisabledModelClient("Cloud reasoning disabled by user")
        val result = disabled.decideNextActionStructured(createStandardRequest())

        assertEquals(DecisionType.ERROR, result.decision)
        assertEquals(ErrorType.CLOUD_AI_DISABLED, result.error?.type)
        assertFalse(result.error!!.isRecoverable)
    }
}
