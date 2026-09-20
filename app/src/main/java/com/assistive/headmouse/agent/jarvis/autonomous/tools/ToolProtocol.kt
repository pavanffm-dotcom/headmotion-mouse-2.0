package com.assistive.headmouse.agent.jarvis.autonomous.tools

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Represents a structured tool request issued by the model or planner.
 */
data class ToolCall(
    val callId: String = "call_${UUID.randomUUID().toString().take(8)}",
    val name: String,
    val arguments: Map<String, Any?>,
    val thought: String? = null
) {
    /**
     * Normalizes alternative or legacy tool names and argument keys to canonical representations.
     */
    fun normalize(): ToolCall {
        return when (name.lowercase()) {
            "tap" -> {
                if (arguments.containsKey("x") && arguments.containsKey("y")) {
                    copy(name = CanonicalTools.TAP_COORDINATES)
                } else {
                    val newArgs = arguments.toMutableMap()
                    if (newArgs.containsKey("target") && !newArgs.containsKey("label")) {
                        val targetVal = newArgs["target"]?.toString() ?: ""
                        if (targetVal.startsWith("#")) {
                            newArgs["node_index"] = targetVal.removePrefix("#").toIntOrNull()
                        } else {
                            newArgs["label"] = targetVal
                        }
                    }
                    copy(name = CanonicalTools.TAP_ELEMENT, arguments = newArgs)
                }
            }
            "press_back", "back", "android_back" -> {
                copy(name = CanonicalTools.PRESS_NAVIGATION, arguments = mapOf("action" to "BACK"))
            }
            "home", "android_home" -> {
                copy(name = CanonicalTools.PRESS_NAVIGATION, arguments = mapOf("action" to "HOME"))
            }
            "recents", "android_recents" -> {
                copy(name = CanonicalTools.PRESS_NAVIGATION, arguments = mapOf("action" to "RECENTS"))
            }
            "android_tap" -> {
                val newArgs = arguments.toMutableMap()
                if (newArgs.containsKey("target")) {
                    val targetVal = newArgs["target"]?.toString() ?: ""
                    if (targetVal.startsWith("#")) {
                        newArgs["node_index"] = targetVal.removePrefix("#").toIntOrNull()
                    } else {
                        newArgs["label"] = targetVal
                    }
                }
                copy(name = CanonicalTools.TAP_ELEMENT, arguments = newArgs)
            }
            "android_open_app" -> {
                val pkg = arguments["app_name"] ?: arguments["package_or_name"] ?: ""
                copy(name = CanonicalTools.LAUNCH_APP, arguments = mapOf("package_or_name" to pkg))
            }
            "web_search", "search_web", "google_search", "search_internet" -> {
                val q = arguments["query"] ?: arguments["q"] ?: arguments["search_query"] ?: ""
                copy(name = CanonicalTools.WEB_SEARCH, arguments = mapOf("query" to q))
            }
            "android_type" -> {
                copy(name = CanonicalTools.TYPE_TEXT)
            }
            "android_scroll" -> {
                copy(name = CanonicalTools.SCROLL)
            }
            else -> this
        }
    }
}

/**
 * Empirical result returned to the agent loop following tool execution.
 * Standardized across all 12 canonical tools.
 */
data class ActionResult(
    val success: Boolean,
    val tool: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val stateChanged: Boolean = false,
    val focusChanged: Boolean = false,
    val screenChanged: Boolean = false,
    val verification: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val recoverable: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val callId: String = "call_${UUID.randomUUID().toString().take(8)}",
    val verified: Boolean = false,
    val durationMs: Long = 0L,
    val postStateHash: String? = null
) {
    val toolName: String get() = tool
    val isRecoverable: Boolean get() = recoverable

    // Secondary constructor for backward compatibility with Phase 2 callers
    constructor(
        callId: String,
        toolName: String,
        success: Boolean,
        verified: Boolean,
        stateChanged: Boolean,
        errorCode: String? = null,
        errorMessage: String? = null,
        isRecoverable: Boolean = true,
        durationMs: Long = 0L,
        postStateHash: String? = null
    ) : this(
        success = success,
        tool = toolName,
        arguments = emptyMap(),
        stateChanged = stateChanged,
        focusChanged = false,
        screenChanged = false,
        verification = null,
        errorCode = errorCode,
        errorMessage = errorMessage,
        recoverable = isRecoverable,
        timestamp = System.currentTimeMillis(),
        callId = callId,
        verified = verified,
        durationMs = durationMs,
        postStateHash = postStateHash
    )
}

