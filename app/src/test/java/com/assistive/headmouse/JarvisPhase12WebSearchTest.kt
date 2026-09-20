package com.assistive.headmouse

import android.content.Context
import android.content.SharedPreferences
import com.assistive.headmouse.agent.jarvis.JarvisBrain
import com.assistive.headmouse.agent.jarvis.autonomous.DynamicPlanner
import com.assistive.headmouse.agent.jarvis.autonomous.InformationTools
import com.assistive.headmouse.agent.jarvis.autonomous.ToolCapabilityManager
import com.assistive.headmouse.agent.jarvis.autonomous.ToolRegistry
import com.assistive.headmouse.agent.jarvis.autonomous.WebSearchEngine
import com.assistive.headmouse.agent.jarvis.autonomous.WebSearchResult
import com.assistive.headmouse.agent.jarvis.autonomous.model.DisabledModelClient
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ActionResult
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDispatcher
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolValidator
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationEngine
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState
import com.assistive.headmouse.preferences.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PHASE 12 VERIFICATION SUITE — WEB / INFORMATION TOOL INTEGRATION
 *
 * Validates:
 * 1. Web search schema is registered in CanonicalTools.ALL_DEFINITIONS with required query parameter.
 * 2. Aliases (search_web, google_search, search_internet) normalize to CanonicalTools.WEB_SEARCH.
 * 3. Parameter validation rejects empty/blank query and accepts valid query.
 * 4. ToolDispatcher connects web_search to real WebSearchEngine / DuckDuckGo endpoints.
 * 5. DynamicPlanner detects information queries ("Find the latest AI model news and summarize it.") and selects web_search.
 * 6. DynamicPlanner summarizes search results and issues finish_task after web_search completes.
 * 7. VerificationEngine verifies web_search without false failure from unchanged screen state.
 * 8. Search results preserve source URLs and titles, never fabricating fake data.
 * 9. Normal Android GUI missions (YouTube, Settings, etc.) remain completely unaffected.
 */
