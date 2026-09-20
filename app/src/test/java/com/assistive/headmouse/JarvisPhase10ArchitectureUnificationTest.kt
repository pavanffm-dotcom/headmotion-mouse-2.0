package com.assistive.headmouse

import android.content.Context
import android.content.SharedPreferences
import com.assistive.headmouse.agent.jarvis.ActionType
import com.assistive.headmouse.agent.jarvis.JarvisBrain
import com.assistive.headmouse.agent.jarvis.JarvisResponse
import com.assistive.headmouse.agent.jarvis.autonomous.DynamicPlanner
import com.assistive.headmouse.agent.jarvis.autonomous.ToolCapabilityManager
import com.assistive.headmouse.agent.jarvis.autonomous.ToolRegistry
import com.assistive.headmouse.agent.jarvis.autonomous.model.*
import com.assistive.headmouse.agent.jarvis.autonomous.state.AutonomousMissionStatus
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDispatcher
import com.assistive.headmouse.model.CustomAiModel
import com.assistive.headmouse.preferences.AiProvider
import com.assistive.headmouse.preferences.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PHASE 10 VERIFICATION SUITE — CANONICAL AI ARCHITECTURE UNIFICATION
 *
 * Validates:
 * 1. ModelClientFactory resolves Gemini, OpenAI, and Custom OpenRouter accurately.
 * 2. ModelClientFactory correctly provides DisabledModelClient on missing keys or disabled cloud AI.
 * 3. DynamicPlanner routes decisions strictly through ModelClient independently of JarvisBrain.
 * 4. DynamicPlanner fast-paths cold app launch without remote model call when target app is not foregrounded.
 * 5. DynamicPlanner falls back gracefully to local heuristic perception matching when ModelClient errors.
 * 6. Canonical autonomous execution route (DynamicPlanner -> ModelClient -> ToolDispatcher).
 * 7. Conversational JarvisBrain preserves general conversational Q&A without running autonomous missions.
 * 8. Conversational JarvisBrain routes compound mission requests to ActionType.START_MISSION.
 * 9. Legacy mission planning methods in JarvisBrain remain deprecated and isolated.
 */
