package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import com.assistive.headmouse.agent.jarvis.JarvisBrain
import com.assistive.headmouse.agent.jarvis.action.ActionStep
import com.assistive.headmouse.agent.jarvis.action.ActionTarget
import com.assistive.headmouse.agent.jarvis.action.AutonomousActionType
import com.assistive.headmouse.agent.jarvis.action.ScreenState
import com.assistive.headmouse.agent.jarvis.action.TargetType
import com.assistive.headmouse.agent.jarvis.autonomous.model.DecisionType
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelClient
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelDecisionRequest
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ActionResult
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.preferences.AppSettings

class DynamicPlanner(
    private val toolRegistry: ToolRegistry,
    private val jarvisBrain: JarvisBrain? = null,
    private val appSettings: AppSettings? = null
) {

    companion object {
        private const val TAG = "DynamicPlanner"
    }

    /**
     * Builds an actionable multi-step plan for the current objective.
     */
    @Deprecated(
        message = "Legacy multi-step plan generation. Autonomous missions use decideNextAction(missionState, modelClient).",
        level = DeprecationLevel.WARNING
    )
    suspend fun createPlanForObjective(
        objective: MissionObjective,
        screenState: ScreenState,
        navigationBreadcrumbs: String
    ): List<ActionStep> {
        val steps = mutableListOf<ActionStep>()
        val goalLower = objective.title.lowercase().trim()

        Log.i(TAG, "Planning objective: \"${objective.title}\" | Foreground: ${screenState.packageName}")

        when {
            // Pattern 1: YouTube Shorts
            goalLower.contains("shorts") -> {
                if (!screenState.packageName.contains("youtube")) {
                    steps.add(ActionStep(steps.size + 1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "com.google.android.youtube"), waitAfterMs = 1500L, spokenUpdate = "Opening YouTube, Sir."))
                }
                steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Shorts"), waitAfterMs = 800L, spokenUpdate = "Switching to Shorts."))
            }

            // Pattern 2: Flashlight / Torch
            goalLower.contains("flashlight") || goalLower.contains("torch") -> {
                if (!screenState.packageName.contains("settings")) {
                    steps.add(ActionStep(steps.size + 1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "com.android.settings"), waitAfterMs = 1200L, spokenUpdate = "Opening Settings, Sir."))
                }
                val directFlashlight = screenState.nodes.find {
                    it.label.contains("flashlight", ignoreCase = true) || it.label.contains("torch", ignoreCase = true)
                }
                if (directFlashlight != null) {
                    steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, directFlashlight.label), waitAfterMs = 800L, spokenUpdate = "Toggling Flashlight, Sir."))
                } else {
                    steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Search settings"), waitAfterMs = 700L, spokenUpdate = "Searching for Flashlight."))
                    steps.add(ActionStep(steps.size + 1, AutonomousActionType.TYPE_TEXT, text = "Flashlight", waitAfterMs = 1000L))
                    steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Flashlight"), waitAfterMs = 800L, spokenUpdate = "Activating Flashlight."))
                }
            }

            // Pattern 3: App launch + search
            goalLower.contains("search") || goalLower.contains("dhoondho") -> {
                val searchTarget = when {
                    goalLower.contains("search") -> goalLower.substringAfter("search").trim().removePrefix("for").removePrefix("karo").trim()
                    goalLower.contains("dhoondho") -> goalLower.substringBefore("dhoondho").trim().substringAfterLast(" ").trim()
                    else -> ""
                }
                val isPlayStore = goalLower.contains("play store") || screenState.packageName.contains("vending")
                val isYouTube = goalLower.contains("youtube") || screenState.packageName.contains("youtube")
                val isInstagram = goalLower.contains("instagram") || screenState.packageName.contains("instagram")
                val isBrowser = goalLower.contains("browser") || goalLower.contains("chrome") || screenState.packageName.contains("chrome")

                when {
                    isPlayStore -> {
                        if (!screenState.packageName.contains("vending")) {
                            steps.add(ActionStep(steps.size + 1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "com.android.vending"), waitAfterMs = 1200L, spokenUpdate = "Opening Play Store, Sir."))
                        }
                        steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Search"), waitAfterMs = 700L, spokenUpdate = "Activating search."))
                        if (searchTarget.isNotBlank()) {
                            steps.add(ActionStep(steps.size + 1, AutonomousActionType.TYPE_TEXT, text = searchTarget, waitAfterMs = 900L, spokenUpdate = "Searching for $searchTarget."))
                        }
                    }
                    isYouTube -> {
                        if (!screenState.packageName.contains("youtube")) {
                            steps.add(ActionStep(steps.size + 1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "com.google.android.youtube"), waitAfterMs = 1200L, spokenUpdate = "Opening YouTube, Sir."))
                        }
                        steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Search"), waitAfterMs = 700L, spokenUpdate = "Activating search."))
                        if (searchTarget.isNotBlank()) {
                            steps.add(ActionStep(steps.size + 1, AutonomousActionType.TYPE_TEXT, text = searchTarget, waitAfterMs = 900L, spokenUpdate = "Searching for $searchTarget."))
                        }
                    }
                    isInstagram -> {
                        if (!screenState.packageName.contains("instagram")) {
                            steps.add(ActionStep(steps.size + 1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "com.instagram.android"), waitAfterMs = 1200L, spokenUpdate = "Opening Instagram, Sir."))
                        }
                        steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Search"), waitAfterMs = 700L, spokenUpdate = "Opening search."))
                        if (searchTarget.isNotBlank()) {
                            steps.add(ActionStep(steps.size + 1, AutonomousActionType.TYPE_TEXT, text = searchTarget, waitAfterMs = 900L, spokenUpdate = "Searching for $searchTarget."))
                        }
                    }
                    isBrowser -> {
                        if (!screenState.packageName.contains("chrome")) {
                            steps.add(ActionStep(steps.size + 1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "com.android.chrome"), waitAfterMs = 1200L, spokenUpdate = "Opening browser, Sir."))
                        }
                        steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Search or type URL"), waitAfterMs = 700L))
                        if (searchTarget.isNotBlank()) {
                            steps.add(ActionStep(steps.size + 1, AutonomousActionType.TYPE_TEXT, text = searchTarget, waitAfterMs = 1000L, spokenUpdate = "Querying $searchTarget."))
                        }
                    }
                    else -> {
                        steps.add(ActionStep(1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Search"), waitAfterMs = 700L))
                        if (searchTarget.isNotBlank()) {
                            steps.add(ActionStep(2, AutonomousActionType.TYPE_TEXT, text = searchTarget, waitAfterMs = 800L))
                        }
                    }
                }
            }

            // Pattern 4: Like
            goalLower.contains("like") || goalLower.contains("pasand") -> {
                steps.add(ActionStep(steps.size + 1, AutonomousActionType.LIKE, waitAfterMs = 700L, spokenUpdate = "Liking item, Sir."))
            }

            // Pattern 5: Install / Download
            goalLower.contains("install") || goalLower.contains("download") -> {
                steps.add(ActionStep(steps.size + 1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Install"), waitAfterMs = 800L, spokenUpdate = "Tapping Install, Sir."))
            }

            // Pattern 6: Navigation commands
            goalLower.contains("home") -> {
                steps.add(ActionStep(1, AutonomousActionType.HOME, waitAfterMs = 600L, spokenUpdate = "Returning to Home screen, Sir."))
            }
            goalLower.contains("back") || goalLower.contains("peeche") -> {
                steps.add(ActionStep(1, AutonomousActionType.BACK, waitAfterMs = 500L, spokenUpdate = "Navigating back, Sir."))
            }
            goalLower.contains("recents") -> {
                steps.add(ActionStep(1, AutonomousActionType.RECENTS, waitAfterMs = 600L, spokenUpdate = "Opening recent apps, Sir."))
            }

            // Pattern 7: Open App (Hindi & English)
            goalLower.startsWith("open ") || goalLower.startsWith("launch ") || goalLower.endsWith(" kholo") || goalLower.endsWith(" chalao") -> {
                val appName = goalLower
                    .removePrefix("open ").removePrefix("launch ")
                    .removeSuffix(" kholo").removeSuffix(" chalao")
                    .removeSuffix(" app").trim()
                steps.add(ActionStep(1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, appName), waitAfterMs = 1200L, spokenUpdate = "Launching $appName, Sir."))
            }

            // Pattern 8: Local element matching fallback
            else -> {
                // Local fallback: Try matching visible element or general click
                val matchedNode = screenState.nodes.find {
                    it.isClickable && goalLower.contains(it.label.lowercase())
                }
                if (matchedNode != null) {
                    steps.add(ActionStep(1, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, matchedNode.label), waitAfterMs = 700L, spokenUpdate = "Selecting ${matchedNode.label}, Sir."))
                } else {
                    steps.add(ActionStep(1, AutonomousActionType.FIND_ELEMENT, ActionTarget(TargetType.TEXT, objective.title), waitAfterMs = 500L))
                }
            }
        }

        return steps
    }

    /**
     * Decides the EXACT NEXT SINGLE ATOMIC ACTION for the closed-loop agent.
     * Grounded in the live WorldState observation and authoritative MissionState.
     */
    suspend fun decideNextAction(
        missionState: MissionState,
        modelClient: ModelClient
    ): ToolCall {
        val currentObservation = missionState.currentObservation
            ?: WorldState(foregroundPackage = "unknown")
        val originalGoal = missionState.originalUserGoal
        val subgoal = missionState.currentSubgoal?.description ?: originalGoal
        val currentPkg = currentObservation.foregroundPackage.lowercase()

        Log.i(TAG, "[CANONICAL_AI_ROUTE] DynamicPlanner deciding next atomic action via ModelClient: ${modelClient.javaClass.simpleName} for goal=\"$originalGoal\" subgoal=\"$subgoal\"")

        // 1. Deterministic Cold App Launch Check:
        // If the goal specifically targets an application and it is not foregrounded, launch it immediately
        val targetApp = detectTargetApp(originalGoal, subgoal)
        if (targetApp != null && !currentPkg.contains(targetApp.packageHint)) {
            Log.i(TAG, "Fast-path: Target app '${targetApp.name}' is not active ($currentPkg). Synthesizing launch_app.")
            return ToolCall(
                name = CanonicalTools.LAUNCH_APP,
                arguments = mapOf("package_or_name" to targetApp.packageName),
                thought = "Opening ${targetApp.name} to begin mission."
            )
        }

        // 2. Query ModelClient with live WorldState and goal
        val historyList = missionState.completedObjectives.map { "Completed: ${it.description}" }
        val lastActionDesc = missionState.lastAction?.let { act ->
            val res = missionState.lastActionResult
            val verifText = if (!res?.verification.isNullOrBlank()) " | Output: ${res?.verification}" else ""
            "Last action: ${act.name} -> success: ${res?.success}, verified: ${res?.verified}$verifText"
        }
        val fullHistory = if (lastActionDesc != null) historyList + lastActionDesc else historyList

        val systemInstruction = """
            You are J.A.R.V.I.S., an autonomous Android agent with screen control and web information access.
            You perceive the device display and execute ONE atomic tool at a time to accomplish the user's goal.
            If the user's goal requires real-time information, current news, facts, or live web search (e.g., 'search web', 'find latest news', 'what is'), call web_search.
            Always inspect the live interactive elements on screen for device UI tasks.
            When the goal is satisfied or information has been retrieved and summarized, call finish_task.
        """.trimIndent()

        val screenshot = currentObservation.screenshotBase64
        if (!screenshot.isNullOrBlank()) {
            Log.i(TAG, "[SCREENSHOT_ATTACHED_TO_MODEL_REQUEST] Attaching screenshot (${screenshot.length} chars) to ModelDecisionRequest")
        } else {
            Log.i(TAG, "[SCREENSHOT_OMITTED] Building ModelDecisionRequest without visual frame")
        }

        val request = ModelDecisionRequest(
            systemInstruction = systemInstruction,
            originalUserGoal = originalGoal,
            currentSubgoal = subgoal,
            compressedScreenIndex = currentObservation.toCompressedSemanticIndex(),
            actionHistory = fullHistory,
            screenshotBase64 = screenshot
        )

        val structuredResult = try {
            modelClient.decideNextActionStructured(request)
        } catch (e: Exception) {
            Log.w(TAG, "ModelClient threw exception, falling back to local perception matching: ", e)
            null
        }

        if (structuredResult != null) {
            Log.i(TAG, "ModelClient Diagnostics: Provider=${structuredResult.diagnostics.providerUsed} Model=${structuredResult.diagnostics.modelUsed} Latency=${structuredResult.diagnostics.durationMs}ms (Connect=${structuredResult.diagnostics.connectTimeMs}ms, Read=${structuredResult.diagnostics.readTimeMs}ms) Tokens=${structuredResult.diagnostics.totalTokens} Decision=${structuredResult.decision}")

            if (structuredResult.decision == DecisionType.EXECUTE_TOOL || structuredResult.decision == DecisionType.FINISH_TASK) {
                val toolName = structuredResult.tool ?: CanonicalTools.FINISH_TASK
                Log.i(TAG, "Model decided next tool: $toolName with args: ${structuredResult.arguments}")
                return ToolCall(
                    name = toolName,
                    arguments = structuredResult.arguments,
                    thought = structuredResult.reason
                )
            }
        }

        // 3. Fallback Heuristic Perception Matching (Grounds decision in live WorldState nodes)
        return decideNextActionHeuristic(
            originalGoal = originalGoal,
            subgoal = subgoal,
            worldState = currentObservation,
            lastAction = missionState.lastAction,
            lastActionResult = missionState.lastActionResult
        )
    }

    private fun decideNextActionHeuristic(
        originalGoal: String,
        subgoal: String,
        worldState: WorldState,
        lastAction: ToolCall? = null,
        lastActionResult: ActionResult? = null
    ): ToolCall {
        // Case 0A: Previous action was WEB_SEARCH -> synthesize finish_task with summarized results
        if (lastAction?.name == CanonicalTools.WEB_SEARCH) {
            val searchSummary = lastActionResult?.verification ?: "Web search completed, Sir."
            return ToolCall(
                name = CanonicalTools.FINISH_TASK,
                arguments = mapOf(
                    "success" to (lastActionResult?.success ?: true),
                    "spoken_summary" to searchSummary
                ),
                thought = "Web search concluded; summarizing results to finish mission."
            )
        }

        // Case 0B: Web Information Query -> trigger WEB_SEARCH
        if (isWebInformationQuery(originalGoal, subgoal)) {
            val query = extractWebSearchQuery(originalGoal, subgoal)
            return ToolCall(
                name = CanonicalTools.WEB_SEARCH,
                arguments = mapOf("query" to query),
                thought = "Information query detected; performing real-time web search for '$query'."
            )
        }

        val targetText = (subgoal.ifBlank { originalGoal }).lowercase()
        val nodes = worldState.nodes

        // Case A: Shorts
        if (targetText.contains("shorts")) {
            val shortsNode = nodes.firstOrNull { it.label.contains("shorts", ignoreCase = true) }
            if (shortsNode != null) {
                return ToolCall(
                    name = CanonicalTools.TAP_ELEMENT,
                    arguments = mapOf("node_index" to shortsNode.index, "label" to shortsNode.label),
                    thought = "Tapping Shorts button on active YouTube screen."
                )
            }
        }

        // Case B: Search
        if (targetText.contains("search") || targetText.contains("dhoondho")) {
            val searchQuery = extractSearchQuery(subgoal.ifBlank { originalGoal })

            // If an editable field is focused or present, type the search query
            val focusedEdit = worldState.focusedNode?.takeIf { it.isEditable }
                ?: worldState.editableNodes.firstOrNull()

            if (focusedEdit != null && searchQuery.isNotBlank()) {
                val currentText = focusedEdit.text ?: ""
                if (!currentText.contains(searchQuery, ignoreCase = true)) {
                    return ToolCall(
                        name = CanonicalTools.TYPE_TEXT,
                        arguments = mapOf(
                            "text" to searchQuery,
                            "node_index" to focusedEdit.index,
                            "press_enter" to true
                        ),
                        thought = "Typing '$searchQuery' into search input field."
                    )
                }
            }

            // Otherwise, find and tap Search icon/button
            val searchBtn = nodes.firstOrNull {
                it.isClickable && (it.label.contains("search", ignoreCase = true) || it.contentDescription?.contains("search", ignoreCase = true) == true)
            }
            if (searchBtn != null) {
                return ToolCall(
                    name = CanonicalTools.TAP_ELEMENT,
                    arguments = mapOf("node_index" to searchBtn.index, "label" to searchBtn.label),
                    thought = "Tapping search button to open input field."
                )
            }
        }

        // Case C: Look for exact label matches from visible nodes
        val matchedNode = nodes.firstOrNull { node ->
            node.isClickable && (
                targetText.contains(node.label.lowercase()) ||
                (node.label.length > 3 && targetText.contains(node.label.lowercase()))
            )
        }
        if (matchedNode != null) {
            return ToolCall(
                name = CanonicalTools.TAP_ELEMENT,
                arguments = mapOf("node_index" to matchedNode.index, "label" to matchedNode.label),
                thought = "Selecting matching interactive element: ${matchedNode.label}"
            )
        }

        // Case D: If scrollable container exists, scroll down to reveal more
        if (worldState.scrollableNodes.isNotEmpty()) {
            return ToolCall(
                name = CanonicalTools.SCROLL,
                arguments = mapOf("direction" to "DOWN"),
                thought = "Target not immediately visible; scrolling down to inspect further content."
            )
        }

        // Fallback: Finish task
        return ToolCall(
            name = CanonicalTools.FINISH_TASK,
            arguments = mapOf("success" to true, "spoken_summary" to "Actions completed for $subgoal, Sir."),
            thought = "No further interactive actions identified; marking task complete."
        )
    }

    private fun isWebInformationQuery(goal: String, subgoal: String): Boolean {
        val text = (subgoal.ifBlank { goal }).lowercase().trim()
        val keywords = listOf(
            "latest", "news", "summarize", "search web", "web search",
            "search the web", "search online", "who is", "what is",
            "tell me about", "look up", "find out", "weather",
            "current price", "score of"
        )
        val isAppSearch = text.contains("youtube") || text.contains("play store") ||
                text.contains("playstore") || text.contains("settings") ||
                text.contains("instagram") || text.contains("whatsapp")
        return !isAppSearch && keywords.any { text.contains(it) }
    }

    private fun extractWebSearchQuery(goal: String, subgoal: String): String {
        var text = (subgoal.ifBlank { goal }).trim()
        val lower = text.lowercase()

        val prefixes = listOf(
            "find the latest ", "find latest ", "search the web for ", "search web for ",
            "search online for ", "search for ", "search ", "look up ", "tell me about ",
            "what is the ", "what is ", "who is the ", "who is ", "find out "
        )
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }

        val suffixes = listOf(
            " and summarize it", " and summarize", " summarize it", " summarize",
            " please", " sir"
        )
        for (suffix in suffixes) {
            if (text.lowercase().endsWith(suffix)) {
                text = text.substring(0, text.length - suffix.length).trim()
                break
            }
        }

        return text.ifBlank { goal }
    }

    private data class AppTarget(val name: String, val packageName: String, val packageHint: String)

    private fun detectTargetApp(goal: String, subgoal: String): AppTarget? {
        val combined = (goal + " " + subgoal).lowercase()
        return when {
            combined.contains("youtube") -> AppTarget("YouTube", "com.google.android.youtube", "youtube")
            combined.contains("play store") || combined.contains("playstore") -> AppTarget("Play Store", "com.android.vending", "vending")
            combined.contains("settings") -> AppTarget("Settings", "com.android.settings", "settings")
            combined.contains("whatsapp") && (combined.startsWith("open") || combined.contains("whatsapp open")) -> AppTarget("WhatsApp", "com.whatsapp", "whatsapp")
            combined.contains("chrome") || combined.contains("browser") -> AppTarget("Chrome", "com.android.chrome", "chrome")
            combined.contains("instagram") -> AppTarget("Instagram", "com.instagram.android", "instagram")
            else -> null
        }
    }

    private fun extractSearchQuery(text: String): String {
        val lower = text.lowercase()
        val raw = when {
            lower.contains("search for") -> {
                val idx = lower.indexOf("search for")
                text.substring(idx + "search for".length)
            }
            lower.contains("search") -> {
                val idx = lower.indexOf("search")
                text.substring(idx + "search".length)
            }
            lower.contains("dhoondho") -> {
                val idx = lower.indexOf("dhoondho")
                text.substring(0, idx).trim().substringAfterLast(" ")
            }
            else -> ""
        }.trim()

        return raw
            .removePrefix("for ")
            .removePrefix("karo ")
            .removePrefix("for")
            .removePrefix("karo")
            .trim()
    }
}