/**
 * Result of static tool validation.
 */
data class ToolValidationResult(
    val isValid: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

/**
 * Deterministic parameter validator for canonical tool calls.
 */
object ToolValidator {
    fun validate(toolCall: ToolCall): ToolValidationResult {
        val normalized = toolCall.normalize()
        val def = CanonicalTools.ALL_DEFINITIONS.firstOrNull { it.name == normalized.name }
            ?: return ToolValidationResult(
                isValid = false,
                errorCode = "UNKNOWN_TOOL",
                errorMessage = "Tool '${toolCall.name}' is not registered in the canonical registry."
            )

        val args = normalized.arguments

        for (param in def.parameters) {
            if (param.required) {
                val value = args[param.name]
                if (value == null || (value is String && value.isBlank())) {
                    return ToolValidationResult(
                        isValid = false,
                        errorCode = "MISSING_ARGUMENT",
                        errorMessage = "Required argument '${param.name}' is missing for tool '${def.name}'."
                    )
                }
            }

            val value = args[param.name] ?: continue

            // Type and range validation
            when (param.name) {
                "x", "y" -> {
                    val num = (value as? Number)?.toFloat()
                    if (num == null || num < 0f) {
                        return ToolValidationResult(
                            isValid = false,
                            errorCode = "INVALID_ARGUMENT",
                            errorMessage = "Coordinate '${param.name}' must be a non-negative number. Got: $value"
                        )
                    }
                }
                "duration_ms" -> {
                    val ms = (value as? Number)?.toLong()
                    if (ms == null || ms < 50L || ms > 10000L) {
                        return ToolValidationResult(
                            isValid = false,
                            errorCode = "INVALID_ARGUMENT",
                            errorMessage = "Argument 'duration_ms' must be between 50 and 10000 ms. Got: $value"
                        )
                    }
                }
                "direction" -> {
                    val dir = value.toString().uppercase()
                    if (param.enumValues != null && !param.enumValues.contains(dir)) {
                        return ToolValidationResult(
                            isValid = false,
                            errorCode = "INVALID_ARGUMENT",
                            errorMessage = "Invalid direction '$value'. Expected one of: ${param.enumValues}"
                        )
                    }
                }
                "action" -> {
                    val act = value.toString().uppercase()
                    if (param.enumValues != null && !param.enumValues.contains(act)) {
                        return ToolValidationResult(
                            isValid = false,
                            errorCode = "INVALID_ARGUMENT",
                            errorMessage = "Invalid navigation action '$value'. Expected one of: ${param.enumValues}"
                        )
                    }
                }
                "package_or_name" -> {
                    if (value.toString().isBlank()) {
                        return ToolValidationResult(
                            isValid = false,
                            errorCode = "INVALID_ARGUMENT",
                            errorMessage = "Argument 'package_or_name' cannot be blank."
                        )
                    }
                }
            }
        }

        return ToolValidationResult(isValid = true)
    }
}

/**
 * Describes a parameter for tool validation and model schema creation.
 */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String>? = null
)

