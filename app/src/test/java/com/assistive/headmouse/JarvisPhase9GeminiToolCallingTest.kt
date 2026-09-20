package com.assistive.headmouse

import android.content.Context
import com.assistive.headmouse.agent.jarvis.autonomous.DynamicPlanner
import com.assistive.headmouse.agent.jarvis.autonomous.ToolCapabilityManager
import com.assistive.headmouse.agent.jarvis.autonomous.ToolRegistry
import com.assistive.headmouse.agent.jarvis.autonomous.model.*
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * PHASE 9 VERIFICATION SUITE — GEMINI STRUCTURED TOOL CALLING
 *
 * Validates:
 * 1. Tool schema generation conforms to Gemini OpenAPI 3.03 function declarations.
 * 2. Gemini request payload attaches function declarations.
 * 3. Structured functionCall responses parsed into canonical ToolCall.
 * 4. Preservation of argument types (integers, booleans, floats, strings).
 * 5. Safe handling of thought text alongside functionCall.
 * 6. Safe handling of multiple/parallel tool calls (atomic serialization).
 * 7. Malformed tool calls return structured PARSER_FAILURE without crashing.
 * 8. Execution of parsed Gemini tool calls through existing ToolDispatcher.
 * 9. Aliases (tap -> tap_element, press_back -> press_navigation) handled seamlessly.
 * 10. DynamicPlanner integration without bypassing the normal agent loop.
 * 11. OpenAI-compatible tool calling remains functional.
 */
class JarvisPhase9GeminiToolCallingTest {

    /**
     * Programmable mock transport to verify HTTP request payload and simulate Gemini responses.
     */
    class MockHttpTransport : HttpTransport {
        data class RecordedRequest(
            val url: String,
            val method: String,
            val headers: Map<String, String>,
            val body: String
        )

        val recordedRequests = mutableListOf<RecordedRequest>()
        private val responseQueue = mutableListOf<HttpResponse>()

        fun enqueueResponse(body: String, statusCode: Int = 200) {
            responseQueue.add(HttpResponse(statusCode = statusCode, body = body))
        }

        override fun execute(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int
        ): HttpResponse {
            recordedRequests.add(RecordedRequest(url, method, headers, body))
            if (responseQueue.isEmpty()) {
                throw IllegalStateException("MockHttpTransport response queue is empty!")
            }
            return responseQueue.removeAt(0)
        }
    }

    private fun createStandardRequest(): ModelDecisionRequest {
        return ModelDecisionRequest(
            systemInstruction = "You are J.A.R.V.I.S., an autonomous Android agent.",
            originalUserGoal = "Open YouTube and search lo-fi beats",
            currentSubgoal = "Search for lo-fi beats",
            compressedScreenIndex = "[1] EditText | \"Search\" | bounds=[100,100,900,200]",
            actionHistory = listOf("Step 1: launch_app -> verified: true"),
            availableTools = CanonicalTools.ALL_DEFINITIONS
        )
    }

