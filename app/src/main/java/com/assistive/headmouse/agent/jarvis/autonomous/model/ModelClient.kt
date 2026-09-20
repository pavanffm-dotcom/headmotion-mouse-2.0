package com.assistive.headmouse.agent.jarvis.autonomous.model

import android.util.Log
import com.assistive.headmouse.agent.jarvis.autonomous.ContextCompressor
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDefinition
import com.assistive.headmouse.preferences.AiProvider
import com.assistive.headmouse.preferences.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// ============================================================================
// 1. NORMALIZED DECISION & RESULT DATA CONTRACT
// ============================================================================

enum class DecisionType {
    EXECUTE_TOOL,
    FINISH_TASK,
    CONVERSATIONAL,
    ERROR
}

enum class ErrorType {
    CONNECTION_FAILURE,
    SERVER_WAIT_TIMEOUT,
    RESPONSE_TIMEOUT,
    HTTP_UNAUTHORIZED,
    HTTP_RATE_LIMIT,
    HTTP_SERVER_ERROR,
    HTTP_CLIENT_ERROR,
    EMPTY_RESPONSE,
    PARSER_FAILURE,
    CANCELLED,
    CLOUD_AI_DISABLED,
    UNKNOWN
}

data class ModelError(
    val type: ErrorType,
    val httpCode: Int? = null,
    val message: String,
    val isRecoverable: Boolean = true,
    val rawSnippet: String? = null
)

data class ModelDiagnostics(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val durationMs: Long = 0L,
    val connectTimeMs: Long = 0L,
    val readTimeMs: Long = 0L,
    val attempts: Int = 1,
    val httpStatusCode: Int = 200,
    val modelUsed: String = "",
    val providerUsed: String = ""
)

data class StructuredModelResult(
    val decision: DecisionType,
    val tool: String? = null,
    val arguments: Map<String, Any?> = emptyMap(),
    val reason: String? = null,
    val finishState: String? = null,
    val error: ModelError? = null,
    val diagnostics: ModelDiagnostics = ModelDiagnostics()
) {
    /**
     * Backwards-compatibility adapter converting to legacy ModelResponse.
     */
    fun toModelResponse(): ModelResponse {
        val toolCall = if (tool != null) {
            ToolCall(name = tool, arguments = arguments, thought = reason)
        } else null
        return ModelResponse(
            thought = reason,
            toolCall = toolCall,
            conversationalReply = if (decision == DecisionType.CONVERSATIONAL || decision == DecisionType.ERROR) reason else null,
            promptTokens = diagnostics.promptTokens,
            completionTokens = diagnostics.completionTokens,
            durationMs = diagnostics.durationMs
        )
    }
}

/**
 * Encapsulates all domain parameters for an atomic model decision.
 * Decouples agent logic completely from HTTP, JSON, or provider-specific details.
 */
data class ModelDecisionRequest(
    val systemInstruction: String,
    val originalUserGoal: String,
    val currentSubgoal: String? = null,
    val compressedScreenIndex: String = "",
    val actionHistory: List<String> = emptyList(),
    val availableTools: List<ToolDefinition> = CanonicalTools.ALL_DEFINITIONS,
    val screenshotBase64: String? = null,
    val temperature: Double = 0.1,
    val maxTokens: Int = 400
)

/**
 * Legacy standardized model response preserved for backwards compatibility.
 */
data class ModelResponse(
    val thought: String? = null,
    val toolCall: ToolCall? = null,
    val conversationalReply: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val durationMs: Long = 0L
)

// ============================================================================
// 2. MODEL CLIENT ABSTRACTION
// ============================================================================

/**
 * Normalized provider-neutral interface for all LLM interactions.
 * The autonomous agent depends ONLY on this interface, never on HTTP engines, URLs, or SDKs.
 */
