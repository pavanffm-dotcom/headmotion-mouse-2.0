package com.assistive.headmouse.agent.jarvis

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.agent.perception.SpatialNodeCache
import com.assistive.headmouse.agent.jarvis.action.*
import com.assistive.headmouse.agent.jarvis.memory.JarvisMemoryManager
import com.assistive.headmouse.preferences.AiProvider
import com.assistive.headmouse.service.HeadMouseAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JarvisMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class JarvisResponse(
    val spokenText: String,
    val displayText: String,
    val actionType: ActionType = ActionType.NONE,
    val actionData: String? = null
)

enum class ActionType {
    NONE,
    LAUNCH_APP,
    CLICK_NODE,
    TAP,
    TYPE_TEXT,
    HOME,
    BACK,
    RECENTS,
    SCROLL_DOWN,
    SCROLL_UP,
    RECENTER_MOUSE,
    PAUSE_MOUSE,
    RESUME_MOUSE,
    LIST_FILES,
    DELETE_FILE,
    SEARCH_FILES,
    OPEN_FILE,
    START_MISSION
}

/**
 * The conversational cognitive brain and offline intent router of J.A.R.V.I.S.
 *
 * ARCHITECTURAL ROLE (Phase 10 Canonical Unification):
 * - Primary role: Conversational Q&A assistant (voice/text) with Stark/JARVIS personality,
 *   offline system command execution, local file management, and mission intent routing.
 * - For complex, multi-step autonomous missions, it detects [ACTION:START_MISSION:goal]
 *   and delegates execution to the authoritative canonical autonomous engine:
 *   [AgentOrchestrator] -> [DynamicPlanner] -> [ModelClient] -> [ToolDispatcher].
 * - Legacy autonomous planning methods in this class are deprecated in favor of [AgentOrchestrator].
 */