    // =========================================================================
    // 1. Tool Schema Generation
    // =========================================================================
    @Test
    fun test1_GeminiSchemaGeneration_AllCanonicalTools() {
        val geminiTools = CanonicalTools.toGeminiTools()
        assertEquals(1, geminiTools.size)

        @Suppress("UNCHECKED_CAST")
        val decls = geminiTools[0]["function_declarations"] as? List<Map<String, Any?>>
        assertNotNull("function_declarations list must not be null", decls)
        assertEquals(CanonicalTools.ALL_DEFINITIONS.size, decls!!.size)

        // Verify tap_element schema
        val tapDef = decls.firstOrNull { it["name"] == CanonicalTools.TAP_ELEMENT }
        assertNotNull("tap_element declaration must exist", tapDef)
        @Suppress("UNCHECKED_CAST")
        val tapParams = tapDef!!["parameters"] as Map<String, Any?>
        assertEquals("OBJECT", tapParams["type"])
        @Suppress("UNCHECKED_CAST")
        val tapProps = tapParams["properties"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val nodeIdxProp = tapProps["node_index"] as Map<String, Any?>
        assertEquals("INTEGER", nodeIdxProp["type"])
        @Suppress("UNCHECKED_CAST")
        val labelProp = tapProps["label"] as Map<String, Any?>
        assertEquals("STRING", labelProp["type"])

        // Verify tap_coordinates schema
        val coordDef = decls.firstOrNull { it["name"] == CanonicalTools.TAP_COORDINATES }
        assertNotNull(coordDef)
        @Suppress("UNCHECKED_CAST")
        val coordParams = coordDef!!["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val coordProps = coordParams["properties"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val xProp = coordProps["x"] as Map<String, Any?>
        assertEquals("NUMBER", xProp["type"])
        @Suppress("UNCHECKED_CAST")
        val requiredList = coordParams["required"] as List<String>
        assertTrue(requiredList.contains("x"))
        assertTrue(requiredList.contains("y"))

        // Verify scroll schema with enum
        val scrollDef = decls.firstOrNull { it["name"] == CanonicalTools.SCROLL }
        assertNotNull(scrollDef)
        @Suppress("UNCHECKED_CAST")
        val scrollParams = scrollDef!!["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val scrollProps = scrollParams["properties"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val dirProp = scrollProps["direction"] as Map<String, Any?>
        assertEquals("STRING", dirProp["type"])
        @Suppress("UNCHECKED_CAST")
        val enumValues = dirProp["enum"] as List<String>
        assertTrue(enumValues.contains("DOWN"))
        assertTrue(enumValues.contains("UP"))
    }

    // =========================================================================
    // 2. Gemini Payload Serialization
    // =========================================================================
    @Test
    fun test2_GeminiPayloadAttachesTools() {
        val client = GoogleGeminiClient(apiKey = "fake_key_123", modelName = "gemini-2.0-flash")
        val request = createStandardRequest()
        val jsonString = client.buildGeminiPayloadJson(request)

        val parsed = SimpleJson.parseObject(jsonString)
        assertTrue("Payload must contain contents", parsed.containsKey("contents"))
        assertTrue("Payload must contain system_instruction", parsed.containsKey("system_instruction"))
        assertTrue("Payload must contain tools array", parsed.containsKey("tools"))

        @Suppress("UNCHECKED_CAST")
        val tools = parsed["tools"] as? List<Map<String, Any?>>
        assertNotNull("tools must be a non-null list", tools)
        assertEquals(1, tools!!.size)

        @Suppress("UNCHECKED_CAST")
        val decls = tools[0]["function_declarations"] as? List<Map<String, Any?>>
        assertNotNull("function_declarations must be present", decls)
        assertEquals(CanonicalTools.ALL_DEFINITIONS.size, decls!!.size)
    }

    // =========================================================================
    // 3. Structured Function Call Parsing: launch_app
    // =========================================================================
    @Test
    fun test3_GeminiParseLaunchApp() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "name": "launch_app",
                      "args": {
                        "package_or_name": "com.google.android.youtube"
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 150L, 200)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.LAUNCH_APP, result.tool)
        assertEquals("com.google.android.youtube", result.arguments["package_or_name"])
    }

    // =========================================================================
    // 4. Structured Function Call Parsing: tap / tap_element (Preserves integer node_index)
    // =========================================================================
    @Test
    fun test4_GeminiParseTap_PreservesIntegerType() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "name": "tap_element",
                      "args": {
                        "node_index": 5,
                        "label": "Shorts"
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 100L, 200)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.TAP_ELEMENT, result.tool)
        assertEquals(5, (result.arguments["node_index"] as Number).toInt())
        assertEquals("Shorts", result.arguments["label"])
    }

    // =========================================================================
    // 5. Structured Function Call Parsing: type_text (Preserves boolean press_enter)
    // =========================================================================
    @Test
    fun test5_GeminiParseTypeText_PreservesBooleanType() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "name": "type_text",
                      "args": {
                        "text": "lo-fi hip hop",
                        "press_enter": true,
                        "node_index": 2
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 120L, 200)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.TYPE_TEXT, result.tool)
        assertEquals("lo-fi hip hop", result.arguments["text"])
        assertEquals(true, result.arguments["press_enter"])
        assertEquals(2, (result.arguments["node_index"] as Number).toInt())
    }

    // =========================================================================
    // 6. Structured Function Call Parsing: scroll
    // =========================================================================
    @Test
    fun test6_GeminiParseScroll() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "name": "scroll",
                      "args": {
                        "direction": "DOWN",
                        "distance": "MEDIUM"
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 90L, 200)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.SCROLL, result.tool)
        assertEquals("DOWN", result.arguments["direction"])
        assertEquals("MEDIUM", result.arguments["distance"])
    }

    // =========================================================================
    // 7. Structured Function Call Parsing: press_back / press_navigation
    // =========================================================================
    @Test
    fun test7_GeminiParsePressNavigation() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "name": "press_navigation",
                      "args": {
                        "action": "BACK"
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 80L, 200)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.PRESS_NAVIGATION, result.tool)
        assertEquals("BACK", result.arguments["action"])
    }

    // =========================================================================
    // 8. Structured Function Call Parsing: wait
    // =========================================================================
    @Test
    fun test8_GeminiParseWait_PreservesIntegerDuration() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "name": "wait",
                      "args": {
                        "duration_ms": 1200
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 85L, 200)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.WAIT, result.tool)
        assertEquals(1200, (result.arguments["duration_ms"] as Number).toInt())
    }

    // =========================================================================
    // 9. Structured Function Call Parsing: finish_task
    // =========================================================================
    @Test
    fun test9_GeminiParseFinishTask() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "name": "finish_task",
                      "args": {
                        "success": true,
                        "spoken_summary": "Lo-fi beats video is now playing, Sir."
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 110L, 200)
        assertEquals(DecisionType.FINISH_TASK, result.decision)
        assertEquals("TASK_FINISHED", result.finishState)
        assertEquals(CanonicalTools.FINISH_TASK, result.tool)
        assertEquals(true, result.arguments["success"])
        assertEquals("Lo-fi beats video is now playing, Sir.", result.arguments["spoken_summary"])
    }

    // =========================================================================
    // 10. Thought text + Function Call combined in candidate parts
    // =========================================================================
    @Test
    fun test10_GeminiThoughtAndFunctionCallCombined() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "text": "The search bar is visible at node #1. I will type lo-fi beats."
                  },
                  {
                    "functionCall": {
                      "name": "type_text",
                      "args": {
                        "text": "lo-fi beats",
                        "node_index": 1
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 95L, 200)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.TYPE_TEXT, result.tool)
        assertEquals("The search bar is visible at node #1. I will type lo-fi beats.", result.reason)
        assertEquals("lo-fi beats", result.arguments["text"])
        assertEquals(1, (result.arguments["node_index"] as Number).toInt())
    }

    // =========================================================================
    // 11. Multiple/Parallel Function Calls (Atomic safety: takes first)
    // =========================================================================
    @Test
    fun test11_GeminiMultipleFunctionCalls_SerializedSafely() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "functionCall": {
                      "name": "tap_element",
                      "args": {
                        "node_index": 1
                      }
                    }
                  },
                  {
                    "functionCall": {
                      "name": "tap_element",
                      "args": {
                        "node_index": 2
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val result = client.parseGeminiResponse(geminiJson, 100L, 200)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.TAP_ELEMENT, result.tool)
        assertEquals(1, (result.arguments["node_index"] as Number).toInt())
    }

    // =========================================================================
    // 12. Malformed Tool Call Handling (Safe error result)
    // =========================================================================
    @Test
    fun test12_GeminiMalformedToolCall_SafeErrorHandling() {
        val client = GoogleGeminiClient(apiKey = "fake_key", modelName = "gemini-2.0-flash")

        // Case A: Missing function name
        val missingNameJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "functionCall": {
                      "name": "",
                      "args": {}
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()
        val resA = client.parseGeminiResponse(missingNameJson, 50L, 200)
        assertEquals(DecisionType.ERROR, resA.decision)
        assertEquals(ErrorType.PARSER_FAILURE, resA.error?.type)

        // Case B: Corrupted JSON
        val corruptJson = "{ \"candidates\": [ { broken json ... "
        val resB = client.parseGeminiResponse(corruptJson, 50L, 200)
        assertEquals(DecisionType.ERROR, resB.decision)
        assertEquals(ErrorType.PARSER_FAILURE, resB.error?.type)
    }

    // =========================================================================
    // 13. End-to-End Execution of Parsed Gemini ToolCall through ToolDispatcher
    // =========================================================================
    @Test
    fun test13_GeminiToolDispatchedToExistingToolDispatcher() = runBlocking {
        val mockContext: Context = object : android.content.ContextWrapper(null) {}

        val dispatcher = ToolDispatcher(context = mockContext, serviceProvider = { null })

        // 1. launch_app
        val launchCall = ToolCall(
            name = CanonicalTools.LAUNCH_APP,
            arguments = mapOf("package_or_name" to "com.android.settings")
        )
        val launchResult = dispatcher.dispatch(launchCall, null)
        assertEquals(CanonicalTools.LAUNCH_APP, launchResult.tool)

        // 2. Alias: tap -> tap_element
        val tapAliasCall = ToolCall(
            name = "tap",
            arguments = mapOf("node_index" to 1, "label" to "Display")
        )
        val activeWorldState = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(index = 1, text = "Display", left = 100f, top = 200f, right = 500f, bottom = 300f, isClickable = true)
            )
        )
        val tapResult = dispatcher.dispatch(tapAliasCall, activeWorldState)
        assertEquals(CanonicalTools.TAP_ELEMENT, tapResult.tool)
        assertTrue("Tap element result must succeed", tapResult.success)

        // 3. Alias: press_back -> press_navigation
        val backAliasCall = ToolCall(
            name = "press_back",
            arguments = emptyMap()
        )
        val backResult = dispatcher.dispatch(backAliasCall, null)
        assertEquals(CanonicalTools.PRESS_NAVIGATION, backResult.tool)
        assertEquals("BACK", backResult.arguments["action"])
        assertTrue("Back result must succeed", backResult.success)

        // 4. wait
        val waitCall = ToolCall(
            name = CanonicalTools.WAIT,
            arguments = mapOf("duration_ms" to 100)
        )
        val waitResult = dispatcher.dispatch(waitCall, null)
        assertEquals(CanonicalTools.WAIT, waitResult.tool)
        assertTrue("Wait result must succeed", waitResult.success)
    }

    // =========================================================================
    // 14. DynamicPlanner Integration with GoogleGeminiClient
    // =========================================================================
    @Test
    fun test14_DynamicPlannerWithGeminiClient() = runBlocking {
        val mockTransport = MockHttpTransport()
        val geminiJson = """
        {
          "candidates": [
            {
              "content": {
                "role": "model",
                "parts": [
                  {
                    "text": "Settings is already open, tapping Display."
                  },
                  {
                    "functionCall": {
                      "name": "tap_element",
                      "args": {
                        "node_index": 1,
                        "label": "Display"
                      }
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()
        mockTransport.enqueueResponse(geminiJson)

        val geminiClient = GoogleGeminiClient(
            apiKey = "test_key",
            modelName = "gemini-2.0-flash",
            transport = mockTransport
        )

        val planner = DynamicPlanner(
            toolRegistry = ToolRegistry(ToolCapabilityManager()),
            jarvisBrain = null,
            appSettings = null
        )

        val missionState = MissionState(originalUserGoal = "Open display settings")
        missionState.currentObservation = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(index = 1, text = "Display", left = 100f, top = 200f, right = 500f, bottom = 300f, isClickable = true)
            )
        )

        val toolCall = planner.decideNextAction(missionState, geminiClient)

        // Verifies tool call produced by Gemini reaches the agent loop
        assertEquals(CanonicalTools.TAP_ELEMENT, toolCall.name)
        assertEquals(1, (toolCall.arguments["node_index"] as Number).toInt())
        assertEquals("Display", toolCall.arguments["label"])
        assertEquals("Settings is already open, tapping Display.", toolCall.thought)

        // Verifies the HTTP request sent by Gemini client attached tools
        val sentRequest = mockTransport.recordedRequests[0]
        assertTrue(sentRequest.body.contains("\"function_declarations\""))
        assertTrue(sentRequest.body.contains("tap_element"))
    }

    // =========================================================================
    // 15. OpenAI Tool Calling Regression Safeguard
    // =========================================================================
    @Test
    fun test15_OpenAiCompatibleToolCallingRemainsFunctional() = runBlocking {
        val mockTransport = MockHttpTransport()
        val openAiJson = """
        {
          "id": "chatcmpl-123",
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": "Tapping search",
                "tool_calls": [
                  {
                    "id": "call_123",
                    "type": "function",
                    "function": {
                      "name": "tap_element",
                      "arguments": "{\"node_index\": 2, \"label\": \"Search\"}"
                    }
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()
        mockTransport.enqueueResponse(openAiJson)

        val openAiClient = OpenAiCompatibleClient(
            apiKey = "test_key",
            baseUrl = "https://api.openai.com/v1",
            modelName = "gpt-4o",
            transport = mockTransport
        )

        val request = createStandardRequest()
        val result = openAiClient.decideNextActionStructured(request)

        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals(CanonicalTools.TAP_ELEMENT, result.tool)
        assertEquals(2, (result.arguments["node_index"] as Number).toInt())
        assertEquals("Search", result.arguments["label"])
    }
}
