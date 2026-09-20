package com.assistive.headmouse.agent.jarvis.autonomous

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.assistive.headmouse.service.HeadMouseAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Definition of an autonomous tool capability (R20, R21).
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JSONObject,
    val requiresAccessibility: Boolean = false,
    val requiresInternet: Boolean = false
) {
    fun toOpenAiToolSchema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parametersSchema)
        })
    }

    fun toGeminiFunctionDeclaration(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("parameters", parametersSchema)
    }
}

/**
 * Tool Permission and Capability Manager (R20).
 * Validates device readiness before advertising or executing tools.
 */
class ToolCapabilityManager(private val context: Context? = null) {

    val isAccessibilityAvailable: Boolean
        get() = HeadMouseAccessibilityService.instance != null

    val isInternetAvailable: Boolean
        get() {
            val ctx = context ?: return true
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    fun isToolCapable(tool: ToolDefinition): Boolean {
        if (tool.requiresAccessibility && !isAccessibilityAvailable) return false
        if (tool.requiresInternet && !isInternetAvailable) return false
        return true
    }
}

/**
 * Central Autonomous Tool Registry (R21, R22).
 * Formulates tool catalogs, advertises only active tools, and executes them with structured results.
 */
class ToolRegistry(private val capabilityManager: ToolCapabilityManager = ToolCapabilityManager()) {

    private val allTools = mutableListOf<ToolDefinition>()

    init {
        registerDefaultTools()
    }

    private fun registerDefaultTools() {
        allTools.add(
            ToolDefinition(
                name = "get_screen_state",
                description = "Captures the live hierarchy of visible interactive UI nodes on the active window.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
                requiresAccessibility = true
            )
        )

        allTools.add(
            ToolDefinition(
                name = "android_tap",
                description = "Clicks a visible button or element identified by node index (#number), text, or content description.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("target", JSONObject().apply {
                            put("type", "string")
                            put("description", "Node index like '#1' or exact element text like 'Search'")
                        })
                    })
                    put("required", JSONArray().apply { put("target") })
                },
                requiresAccessibility = true
            )
        )

        allTools.add(
            ToolDefinition(
                name = "android_type",
                description = "Types text into the currently focused or active input field.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply {
                            put("type", "string")
                            put("description", "The text to enter")
                        })
                    })
                    put("required", JSONArray().apply { put("text") })
                },
                requiresAccessibility = true
            )
        )

        allTools.add(
            ToolDefinition(
                name = "android_open_app",
                description = "Launches an Android application by package name or common app name.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("app_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "App name or package e.g. 'com.android.chrome', 'YouTube', 'Play Store'")
                        })
                    })
                    put("required", JSONArray().apply { put("app_name") })
                }
            )
        )

        allTools.add(
            ToolDefinition(
                name = "android_home",
                description = "Presses the global hardware Home button to return to the Android launcher.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
                requiresAccessibility = true
            )
        )

        allTools.add(
            ToolDefinition(
                name = "android_back",
                description = "Presses the global hardware Back button to navigate back in history.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
                requiresAccessibility = true
            )
        )

        allTools.add(
            ToolDefinition(
                name = "android_recents",
                description = "Opens the Android Recent Apps / Overview screen.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
                requiresAccessibility = true
            )
        )

        allTools.add(
            ToolDefinition(
                name = "android_scroll",
                description = "Scrolls the screen in a specified direction.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("direction", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray().apply { put("DOWN"); put("UP"); put("LEFT"); put("RIGHT") })
                        })
                    })
                    put("required", JSONArray().apply { put("direction") })
                },
                requiresAccessibility = true
            )
        )

        allTools.add(
            ToolDefinition(
                name = "get_current_time",
                description = "Retrieves true local date, time, day of week, and timezone from the device clock.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            )
        )

        allTools.add(
            ToolDefinition(
                name = "web_search",
                description = "Executes a live web query to fetch real-time facts, news, and search snippets.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "Search query")
                        })
                    })
                    put("required", JSONArray().apply { put("query") })
                },
                requiresInternet = true
            )
        )

        allTools.add(
            ToolDefinition(
                name = "get_device_status",
                description = "Inspects hardware status: battery percentage, charging state, network type, and storage.",
                parametersSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            )
        )
    }

    /**
     * Returns only tools that currently satisfy capability and permission prerequisites (R21).
     */
    fun getAvailableTools(): List<ToolDefinition> {
        return allTools.filter { capabilityManager.isToolCapable(it) }
    }

    /**
     * Generates an OpenAI-compatible tools array for LLM requests.
     */
    fun getOpenAiToolsJson(): JSONArray {
        val arr = JSONArray()
        for (tool in getAvailableTools()) {
            arr.put(tool.toOpenAiToolSchema())
        }
        return arr
    }

    /**
     * Formats available tools into a clear text prompt block.
     */
    fun getPromptToolSummary(): String {
        val tools = getAvailableTools()
        val sb = StringBuilder()
        sb.append("AVAILABLE REAL-WORLD TOOLS:\n")
        for (t in tools) {
            sb.append("- ${t.name}: ${t.description}\n")
        }
        return sb.toString().trim()
    }
}