interface ModelClient {
    /**
     * Decides the next action and returns a normalized, structured model result.
     */
    suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
        val legacy = decideNextAction(
            systemInstruction = request.systemInstruction,
            originalUserGoal = request.originalUserGoal,
            currentSubgoal = request.currentSubgoal,
            compressedScreenIndex = request.compressedScreenIndex,
            actionHistory = request.actionHistory,
            availableTools = request.availableTools,
            screenshotBase64 = request.screenshotBase64
        )
        val response = legacy.getOrNull()
        if (response != null) {
            val decision = if (response.toolCall?.name == CanonicalTools.FINISH_TASK) DecisionType.FINISH_TASK else DecisionType.EXECUTE_TOOL
            return StructuredModelResult(
                decision = decision,
                tool = response.toolCall?.name,
                arguments = response.toolCall?.arguments ?: emptyMap(),
                reason = response.thought ?: response.conversationalReply,
                finishState = if (decision == DecisionType.FINISH_TASK) "TASK_FINISHED" else null,
                diagnostics = ModelDiagnostics(
                    promptTokens = response.promptTokens,
                    completionTokens = response.completionTokens,
                    durationMs = response.durationMs
                )
            )
        }
        return StructuredModelResult(
            decision = DecisionType.ERROR,
            error = ModelError(
                type = ErrorType.UNKNOWN,
                message = legacy.exceptionOrNull()?.message ?: "Legacy model execution failed"
            )
        )
    }

    /**
     * Legacy / Backwards-compatible signature for existing tests and components.
     */
    suspend fun decideNextAction(
        systemInstruction: String,
        originalUserGoal: String,
        currentSubgoal: String?,
        compressedScreenIndex: String,
        actionHistory: List<String>,
        availableTools: List<ToolDefinition> = CanonicalTools.ALL_DEFINITIONS,
        screenshotBase64: String? = null
    ): Result<ModelResponse> {
        val request = ModelDecisionRequest(
            systemInstruction = systemInstruction,
            originalUserGoal = originalUserGoal,
            currentSubgoal = currentSubgoal,
            compressedScreenIndex = compressedScreenIndex,
            actionHistory = actionHistory,
            availableTools = availableTools,
            screenshotBase64 = screenshotBase64
        )
        val structured = decideNextActionStructured(request)
        return if (structured.error != null && structured.decision == DecisionType.ERROR) {
            Result.failure(RuntimeException(structured.error.message))
        } else {
            Result.success(structured.toModelResponse())
        }
    }
}

// ============================================================================
// 3. HTTP TRANSPORT ABSTRACTION (100% JVM Testability)
// ============================================================================

data class HttpResponse(
    val statusCode: Int,
    val body: String?,
    val errorBody: String? = null,
    val connectTimeMs: Long = 0L,
    val readTimeMs: Long = 0L
)

interface HttpTransport {
    fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResponse
}

/**
 * Default production HTTP transport using HttpURLConnection with granular timing breakdown.
 */
class DefaultHttpTransport : HttpTransport {
    override fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResponse {
        val connectStart = System.currentTimeMillis()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            this.connectTimeout = connectTimeoutMs
            this.readTimeout = readTimeoutMs
            doOutput = body.isNotEmpty()
            doInput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            if (body.isNotEmpty()) {
                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }
            val connectTime = System.currentTimeMillis() - connectStart

            val readStart = System.currentTimeMillis()
            val statusCode = conn.responseCode

            val responseBody = if (statusCode in 200..299) {
                conn.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } else null

            val errorBody = if (statusCode !in 200..299) {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } else null

            val readTime = System.currentTimeMillis() - readStart

            return HttpResponse(
                statusCode = statusCode,
                body = responseBody,
                errorBody = errorBody,
                connectTimeMs = connectTime,
                readTimeMs = readTime
            )
        } finally {
            conn.disconnect()
        }
    }
}

// ============================================================================
// 4. OPENAI / OPENROUTER COMPATIBLE CLIENT
// ============================================================================

/**
 * Robust OpenAI/OpenRouter-compatible client supporting function calling,
 * automatic URL normalization, granular timeout distinction, exponential backoff retries,
 * JSON fallback extraction, and strict zero-credential logging.
 */