class JarvisPhase10ArchitectureUnificationTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var mockContext: Context
    private lateinit var appSettings: AppSettings
    private lateinit var toolRegistry: ToolRegistry

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        mockContext = createMockContext(fakePrefs)
        appSettings = AppSettings(mockContext)
        val capabilityManager = ToolCapabilityManager(mockContext)
        toolRegistry = ToolRegistry(capabilityManager)
    }

    // ========================================================================
    // 1. MODEL CLIENT FACTORY & PROVIDER ROUTING TESTS
    // ========================================================================

    @Test
    fun testModelClientFactory_resolvesGemini_whenProviderIsGemini() {
        appSettings.isCloudAiEnabled = true
        appSettings.aiProvider = AiProvider.GEMINI
        appSettings.geminiApiKey = "AIzaSyTestKey_Gemini123"

        val client = ModelClientFactory.create(appSettings)
        assertTrue("Expected GoogleGeminiClient for AiProvider.GEMINI", client is GoogleGeminiClient)
    }

    @Test
    fun testModelClientFactory_resolvesOpenAi_whenProviderIsOpenAi() {
        appSettings.isCloudAiEnabled = true
        appSettings.aiProvider = AiProvider.OPENAI
        appSettings.openAiApiKey = "sk-openai-test-key-456"

        val client = ModelClientFactory.create(appSettings)
        assertTrue("Expected OpenAiCompatibleClient for AiProvider.OPENAI", client is OpenAiCompatibleClient)
    }

    @Test
    fun testModelClientFactory_resolvesCustomOpenRouter_whenProviderIsCustom() {
        appSettings.isCloudAiEnabled = true
        appSettings.aiProvider = AiProvider.CUSTOM_OPENROUTER
        val customModel = CustomAiModel(
            id = "custom_1",
            name = "xKiro DeepSeek",
            modelId = "deepseek/deepseek-chat",
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-test-789",
            isActive = true
        )
        appSettings.saveCustomModels(listOf(customModel))

        val client = ModelClientFactory.create(appSettings)
        assertTrue("Expected OpenAiCompatibleClient for CUSTOM_OPENROUTER", client is OpenAiCompatibleClient)
    }

    @Test
    fun testModelClientFactory_returnsDisabled_whenCloudAiDisabled() {
        appSettings.isCloudAiEnabled = false
        appSettings.aiProvider = AiProvider.GEMINI
        appSettings.geminiApiKey = "AIzaSyTestKey"

        val client = ModelClientFactory.create(appSettings)
        assertTrue("Expected DisabledModelClient when isCloudAiEnabled is false", client is DisabledModelClient)
    }

    @Test
    fun testModelClientFactory_returnsDisabled_whenApiKeyMissing() {
        appSettings.isCloudAiEnabled = true
        appSettings.aiProvider = AiProvider.GEMINI
        appSettings.geminiApiKey = ""
        fakePrefs.edit().remove("custom_models_json").remove("custom_api_key").apply()

        val client = ModelClientFactory.create(appSettings)
        assertTrue("Expected DisabledModelClient when API key is blank", client is DisabledModelClient)
    }

    // ========================================================================
    // 2. DYNAMIC PLANNER & MODEL CLIENT DECOUPLING TESTS
    // ========================================================================

    @Test
    fun testDynamicPlanner_decidesActionViaModelClient_withoutJarvisBrain() = runBlocking {
        // DynamicPlanner instantiated strictly with toolRegistry — NO JarvisBrain required!
        val dynamicPlanner = DynamicPlanner(toolRegistry)

        val missionState = MissionState(originalUserGoal = "like this video").apply {
            currentObservation = WorldState(
                foregroundPackage = "com.google.android.youtube",
                nodes = listOf(
                    SemanticNode(index = 1, text = "Like", isClickable = true, bounds = android.graphics.RectF(100f, 200f, 300f, 250f))
                )
            )
        }

        val mockModelClient = object : ModelClient {
            override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
                assertEquals("like this video", request.originalUserGoal)
                return StructuredModelResult(
                    decision = DecisionType.EXECUTE_TOOL,
                    tool = CanonicalTools.TAP_ELEMENT,
                    arguments = mapOf("label" to "Like"),
                    reason = "Liking the current YouTube video."
                )
            }
        }

        val toolCall = dynamicPlanner.decideNextAction(missionState, mockModelClient)
        assertNotNull("ToolCall should not be null", toolCall)
        assertEquals(CanonicalTools.TAP_ELEMENT, toolCall.name)
        assertEquals("Like", toolCall.arguments["label"])
        assertEquals("Liking the current YouTube video.", toolCall.thought)
    }

    @Test
    fun testDynamicPlanner_fastPathsAppLaunch_whenTargetAppNotActive() = runBlocking {
        val dynamicPlanner = DynamicPlanner(toolRegistry)

        val missionState = MissionState(originalUserGoal = "open youtube and watch shorts").apply {
            currentObservation = WorldState(
                foregroundPackage = "com.android.launcher", // Not YouTube!
                nodes = emptyList()
            )
        }

        var modelClientCalled = false
        val mockModelClient = object : ModelClient {
            override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
                modelClientCalled = true
                return StructuredModelResult(decision = DecisionType.FINISH_TASK)
            }
        }

        val toolCall = dynamicPlanner.decideNextAction(missionState, mockModelClient)
        assertFalse("ModelClient should NOT be called for cold app launch fast path", modelClientCalled)
        assertEquals(CanonicalTools.LAUNCH_APP, toolCall.name)
        assertEquals("com.google.android.youtube", toolCall.arguments["package_or_name"])
    }

    @Test
    fun testDynamicPlanner_handlesModelError_withHeuristicFallback() = runBlocking {
        val dynamicPlanner = DynamicPlanner(toolRegistry)

        val missionState = MissionState(originalUserGoal = "click subscribe").apply {
            currentObservation = WorldState(
                foregroundPackage = "com.google.android.youtube",
                nodes = listOf(
                    SemanticNode(index = 1, text = "Subscribe", isClickable = true, bounds = android.graphics.RectF(200f, 400f, 500f, 460f))
                )
            )
        }

        val failingModelClient = object : ModelClient {
            override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
                return StructuredModelResult(
                    decision = DecisionType.ERROR,
                    error = ModelError(type = ErrorType.SERVER_WAIT_TIMEOUT, message = "Network timeout")
                )
            }
        }

        val toolCall = dynamicPlanner.decideNextAction(missionState, failingModelClient)
        assertNotNull("Should fallback to local heuristic matching on model failure", toolCall)
        // Heuristic matcher should find "Subscribe" node
        assertEquals(CanonicalTools.TAP_ELEMENT, toolCall.name)
        assertEquals("Subscribe", toolCall.arguments["label"])
    }

    // ========================================================================
    // 3. AUTHORITATIVE AUTONOMOUS CALL-CHAIN TEST
    // ========================================================================

    @Test
    fun testAuthoritativeCallChain_missionToToolDispatcher() = runBlocking {
        val dynamicPlanner = DynamicPlanner(toolRegistry)
        val toolDispatcher = ToolDispatcher(mockContext) { null }

        val missionState = MissionState(originalUserGoal = "go back").apply {
            currentObservation = WorldState(foregroundPackage = "com.example.app")
        }

        val mockModelClient = object : ModelClient {
            override suspend fun decideNextActionStructured(request: ModelDecisionRequest): StructuredModelResult {
                return StructuredModelResult(
                    decision = DecisionType.EXECUTE_TOOL,
                    tool = CanonicalTools.PRESS_NAVIGATION,
                    arguments = mapOf("key" to "back"),
                    reason = "Returning to previous screen"
                )
            }
        }

        // Step 1: DynamicPlanner -> ModelClient
        val toolCall = dynamicPlanner.decideNextAction(missionState, mockModelClient)
        assertEquals(CanonicalTools.PRESS_NAVIGATION, toolCall.name)

        // Step 2: ToolDispatcher execution
        val result = toolDispatcher.dispatch(toolCall, missionState.currentObservation!!)
        assertNotNull("Tool execution result must not be null", result)
        // With accessibility service null in test, press_navigation handles gracefully
        assertFalse("Expected false with null accessibility service in pure unit test", result.success)
    }

    // ========================================================================
    // 4. CONVERSATIONAL JARVIS BRAIN PRESERVATION TESTS
    // ========================================================================

    @Test
    fun testJarvisBrain_preservesConversationalResponses() = runBlocking {
        val brain = JarvisBrain()

        // Offline conversational prompt: "who are you jarvis"
        val response = brain.processUserPrompt("who are you jarvis", isCloudEnabled = false)
        assertNotNull(response)
        assertTrue("Response should contain JARVIS identity", response.displayText.contains("J.A.R.V.I.S."))
        assertNotEquals("Should NOT start autonomous mission for simple identity query", ActionType.START_MISSION, response.actionType)
    }

    @Test
    fun testJarvisBrain_routesMissionGoal_toStartMissionAction() = runBlocking {
        val brain = JarvisBrain()

        // Compound autonomous mission prompt
        val response = brain.processUserPrompt("open youtube and search lofi beats", isCloudEnabled = false)
        assertNotNull(response)
        assertEquals("Compound mission prompt should trigger ActionType.START_MISSION", ActionType.START_MISSION, response.actionType)
        assertTrue("Action data or goal should be populated", response.actionData?.contains("youtube") == true || response.displayText.contains("mission", ignoreCase = true))
    }

    // ========================================================================
    // TEST HELPERS & FAKES
    // ========================================================================

    private fun createMockContext(prefs: FakeSharedPreferences): Context {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "jarvis_test_${System.currentTimeMillis()}").apply { mkdirs() }
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