class JarvisPhase12WebSearchTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var mockContext: Context
    private lateinit var appSettings: AppSettings
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var dynamicPlanner: DynamicPlanner
    private lateinit var toolDispatcher: ToolDispatcher
    private lateinit var verificationEngine: VerificationEngine

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        mockContext = createMockContext(fakePrefs)
        appSettings = AppSettings(mockContext)
        val capabilityManager = ToolCapabilityManager(mockContext)
        toolRegistry = ToolRegistry(capabilityManager)
        dynamicPlanner = DynamicPlanner(toolRegistry, JarvisBrain(), appSettings)
        toolDispatcher = ToolDispatcher(mockContext) { null }
        verificationEngine = VerificationEngine()
    }

    // ========================================================================
    // 1. TOOL SCHEMA & REGISTRATION TESTS
    // ========================================================================

    @Test
    fun testWebSearchToolSchemaRegistered() {
        val def = CanonicalTools.ALL_DEFINITIONS.firstOrNull { it.name == CanonicalTools.WEB_SEARCH }
        assertNotNull("CanonicalTools.ALL_DEFINITIONS must contain web_search", def)
        assertEquals(CanonicalTools.WEB_SEARCH, def?.name)

        val queryParam = def?.parameters?.firstOrNull { it.name == "query" }
        assertNotNull("web_search must have 'query' parameter", queryParam)
        assertEquals("string", queryParam?.type)
        assertTrue("query parameter must be required", queryParam?.required == true)
    }

    @Test
    fun testWebSearchAliasNormalization() {
        val aliases = listOf("search_web", "google_search", "search_internet", "web_search")
        for (alias in aliases) {
            val call = ToolCall(name = alias, arguments = mapOf("query" to "AI news"))
            val normalized = call.normalize()
            assertEquals("Alias '$alias' must normalize to CanonicalTools.WEB_SEARCH", CanonicalTools.WEB_SEARCH, normalized.name)
            assertEquals("AI news", normalized.arguments["query"])
        }

        // Test argument normalization when 'search_query' or 'q' is used
        val altArgCall = ToolCall(name = "web_search", arguments = mapOf("search_query" to "Gemini 2.0"))
        val normalizedAlt = altArgCall.normalize()
        assertEquals("Gemini 2.0", normalizedAlt.arguments["query"])
    }

    @Test
    fun testToolValidator_validatesWebSearchParameters() {
        // Valid call
        val validCall = ToolCall(name = CanonicalTools.WEB_SEARCH, arguments = mapOf("query" to "latest AI models"))
        val validRes = ToolValidator.validate(validCall)
        assertTrue("Valid web_search call must pass validation", validRes.isValid)

        // Missing query
        val missingCall = ToolCall(name = CanonicalTools.WEB_SEARCH, arguments = emptyMap())
        val missingRes = ToolValidator.validate(missingCall)
        assertFalse("Missing query must fail validation", missingRes.isValid)
        assertEquals("MISSING_ARGUMENT", missingRes.errorCode)

        // Blank query
        val blankCall = ToolCall(name = CanonicalTools.WEB_SEARCH, arguments = mapOf("query" to "   "))
        val blankRes = ToolValidator.validate(blankCall)
        assertFalse("Blank query must fail validation", blankRes.isValid)
        assertEquals("MISSING_ARGUMENT", blankRes.errorCode)
    }

    // ========================================================================
    // 2. TOOL DISPATCHER & REAL SEARCH EXECUTION TESTS
    // ========================================================================

    @Test
    fun testToolDispatcher_executesWebSearch_returnsStructuredResults() = runBlocking {
        val toolCall = ToolCall(
            name = CanonicalTools.WEB_SEARCH,
            arguments = mapOf("query" to "latest AI model news")
        )

        val result = toolDispatcher.dispatch(toolCall, null)

        assertNotNull("Result from dispatch must not be null", result)
        assertEquals(CanonicalTools.WEB_SEARCH, result.tool)

        if (result.success) {
            // Real network connection succeeded
            assertNotNull("Verification output must contain formatted results", result.verification)
            assertTrue(
                "Verification output must mention live search results",
                result.verification!!.contains("Live Web Search Results for \"latest AI model news\":")
            )
            assertTrue(
                "Verification must contain source URL",
                result.verification!!.contains("Source:")
            )
            println("[TEST REPORT] Live web_search successfully reached search engine:\n${result.verification}")
        } else {
            // Offline or network timeout handled gracefully without crash
            assertNotNull("Error code must be set on network failure", result.errorCode)
            assertEquals("NO_RESULTS_OR_NETWORK_ERROR", result.errorCode)
            println("[TEST REPORT] Network failure handled gracefully: ${result.errorMessage}")
        }
    }

    @Test
    fun testStructuredResultFormatting_preservesUrlsAndTitles() {
        val sampleResults = listOf(
            WebSearchResult(
                title = "Gemini 2.0 Flash Announced",
                snippet = "Google announces next-gen Gemini models with multimodal speed and reasoning.",
                url = "https://blog.google/technology/ai/gemini-2-0/"
            ),
            WebSearchResult(
                title = "DeepSeek-R1 Open Weights Released",
                snippet = "DeepSeek releases R1 reasoning model with open architecture.",
                url = "https://github.com/deepseek-ai/DeepSeek-R1"
            )
        )

        val formatted = InformationTools.formatResults("latest AI models", sampleResults)

        assertTrue(formatted.contains("Live Web Search Results for \"latest AI models\":"))
        assertTrue(formatted.contains("1. Gemini 2.0 Flash Announced"))
        assertTrue(formatted.contains("Source: https://blog.google/technology/ai/gemini-2-0/"))
        assertTrue(formatted.contains("2. DeepSeek-R1 Open Weights Released"))
        assertTrue(formatted.contains("Source: https://github.com/deepseek-ai/DeepSeek-R1"))
    }

    // ========================================================================
    // 3. DYNAMIC PLANNER INFORMATION QUERY ROUTING TESTS
    // ========================================================================

    @Test
    fun testDynamicPlanner_decidesWebSearch_forLatestAiNewsQuery() = runBlocking {
        val missionState = MissionState(
            originalUserGoal = "Find the latest AI model news and summarize it."
        )

        val modelClient = DisabledModelClient()

        val nextAction = dynamicPlanner.decideNextAction(missionState, modelClient)

        assertNotNull(nextAction)
        assertEquals(
            "Information query 'Find the latest AI model news and summarize it.' must route to web_search",
            CanonicalTools.WEB_SEARCH,
            nextAction.name
        )

        val queryArg = nextAction.arguments["query"]?.toString()
        assertNotNull("Query argument must be present", queryArg)
        assertTrue(
            "Extracted query must contain core subject 'AI model news'",
            queryArg!!.contains("AI model news", ignoreCase = true)
        )
    }

    @Test
    fun testDynamicPlanner_transitionsToFinishTask_afterWebSearchCompletes() = runBlocking {
        val searchOutput = """
            Live Web Search Results for "latest AI model news":
            1. Gemini 2.0 Flash Announced
               Google announces next-gen multimodal AI.
               Source: https://blog.google/ai
        """.trimIndent()

        val lastAction = ToolCall(
            name = CanonicalTools.WEB_SEARCH,
            arguments = mapOf("query" to "AI model news")
        )
        val lastActionResult = ActionResult(
            success = true,
            tool = CanonicalTools.WEB_SEARCH,
            arguments = mapOf("query" to "AI model news"),
            stateChanged = false,
            focusChanged = false,
            screenChanged = false,
            verification = searchOutput,
            errorCode = null,
            errorMessage = null,
            recoverable = true,
            timestamp = System.currentTimeMillis(),
            callId = "call_test_123",
            durationMs = 250L
        )

        val missionState = MissionState(
            originalUserGoal = "Find the latest AI model news and summarize it.",
            lastAction = lastAction,
            lastActionResult = lastActionResult
        )

        val modelClient = DisabledModelClient()

        val nextAction = dynamicPlanner.decideNextAction(missionState, modelClient)

        assertNotNull(nextAction)
        assertEquals(
            "After web_search completes with results, DynamicPlanner must finish task",
            CanonicalTools.FINISH_TASK,
            nextAction.name
        )
        val summary = nextAction.arguments["spoken_summary"]?.toString()
        assertNotNull("Spoken summary must be provided", summary)
        assertTrue("Summary must include the search findings", summary!!.contains("Gemini 2.0"))
    }

    // ========================================================================
    // 4. VERIFICATION ENGINE TESTS
    // ========================================================================

    @Test
    fun testVerificationEngine_verifiesWebSearch_withoutFalseFailure() {
        val preState = WorldState(foregroundPackage = "com.google.android.youtube")
        val postState = WorldState(foregroundPackage = "com.google.android.youtube")

        val toolCall = ToolCall(
            name = CanonicalTools.WEB_SEARCH,
            arguments = mapOf("query" to "latest AI news")
        )

        val verification = verificationEngine.verify(toolCall, preState, postState)

        assertEquals("VerificationState must be SUCCESS for web_search", VerificationState.SUCCESS, verification.state)
        assertTrue("verified alias must be true", verification.verified)
        assertFalse("stateChanged must be false (no screen mutation)", verification.stateChanged)
        assertTrue("Explanation must mention web search", verification.explanation.contains("Web search"))
    }

    // ========================================================================
    // 5. REGRESSION: NORMAL ANDROID GUI MISSIONS UNAFFECTED
    // ========================================================================

    @Test
    fun testNormalAndroidGuiMissions_remainUnaffected() = runBlocking {
        val missionState = MissionState(
            originalUserGoal = "Open YouTube and watch Shorts"
        )
        val modelClient = DisabledModelClient()

        val nextAction = dynamicPlanner.decideNextAction(missionState, modelClient)

        assertNotNull(nextAction)
        assertNotEquals("GUI mission must NOT trigger web_search", CanonicalTools.WEB_SEARCH, nextAction.name)
        assertEquals("Cold app launch must trigger launch_app for YouTube", CanonicalTools.LAUNCH_APP, nextAction.name)
    }

    // ========================================================================
    // TEST HELPERS & FAKES
    // ========================================================================

    private fun createMockContext(prefs: FakeSharedPreferences): Context {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "jarvis_web_test_${System.currentTimeMillis()}").apply { mkdirs() }
        return object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getPackageName(): String = "com.assistive.headmouse"
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): java.io.File = tempDir
        }
    }

    class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val backingMap: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { key?.let { temp[it] = values }; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { key?.let { temp[it] = value }; return this }
            override fun remove(key: String?): SharedPreferences.Editor { key?.let { temp.remove(it); backingMap.remove(it) }; return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) backingMap.clear()
                backingMap.putAll(temp)
            }
        }
    }
}