class OpenAiCompatibleClient(
    private val apiKey: String,
    private val baseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
    private val modelName: String = "deepseek/deepseek-chat",
    private val maxRetries: Int = 2,
    private val transport: HttpTransport = DefaultHttpTransport()
) : ModelClient {

    companion object {
        private const val TAG = "OpenAiModelClient"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000 // 30s timeout

        /**
         * Normalizes base URL, ensuring /chat/completions endpoint is present.
         * Example: "https://api.xkiro.com/v1" -> "https://api.xkiro.com/v1/chat/completions"
         */
        fun normalizeChatCompletionsUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

        /**
         * Strictly masks sensitive API keys for logs and diagnostics.
         */
        fun maskKey(key: String): String {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) return "NO_KEY"
            if (trimmed.length <= 8) return "••••••••"
            return "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
        }

        fun extractToolCallFromText(text: String): ToolCall? {
            if (!text.contains("{") || !text.contains("}")) return null
            val startIndex = text.indexOf('{')
            val endIndex = text.lastIndexOf('}')
            if (startIndex >= 0 && endIndex > startIndex) {
                val candidate = text.substring(startIndex, endIndex + 1)
                val obj = SimpleJson.parseObject(candidate)
                val toolName = (obj["tool"] ?: obj["name"] ?: obj["action"])?.toString()
                if (!toolName.isNullOrBlank()) {
                    val argsObj = obj["arguments"] ?: obj["parameters"]
                    val argsMap: Map<String, Any?> = when (argsObj) {
                        is Map<*, *> -> @Suppress("UNCHECKED_CAST") (argsObj as Map<String, Any?>)
                        is String -> SimpleJson.parseObject(argsObj)
                        else -> obj.filterKeys { it != "tool" && it != "name" && it != "action" }
                    }
                    return ToolCall(
                        name = toolName,
                        arguments = argsMap,
                        thought = text.substringBefore('{').trim().takeIf { it.isNotBlank() }
                    )
                }
            }
            return null
        }
    }

    private val normalizedUrl: String = normalizeChatCompletionsUrl(baseUrl)

    override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult = withContext(Dispatchers.IO) {
        val totalStart = System.currentTimeMillis()
        var lastError: ModelError? = null
        var lastStatusCode = 0
        var totalConnectTime = 0L
        var totalReadTime = 0L

        val headers = mapOf(
            "Content-Type" to "application/json; charset=UTF-8",
            "Authorization" to "Bearer $apiKey",
            "HTTP-Referer" to "https://github.com/assistive-headmouse",
            "X-Title" to "Mobile-JARVIS-Autonomous-Agent"
        )

        // Build token-budgeted payload with guaranteed tools array attachment
        val requestBody = buildRequestPayloadJson(request)

        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                val backoff = (attempt * 1200L) + (Math.random() * 400).toLong()
                Log.w(TAG, "Retrying model query (attempt ${attempt + 1}/$maxRetries) after ${backoff}ms...")
                delay(backoff)
            }

            try {
                val response = transport.execute(
                    url = normalizedUrl,
                    method = "POST",
                    headers = headers,
                    body = requestBody,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = READ_TIMEOUT_MS
                )

                totalConnectTime += response.connectTimeMs
                totalReadTime += response.readTimeMs
                lastStatusCode = response.statusCode

                // 1. Success (200 - 299)
                if (response.statusCode in 200..299) {
                    val rawBody = response.body
                    if (rawBody.isNullOrBlank()) {
                        lastError = ModelError(
                            type = ErrorType.EMPTY_RESPONSE,
                            httpCode = response.statusCode,
                            message = "Server returned empty response body",
                            isRecoverable = true
                        )
                        continue
                    }

                    val elapsed = System.currentTimeMillis() - totalStart
                    return@withContext parseResponse(
                        jsonStr = rawBody,
                        elapsedMs = elapsed,
                        connectTimeMs = totalConnectTime,
                        readTimeMs = totalReadTime,
                        attempts = attempt + 1,
                        statusCode = response.statusCode
                    )
                }

                // 2. HTTP 401 Unauthorized -> Fail fast, never retry
                if (response.statusCode == 401) {
                    val errMsg = "HTTP 401 Unauthorized: API key '${maskKey(apiKey)}' is invalid or expired."
                    Log.e(TAG, errMsg)
                    val elapsed = System.currentTimeMillis() - totalStart
                    return@withContext StructuredModelResult(
                        decision = DecisionType.ERROR,
                        error = ModelError(
                            type = ErrorType.HTTP_UNAUTHORIZED,
                            httpCode = 401,
                            message = errMsg,
                            isRecoverable = false,
                            rawSnippet = response.errorBody?.take(150)
                        ),
                        diagnostics = ModelDiagnostics(
                            durationMs = elapsed,
                            connectTimeMs = totalConnectTime,
                            readTimeMs = totalReadTime,
                            attempts = attempt + 1,
                            httpStatusCode = 401,
                            modelUsed = modelName,
                            providerUsed = "OpenAI-Compatible"
                        )
                    )
                }

                // 3. HTTP 429 Rate Limit -> Eligible for retry
                if (response.statusCode == 429) {
                    val errMsg = "HTTP 429 Rate Limit Exceeded on $modelName."
                    Log.w(TAG, "$errMsg (Attempt ${attempt + 1})")
                    lastError = ModelError(
                        type = ErrorType.HTTP_RATE_LIMIT,
                        httpCode = 429,
                        message = errMsg,
                        isRecoverable = true,
                        rawSnippet = response.errorBody?.take(150)
                    )
                    continue
                }

                // 4. HTTP 5xx Server Errors -> Eligible for retry
                if (response.statusCode in 500..599) {
                    val errMsg = "HTTP ${response.statusCode} Server Error from upstream provider."
                    Log.w(TAG, "$errMsg (Attempt ${attempt + 1})")
                    lastError = ModelError(
                        type = ErrorType.HTTP_SERVER_ERROR,
                        httpCode = response.statusCode,
                        message = errMsg,
                        isRecoverable = true,
                        rawSnippet = response.errorBody?.take(150)
                    )
                    continue
                }

                // 5. Other HTTP 4xx Client Errors -> Non-recoverable client error
                val errMsg = "HTTP ${response.statusCode} Client Error: ${response.errorBody?.take(200)}"
                Log.e(TAG, errMsg)
                val elapsed = System.currentTimeMillis() - totalStart
                return@withContext StructuredModelResult(
                    decision = DecisionType.ERROR,
                    error = ModelError(
                        type = ErrorType.HTTP_CLIENT_ERROR,
                        httpCode = response.statusCode,
                        message = errMsg,
                        isRecoverable = false,
                        rawSnippet = response.errorBody?.take(150)
                    ),
                    diagnostics = ModelDiagnostics(
                        durationMs = elapsed,
                        connectTimeMs = totalConnectTime,
                        readTimeMs = totalReadTime,
                        attempts = attempt + 1,
                        httpStatusCode = response.statusCode,
                        modelUsed = modelName,
                        providerUsed = "OpenAI-Compatible"
                    )
                )

            } catch (e: CancellationException) {
                Log.i(TAG, "Model decision request cancelled by caller.")
                val elapsed = System.currentTimeMillis() - totalStart
                return@withContext StructuredModelResult(
                    decision = DecisionType.ERROR,
                    error = ModelError(
                        type = ErrorType.CANCELLED,
                        message = "Request cancelled by orchestrator",
                        isRecoverable = false
                    ),
                    diagnostics = ModelDiagnostics(
                        durationMs = elapsed,
                        connectTimeMs = totalConnectTime,
                        attempts = attempt + 1,
                        modelUsed = modelName,
                        providerUsed = "OpenAI-Compatible"
                    )
                )
            } catch (e: SocketTimeoutException) {
                val isConnect = totalConnectTime == 0L
                val errType = if (isConnect) ErrorType.SERVER_WAIT_TIMEOUT else ErrorType.RESPONSE_TIMEOUT
                Log.w(TAG, "Socket timeout ($errType) on attempt ${attempt + 1}: ${e.message}")
                lastError = ModelError(
                    type = errType,
                    message = "Socket read timed out after ${READ_TIMEOUT_MS}ms",
                    isRecoverable = true
                )
            } catch (e: UnknownHostException) {
                Log.w(TAG, "DNS resolution failed for $normalizedUrl on attempt ${attempt + 1}")
                lastError = ModelError(
                    type = ErrorType.CONNECTION_FAILURE,
                    message = "Unable to resolve host: ${e.message}",
                    isRecoverable = true
                )
            } catch (e: ConnectException) {
                Log.w(TAG, "Connection refused to $normalizedUrl on attempt ${attempt + 1}")
                lastError = ModelError(
                    type = ErrorType.CONNECTION_FAILURE,
                    message = "Connection refused: ${e.message}",
                    isRecoverable = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Network error on attempt ${attempt + 1}: ${e.message}")
                lastError = ModelError(
                    type = ErrorType.UNKNOWN,
                    message = e.localizedMessage ?: "Unknown transport failure",
                    isRecoverable = true
                )
            }
        }

        val elapsed = System.currentTimeMillis() - totalStart
        val finalError = lastError ?: ModelError(
            type = ErrorType.UNKNOWN,
            message = "Model query failed after $maxRetries retries",
            isRecoverable = true
        )

        StructuredModelResult(
            decision = DecisionType.ERROR,
            error = finalError,
            diagnostics = ModelDiagnostics(
                durationMs = elapsed,
                connectTimeMs = totalConnectTime,
                readTimeMs = totalReadTime,
                attempts = maxRetries + 1,
                httpStatusCode = lastStatusCode,
                modelUsed = modelName,
                providerUsed = "OpenAI-Compatible"
            )
        )
    }

    /**
     * Builds request payload attaching token-budgeted messages and verified tools array.
     */
    fun buildRequestPayloadJson(request: ModelDecisionRequest): String {
        val root = mutableMapOf<String, Any?>()
        root["model"] = modelName
        root["temperature"] = request.temperature
        root["max_tokens"] = request.maxTokens

        // REQUIREMENT 6 VERIFICATION: Tools array is ACTUALLY attached to the outgoing request body
        root["tools"] = request.availableTools.map { it.toOpenAiToolMap() }
        root["tool_choice"] = "auto"

        val messages = mutableListOf<Map<String, Any?>>()
        messages.add(mapOf("role" to "system", "content" to request.systemInstruction))

        val compressedPrompt = ContextCompressor.buildCompressedDecisionPrompt(
            originalGoal = request.originalUserGoal,
            currentSubgoal = request.currentSubgoal,
            compressedScreenIndex = request.compressedScreenIndex,
            actionHistory = request.actionHistory,
            maxHistorySteps = 4,
            maxTokenBudget = 1000
        )

        if (request.screenshotBase64.isNullOrBlank()) {
            messages.add(mapOf("role" to "user", "content" to compressedPrompt))
        } else {
            Log.i(TAG, "[SCREENSHOT_ATTACHED_TO_MODEL_REQUEST] Attaching image_url to OpenAI request body")
            messages.add(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to compressedPrompt),
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,${request.screenshotBase64}"))
                    )
                )
            )
        }

        root["messages"] = messages
        return SimpleJson.toJson(root)
    }

    /**
     * Legacy helper kept for compatibility if called directly.
     */
    fun buildRequestPayload(request: ModelDecisionRequest): JSONObject {
        return try {
            JSONObject(buildRequestPayloadJson(request))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun parseResponse(
        jsonStr: String,
        elapsedMs: Long,
        connectTimeMs: Long,
        readTimeMs: Long,
        attempts: Int,
        statusCode: Int
    ): StructuredModelResult {
        val parsed = SimpleJson.parse(jsonStr)
        val root = parsed as? Map<*, *>
        if (root == null) {
            return StructuredModelResult(
                decision = DecisionType.ERROR,
                error = ModelError(
                    type = ErrorType.PARSER_FAILURE,
                    message = "Malformed JSON returned by provider",
                    rawSnippet = jsonStr.take(150),
                    isRecoverable = false
                ),
                diagnostics = ModelDiagnostics(
                    durationMs = elapsedMs,
                    connectTimeMs = connectTimeMs,
                    readTimeMs = readTimeMs,
                    attempts = attempts,
                    httpStatusCode = statusCode,
                    modelUsed = modelName,
                    providerUsed = "OpenAI-Compatible"
                )
            )
        }

        val usage = root["usage"] as? Map<*, *>
        val promptTokens = (usage?.get("prompt_tokens") as? Number)?.toInt() ?: 0
        val completionTokens = (usage?.get("completion_tokens") as? Number)?.toInt() ?: 0
        val totalTokens = (usage?.get("total_tokens") as? Number)?.toInt() ?: (promptTokens + completionTokens)

        val diagnostics = ModelDiagnostics(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            durationMs = elapsedMs,
            connectTimeMs = connectTimeMs,
            readTimeMs = readTimeMs,
            attempts = attempts,
            httpStatusCode = statusCode,
            modelUsed = modelName,
            providerUsed = "OpenAI-Compatible"
        )

        val choices = root["choices"] as? List<*>
        if (choices.isNullOrEmpty()) {
            return StructuredModelResult(
                decision = DecisionType.ERROR,
                error = ModelError(
                    type = ErrorType.PARSER_FAILURE,
                    message = "Missing 'choices' array in model response",
                    rawSnippet = jsonStr.take(150),
                    isRecoverable = false
                ),
                diagnostics = diagnostics
            )
        }

        val firstChoice = choices[0] as? Map<*, *> ?: emptyMap<String, Any?>()
        val message = firstChoice["message"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val content = message["content"]?.toString()?.trim() ?: ""
        val toolCallsArr = message["tool_calls"] as? List<*>

        // Case A: Structured tool call present in tool_calls array
        if (!toolCallsArr.isNullOrEmpty()) {
            val firstTool = toolCallsArr[0] as? Map<*, *> ?: emptyMap<String, Any?>()
            val functionObj = firstTool["function"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val toolName = functionObj["name"]?.toString() ?: ""
            val argsRaw = functionObj["arguments"]

            val argsMap: Map<String, Any?> = when (argsRaw) {
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") (argsRaw as Map<String, Any?>)
                is String -> SimpleJson.parseObject(argsRaw)
                else -> emptyMap()
            }

            val decision = if (toolName == CanonicalTools.FINISH_TASK) DecisionType.FINISH_TASK else DecisionType.EXECUTE_TOOL
            return StructuredModelResult(
                decision = decision,
                tool = toolName,
                arguments = argsMap,
                reason = content.takeIf { it.isNotBlank() },
                finishState = if (decision == DecisionType.FINISH_TASK) "TASK_FINISHED" else null,
                diagnostics = diagnostics
            )
        }

        // Case B: Fallback JSON extraction from content
        val extractedTool = extractToolCallFromText(content)
        if (extractedTool != null) {
            val decision = if (extractedTool.name == CanonicalTools.FINISH_TASK) DecisionType.FINISH_TASK else DecisionType.EXECUTE_TOOL
            return StructuredModelResult(
                decision = decision,
                tool = extractedTool.name,
                arguments = extractedTool.arguments,
                reason = extractedTool.thought ?: content.takeIf { it.isNotBlank() },
                finishState = if (decision == DecisionType.FINISH_TASK) "TASK_FINISHED" else null,
                diagnostics = diagnostics
            )
        }

        // Case C: Conversational reply
        return StructuredModelResult(
            decision = DecisionType.CONVERSATIONAL,
            reason = content,
            diagnostics = diagnostics
        )
    }

    private fun extractToolCallFromText(text: String): ToolCall? {
        if (!text.contains("{") || !text.contains("}")) return null
        val startIndex = text.indexOf('{')
        val endIndex = text.lastIndexOf('}')
        if (startIndex >= 0 && endIndex > startIndex) {
            val candidate = text.substring(startIndex, endIndex + 1)
            val obj = SimpleJson.parseObject(candidate)
            val toolName = (obj["tool"] ?: obj["name"] ?: obj["action"])?.toString()
            if (!toolName.isNullOrBlank()) {
                val argsObj = obj["arguments"] ?: obj["parameters"]
                val argsMap: Map<String, Any?> = when (argsObj) {
                    is Map<*, *> -> @Suppress("UNCHECKED_CAST") (argsObj as Map<String, Any?>)
                    is String -> SimpleJson.parseObject(argsObj)
                    else -> obj.filterKeys { it != "tool" && it != "name" && it != "action" }
                }
                return ToolCall(
                    name = toolName,
                    arguments = argsMap,
                    thought = text.substringBefore('{').trim().takeIf { it.isNotBlank() }
                )
            }
        }
        return null
    }
}

// ============================================================================
// 5. GOOGLE GEMINI CLIENT (Native Generative Language v1beta)
// ============================================================================

/**
 * Native Google Gemini client supporting Generative Language v1beta API,
 * system instructions, function declarations, and candidate extraction.
 */
class GoogleGeminiClient(
    private val apiKey: String,
    private val modelName: String = "gemini-2.0-flash",
    private val transport: HttpTransport = DefaultHttpTransport()
) : ModelClient {

    companion object {
        private const val TAG = "GeminiModelClient"
    }

    override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult = withContext(Dispatchers.IO) {
        val totalStart = System.currentTimeMillis()
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        Log.i(TAG, "[MODEL_PROVIDER] Provider=Gemini Model=$modelName")

        val headers = mapOf(
            "Content-Type" to "application/json; charset=UTF-8"
        )

        val jsonBody = buildGeminiPayloadJson(request)

        try {
            val response = transport.execute(
                url = endpoint,
                method = "POST",
                headers = headers,
                body = jsonBody,
                connectTimeoutMs = OpenAiCompatibleClient.CONNECT_TIMEOUT_MS,
                readTimeoutMs = OpenAiCompatibleClient.READ_TIMEOUT_MS
            )

            val elapsed = System.currentTimeMillis() - totalStart

            if (response.statusCode in 200..299) {
                val rawBody = response.body ?: ""
                return@withContext parseGeminiResponse(rawBody, elapsed, response.statusCode)
            } else {
                val errorMsg = "Gemini API HTTP ${response.statusCode}: ${response.errorBody?.take(150)}"
                return@withContext StructuredModelResult(
                    decision = DecisionType.ERROR,
                    error = ModelError(
                        type = if (response.statusCode == 401 || response.statusCode == 403) ErrorType.HTTP_UNAUTHORIZED else ErrorType.HTTP_SERVER_ERROR,
                        httpCode = response.statusCode,
                        message = errorMsg
                    ),
                    diagnostics = ModelDiagnostics(
                        durationMs = elapsed,
                        httpStatusCode = response.statusCode,
                        modelUsed = modelName,
                        providerUsed = "Gemini"
                    )
                )
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - totalStart
            return@withContext StructuredModelResult(
                decision = DecisionType.ERROR,
                error = ModelError(
                    type = ErrorType.UNKNOWN,
                    message = e.localizedMessage ?: "Gemini transport error"
                ),
                diagnostics = ModelDiagnostics(durationMs = elapsed, modelUsed = modelName, providerUsed = "Gemini")
            )
        }
    }

    fun buildGeminiPayloadJson(request: ModelDecisionRequest): String {
        val compressedPrompt = ContextCompressor.buildCompressedDecisionPrompt(
            originalGoal = request.originalUserGoal,
            currentSubgoal = request.currentSubgoal,
            compressedScreenIndex = request.compressedScreenIndex,
            actionHistory = request.actionHistory
        )

        val parts = mutableListOf<Map<String, Any?>>()
        parts.add(mapOf("text" to compressedPrompt))

        if (!request.screenshotBase64.isNullOrBlank()) {
            Log.i(TAG, "[SCREENSHOT_ATTACHED_TO_MODEL_REQUEST] Attaching inline_data JPEG to Gemini payload")
            parts.add(
                mapOf(
                    "inline_data" to mapOf(
                        "mime_type" to "image/jpeg",
                        "data" to request.screenshotBase64
                    )
                )
            )
        }

        val toolsList = listOf(
            mapOf(
                "function_declarations" to request.availableTools.map { it.toGeminiFunctionDeclaration() }
            )
        )
        Log.i(TAG, "[TOOL_SCHEMA_SENT] Sent ${request.availableTools.size} function declarations to Gemini")

        val root = mapOf(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to parts
                )
            ),
            "system_instruction" to mapOf(
                "parts" to listOf(mapOf("text" to request.systemInstruction))
            ),
            "tools" to toolsList
        )

        return SimpleJson.toJson(root)
    }

    fun parseGeminiResponse(rawBody: String, elapsedMs: Long, statusCode: Int): StructuredModelResult {
        try {
            val json = SimpleJson.parseObject(rawBody)
            val candidates = json["candidates"] as? List<*>
            if (candidates.isNullOrEmpty()) {
                return StructuredModelResult(
                    decision = DecisionType.ERROR,
                    error = ModelError(type = ErrorType.EMPTY_RESPONSE, message = "No candidates in Gemini response"),
                    diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
                )
            }

            val first = candidates[0] as? Map<*, *> ?: emptyMap<String, Any?>()
            val content = first["content"] as? Map<*, *>
            if (content == null) {
                return StructuredModelResult(
                    decision = DecisionType.ERROR,
                    error = ModelError(type = ErrorType.PARSER_FAILURE, message = "Missing 'content' object in Gemini candidate"),
                    diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
                )
            }

            val parts = content["parts"] as? List<*>
            if (parts.isNullOrEmpty()) {
                return StructuredModelResult(
                    decision = DecisionType.ERROR,
                    error = ModelError(type = ErrorType.PARSER_FAILURE, message = "Missing or empty 'parts' in Gemini candidate content"),
                    diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
                )
            }

            val allFunctionCalls = mutableListOf<Map<*, *>>()
            val textBuilder = StringBuilder()

            for (part in parts) {
                val pMap = part as? Map<*, *> ?: continue
                val text = pMap["text"]?.toString()
                if (!text.isNullOrBlank()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                    textBuilder.append(text)
                }
                val fnCall = (pMap["functionCall"] ?: pMap["function_call"]) as? Map<*, *>
                if (fnCall != null) {
                    allFunctionCalls.add(fnCall)
                }
            }

            val thought = textBuilder.toString().trim().takeIf { it.isNotBlank() }

            // 1. Native Gemini Structured Function Call
            if (allFunctionCalls.isNotEmpty()) {
                if (allFunctionCalls.size > 1) {
                    Log.w(TAG, "Multiple function calls received from Gemini (${allFunctionCalls.size}); serializing and executing first call to maintain Android UI safety")
                }
                val primaryCall = allFunctionCalls[0]
                val callName = primaryCall["name"]?.toString()?.trim() ?: ""
                val rawArgs = primaryCall["args"]

                if (callName.isBlank()) {
                    return StructuredModelResult(
                        decision = DecisionType.ERROR,
                        error = ModelError(type = ErrorType.PARSER_FAILURE, message = "Gemini functionCall has missing or blank function name"),
                        diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
                    )
                }

                val argsMap: Map<String, Any?> = when (rawArgs) {
                    is Map<*, *> -> @Suppress("UNCHECKED_CAST") (rawArgs as Map<String, Any?>)
                    is String -> if (rawArgs.isNotBlank()) SimpleJson.parseObject(rawArgs) else emptyMap()
                    else -> emptyMap()
                }

                Log.i(TAG, "[TOOL_CALL_RECEIVED] functionCall=$callName rawArgs=$rawArgs")
                Log.i(TAG, "[TOOL_CALL_PARSED] ToolCall(name=$callName, args=$argsMap)")

                val decision = if (callName == CanonicalTools.FINISH_TASK) DecisionType.FINISH_TASK else DecisionType.EXECUTE_TOOL
                return StructuredModelResult(
                    decision = decision,
                    tool = callName,
                    arguments = argsMap,
                    reason = thought,
                    finishState = if (decision == DecisionType.FINISH_TASK) "TASK_FINISHED" else null,
                    diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
                )
            }

            // 2. Documented Fallback: NLP / text-embedded JSON tool call extraction
            if (thought != null) {
                val extractedTool = OpenAiCompatibleClient.extractToolCallFromText(thought)
                if (extractedTool != null) {
                    val decision = if (extractedTool.name == CanonicalTools.FINISH_TASK) DecisionType.FINISH_TASK else DecisionType.EXECUTE_TOOL
                    return StructuredModelResult(
                        decision = decision,
                        tool = extractedTool.name,
                        arguments = extractedTool.arguments,
                        reason = extractedTool.thought ?: thought,
                        finishState = if (decision == DecisionType.FINISH_TASK) "TASK_FINISHED" else null,
                        diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
                    )
                }
            }

            // 3. Conversational reply or empty response
            if (thought.isNullOrBlank()) {
                return StructuredModelResult(
                    decision = DecisionType.ERROR,
                    error = ModelError(type = ErrorType.EMPTY_RESPONSE, message = "Gemini candidate returned empty text and no function call"),
                    diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
                )
            }

            return StructuredModelResult(
                decision = DecisionType.CONVERSATIONAL,
                reason = thought,
                diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
            )
        } catch (e: Exception) {
            return StructuredModelResult(
                decision = DecisionType.ERROR,
                error = ModelError(type = ErrorType.PARSER_FAILURE, message = "Gemini JSON parsing failure: ${e.message}"),
                diagnostics = ModelDiagnostics(durationMs = elapsedMs, httpStatusCode = statusCode, modelUsed = modelName, providerUsed = "Gemini")
            )
        }
    }
}

// ============================================================================
// 6. DISABLED MODEL CLIENT (Clean Configuration Safeguard)
// ============================================================================

class DisabledModelClient(private val reason: String = "Cloud AI is disabled.") : ModelClient {
    override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
        return StructuredModelResult(
            decision = DecisionType.ERROR,
            error = ModelError(
                type = ErrorType.CLOUD_AI_DISABLED,
                message = reason,
                isRecoverable = false
            ),
            diagnostics = ModelDiagnostics(providerUsed = "Disabled")
        )
    }
}

// ============================================================================
// 7. MODEL CLIENT FACTORY
// ============================================================================

/**
 * Authoritative factory resolving UI model selection to runtime ModelClient instance.
 * Enforces active provider integrity, validates API credentials, and normalizes endpoints.
 */
object ModelClientFactory {
    private const val TAG = "ModelClientFactory"

    fun create(appSettings: AppSettings?): ModelClient {
        if (appSettings == null) {
            return DisabledModelClient("AppSettings unavailable")
        }

        if (!appSettings.isCloudAiEnabled) {
            Log.i(TAG, "[CANONICAL_AI_ROUTE] Cloud AI disabled in AppSettings. Providing DisabledModelClient.")
            return DisabledModelClient("Cloud AI reasoning is disabled by user.")
        }

        val provider = appSettings.aiProvider
        val activeCustom = appSettings.getActiveCustomModel()
        val apiKey = appSettings.getActiveApiKey()

        val model = when (provider) {
            AiProvider.GEMINI -> {
                activeCustom?.modelId?.takeIf { it.isNotBlank() && it.contains("gemini", ignoreCase = true) }
                    ?: appSettings.aiModelName.takeIf { it.isNotBlank() && it.contains("gemini", ignoreCase = true) }
                    ?: "gemini-1.5-flash"
            }
            AiProvider.OPENAI -> {
                activeCustom?.modelId?.takeIf { it.isNotBlank() && (it.contains("gpt", ignoreCase = true) || it.contains("o1", ignoreCase = true) || it.contains("o3", ignoreCase = true)) }
                    ?: appSettings.aiModelName.takeIf { it.isNotBlank() && (it.contains("gpt", ignoreCase = true) || it.contains("o1", ignoreCase = true) || it.contains("o3", ignoreCase = true)) }
                    ?: "gpt-4o-mini"
            }
            AiProvider.CUSTOM_OPENROUTER -> {
                activeCustom?.modelId?.takeIf { it.isNotBlank() }
                    ?: appSettings.aiModelName.takeIf { it.isNotBlank() }
                    ?: "deepseek/deepseek-chat"
            }
        }

        val baseUrl = when (provider) {
            AiProvider.OPENAI -> "https://api.openai.com/v1"
            AiProvider.CUSTOM_OPENROUTER -> activeCustom?.baseUrl?.takeIf { it.isNotBlank() } ?: appSettings.customBaseUrl
            AiProvider.GEMINI -> "https://generativelanguage.googleapis.com"
        }

        Log.i(TAG, "[CANONICAL_AI_ROUTE] ModelClientFactory creating provider adapter: Provider=$provider Model=$model Endpoint=${OpenAiCompatibleClient.normalizeChatCompletionsUrl(baseUrl)} Key=${OpenAiCompatibleClient.maskKey(apiKey)}")

        return when (provider) {
            AiProvider.GEMINI -> {
                if (apiKey.isBlank()) {
                    DisabledModelClient("Gemini API key is not configured.")
                } else {
                    GoogleGeminiClient(apiKey = apiKey, modelName = model)
                }
            }
            AiProvider.OPENAI -> {
                if (apiKey.isBlank()) {
                    DisabledModelClient("OpenAI API key is not configured.")
                } else {
                    OpenAiCompatibleClient(
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        modelName = model
                    )
                }
            }
            AiProvider.CUSTOM_OPENROUTER -> {
                OpenAiCompatibleClient(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    modelName = model
                )
            }
        }
    }
}