class JarvisBrain(
    private val context: Context? = null,
    private val spatialCache: SpatialNodeCache? = null
) {

    private val conversationHistory = mutableListOf<JarvisMessage>()
    val fileManager = context?.let { JarvisFileManager(it) }
    val memoryManager = context?.let { JarvisMemoryManager(it) }
    val screenObserver = ScreenObserver()
    val actionExecutor = context?.let { ActionExecutor(it, screenObserver) }

    fun getHistory(): List<JarvisMessage> = conversationHistory.toList()

    fun addMessage(msg: JarvisMessage) {
        conversationHistory.add(msg)
    }

    fun clearHistory() {
        conversationHistory.clear()
        memoryManager?.clearMemory()
    }

    /**
     * Processes natural language prompt from user.
     * Determines intent, executes any physical device actions, and returns response.
     */
    suspend fun processUserPrompt(
        prompt: String,
        apiKey: String? = null,
        isCloudEnabled: Boolean = true,
        provider: AiProvider = AiProvider.GEMINI,
        modelName: String = "gemini-1.5-flash",
        customBaseUrl: String = "https://openrouter.ai/api/v1/chat/completions"
    ): JarvisResponse = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        val lowerPrompt = cleanPrompt.lowercase()

        // 1. Direct Physical / System Actions (Offline first for zero latency)
        val offlineResponse = evaluateOfflineCommands(cleanPrompt, lowerPrompt)
        if (offlineResponse != null) {
            recordTurn(cleanPrompt, offlineResponse)
            return@withContext offlineResponse
        }

        // 2. Built-in Identity & Conversational offline fallback (Zero API key requirement)
        val isIdentityQuery = lowerPrompt.contains("who are you") || lowerPrompt.contains("kaun ho") ||
                lowerPrompt.contains("what are you") || lowerPrompt.contains("your name") ||
                lowerPrompt.contains("identity") || lowerPrompt.contains("who created you")
        if (isIdentityQuery) {
            val identityResponse = evaluateConversationalFallback(cleanPrompt, lowerPrompt)
            recordTurn(cleanPrompt, identityResponse)
            return@withContext identityResponse
        }

        // 3. Cloud AI Reasoning via Selected Provider (if enabled)
        if (isCloudEnabled) {
            if (apiKey.isNullOrBlank()) {
                val noKeyResponse = JarvisResponse(
                    spokenText = "Sir, please configure your ${provider.name} API key in Settings to enable cloud intelligence.",
                    displayText = "API Key required for ${provider.name}. Please enter your key in Settings."
                )
                recordTurn(cleanPrompt, noKeyResponse)
                return@withContext noKeyResponse
            }

            try {
                val cloudAnswer = when (provider) {
                    AiProvider.GEMINI -> callGeminiApi(cleanPrompt, apiKey, modelName)
                    AiProvider.OPENAI -> callOpenAiApi(cleanPrompt, apiKey, modelName)
                    AiProvider.CUSTOM_OPENROUTER -> callCustomOpenRouterApi(cleanPrompt, apiKey, modelName, customBaseUrl)
                }

                if (cloudAnswer.startsWith("API Error") || cloudAnswer.startsWith("OpenAI Error") || cloudAnswer.startsWith("Gemini Error")) {
                    val errResponse = JarvisResponse(
                        spokenText = "Cloud service error: $cloudAnswer",
                        displayText = cloudAnswer
                    )
                    recordTurn(cleanPrompt, errResponse)
                    return@withContext errResponse
                }

                if (cloudAnswer.isNotBlank()) {
                    val actionResponse = parseAndExecuteCloudAction(cloudAnswer)
                    recordTurn(cleanPrompt, actionResponse)
                    return@withContext actionResponse
                }
            } catch (e: Exception) {
                Log.w("JarvisBrain", "Cloud API call threw exception: ", e)
                val errResponse = JarvisResponse(
                    spokenText = "Unable to connect to the cloud AI server, Sir. Please check your internet connection or API settings.",
                    displayText = "Network Error: ${e.localizedMessage ?: "Connection failed"}"
                )
                recordTurn(cleanPrompt, errResponse)
                return@withContext errResponse
            }
        }

        // 3. Built-in Local Conversational Fallback
        val localConversational = evaluateConversationalFallback(cleanPrompt, lowerPrompt)
        recordTurn(cleanPrompt, localConversational)
        return@withContext localConversational
    }

    private fun recordTurn(userPrompt: String, response: JarvisResponse) {
        conversationHistory.add(JarvisMessage(userPrompt, isUser = true))
        conversationHistory.add(JarvisMessage(response.displayText, isUser = false))
        memoryManager?.addTurn(role = "user", content = userPrompt)
        memoryManager?.addTurn(
            role = "assistant",
            content = response.displayText,
            actionExecuted = if (response.actionType != ActionType.NONE) response.actionType.name else null
        )
    }

    private fun isCompoundTask(raw: String, lower: String): Boolean {
        // 1. Multi-clause punctuation
        if (raw.contains(".") || raw.contains(";") || raw.contains("\n") || raw.contains(",")) {
            val parts = raw.split(Regex("[.;\n,]+")).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 2) return true
        }

        // 2. Chaining conjunctions
        val hasConjunction = lower.contains(" and ") || lower.contains(" aur ") ||
                lower.contains(" then ") || lower.contains(" phir ") ||
                lower.contains(" fir ") || lower.contains(" ke baad ") ||
                lower.contains(" uske baad ") || lower.contains(" karke ") ||
                lower.contains(" to ") || lower.contains(" so ")

        // 3. Action verbs across the phone
        val hasActionVerb = lower.contains("click") || lower.contains("tap") ||
                lower.contains("search") || lower.contains("dhoondho") ||
                lower.contains("find") || lower.contains("like") ||
                lower.contains("install") || lower.contains("download") ||
                lower.contains("type") || lower.contains("write") ||
                lower.contains("scroll") || lower.contains("shorts") ||
                lower.contains("flashlight") || lower.contains("torch") ||
                lower.contains("on karo") || lower.contains("off karo") ||
                lower.contains("chalao") || lower.contains("play") ||
                lower.contains("dabao")

        if (hasConjunction && hasActionVerb) return true

        // 4. Target app or settings combined with actions (e.g. "settings mein jao flashlight dhoondho")
        val hasAppOrSetting = lower.contains("youtube") || lower.contains("settings") ||
                lower.contains("setting") || lower.contains("instagram") ||
                lower.contains("play store") || lower.contains("whatsapp") ||
                lower.contains("chrome")

        if (hasAppOrSetting && (lower.contains("karke") || lower.contains("jao") || lower.contains("mein jao") || lower.contains("mein ja")) && hasActionVerb) {
            return true
        }

        // 5. Open + action command with multiple words (e.g. "youtube open karo shorts pe click karo")
        if (hasAppOrSetting && (lower.contains("open") || lower.contains("kholo") || lower.contains("launch")) && hasActionVerb) {
            val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size > 3) return true
        }

        return false
    }

    private fun evaluateOfflineCommands(raw: String, lower: String): JarvisResponse? {
        // Memory reset command: "clear memory", "forget everything", "sab bhool jao"
        if (lower.contains("clear memory") || lower.contains("forget everything") ||
            lower.contains("sab bhool jao") || lower.contains("memory clear karo") ||
            lower.contains("purani baatein bhool jao")) {
            memoryManager?.clearMemory()
            conversationHistory.clear()
            return JarvisResponse(
                spokenText = "All conversational memories have been cleared, Sir. We have a fresh slate.",
                displayText = "Memory Cleared. Fresh slate initialized.",
                actionType = ActionType.NONE
            )
        }

        // Global Navigation: Home
        if (lower == "home" || lower == "go home" || lower == "home button" ||
            lower.contains("home screen") || lower.contains("ghar chalo") ||
            lower.contains("home pe jao") || lower.contains("click on home") || lower.contains("home click")) {
            HeadMouseAccessibilityService.instance?.performHome()
            return JarvisResponse(
                spokenText = "Returning to Home screen, Sir.",
                displayText = "Navigating to Home screen...",
                actionType = ActionType.HOME
            )
        }

        // Global Navigation: Back
        if (lower == "back" || lower == "go back" || lower == "back button" ||
            lower.contains("peeche jao") || lower.contains("back jao") ||
            lower.contains("press back") || lower.contains("click on back")) {
            HeadMouseAccessibilityService.instance?.performBack()
            return JarvisResponse(
                spokenText = "Going back, Sir.",
                displayText = "Navigating back...",
                actionType = ActionType.BACK
            )
        }

        // Global Navigation: Recents
        if (lower == "recents" || lower.contains("recent apps") || lower.contains("app switcher") || lower.contains("recent tasks")) {
            HeadMouseAccessibilityService.instance?.performRecents()
            return JarvisResponse(
                spokenText = "Opening recent apps, Sir.",
                displayText = "Opening Recent Apps...",
                actionType = ActionType.RECENTS
            )
        }

        // Typing Offline: "type ...", "write ...", "enter ..."
        if (lower.startsWith("type ") || lower.startsWith("write ") || lower.startsWith("enter text ")) {
            val textToType = raw.removePrefix("type ").removePrefix("Type ")
                .removePrefix("write ").removePrefix("Write ")
                .removePrefix("enter text ").removePrefix("Enter text ").trim()
            if (textToType.isNotBlank()) {
                actionExecutor?.let {
                    kotlinx.coroutines.runBlocking {
                        it.executeStep(ActionStep(1, AutonomousActionType.TYPE_TEXT, text = textToType))
                    }
                }
                return JarvisResponse(
                    spokenText = "Entered '$textToType', Sir.",
                    displayText = "Typed: $textToType",
                    actionType = ActionType.TYPE_TEXT,
                    actionData = textToType
                )
            }
        }

        // A. Screen Awareness: "What's on my screen?", "Read screen", "change the screen just see the latest screen", "see screen"
        val isScreenQuery = lower.contains("what is on my screen") || lower.contains("what's on my screen") ||
            lower.contains("read screen") || lower.contains("analyze screen") ||
            lower.contains("screen pe kya hai") || lower.contains("dekho screen") ||
            (lower.contains("screen") && (lower.contains("see") || lower.contains("check") || lower.contains("latest") || lower.contains("change") || lower.contains("read")))
        if (isScreenQuery) {
            val liveState = screenObserver.getLiveScreenState()
            val nodes = if (liveState.nodes.isNotEmpty()) {
                liveState.nodes
            } else if (spatialCache != null) {
                spatialCache.getNodes()
            } else {
                emptyList()
            }
            if (nodes.isEmpty()) {
                return JarvisResponse(
                    spokenText = "Sir, I currently do not detect any interactive elements on the screen. Please ensure the accessibility service is active.",
                    displayText = "No interactive elements detected on active window (Package: ${liveState.packageName}).",
                    actionType = ActionType.NONE
                )
            }

            val appName = if (liveState.packageName.isNotBlank()) liveState.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() } else "Screen"
            val namedNodes = nodes.map { it.label }.filter { it.isNotBlank() }.distinct().take(8)
            val summary = if (namedNodes.isNotEmpty()) {
                "Sir, on $appName I can see ${nodes.size} elements, including ${namedNodes.take(4).joinToString(", ")}. Would you like me to click any of them?"
            } else {
                "Sir, there are ${nodes.size} interactive elements on the screen."
            }

            val display = if (liveState.nodes.isNotEmpty()) {
                liveState.toPromptSummary(15)
            } else {
                nodes.joinToString("\n") { it.label }
            }

            return JarvisResponse(
                spokenText = summary,
                displayText = display,
                actionType = ActionType.NONE
            )
        }

        // B. Autonomous Multi-Step Compound Missions (e.g. "YouTube open karo. Shorts ke upar click karo", "Settings mein jao and flashlight dhoondho", "Play Store mein X search karo aur install karo")
        if (isCompoundTask(raw, lower)) {
            return JarvisResponse(
                spokenText = "Right away, Sir. Generating structured mission plan.",
                displayText = "Starting autonomous mission: \"$raw\"",
                actionType = ActionType.START_MISSION,
                actionData = raw
            )
        }

        // C. Universal App Launching (Strictly single-intent only)
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.endsWith(" kholo") || lower.endsWith(" chalao")) {
            val appQuery = lower
                .removePrefix("open ").removePrefix("launch ")
                .removeSuffix(" kholo").removeSuffix(" chalao")
                .removeSuffix(" app").trim()

            if (appQuery.isNotBlank() && context != null && AppLauncher.isAppInstalled(context, appQuery)) {
                launchAppOrAction(appQuery)
                return JarvisResponse(
                    spokenText = "Right away, Sir. Opening $appQuery.",
                    displayText = "Opening $appQuery...",
                    actionType = ActionType.LAUNCH_APP,
                    actionData = appQuery
                )
            }
        }

        // D. High-priority standard app aliases (Strictly single-intent only)
        val appTarget = when {
            (lower == "open youtube" || lower == "youtube" || lower == "youtube kholo" || lower == "youtube open karo" || lower == "youtube chalao") ->
                Pair("com.google.android.youtube", "YouTube")
            (lower == "open whatsapp" || lower == "whatsapp" || lower == "whatsapp kholo" || lower == "whatsapp open karo" || lower == "whatsapp chalao") ->
                Pair("com.whatsapp", "WhatsApp")
            (lower == "open instagram" || lower == "instagram" || lower == "instagram kholo" || lower == "instagram open karo" || lower == "instagram chalao") ->
                Pair("com.instagram.android", "Instagram")
            (lower == "open chrome" || lower == "chrome" || lower == "browser" || lower == "chrome kholo" || lower == "browser kholo") ->
                Pair("com.android.chrome", "Chrome")
            (lower == "open settings" || lower == "settings" || lower == "setting" || lower == "settings kholo" || lower == "setting kholo") ->
                Pair("com.android.settings", "Settings")
            (lower == "open camera" || lower == "camera" || lower == "camera kholo") ->
                Pair("android.media.action.IMAGE_CAPTURE", "Camera")
            else -> null
        }

        if (appTarget != null) {
            launchAppOrAction(appTarget.first)
            return JarvisResponse(
                spokenText = "Right away, Sir. Opening ${appTarget.second}.",
                displayText = "Opening ${appTarget.second}...",
                actionType = ActionType.LAUNCH_APP,
                actionData = appTarget.first
            )
        }

        // E. Named Element Clicking with Hindi/English phrasing (e.g. "Shorts mein click karna hai", "Shorts ke upar click karo", "click search", "search button dabao")
        val isClickIntent = lower.startsWith("click ") || lower.startsWith("tap ") ||
                lower.contains("click karo") || lower.contains("pe click") ||
                lower.contains("mein click") || lower.contains("me click") ||
                lower.contains("ke upar click") || lower.contains("click karna") ||
                lower.contains("dabao") || lower.contains("press ")

        if (isClickIntent) {
            val liveNodes = screenObserver.getLiveScreenState().nodes
            val targetName = lower
                .replace("click", "")
                .replace("tap", "")
                .replace("karo", "")
                .replace("karna hai", "")
                .replace("karna", "")
                .replace("hai", "")
                .replace("ke upar", "")
                .replace("pe", "")
                .replace("mein", "")
                .replace("me", "")
                .replace("button", "")
                .replace("dabao", "")
                .replace("press", "")
                .trim()

            if (targetName.isNotBlank() && liveNodes.isNotEmpty()) {
                val matched = liveNodes.find {
                    it.isClickable && (it.label.equals(targetName, ignoreCase = true) ||
                            it.label.contains(targetName, ignoreCase = true) ||
                            targetName.contains(it.label, ignoreCase = true))
                } ?: liveNodes.find {
                    it.label.equals(targetName, ignoreCase = true) ||
                            it.label.contains(targetName, ignoreCase = true) ||
                            targetName.contains(it.label, ignoreCase = true)
                }

                if (matched != null) {
                    clickScreenNode(matched)
                    return JarvisResponse(
                        spokenText = "Clicking ${matched.label}, Sir.",
                        displayText = "Clicked: ${matched.label}",
                        actionType = ActionType.CLICK_NODE,
                        actionData = matched.label
                    )
                }
            }
        }

        // C. Mouse Recenter & Pause / Resume
        if (lower.contains("recenter") || lower.contains("calibrate")) {
            HeadMouseAccessibilityService.instance?.triggerRecenter()
            return JarvisResponse(
                spokenText = "Recentering cursor origin now, Sir.",
                displayText = "Recentering mouse in 5 seconds...",
                actionType = ActionType.RECENTER_MOUSE
            )
        }
        if (lower.contains("pause mouse") || lower.contains("stop tracking")) {
            HeadMouseAccessibilityService.instance?.let {
                if (!it.isPaused()) it.togglePauseResume()
            }
            return JarvisResponse(
                spokenText = "Mouse tracking paused, Sir.",
                displayText = "Tracking Paused.",
                actionType = ActionType.PAUSE_MOUSE
            )
        }
        if (lower.contains("resume mouse") || lower.contains("start tracking")) {
            HeadMouseAccessibilityService.instance?.let {
                if (it.isPaused()) it.togglePauseResume()
            }
            return JarvisResponse(
                spokenText = "Mouse tracking resumed, Sir.",
                displayText = "Tracking Resumed.",
                actionType = ActionType.RESUME_MOUSE
            )
        }

        // D. Contextual Scrolling
        if (lower.contains("scroll down") || lower.contains("niche scroll")) {
            HeadMouseAccessibilityService.instance?.scrollDown()
            return JarvisResponse(
                spokenText = "Scrolling down, Sir.",
                displayText = "Scrolled down.",
                actionType = ActionType.SCROLL_DOWN
            )
        }
        if (lower.contains("scroll up") || lower.contains("upar scroll")) {
            HeadMouseAccessibilityService.instance?.scrollUp()
            return JarvisResponse(
                spokenText = "Scrolling up, Sir.",
                displayText = "Scrolled up.",
                actionType = ActionType.SCROLL_UP
            )
        }

        // E. Time & Battery Diagnostics
        if (lower.contains("time") || lower.contains("samay")) {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return JarvisResponse(
                spokenText = "The time is $timeStr, Sir.",
                displayText = "Current Time: $timeStr"
            )
        }
        if (lower.contains("battery") || lower.contains("charge")) {
            val batLevel = try {
                val bm = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
            } catch (e: Exception) {
                85
            }
            return JarvisResponse(
                spokenText = "Power reserves are at $batLevel percent, Sir.",
                displayText = "Battery Level: $batLevel%"
            )
        }

        // F. File System & Storage Management
        if (lower.contains("storage") || lower.contains("memory status") || lower.contains("space kitna hai")) {
            val stats = fileManager?.getStorageOverview()
            if (stats != null) {
                val reply = "Storage: ${stats.freeSpaceGb} free of ${stats.totalSpaceGb}. Folders: ${stats.quickFolders.joinToString(", ")}."
                return JarvisResponse(
                    spokenText = "You have ${stats.freeSpaceGb} free of ${stats.totalSpaceGb} total storage, Sir.",
                    displayText = reply,
                    actionType = ActionType.LIST_FILES
                )
            }
        }

        if (lower.startsWith("list ") || lower.contains("folder me kya hai") || lower.contains("files dikhao") ||
            lower.contains("downloads me kya hai") || lower.contains("show files") || lower.contains("list files")) {
            val folderQuery = when {
                lower.contains("download") -> "Download"
                lower.contains("dcim") || lower.contains("camera") -> "DCIM"
                lower.contains("document") -> "Documents"
                lower.contains("picture") || lower.contains("photo") -> "Pictures"
                lower.contains("whatsapp") -> "WhatsApp"
                else -> ""
            }
            val files = fileManager?.listDirectory(folderQuery, 10) ?: emptyList()
            if (files.isNotEmpty()) {
                val count = files.size
                val preview = files.take(5).joinToString(", ") { "${it.name} (${it.formattedSize})" }
                val reply = "Found $count items in $folderQuery: $preview"
                return JarvisResponse(
                    spokenText = "Found $count items in $folderQuery, Sir. First few include: ${files.take(3).joinToString(", ") { it.name }}.",
                    displayText = reply,
                    actionType = ActionType.LIST_FILES,
                    actionData = folderQuery
                )
            } else {
                return JarvisResponse(
                    spokenText = "No accessible files found in $folderQuery, Sir.",
                    displayText = "Folder is empty or permission required.",
                    actionType = ActionType.LIST_FILES,
                    actionData = folderQuery
                )
            }
        }

        if (lower.startsWith("delete ") || lower.contains("hata do") || lower.contains("delete karo")) {
            val target = lower.removePrefix("delete").removePrefix("file").removePrefix("folder").trim()
            if (target.isNotBlank()) {
                val success = fileManager?.deleteFileOrDirectory(target) ?: false
                val reply = if (success) "Successfully deleted $target, Sir." else "Could not locate $target to delete, Sir."
                return JarvisResponse(
                    spokenText = reply,
                    displayText = reply,
                    actionType = ActionType.DELETE_FILE,
                    actionData = target
                )
            }
        }

        return null
    }

    private fun evaluateConversationalFallback(raw: String, lower: String): JarvisResponse {
        return when {
            lower.contains("who are you") || lower.contains("kaun ho") -> {
                JarvisResponse(
                    spokenText = "I am J.A.R.V.I.S., your autonomous intelligent assistant. I work in tandem with HeadMotion Mouse to grant you complete hands-free computing power, Sir.",
                    displayText = "I am J.A.R.V.I.S., an intelligent assistant integrated into HeadMotion Mouse."
                )
            }
            lower.contains("how are you") || lower.contains("kaise ho") -> {
                JarvisResponse(
                    spokenText = "I am functioning at peak efficiency, Sir. All sensory and tracking systems are online.",
                    displayText = "Operational at 100% capacity."
                )
            }
            lower.contains("hello") || lower.contains("hi jarvis") || lower.contains("hey jarvis") -> {
                JarvisResponse(
                    spokenText = "At your service, Sir. What would you like to accomplish?",
                    displayText = "At your service, Sir."
                )
            }
            lower.contains("thank") || lower.contains("shukriya") -> {
                JarvisResponse(
                    spokenText = "Always a pleasure to assist, Sir.",
                    displayText = "You're very welcome, Sir."
                )
            }
            lower.contains("what can you do") || lower.contains("help") -> {
                JarvisResponse(
                    spokenText = "I can inspect what is on your screen, launch your apps, click buttons, scroll, and execute autonomous missions, Sir.",
                    displayText = "Capabilities:\n• Screen Inspection\n• Autonomous App Control\n• Voice & Gesture Mouse\n• Multi-Turn Memory"
                )
            }
            else -> {
                JarvisResponse(
                    spokenText = "I am listening, Sir. You can ask me to open any app, click elements, or perform phone actions.",
                    displayText = "J.A.R.V.I.S. is listening and ready for commands."
                )
            }
        }
    }

    private fun launchAppOrAction(pkgOrAction: String) {
        try {
            if (context != null) {
                val launched = AppLauncher.launchApp(context, pkgOrAction)
                if (launched) return
            }
            HeadMouseAccessibilityService.instance?.launchAppByNameOrPackage(pkgOrAction)
        } catch (e: Exception) {
            Log.w("JarvisBrain", "Could not launch $pkgOrAction: ", e)
        }
    }

    private fun callGeminiApi(prompt: String, apiKey: String, model: String): String {
        val targetModel = if (model.isBlank()) "gemini-1.5-flash" else model
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$apiKey"
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 12000

        val systemInstruction = getSystemInstruction()
        val recentTurns = memoryManager?.getRecentTurns(12) ?: emptyList()

        val jsonBody = JSONObject().apply {
            val contents = JSONArray().apply {
                for (turn in recentTurns) {
                    put(JSONObject().apply {
                        put("role", if (turn.role == "user") "user" else "model")
                        put("parts", JSONArray().put(JSONObject().put("text", turn.content)))
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", if (recentTurns.isEmpty()) "$systemInstruction\n\nUser: $prompt" else prompt)))
                })
            }
            put("contents", contents)
            val systemObj = JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            }
            put("system_instruction", systemObj)
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(jsonBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()

            val responseJson = JSONObject(sb.toString())
            val candidates = responseJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val first = candidates.getJSONObject(0)
                val content = first.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text", "").trim()
                }
            }
        } else {
            val errBody = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            val parsedMsg = try {
                val errJson = JSONObject(errBody)
                errJson.optJSONObject("error")?.optString("message") ?: errBody
            } catch (_: Exception) { errBody }

            if (parsedMsg.isNotBlank()) {
                return "Gemini API Error ($responseCode): ${parsedMsg.take(150)}"
            }
        }

        return ""
    }

    private fun extractChoiceMessageContent(firstChoice: JSONObject): String {
        val message = firstChoice.optJSONObject("message") ?: return ""
        val content = message.optString("content", "").trim()
        val toolCalls = message.optJSONArray("tool_calls")

        if (content.isNotBlank() && !content.equals("null", ignoreCase = true) && !content.equals("none", ignoreCase = true)) {
            return content
        }

        if (toolCalls != null && toolCalls.length() > 0) {
            val firstCall = toolCalls.optJSONObject(0)
            val fn = firstCall?.optJSONObject("function")
            val fnName = fn?.optString("name", "") ?: ""
            val argsStr = fn?.optString("arguments", "{}") ?: "{}"
            val argsJson = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
            val targetVal = argsJson.optString("target", argsJson.optString("app_name", argsJson.optString("query", "")))
            val textVal = argsJson.optString("text", "")

            val actionTag = when (fnName.lowercase()) {
                "android_home", "home" -> "[ACTION:HOME]"
                "android_back", "back" -> "[ACTION:BACK]"
                "android_recents", "recents" -> "[ACTION:RECENTS]"
                "android_open_app", "open_app", "launch_app" -> "[ACTION:OPEN_APP:$targetVal]"
                "android_tap", "tap", "click", "click_node" -> "[ACTION:TAP:$targetVal]"
                "android_type", "type", "type_text" -> "[ACTION:TYPE:$textVal]"
                "android_scroll_down", "scroll_down" -> "[ACTION:SCROLL_DOWN]"
                "android_scroll_up", "scroll_up" -> "[ACTION:SCROLL_UP]"
                "get_screen_state", "see_screen" -> "[ACTION:GET_SCREEN_STATE]"
                else -> ""
            }

            if (actionTag.isNotBlank()) {
                return "Executing requested operation, Sir. $actionTag"
            }
        }

        return if (content.equals("null", ignoreCase = true) || content.equals("none", ignoreCase = true)) "" else content
    }

    private fun callOpenAiApi(prompt: String, apiKey: String, model: String): String {
        val targetModel = if (model.isBlank()) "gpt-4o-mini" else model
        val endpoint = "https://api.openai.com/v1/chat/completions"
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 12000

        val systemInstruction = getSystemInstruction()
        val recentTurns = memoryManager?.getRecentTurns(16) ?: emptyList()

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemInstruction)
            })
            for (turn in recentTurns) {
                put(JSONObject().apply {
                    put("role", if (turn.role == "user") "user" else "assistant")
                    put("content", turn.content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val jsonBody = JSONObject().apply {
            put("model", targetModel)
            put("messages", messages)
            put("max_tokens", 250)
            put("temperature", 0.7)
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(jsonBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()

            val responseJson = JSONObject(sb.toString())
            val choices = responseJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                return extractChoiceMessageContent(firstChoice)
            }
        } else {
            val errBody = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            val parsedMsg = try {
                val errJson = JSONObject(errBody)
                errJson.optJSONObject("error")?.optString("message") ?: errBody
            } catch (_: Exception) { errBody }

            if (parsedMsg.isNotBlank()) {
                return "OpenAI Error ($responseCode): ${parsedMsg.take(150)}"
            }
        }

        return ""
    }

    private fun callCustomOpenRouterApi(prompt: String, apiKey: String, model: String, baseUrl: String): String {
        val targetModel = if (model.isBlank()) "deepseek/deepseek-chat" else model
        val cleanBase = baseUrl.trim().removeSuffix("/")
        val targetEndpoint = when {
            cleanBase.isBlank() -> "https://api.xkiro.com/v1/chat/completions"
            cleanBase.endsWith("/chat/completions") -> cleanBase
            else -> "$cleanBase/chat/completions"
        }

        val cleanKey = apiKey.trim().removePrefix("Bearer ").trim()
        if (cleanKey.isBlank()) {
            Log.w("JarvisBrain", "[Custom/xKiro Auth] API key is MISSING (length: 0) for $targetEndpoint")
        } else {
            Log.i("JarvisBrain", "[Custom/xKiro Auth] API key is PRESENT (length: ${cleanKey.length}) for $targetEndpoint")
        }

        val url = URL(targetEndpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (cleanKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $cleanKey")
            conn.setRequestProperty("Authentication", "Bearer $cleanKey")
        }
        conn.setRequestProperty("User-Agent", "HeadMotionMouse-JARVIS/1.0 (Android; Mobile)")
        conn.setRequestProperty("HTTP-Referer", "https://github.com/assistive-headmouse")
        conn.setRequestProperty("X-Title", "Mobile JARVIS")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000

        val systemInstruction = getSystemInstruction()
        val recentTurns = memoryManager?.getRecentTurns(16) ?: emptyList()

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemInstruction)
            })
            for (turn in recentTurns) {
                put(JSONObject().apply {
                    put("role", if (turn.role == "user") "user" else "assistant")
                    put("content", turn.content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val jsonBody = JSONObject().apply {
            put("model", targetModel)
            put("messages", messages)
            put("max_tokens", 250)
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(jsonBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()

            val responseJson = JSONObject(sb.toString())
            val choices = responseJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                return extractChoiceMessageContent(firstChoice)
            }
        } else {
            val errBody = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            Log.e("JarvisBrain", "[Custom/OpenRouter Error] HTTP $responseCode from $targetEndpoint | Key present: ${cleanKey.isNotBlank()} (length: ${cleanKey.length}) | Body: $errBody")

            val parsedMsg = try {
                val errJson = JSONObject(errBody)
                errJson.optJSONObject("error")?.optString("message") ?: errBody
            } catch (_: Exception) { errBody }

            val friendlyMsg = when (responseCode) {
                401 -> "Authentication failed (401). Please verify your API Key in Brain Settings."
                402 -> "Insufficient credits or payment required (402) on your AI provider account."
                404 -> "Model not found (404) at $targetEndpoint. Check model identifier."
                429 -> "Rate limit or quota exceeded (429). Please try again shortly."
                in 500..599 -> "AI Provider server error ($responseCode). Service temporarily unavailable."
                else -> if (parsedMsg.isNotBlank()) "API Error ($responseCode): ${parsedMsg.take(150)}" else "API Error ($responseCode)"
            }
            return friendlyMsg
        }

        return ""
    }

    private fun clickScreenNode(target: ScreenNode) {
        try {
            HeadMouseAccessibilityService.instance?.clickScreenNode(target)
        } catch (e: Exception) {
            Log.w("JarvisBrain", "Error clicking screen node: ", e)
        }
    }

    private fun parseAndExecuteCloudAction(cloudAnswer: String): JarvisResponse {
        val actionRegex = Regex("""\[ACTION:\s*([A-Z_]+)(?::([^\]]+))?\]""")
        val match = actionRegex.find(cloudAnswer)

        var cleanText = cloudAnswer.replace(actionRegex, "").trim()
        if (cleanText.isBlank()) cleanText = "Right away, Sir."

        var actionType = ActionType.NONE
        var actionData: String? = null

        // Support structured JSON response format if model replied with JSON
        val trimmed = cloudAnswer.trim()
        val jsonObj = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try { JSONObject(trimmed) } catch (_: Exception) { null }
        } else {
            val sIdx = trimmed.indexOf("{")
            val eIdx = trimmed.lastIndexOf("}")
            if (sIdx != -1 && eIdx > sIdx) {
                try { JSONObject(trimmed.substring(sIdx, eIdx + 1)) } catch (_: Exception) { null }
            } else null
        }

        if (jsonObj != null && (jsonObj.has("action") || jsonObj.has("spoken") || jsonObj.has("thought"))) {
            val jsonAction = jsonObj.optString("action", "").trim().uppercase()
            val targetParam = when {
                jsonObj.has("target") && !jsonObj.isNull("target") -> jsonObj.optString("target")
                jsonObj.has("data") && !jsonObj.isNull("data") -> jsonObj.optString("data")
                jsonObj.has("param") && !jsonObj.isNull("param") -> jsonObj.optString("param")
                jsonObj.has("package") && !jsonObj.isNull("package") -> jsonObj.optString("package")
                else -> null
            }
            val spoken = when {
                jsonObj.has("spoken") && !jsonObj.isNull("spoken") -> jsonObj.optString("spoken")
                jsonObj.has("spokenUpdate") && !jsonObj.isNull("spokenUpdate") -> jsonObj.optString("spokenUpdate")
                jsonObj.has("reply") && !jsonObj.isNull("reply") -> jsonObj.optString("reply")
                jsonObj.has("text") && !jsonObj.isNull("text") -> jsonObj.optString("text")
                else -> ""
            }
            if (spoken.isNotBlank()) {
                cleanText = spoken
            }

            when (jsonAction) {
                "HOME" -> {
                    actionType = ActionType.HOME
                    HeadMouseAccessibilityService.instance?.performHome()
                    if (spoken.isBlank()) cleanText = "Returning to Home screen, Sir."
                }
                "BACK" -> {
                    actionType = ActionType.BACK
                    HeadMouseAccessibilityService.instance?.performBack()
                    if (spoken.isBlank()) cleanText = "Going back, Sir."
                }
                "RECENTS" -> {
                    actionType = ActionType.RECENTS
                    HeadMouseAccessibilityService.instance?.performRecents()
                    if (spoken.isBlank()) cleanText = "Opening recent apps, Sir."
                }
                "TYPE", "TYPE_TEXT" -> {
                    actionType = ActionType.TYPE_TEXT
                    val textToType = jsonObj.optString("text", targetParam ?: "")
                    actionData = textToType
                    if (textToType.isNotBlank()) {
                        actionExecutor?.let {
                            kotlinx.coroutines.runBlocking {
                                it.executeStep(ActionStep(1, AutonomousActionType.TYPE_TEXT, text = textToType))
                            }
                        }
                        if (spoken.isBlank()) cleanText = "Entered '$textToType', Sir."
                    }
                }
                "GET_SCREEN_STATE", "SEE_SCREEN" -> {
                    val state = screenObserver.getLiveScreenState()
                    cleanText = if (state.nodes.isNotEmpty()) {
                        val appName = state.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                        "Sir, on $appName I see: ${state.nodes.take(5).joinToString(", ") { it.label }}."
                    } else {
                        "Sir, no interactive elements detected on the active window."
                    }
                }
                "OPEN_APP", "LAUNCH_APP" -> {
                    actionType = ActionType.LAUNCH_APP
                    actionData = targetParam
                    if (!targetParam.isNullOrBlank()) {
                        launchAppOrAction(targetParam)
                    }
                }
                "CLICK_NODE", "TAP" -> {
                    actionType = ActionType.TAP
                    actionData = targetParam
                    if (!targetParam.isNullOrBlank()) {
                        val activeNodes = screenObserver.getActiveNodes()
                        val resolved = TargetResolver.resolveTarget(ActionTarget(TargetType.TEXT, targetParam), activeNodes)
                        if (resolved != null) {
                            HeadMouseAccessibilityService.instance?.updateCursorPositionExplicit(resolved.x, resolved.y)
                            if (resolved.matchedNode != null) {
                                clickScreenNode(resolved.matchedNode)
                            } else {
                                HeadMouseAccessibilityService.instance?.clickAt(resolved.x, resolved.y)
                            }
                            if (spoken.isBlank()) cleanText = "Clicking ${resolved.matchedNode?.label ?: targetParam}, Sir."
                        } else {
                            val matches = spatialCache?.findNodesByText(targetParam) ?: emptyList()
                            if (matches.isNotEmpty()) {
                                clickScreenNode(matches.first())
                                if (spoken.isBlank()) cleanText = "Clicking ${matches.first().label}, Sir."
                            } else {
                                cleanText = "I could not locate '$targetParam' on the current screen, Sir."
                            }
                        }
                    }
                }
                "SCROLL_DOWN" -> {
                    actionType = ActionType.SCROLL_DOWN
                    HeadMouseAccessibilityService.instance?.scrollDown()
                }
                "SCROLL_UP" -> {
                    actionType = ActionType.SCROLL_UP
                    HeadMouseAccessibilityService.instance?.scrollUp()
                }
                "RECENTER", "RECENTER_MOUSE" -> {
                    actionType = ActionType.RECENTER_MOUSE
                    HeadMouseAccessibilityService.instance?.triggerRecenter()
                }
                "PAUSE_MOUSE" -> {
                    actionType = ActionType.PAUSE_MOUSE
                    HeadMouseAccessibilityService.instance?.let {
                        if (!it.isPaused()) it.togglePauseResume()
                    }
                }
                "RESUME_MOUSE" -> {
                    actionType = ActionType.RESUME_MOUSE
                    HeadMouseAccessibilityService.instance?.let {
                        if (it.isPaused()) it.togglePauseResume()
                    }
                }
                "LIST_FILES" -> {
                    actionType = ActionType.LIST_FILES
                    actionData = targetParam
                    val list = fileManager?.listDirectory(targetParam ?: "", 8) ?: emptyList()
                    if (list.isNotEmpty()) {
                        cleanText = "Files: " + list.joinToString(", ") { "${it.name} (${it.formattedSize})" }
                    }
                }
                "DELETE_FILE" -> {
                    actionType = ActionType.DELETE_FILE
                    actionData = targetParam
                    if (!targetParam.isNullOrBlank()) {
                        val deleted = fileManager?.deleteFileOrDirectory(targetParam) ?: false
                        cleanText = if (deleted) "Successfully deleted $targetParam, Sir." else "Could not locate $targetParam to delete."
                    }
                }
                "SEARCH_FILES" -> {
                    actionType = ActionType.SEARCH_FILES
                    actionData = targetParam
                    if (!targetParam.isNullOrBlank()) {
                        val found = fileManager?.searchFiles(targetParam, 5) ?: emptyList()
                        cleanText = if (found.isNotEmpty()) {
                            "Found ${found.size} matching items: " + found.joinToString(", ") { it.name }
                        } else {
                            "No files found matching $targetParam, Sir."
                        }
                    }
                }
                "OPEN_FILE" -> {
                    actionType = ActionType.OPEN_FILE
                    actionData = targetParam
                    if (!targetParam.isNullOrBlank()) {
                        fileManager?.openFile(targetParam)
                    }
                }
                "START_MISSION" -> {
                    actionType = ActionType.START_MISSION
                    actionData = targetParam
                }
            }

            if (cleanText.isBlank() || cleanText.equals("null", ignoreCase = true) || cleanText.equals("none", ignoreCase = true)) {
                cleanText = when (actionType) {
                    ActionType.HOME -> "Returning to Home screen, Sir."
                    ActionType.BACK -> "Going back, Sir."
                    ActionType.RECENTS -> "Opening recent apps, Sir."
                    ActionType.LAUNCH_APP -> "Opening ${actionData ?: "application"}, Sir."
                    ActionType.CLICK_NODE, ActionType.TAP -> "Clicking ${actionData ?: "target"}, Sir."
                    ActionType.TYPE_TEXT -> "Entering text, Sir."
                    ActionType.SCROLL_DOWN -> "Scrolling down, Sir."
                    ActionType.SCROLL_UP -> "Scrolling up, Sir."
                    ActionType.RECENTER_MOUSE -> "Recentering cursor, Sir."
                    ActionType.PAUSE_MOUSE -> "Cursor tracking paused, Sir."
                    ActionType.RESUME_MOUSE -> "Cursor tracking resumed, Sir."
                    else -> "At your command, Sir."
                }
            }

            return JarvisResponse(
                spokenText = cleanText,
                displayText = cleanText,
                actionType = actionType,
                actionData = actionData
            )
        }

        if (match != null) {
            val command = match.groupValues[1].trim()
            val param = match.groupValues.getOrNull(2)?.trim()

            when (command) {
                "HOME" -> {
                    actionType = ActionType.HOME
                    HeadMouseAccessibilityService.instance?.performHome()
                    cleanText = "Returning to Home screen, Sir."
                }
                "BACK" -> {
                    actionType = ActionType.BACK
                    HeadMouseAccessibilityService.instance?.performBack()
                    cleanText = "Going back, Sir."
                }
                "RECENTS" -> {
                    actionType = ActionType.RECENTS
                    HeadMouseAccessibilityService.instance?.performRecents()
                    cleanText = "Opening recent apps, Sir."
                }
                "TYPE" -> {
                    actionType = ActionType.TYPE_TEXT
                    actionData = param
                    if (!param.isNullOrBlank()) {
                        actionExecutor?.let {
                            kotlinx.coroutines.runBlocking {
                                it.executeStep(ActionStep(1, AutonomousActionType.TYPE_TEXT, text = param))
                            }
                        }
                        cleanText = "Entered '$param', Sir."
                    }
                }
                "GET_SCREEN_STATE" -> {
                    val state = screenObserver.getLiveScreenState()
                    cleanText = if (state.nodes.isNotEmpty()) {
                        val appName = state.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                        "Sir, on $appName I see: ${state.nodes.take(5).joinToString(", ") { it.label }}."
                    } else {
                        "Sir, no interactive elements detected on the active window."
                    }
                }
                "OPEN_APP" -> {
                    actionType = ActionType.LAUNCH_APP
                    actionData = param
                    if (!param.isNullOrBlank()) {
                        launchAppOrAction(param)
                    }
                }
                "TAP", "CLICK_NODE" -> {
                    actionType = ActionType.TAP
                    actionData = param
                    if (!param.isNullOrBlank()) {
                        val activeNodes = screenObserver.getActiveNodes()
                        val resolved = TargetResolver.resolveTarget(ActionTarget(TargetType.TEXT, param), activeNodes)
                        if (resolved != null) {
                            HeadMouseAccessibilityService.instance?.updateCursorPositionExplicit(resolved.x, resolved.y)
                            if (resolved.matchedNode != null) {
                                clickScreenNode(resolved.matchedNode)
                            } else {
                                HeadMouseAccessibilityService.instance?.clickAt(resolved.x, resolved.y)
                            }
                            cleanText = "Clicking ${resolved.matchedNode?.label ?: param}, Sir."
                        } else {
                            val matches = spatialCache?.findNodesByText(param) ?: emptyList()
                            if (matches.isNotEmpty()) {
                                clickScreenNode(matches.first())
                                cleanText = "Clicking ${matches.first().label}, Sir."
                            } else {
                                cleanText = "I could not locate '$param' on the current screen, Sir."
                            }
                        }
                    }
                }
                "SCROLL_DOWN" -> {
                    actionType = ActionType.SCROLL_DOWN
                    HeadMouseAccessibilityService.instance?.scrollDown()
                }
                "SCROLL_UP" -> {
                    actionType = ActionType.SCROLL_UP
                    HeadMouseAccessibilityService.instance?.scrollUp()
                }
                "RECENTER" -> {
                    actionType = ActionType.RECENTER_MOUSE
                    HeadMouseAccessibilityService.instance?.triggerRecenter()
                }
                "PAUSE_MOUSE" -> {
                    actionType = ActionType.PAUSE_MOUSE
                    HeadMouseAccessibilityService.instance?.let {
                        if (!it.isPaused()) it.togglePauseResume()
                    }
                }
                "RESUME_MOUSE" -> {
                    actionType = ActionType.RESUME_MOUSE
                    HeadMouseAccessibilityService.instance?.let {
                        if (it.isPaused()) it.togglePauseResume()
                    }
                }
                "LIST_FILES" -> {
                    actionType = ActionType.LIST_FILES
                    actionData = param
                    val list = fileManager?.listDirectory(param ?: "", 8) ?: emptyList()
                    if (list.isNotEmpty()) {
                        cleanText = "Files: " + list.joinToString(", ") { "${it.name} (${it.formattedSize})" }
                    }
                }
                "DELETE_FILE" -> {
                    actionType = ActionType.DELETE_FILE
                    actionData = param
                    if (!param.isNullOrBlank()) {
                        val deleted = fileManager?.deleteFileOrDirectory(param) ?: false
                        cleanText = if (deleted) "Successfully deleted $param, Sir." else "Could not locate $param to delete."
                    }
                }
                "SEARCH_FILES" -> {
                    actionType = ActionType.SEARCH_FILES
                    actionData = param
                    if (!param.isNullOrBlank()) {
                        val found = fileManager?.searchFiles(param, 5) ?: emptyList()
                        cleanText = if (found.isNotEmpty()) {
                            "Found ${found.size} matching items: " + found.joinToString(", ") { it.name }
                        } else {
                            "No files found matching $param, Sir."
                        }
                    }
                }
                "OPEN_FILE" -> {
                    actionType = ActionType.OPEN_FILE
                    actionData = param
                    if (!param.isNullOrBlank()) {
                        fileManager?.openFile(param)
                    }
                }
                "START_MISSION" -> {
                    actionType = ActionType.START_MISSION
                    actionData = param
                }
            }
        } else {
            // Natural conversational fallback for app launches and navigation
            val lower = cloudAnswer.lowercase()
            when {
                lower.contains("home screen") || lower.contains("going to home") || lower.contains("returning home") -> {
                    HeadMouseAccessibilityService.instance?.performHome()
                    actionType = ActionType.HOME
                }
                lower.contains("going back") || lower.contains("pressing back") || lower.contains("navigating back") -> {
                    HeadMouseAccessibilityService.instance?.performBack()
                    actionType = ActionType.BACK
                }
                lower.contains("recent apps") || lower.contains("opening recents") -> {
                    HeadMouseAccessibilityService.instance?.performRecents()
                    actionType = ActionType.RECENTS
                }
                lower.contains("opening youtube") || lower.contains("launching youtube") -> {
                    launchAppOrAction("com.google.android.youtube")
                    actionType = ActionType.LAUNCH_APP
                    actionData = "com.google.android.youtube"
                }
                lower.contains("opening whatsapp") || lower.contains("launching whatsapp") -> {
                    launchAppOrAction("com.whatsapp")
                    actionType = ActionType.LAUNCH_APP
                    actionData = "com.whatsapp"
                }
                lower.contains("opening instagram") || lower.contains("launching instagram") -> {
                    launchAppOrAction("com.instagram.android")
                    actionType = ActionType.LAUNCH_APP
                    actionData = "com.instagram.android"
                }
                lower.contains("opening chrome") || lower.contains("opening browser") -> {
                    launchAppOrAction("com.android.chrome")
                    actionType = ActionType.LAUNCH_APP
                    actionData = "com.android.chrome"
                }
                lower.contains("opening settings") -> {
                    launchAppOrAction("com.android.settings")
                    actionType = ActionType.LAUNCH_APP
                    actionData = "com.android.settings"
                }
                lower.contains("opening camera") -> {
                    launchAppOrAction("android.media.action.IMAGE_CAPTURE")
                    actionType = ActionType.LAUNCH_APP
                    actionData = "android.media.action.IMAGE_CAPTURE"
                }
            }
        }

        if (cleanText.isBlank() || cleanText.equals("null", ignoreCase = true) || cleanText.equals("none", ignoreCase = true)) {
            cleanText = when (actionType) {
                ActionType.HOME -> "Returning to Home screen, Sir."
                ActionType.BACK -> "Going back, Sir."
                ActionType.RECENTS -> "Opening recent apps, Sir."
                ActionType.LAUNCH_APP -> "Opening ${actionData ?: "application"}, Sir."
                ActionType.CLICK_NODE, ActionType.TAP -> "Clicking ${actionData ?: "target"}, Sir."
                ActionType.TYPE_TEXT -> "Entering text, Sir."
                ActionType.SCROLL_DOWN -> "Scrolling down, Sir."
                ActionType.SCROLL_UP -> "Scrolling up, Sir."
                ActionType.RECENTER_MOUSE -> "Recentering cursor, Sir."
                ActionType.PAUSE_MOUSE -> "Cursor tracking paused, Sir."
                ActionType.RESUME_MOUSE -> "Cursor tracking resumed, Sir."
                else -> "I am at your command, Sir."
            }
        }

        return JarvisResponse(
            spokenText = cleanText,
            displayText = cleanText,
            actionType = actionType,
            actionData = actionData
        )
    }

    /**
     * Legacy multi-step ActionPlan generator.
     * Superseded by canonical AgentOrchestrator -> DynamicPlanner -> ModelClient architecture.
     */
    @Deprecated(
        message = "Superseded by canonical AgentOrchestrator -> DynamicPlanner -> ModelClient architecture. Do not use for autonomous missions.",
        level = DeprecationLevel.WARNING
    )
    suspend fun generateActionPlan(
        missionGoal: String,
        apiKey: String? = null,
        isCloudEnabled: Boolean = true,
        provider: AiProvider = AiProvider.GEMINI,
        modelName: String = "gemini-1.5-flash",
        customBaseUrl: String = "https://openrouter.ai/api/v1/chat/completions"
    ): ActionPlan = withContext(Dispatchers.IO) {
        val taskId = "task_${System.currentTimeMillis()}"

        if (isCloudEnabled && !apiKey.isNullOrBlank()) {
            try {
                val liveScreen = screenObserver.getLiveScreenState().toPromptSummary(30)
                val promptText = "You are J.A.R.V.I.S., an autonomous Android planning brain with full direct control of this device.\n" +
                        "Task Goal: $missionGoal\n\n" +
                        "=== CURRENT LIVE FOREGROUND SCREEN STATE ===\n" +
                        "$liveScreen\n\n" +
                        "Generate an executable multi-step machine plan in valid JSON format. If you need to open an app first, include OPEN_APP with package name. If clicking, use the target label or #ID from the screen state above. Follow this exact schema:\n" +
                        "{\n" +
                        "  \"task\": \"$missionGoal\",\n" +
                        "  \"steps\": [\n" +
                        "    {\n" +
                        "      \"id\": 1,\n" +
                        "      \"action\": \"OPEN_APP\" | \"TAP\" | \"TYPE_TEXT\" | \"SCROLL_DOWN\" | \"SCROLL_UP\" | \"LIKE\" | \"WAIT\" | \"END_TASK\",\n" +
                        "      \"target\": {\"type\": \"TEXT\" | \"CONTENT_DESCRIPTION\" | \"RESOURCE_ID\", \"value\": \"...\"},\n" +
                        "      \"text\": null,\n" +
                        "      \"waitAfterMs\": 600,\n" +
                        "      \"spokenUpdate\": \"brief British status update\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}\n" +
                        "Return ONLY valid JSON. No conversational text."

                val rawResponse = when (provider) {
                    AiProvider.GEMINI -> callGeminiApi(promptText, apiKey, modelName)
                    AiProvider.OPENAI -> callOpenAiApi(promptText, apiKey, modelName)
                    AiProvider.CUSTOM_OPENROUTER -> callCustomOpenRouterApi(promptText, apiKey, modelName, customBaseUrl)
                }

                val plan = parseActionPlanJson(taskId, missionGoal, rawResponse)
                if (plan != null && plan.steps.isNotEmpty()) {
                    return@withContext plan
                }
            } catch (e: Exception) {
                Log.w("JarvisBrain", "Cloud plan generation error, falling back to offline planner: ", e)
            }
        }

        return@withContext generateOfflineActionPlan(taskId, missionGoal)
    }

    private fun parseActionPlanJson(taskId: String, missionGoal: String, rawJson: String): ActionPlan? {
        return try {
            val clean = rawJson
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val startIdx = clean.indexOf('{')
            val endIdx = clean.lastIndexOf('}')
            if (startIdx == -1 || endIdx == -1) return null

            val jsonStr = clean.substring(startIdx, endIdx + 1)
            val root = JSONObject(jsonStr)
            val stepsArr = root.optJSONArray("steps") ?: return null

            val stepsList = mutableListOf<ActionStep>()
            for (i in 0 until stepsArr.length()) {
                val stepObj = stepsArr.getJSONObject(i)
                val id = stepObj.optInt("id", i + 1)
                val actionStr = stepObj.optString("action", "TAP").uppercase()
                val action = try {
                    AutonomousActionType.valueOf(actionStr)
                } catch (_: Exception) {
                    AutonomousActionType.TAP
                }

                val targetObj = stepObj.optJSONObject("target")
                val target = if (targetObj != null) {
                    val tTypeStr = targetObj.optString("type", "TEXT").uppercase()
                    val tType = try { TargetType.valueOf(tTypeStr) } catch (_: Exception) { TargetType.TEXT }
                    val tVal = targetObj.optString("value", "")
                    ActionTarget(tType, tVal)
                } else if (stepObj.has("target") && stepObj.opt("target") is String) {
                    ActionTarget(TargetType.TEXT, stepObj.getString("target"))
                } else null

                val text = stepObj.optString("text").takeIf { it.isNotBlank() && it != "null" }
                val waitAfter = stepObj.optLong("waitAfterMs", 600L)
                val spoken = stepObj.optString("spokenUpdate").takeIf { it.isNotBlank() && it != "null" }

                stepsList.add(
                    ActionStep(
                        id = id,
                        action = action,
                        target = target,
                        text = text,
                        waitAfterMs = waitAfter,
                        spokenUpdate = spoken
                    )
                )
            }

            ActionPlan(taskId = taskId, taskGoal = missionGoal, steps = stepsList)
        } catch (e: Exception) {
            Log.w("JarvisBrain", "Failed to parse action plan JSON: ", e)
            null
        }
    }

    private fun generateOfflineActionPlan(taskId: String, missionGoal: String): ActionPlan {
        val lower = missionGoal.lowercase()
        val steps = mutableListOf<ActionStep>()

        when {
            lower.contains("youtube") && (lower.contains("search") || lower.contains("kholo")) -> {
                val query = lower.substringAfter("search").replace("karo", "").trim().ifBlank { "trending" }
                steps.add(ActionStep(1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "YouTube"), waitAfterMs = 1200L, spokenUpdate = "Opening YouTube, Sir."))
                steps.add(ActionStep(2, AutonomousActionType.WAIT, waitAfterMs = 600L))
                steps.add(ActionStep(3, AutonomousActionType.TAP, ActionTarget(TargetType.CONTENT_DESCRIPTION, "Search"), waitAfterMs = 500L, spokenUpdate = "Locating search interface."))
                steps.add(ActionStep(4, AutonomousActionType.TYPE_TEXT, text = query, waitAfterMs = 500L, spokenUpdate = "Entering search query: $query"))
                steps.add(ActionStep(5, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, query), waitAfterMs = 1200L, spokenUpdate = "Submitting search."))
                steps.add(ActionStep(6, AutonomousActionType.END_TASK, spokenUpdate = "Search completed, Sir."))
            }
            lower.contains("instagram") -> {
                val query = if (lower.contains("search")) lower.substringAfter("search").substringBefore("profile").replace("karo", "").trim().ifBlank { "explore" } else "explore"
                steps.add(ActionStep(1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "Instagram"), waitAfterMs = 1500L, spokenUpdate = "Opening Instagram, Sir."))
                steps.add(ActionStep(2, AutonomousActionType.TAP, ActionTarget(TargetType.CONTENT_DESCRIPTION, "Search"), waitAfterMs = 600L, spokenUpdate = "Navigating to search."))
                steps.add(ActionStep(3, AutonomousActionType.TYPE_TEXT, text = query, waitAfterMs = 500L, spokenUpdate = "Searching for $query."))
                steps.add(ActionStep(4, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, query), waitAfterMs = 1200L, spokenUpdate = "Opening profile."))
                if (lower.contains("like")) {
                    steps.add(ActionStep(5, AutonomousActionType.TAP, ActionTarget(TargetType.CONTENT_DESCRIPTION, "Reel"), waitAfterMs = 800L, spokenUpdate = "Opening video."))
                    steps.add(ActionStep(6, AutonomousActionType.LIKE, ActionTarget(TargetType.CONTENT_DESCRIPTION, "Like"), waitAfterMs = 400L, spokenUpdate = "Liking content, Sir."))
                }
                steps.add(ActionStep(steps.size + 1, AutonomousActionType.END_TASK, spokenUpdate = "Instagram mission completed, Sir."))
            }
            lower.contains("shorts") -> {
                steps.add(ActionStep(1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "YouTube"), waitAfterMs = 1200L, spokenUpdate = "Opening YouTube Shorts, Sir."))
                steps.add(ActionStep(2, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Shorts"), waitAfterMs = 800L, spokenUpdate = "Switching to Shorts feed."))
                steps.add(ActionStep(3, AutonomousActionType.SCROLL_DOWN, waitAfterMs = 2500L, spokenUpdate = "Scrolling Shorts."))
                steps.add(ActionStep(4, AutonomousActionType.SCROLL_DOWN, waitAfterMs = 2500L, spokenUpdate = "Next Short."))
                steps.add(ActionStep(5, AutonomousActionType.END_TASK, spokenUpdate = "Shorts feed active, Sir."))
            }
            else -> {
                // Default fallback plan
                steps.add(ActionStep(1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, missionGoal.split(" ").firstOrNull() ?: "YouTube"), waitAfterMs = 1000L, spokenUpdate = "Executing $missionGoal, Sir."))
                steps.add(ActionStep(2, AutonomousActionType.END_TASK, spokenUpdate = "Task finalized, Sir."))
            }
        }

        return ActionPlan(taskId = taskId, taskGoal = missionGoal, steps = steps)
    }

    /**
     * Minimal-state targeted replanning when local recovery is exhausted.
     * Superseded by canonical AgentOrchestrator -> DynamicPlanner -> ModelClient architecture.
     */
    @Deprecated(
        message = "Superseded by canonical AgentOrchestrator -> DynamicPlanner -> ModelClient architecture. Do not use for autonomous missions.",
        level = DeprecationLevel.WARNING
    )
    suspend fun replanTargetedStep(
        taskContext: TaskContext,
        failedStep: ActionStep,
        reason: String,
        apiKey: String? = null,
        provider: AiProvider = AiProvider.GEMINI,
        modelName: String = "gemini-1.5-flash",
        customBaseUrl: String = "https://openrouter.ai/api/v1/chat/completions"
    ): List<ActionStep>? = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) {
            // Offline fallback replan: try tap center or skip
            return@withContext listOf(
                ActionStep(
                    id = failedStep.id,
                    action = AutonomousActionType.SCROLL_DOWN,
                    waitAfterMs = 600L,
                    spokenUpdate = "Adjusting screen view to locate target, Sir."
                )
            )
        }

        try {
            val prompt = "You are J.A.R.V.I.S. replanning an autonomous step.\n" +
                    "Task: ${taskContext.originalCommand}\n" +
                    "Failed Step: ${failedStep.action} on ${failedStep.target?.value}, reason: $reason\n" +
                    "Current screen elements: ${taskContext.lastObservation?.visibleTexts?.take(6)?.joinToString(", ")}\n\n" +
                    "Output 1-2 replacement steps in JSON: {\"steps\": [{\"id\": 1, \"action\": \"...\", \"target\": {\"type\": \"...\", \"value\": \"...\"}}]}"

            val response = when (provider) {
                AiProvider.GEMINI -> callGeminiApi(prompt, apiKey, modelName)
                AiProvider.OPENAI -> callOpenAiApi(prompt, apiKey, modelName)
                AiProvider.CUSTOM_OPENROUTER -> callCustomOpenRouterApi(prompt, apiKey, modelName, customBaseUrl)
            }

            val plan = parseActionPlanJson(taskContext.taskId, taskContext.originalCommand, response)
            return@withContext plan?.steps
        } catch (e: Exception) {
            Log.w("JarvisBrain", "Targeted replan failed: ", e)
            return@withContext null
        }
    }

    private fun getSystemInstruction(): String {
        val liveState = screenObserver.getLiveScreenState()
        val screenSummary = liveState.toPromptSummary(25)
        val timeStr = java.text.SimpleDateFormat("hh:mm a, EEEE, MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date())
        val memoryContext = memoryManager?.getMemoryContextSummary()?.let { "\nMemory / Past Context:\n$it" } ?: ""

        return "You are J.A.R.V.I.S., Tony Stark's autonomous AI assistant and Android GUI agent, directly integrated into this device via the Accessibility Service.\n" +
            "You HAVE DIRECT CONTROL of this Android device, including its hands-free cursor, hardware navigation (Home, Back, Recents), app launching, element clicking, text input, and files.\n" +
            "Speak politely in a crisp, intelligent British cadence. Address the user respectfully as 'Sir'.\n" +
            "Keep answers concise (under 2 sentences) suitable for Text-To-Speech playback.\n\n" +
            "=== CURRENT DEVICE & LIVE SCREEN STATE ===\n" +
            "$screenSummary\n" +
            "Current Time: $timeStr\n" +
            "$memoryContext\n\n" +
            "=== AVAILABLE AUTONOMOUS TOOLS & ACTION TAGS ===\n" +
            "Whenever the user asks you to perform an action, you MUST execute it by appending the appropriate action command tag or JSON at the end of your response:\n" +
            "- [ACTION:HOME] (to press the Home button and return to home screen)\n" +
            "- [ACTION:BACK] (to press the Back button and return to previous screen)\n" +
            "- [ACTION:RECENTS] (to open Recent Apps overview / app switcher)\n" +
            "- [ACTION:OPEN_APP:package_or_name] (e.g. [ACTION:OPEN_APP:com.google.android.youtube], [ACTION:OPEN_APP:com.whatsapp], [ACTION:OPEN_APP:com.instagram.android], [ACTION:OPEN_APP:com.android.chrome], [ACTION:OPEN_APP:com.android.settings], [ACTION:OPEN_APP:play store])\n" +
            "- [ACTION:TAP:target] (where target is #number, text label, or content description e.g. [ACTION:TAP:#1] or [ACTION:TAP:Install] or [ACTION:TAP:Search])\n" +
            "- [ACTION:CLICK_NODE:target] (same as TAP)\n" +
            "- [ACTION:TYPE:text] (to type text into the currently focused or active input field)\n" +
            "- [ACTION:SCROLL_DOWN] (to scroll down the screen)\n" +
            "- [ACTION:SCROLL_UP] (to scroll up the screen)\n" +
            "- [ACTION:GET_SCREEN_STATE] (to refresh and inspect the live screen elements)\n" +
            "- [ACTION:RECENTER] (to recenter mouse cursor origin)\n" +
            "- [ACTION:PAUSE_MOUSE] (to pause cursor tracking)\n" +
            "- [ACTION:RESUME_MOUSE] (to resume cursor tracking)\n" +
            "- [ACTION:LIST_FILES:folder] (to list files in Download, DCIM, Documents)\n" +
            "- [ACTION:DELETE_FILE:name] (to delete a file)\n" +
            "- [ACTION:SEARCH_FILES:query] (to search files)\n" +
            "- [ACTION:START_MISSION:goal] (for compound multi-step cross-app missions)\n\n" +
            "CRITICAL INSTRUCTIONS:\n" +
            "1. NEVER refuse by saying 'I cannot press the home button', 'I cannot click buttons', or 'I operate blind'. You HAVE full control and live screen elements above.\n" +
            "2. NEVER reply with literal string 'null'. If no action is needed, return a helpful conversational answer.\n" +
            "3. When the user asks to see or read the screen, describe the visible elements from the state above."
    }

    /**
     * Legacy disconnected vision planning.
     * Superseded by canonical AgentOrchestrator -> DynamicPlanner -> ModelClient architecture.
     */
    @Deprecated(
        message = "Superseded by canonical AgentOrchestrator -> DynamicPlanner -> ModelClient architecture. Do not use for autonomous missions.",
        level = DeprecationLevel.WARNING
    )
    suspend fun planNextMissionStep(
        missionGoal: String,
        stepNumber: Int,
        stepHistory: List<String>,
        screenImageBase64: String?,
        apiKey: String,
        isCloudEnabled: Boolean,
        provider: AiProvider,
        modelName: String,
        customBaseUrl: String
    ): MissionStep? = withContext(Dispatchers.IO) {
        if (!isCloudEnabled || apiKey.isBlank()) {
            return@withContext evaluateOfflineMissionStep(missionGoal, stepNumber)
        }

        try {
            val jsonResponse = when (provider) {
                AiProvider.GEMINI -> callGeminiVisionPlan(missionGoal, stepNumber, stepHistory, screenImageBase64, apiKey, modelName)
                AiProvider.OPENAI -> callOpenAiVisionPlan(missionGoal, stepNumber, stepHistory, screenImageBase64, apiKey, modelName)
                AiProvider.CUSTOM_OPENROUTER -> callCustomVisionPlan(missionGoal, stepNumber, stepHistory, screenImageBase64, apiKey, modelName, customBaseUrl)
            }
            val parsed = parseMissionStepJson(jsonResponse, stepNumber)
            parsed ?: evaluateOfflineMissionStep(missionGoal, stepNumber)
        } catch (e: Exception) {
            Log.w("JarvisBrain", "Error during vision mission planning: ", e)
            evaluateOfflineMissionStep(missionGoal, stepNumber)
        }
    }

    private fun callGeminiVisionPlan(
        missionGoal: String,
        stepNumber: Int,
        history: List<String>,
        base64Image: String?,
        apiKey: String,
        model: String
    ): String {
        val targetModel = if (model.isBlank() || !model.startsWith("gemini")) "gemini-1.5-flash" else model
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$apiKey"
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000

        val promptText = "You are J.A.R.V.I.S., an autonomous Android GUI agent.\n" +
            "Goal: $missionGoal\n" +
            "Step Number: $stepNumber\n" +
            "History: ${history.joinToString(" | ")}\n\n" +
            "Analyze the screen and return ONLY valid JSON in this exact structure:\n" +
            "{\n" +
            "  \"thought\": \"reason for action\",\n" +
            "  \"action\": \"TAP\" or \"LAUNCH_APP\" or \"SWIPE_UP\" or \"SWIPE_DOWN\" or \"BACK\" or \"HOME\" or \"FINISHED\",\n" +
            "  \"x\": 540,\n" +
            "  \"y\": 960,\n" +
            "  \"text\": null,\n" +
            "  \"spokenUpdate\": \"brief British status update\",\n" +
            "  \"isFinished\": false\n" +
            "}"

        val jsonBody = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().apply { put("text", promptText) })
                        if (!base64Image.isNullOrBlank()) {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        }
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
                put("temperature", 0.1)
            })
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(jsonBody.toString())
            writer.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val responseText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val root = JSONObject(responseText)
            val candidates = root.optJSONArray("candidates")
            val firstCand = candidates?.optJSONObject(0)
            val content = firstCand?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            return parts?.optJSONObject(0)?.optString("text") ?: ""
        }
        return ""
    }

    private fun callOpenAiVisionPlan(
        missionGoal: String,
        stepNumber: Int,
        history: List<String>,
        base64Image: String?,
        apiKey: String,
        model: String
    ): String {
        val targetModel = if (model.isBlank() || !model.startsWith("gpt")) "gpt-4o-mini" else model
        val endpoint = "https://api.openai.com/v1/chat/completions"
        return executeOpenAiCompatibleVision(endpoint, apiKey, targetModel, missionGoal, stepNumber, history, base64Image)
    }

    private fun callCustomVisionPlan(
        missionGoal: String,
        stepNumber: Int,
        history: List<String>,
        base64Image: String?,
        apiKey: String,
        model: String,
        baseUrl: String
    ): String {
        val cleanBase = baseUrl.trim().removeSuffix("/")
        val endpoint = if (cleanBase.endsWith("/chat/completions")) cleanBase else "$cleanBase/chat/completions"
        return executeOpenAiCompatibleVision(endpoint, apiKey, model, missionGoal, stepNumber, history, base64Image)
    }

    private fun executeOpenAiCompatibleVision(
        endpoint: String,
        apiKey: String,
        model: String,
        missionGoal: String,
        stepNumber: Int,
        history: List<String>,
        base64Image: String?
    ): String {
        val cleanKey = apiKey.trim().removePrefix("Bearer ").trim()
        if (cleanKey.isBlank()) {
            Log.w("JarvisBrain", "[Vision Auth] API key is MISSING (length: 0) for $endpoint")
        } else {
            Log.i("JarvisBrain", "[Vision Auth] API key is PRESENT (length: ${cleanKey.length}) for $endpoint")
        }

        val cleanBase = endpoint.trim().removeSuffix("/")
        val targetEndpoint = when {
            cleanBase.isBlank() -> "https://api.xkiro.com/v1/chat/completions"
            cleanBase.endsWith("/chat/completions") -> cleanBase
            else -> "$cleanBase/chat/completions"
        }

        val url = URL(targetEndpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (cleanKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $cleanKey")
            conn.setRequestProperty("Authentication", "Bearer $cleanKey")
        }
        conn.setRequestProperty("User-Agent", "HeadMotionMouse-JARVIS/1.0 (Android; Mobile)")
        conn.setRequestProperty("HTTP-Referer", "https://github.com/assistive-headmouse")
        conn.setRequestProperty("X-Title", "Mobile JARVIS")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000

        val sysPrompt = "You are J.A.R.V.I.S., an autonomous Android GUI agent. Given the goal and current screen, output next atomic action strictly in JSON: {\"thought\":\"...\", \"action\":\"TAP\"|\"LAUNCH_APP\"|\"SWIPE_UP\"|\"BACK\"|\"HOME\"|\"FINISHED\", \"x\":540, \"y\":960, \"text\":null, \"spokenUpdate\":\"...\", \"isFinished\":false}"
        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Goal: $missionGoal\nStep: $stepNumber\nHistory: ${history.joinToString(" | ")}")
            })
            if (!base64Image.isNullOrBlank()) {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                    })
                })
            }
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", sysPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        }

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("max_tokens", 300)
            put("temperature", 0.1)
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(jsonBody.toString())
            writer.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val responseText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val root = JSONObject(responseText)
            val choices = root.optJSONArray("choices")
            return choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
        } else {
            val errBody = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            Log.e("JarvisBrain", "[Vision Error] HTTP $code from $targetEndpoint | Body: $errBody")
        }
        return ""
    }

    private fun parseMissionStepJson(rawJson: String, stepNumber: Int): MissionStep? {
        if (rawJson.isBlank()) return null
        return try {
            val startIdx = rawJson.indexOf("{")
            val endIdx = rawJson.lastIndexOf("}")
            if (startIdx == -1 || endIdx == -1 || endIdx < startIdx) return null

            val clean = rawJson.substring(startIdx, endIdx + 1)
            val obj = JSONObject(clean)
            MissionStep(
                stepNumber = stepNumber,
                thought = obj.optString("thought", "Executing next action"),
                action = obj.optString("action", "TAP").uppercase(),
                x = if (obj.has("x") && !obj.isNull("x")) obj.optDouble("x").toFloat() else null,
                y = if (obj.has("y") && !obj.isNull("y")) obj.optDouble("y").toFloat() else null,
                text = if (obj.has("text") && !obj.isNull("text")) obj.optString("text") else null,
                spokenUpdate = if (obj.has("spokenUpdate") && !obj.isNull("spokenUpdate")) obj.optString("spokenUpdate") else null,
                isFinished = obj.optBoolean("isFinished", false) || obj.optString("action").equals("FINISHED", ignoreCase = true)
            )
        } catch (e: Exception) {
            Log.w("JarvisBrain", "Failed to parse mission step JSON: $rawJson", e)
            null
        }
    }

    private fun evaluateOfflineMissionStep(goal: String, stepNumber: Int): MissionStep? {
        val lower = goal.lowercase()
        return when {
            lower.contains("youtube") && (lower.contains("short") || lower.contains("video")) -> {
                when (stepNumber) {
                    1 -> MissionStep(1, "Launching YouTube", "LAUNCH_APP", text = "com.google.android.youtube", spokenUpdate = "Opening YouTube, Sir.")
                    2 -> MissionStep(2, "Selecting Shorts tab", "TAP", x = 360f, y = 2220f, spokenUpdate = "Activating Shorts, Sir.")
                    3 -> MissionStep(3, "Scrolling to next Short", "SWIPE_UP", spokenUpdate = "Playing Shorts, Sir.", isFinished = true)
                    else -> MissionStep(stepNumber, "Mission complete", "FINISHED", isFinished = true)
                }
            }
            lower.contains("instagram") -> {
                when (stepNumber) {
                    1 -> MissionStep(1, "Opening Instagram", "LAUNCH_APP", text = "com.instagram.android", spokenUpdate = "Opening Instagram, Sir.")
                    2 -> MissionStep(2, "Tapping Search Tab", "TAP", x = 270f, y = 2280f, spokenUpdate = "Searching profile, Sir.")
                    3 -> MissionStep(3, "Opening Top Profile", "TAP", x = 450f, y = 520f, spokenUpdate = "Profile opened, Sir.")
                    4 -> MissionStep(4, "Selecting 2nd Photo", "TAP", x = 540f, y = 1100f, spokenUpdate = "Interacting with photo, Sir.")
                    5 -> MissionStep(5, "Returning Home", "HOME", spokenUpdate = "Mission accomplished, Sir.", isFinished = true)
                    else -> MissionStep(stepNumber, "Mission complete", "FINISHED", isFinished = true)
                }
            }
            else -> {
                if (stepNumber == 1) {
                    MissionStep(1, "Executing mission step", "RECENTER", spokenUpdate = "Executing autonomous mission, Sir.", isFinished = true)
                } else {
                    MissionStep(stepNumber, "Mission finalized", "FINISHED", isFinished = true)
                }
            }
        }
    }
}