/**
 * Canonical tool schema specification.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
) {
    fun toOpenAiToolJsonObject(): JSONObject {
        val properties = JSONObject()
        val requiredList = JSONArray()

        for (param in parameters) {
            val pObj = JSONObject()
            pObj.put("type", param.type)
            pObj.put("description", param.description)
            if (!param.enumValues.isNullOrEmpty()) {
                val eArr = JSONArray()
                param.enumValues.forEach { eArr.put(it) }
                pObj.put("enum", eArr)
            }
            properties.put(param.name, pObj)
            if (param.required) {
                requiredList.put(param.name)
            }
        }

        val parametersObj = JSONObject()
        parametersObj.put("type", "object")
        parametersObj.put("properties", properties)
        parametersObj.put("required", requiredList)

        val functionObj = JSONObject()
        functionObj.put("name", name)
        functionObj.put("description", description)
        functionObj.put("parameters", parametersObj)

        val toolObj = JSONObject()
        toolObj.put("type", "function")
        toolObj.put("function", functionObj)

        return toolObj
    }

    fun toOpenAiToolMap(): Map<String, Any?> {
        val properties = mutableMapOf<String, Any?>()
        val requiredList = mutableListOf<String>()

        for (param in parameters) {
            val pMap = mutableMapOf<String, Any?>()
            pMap["type"] = param.type
            pMap["description"] = param.description
            if (!param.enumValues.isNullOrEmpty()) {
                pMap["enum"] = param.enumValues
            }
            properties[param.name] = pMap
            if (param.required) {
                requiredList.add(param.name)
            }
        }

        val parametersMap = mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to requiredList
        )

        val functionMap = mapOf(
            "name" to name,
            "description" to description,
            "parameters" to parametersMap
        )

        return mapOf(
            "type" to "function",
            "function" to functionMap
        )
    }

    /**
     * Converts canonical tool definition to Gemini OpenAPI 3.03 FunctionDeclaration schema.
     */
    fun toGeminiFunctionDeclaration(): Map<String, Any?> {
        val properties = mutableMapOf<String, Any?>()
        val requiredList = mutableListOf<String>()

        for (param in parameters) {
            val pMap = mutableMapOf<String, Any?>()
            val geminiType = when (param.type.lowercase()) {
                "integer", "int" -> "INTEGER"
                "number", "float", "double" -> "NUMBER"
                "boolean", "bool" -> "BOOLEAN"
                "array" -> "ARRAY"
                "object" -> "OBJECT"
                else -> "STRING"
            }
            pMap["type"] = geminiType
            pMap["description"] = param.description
            if (!param.enumValues.isNullOrEmpty()) {
                pMap["enum"] = param.enumValues
            }
            properties[param.name] = pMap
            if (param.required) {
                requiredList.add(param.name)
            }
        }

        val parametersMap = mutableMapOf<String, Any?>(
            "type" to "OBJECT",
            "properties" to properties
        )
        if (requiredList.isNotEmpty()) {
            parametersMap["required"] = requiredList
        }

        return mapOf(
            "name" to name,
            "description" to description,
            "parameters" to parametersMap
        )
    }
}

/**
 * Canonical registry of the 12 primary autonomous tools.
 */
object CanonicalTools {
    const val OBSERVE_SCREEN = "observe_screen"
    const val TAP_ELEMENT = "tap_element"
    const val TAP_COORDINATES = "tap_coordinates"
    const val TYPE_TEXT = "type_text"
    const val SCROLL = "scroll"
    const val SWIPE = "swipe"
    const val LONG_PRESS = "long_press"
    const val PRESS_NAVIGATION = "press_navigation"
    const val LAUNCH_APP = "launch_app"
    const val WAIT = "wait"
    const val TAKE_SCREENSHOT = "take_screenshot"
    const val WEB_SEARCH = "web_search"
    const val FINISH_TASK = "finish_task"

    val ALL_DEFINITIONS: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = OBSERVE_SCREEN,
            description = "Captures and returns the live interactive UI hierarchy and active foreground app on screen.",
            parameters = listOf(
                ToolParameter("force_refresh", "boolean", "Whether to invalidate cache before reading.", required = false)
            )
        ),
        ToolDefinition(
            name = TAP_ELEMENT,
            description = "Clicks an interactive UI element identified by its numeric index (#ID) or visible label/text.",
            parameters = listOf(
                ToolParameter("node_index", "integer", "The numeric index of the node, e.g. 1 for #1", required = false),
                ToolParameter("label", "string", "The exact text, content description, or identifier of the element", required = false)
            )
        ),
        ToolDefinition(
            name = TAP_COORDINATES,
            description = "Dispatches a physical touch gesture at exact pixel coordinates (x, y) on the display.",
            parameters = listOf(
                ToolParameter("x", "number", "X coordinate in screen pixels", required = true),
                ToolParameter("y", "number", "Y coordinate in screen pixels", required = true)
            )
        ),
        ToolDefinition(
            name = TYPE_TEXT,
            description = "Enters text into the currently active or targeted editable input field.",
            parameters = listOf(
                ToolParameter("text", "string", "The text string to type into the field", required = true),
                ToolParameter("node_index", "integer", "Optional node index of the target EditText", required = false),
                ToolParameter("press_enter", "boolean", "Whether to submit / press search after typing", required = false)
            )
        ),
        ToolDefinition(
            name = SCROLL,
            description = "Scrolls the active scrollable view up or down.",
            parameters = listOf(
                ToolParameter("direction", "string", "DOWN reveals lower content; UP reveals upper content.", required = true, enumValues = listOf("DOWN", "UP")),
                ToolParameter("distance", "string", "Scroll distance: SHORT, MEDIUM, LONG", required = false, enumValues = listOf("SHORT", "MEDIUM", "LONG"))
            )
        ),
        ToolDefinition(
            name = SWIPE,
            description = "Executes a directional swipe gesture across the screen.",
            parameters = listOf(
                ToolParameter("direction", "string", "Swipe direction", required = true, enumValues = listOf("LEFT", "RIGHT", "UP", "DOWN")),
                ToolParameter("startX", "number", "Optional start X coordinate", required = false),
                ToolParameter("startY", "number", "Optional start Y coordinate", required = false)
            )
        ),
        ToolDefinition(
            name = LONG_PRESS,
            description = "Performs an extended hold gesture (800ms) on a UI element.",
            parameters = listOf(
                ToolParameter("node_index", "integer", "Node index of the element", required = false),
                ToolParameter("label", "string", "Label or text of the element", required = false)
            )
        ),
        ToolDefinition(
            name = PRESS_NAVIGATION,
            description = "Performs system-level navigation: BACK, HOME, or RECENTS.",
            parameters = listOf(
                ToolParameter("action", "string", "Navigation action to perform", required = true, enumValues = listOf("BACK", "HOME", "RECENTS"))
            )
        ),
        ToolDefinition(
            name = LAUNCH_APP,
            description = "Launches an installed Android application by package name or common app name (e.g. YouTube, Settings, WhatsApp).",
            parameters = listOf(
                ToolParameter("package_or_name", "string", "Package name like 'com.google.android.youtube' or app name like 'YouTube'", required = true)
            )
        ),
        ToolDefinition(
            name = WAIT,
            description = "Waits for a specified duration to allow screen content or network requests to settle.",
            parameters = listOf(
                ToolParameter("duration_ms", "integer", "Milliseconds to wait (100 to 3000)", required = true)
            )
        ),
        ToolDefinition(
            name = TAKE_SCREENSHOT,
            description = "Captures a visual frame of the active display for multimodal vision reasoning.",
            parameters = emptyList()
        ),
        ToolDefinition(
            name = WEB_SEARCH,
            description = "Executes a live web query to fetch real-time facts, news, information, and search snippets from the internet.",
            parameters = listOf(
                ToolParameter("query", "string", "The search query string to execute", required = true)
            )
        ),
        ToolDefinition(
            name = FINISH_TASK,
            description = "Signals that the user's high-level goal has been completely achieved or determined unachievable.",
            parameters = listOf(
                ToolParameter("success", "boolean", "True if the user goal was satisfied, false if unachievable", required = true),
                ToolParameter("spoken_summary", "string", "Brief, natural response spoken to the user", required = true)
            )
        )
    )

    fun toOpenAiToolsJsonArray(): JSONArray {
        val array = JSONArray()
        ALL_DEFINITIONS.forEach { array.put(it.toOpenAiToolJsonObject()) }
        return array
    }

    /**
     * Converts all canonical tools into Gemini-compatible tools payload wrapper.
     */
    fun toGeminiTools(): List<Map<String, Any?>> {
        return listOf(
            mapOf(
                "function_declarations" to ALL_DEFINITIONS.map { it.toGeminiFunctionDeclaration() }
            )
        )
    }
}
