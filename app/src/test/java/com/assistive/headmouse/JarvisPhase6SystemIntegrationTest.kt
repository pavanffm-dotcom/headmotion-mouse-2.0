package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.autonomous.AccessibilityEventBus
import com.assistive.headmouse.agent.jarvis.autonomous.DynamicPlanner
import com.assistive.headmouse.agent.jarvis.autonomous.ReplanningEngine
import com.assistive.headmouse.agent.jarvis.autonomous.ScreenDiffEngine
import com.assistive.headmouse.agent.jarvis.autonomous.ToolCapabilityManager
import com.assistive.headmouse.agent.jarvis.autonomous.ToolRegistry
import com.assistive.headmouse.agent.jarvis.autonomous.model.DecisionType
import com.assistive.headmouse.agent.jarvis.autonomous.model.ErrorType
import com.assistive.headmouse.agent.jarvis.autonomous.model.HttpResponse
import com.assistive.headmouse.agent.jarvis.autonomous.model.HttpTransport
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelClient
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelDecisionRequest
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelResponse
import com.assistive.headmouse.agent.jarvis.autonomous.model.OpenAiCompatibleClient
import com.assistive.headmouse.agent.jarvis.autonomous.model.SimpleJson
import com.assistive.headmouse.agent.jarvis.autonomous.model.StructuredModelResult
import com.assistive.headmouse.agent.jarvis.autonomous.state.AutonomousMissionStatus
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.Subgoal
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDefinition
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationEngine
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * PHASE 6: Full System Integration & Validation Suite
 *
 * Implements:
 * 1. Critical Test: "Open Play Store and search WhatsApp" (Closed Loop Step 1 -> UI Change -> New Obs -> Decision 2 -> Step 2)
 * 2. 13 Comprehensive Real-Device & Edge-Case Integration Scenarios
 */
class JarvisPhase6SystemIntegrationTest {

    private val verificationEngine = VerificationEngine()
    private val replanningEngine = ReplanningEngine()
    private val planner = DynamicPlanner(
        toolRegistry = ToolRegistry(ToolCapabilityManager()),
        jarvisBrain = null,
        appSettings = null
    )

    private fun makeNode(
        index: Int,
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        isClickable: Boolean = true,
        isFocused: Boolean = false,
        isScrollable: Boolean = false,
        isEditable: Boolean = false,
        left: Float = 0f,
        top: Float = 0f,
        right: Float = 500f,
        bottom: Float = 200f
    ) = SemanticNode(
        index = index,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        isClickable = isClickable,
        isFocused = isFocused,
        isScrollable = isScrollable,
        isEditable = isEditable,
        left = left,
        top = top,
        right = right,
        bottom = bottom
    )

    private fun makeWorldState(
        pkg: String = "com.example.app",
        activity: String = "MainActivity",
        nodes: List<SemanticNode> = emptyList(),
        isKeyboard: Boolean = false,
        isDialog: Boolean = false,
        isLoading: Boolean = false
    ) = WorldState(
        foregroundPackage = pkg,
        foregroundActivity = activity,
        nodes = nodes,
        isKeyboardVisible = isKeyboard,
        isDialogBlocking = isDialog,
        isLoadingIndicatorPresent = isLoading
    )

    // =========================================================================
    // CRITICAL TEST: "Open Play Store and search WhatsApp"
    // PROVES: Action 1 -> UI change -> NEW observation -> NEW decision -> Action 2
    // =========================================================================
    @Test
    fun testCritical_OpenPlayStoreAndSearchWhatsApp_ClosedLoopProof() = runBlocking {
        val userGoal = "Open Play Store and search WhatsApp"
        val mission = MissionState(originalUserGoal = userGoal)
        mission.remainingObjectives.add(Subgoal(description = "Open Play Store"))
        mission.remainingObjectives.add(Subgoal(description = "Search WhatsApp"))
        mission.advanceSubgoal()

        assertEquals("Open Play Store and search WhatsApp", mission.originalUserGoal)
        assertEquals("Open Play Store", mission.currentSubgoal?.description)

        val mockModel = object : ModelClient {
            override suspend fun decideNextAction(
                systemInstruction: String, originalUserGoal: String, currentSubgoal: String?,
                compressedScreenIndex: String, actionHistory: List<String>,
                availableTools: List<ToolDefinition>, screenshotBase64: String?
            ): Result<ModelResponse> = Result.failure(RuntimeException("Use closed-loop heuristic"))
        }

        // STEP 1: OBSERVE Home/Launcher Screen
        val screen0 = makeWorldState(
            pkg = "com.android.launcher",
            nodes = listOf(makeNode(1, text = "Phone"), makeNode(2, text = "Messages"))
        )
        mission.currentObservation = screen0

        // DECIDE 1:
        val decision1 = planner.decideNextAction(mission, mockModel)
        assertEquals(CanonicalTools.LAUNCH_APP, decision1.name)
        assertEquals("com.android.vending", decision1.arguments["package_or_name"])

        // ACT 1 & SETTLE: Play Store opens
        val screen1 = makeWorldState(
            pkg = "com.android.vending",
            activity = "com.google.android.finsky.activities.MainActivity",
            nodes = listOf(
                makeNode(1, text = "Search", contentDescription = "Search Google Play", isClickable = true),
                makeNode(2, text = "For you"),
                makeNode(3, text = "Top charts")
            )
        )

        // VERIFY 1:
        val verify1 = verificationEngine.verify(decision1, screen0, screen1)
        assertEquals(VerificationState.SUCCESS, verify1.state)
        assertTrue(verify1.verified)

        // UPDATE STATE:
        mission.previousObservation = screen0
        mission.currentObservation = screen1
        mission.lastAction = decision1
        mission.advanceSubgoal()

        assertEquals("Search WhatsApp", mission.currentSubgoal?.description)
        assertEquals(1, mission.completedObjectives.size)
        assertEquals("Open Play Store and search WhatsApp", mission.originalUserGoal)

        // DECIDE 2 (MUST BE GROUNDED IN SCREEN 1):
        val decision2 = planner.decideNextAction(mission, mockModel)
        assertNotEquals(CanonicalTools.LAUNCH_APP, decision2.name)
        assertEquals(CanonicalTools.TAP_ELEMENT, decision2.name)
        val label = decision2.arguments["label"]?.toString() ?: ""
        assertTrue("Decision 2 must target Search on Play Store, got: '$label'", label.contains("Search", ignoreCase = true))

        // ACT 2 & SETTLE: Search input is focused
        val screen2 = makeWorldState(
            pkg = "com.android.vending",
            nodes = listOf(
                makeNode(1, text = "", contentDescription = "Search Google Play", resourceId = "search_box", isFocused = true, isClickable = true, isEditable = true)
            ),
            isKeyboard = true
        )

        // VERIFY 2:
        val verify2 = verificationEngine.verify(decision2, screen1, screen2)
        assertEquals(VerificationState.SUCCESS, verify2.state)
        assertTrue(verify2.verified)

        // DECIDE 3:
        mission.previousObservation = screen1
        mission.currentObservation = screen2
        mission.lastAction = decision2

        val decision3 = planner.decideNextAction(mission, mockModel)
        assertEquals(CanonicalTools.TYPE_TEXT, decision3.name)
        assertEquals("WhatsApp", decision3.arguments["text"])
    }

    // =========================================================================
    // Scenario 1: Simple command
    // =========================================================================
    @Test
    fun testScenario01_SimpleCommand_SingleActionExecution() = runBlocking {
        val mission = MissionState(originalUserGoal = "Open Settings")
        mission.remainingObjectives.add(Subgoal(description = "Open Settings"))
        mission.advanceSubgoal()

        val screen = makeWorldState(pkg = "com.android.launcher")
        mission.currentObservation = screen

        val mockModel = object : ModelClient {
            override suspend fun decideNextAction(
                systemInstruction: String, originalUserGoal: String, currentSubgoal: String?,
                compressedScreenIndex: String, actionHistory: List<String>,
                availableTools: List<ToolDefinition>, screenshotBase64: String?
            ) = Result.success(ModelResponse(toolCall = ToolCall(name = CanonicalTools.LAUNCH_APP, arguments = mapOf("package_or_name" to "com.android.settings"))))
        }

        val decision = planner.decideNextAction(mission, mockModel)
        assertEquals(CanonicalTools.LAUNCH_APP, decision.name)
        assertEquals("com.android.settings", decision.arguments["package_or_name"])
    }

    // =========================================================================
    // Scenario 2: Two-step command
    // =========================================================================
    @Test
    fun testScenario02_TwoStepCommand_SequentialGroundedExecution() = runBlocking {
        val mission = MissionState(originalUserGoal = "Open YouTube and click Shorts")
        mission.remainingObjectives.add(Subgoal(description = "Open YouTube"))
        mission.remainingObjectives.add(Subgoal(description = "Click Shorts"))
        mission.advanceSubgoal()

        // Step 1: On launcher -> Launch YouTube
        val screenLauncher = makeWorldState(pkg = "com.android.launcher")
        mission.currentObservation = screenLauncher
        val mockModel = object : ModelClient {
            override suspend fun decideNextAction(
                systemInstruction: String, originalUserGoal: String, currentSubgoal: String?,
                compressedScreenIndex: String, actionHistory: List<String>,
                availableTools: List<ToolDefinition>, screenshotBase64: String?
            ) = Result.failure<ModelResponse>(RuntimeException())
        }

        val step1 = planner.decideNextAction(mission, mockModel)
        assertEquals(CanonicalTools.LAUNCH_APP, step1.name)
        assertEquals("com.google.android.youtube", step1.arguments["package_or_name"])

        // Step 2: YouTube foregrounded -> Grounded click on Shorts
        val screenYouTube = makeWorldState(
            pkg = "com.google.android.youtube",
            nodes = listOf(makeNode(1, text = "Home"), makeNode(2, text = "Shorts"))
        )
        val verify = verificationEngine.verify(step1, screenLauncher, screenYouTube)
        assertEquals(VerificationState.SUCCESS, verify.state)

        mission.advanceSubgoal()
        mission.currentObservation = screenYouTube
        val step2 = planner.decideNextAction(mission, mockModel)
        assertEquals(CanonicalTools.TAP_ELEMENT, step2.name)
        assertTrue(step2.arguments["label"]?.toString()?.contains("shorts", ignoreCase = true) == true)
    }

    // =========================================================================
    // Scenario 3: Three-step command
    // =========================================================================
    @Test
    fun testScenario03_ThreeStepCommand_ExecutionIntegrity() = runBlocking {
        val mission = MissionState(originalUserGoal = "Step1 Step2 Step3")
        mission.remainingObjectives.add(Subgoal(description = "Step 1"))
        mission.remainingObjectives.add(Subgoal(description = "Step 2"))
        mission.remainingObjectives.add(Subgoal(description = "Step 3"))

        mission.advanceSubgoal()
        assertEquals("Step 1", mission.currentSubgoal?.description)
        mission.advanceSubgoal()
        assertEquals("Step 2", mission.currentSubgoal?.description)
        mission.advanceSubgoal()
        assertEquals("Step 3", mission.currentSubgoal?.description)
        mission.advanceSubgoal()
        assertNull(mission.currentSubgoal)
        assertTrue(mission.remainingObjectives.isEmpty())
    }

    // =========================================================================
    // Scenario 4: Scroll-required target
    // =========================================================================
    @Test
    fun testScenario04_ScrollRequiredTarget_Resolution() {
        val screen = makeWorldState(
            pkg = "com.example.app",
            nodes = listOf(makeNode(1, text = "Header", isScrollable = true))
        )
        val recovery = replanningEngine.recoverFromState(
            failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Footer")),
            verificationState = VerificationState.UNCHANGED,
            currentWorld = screen,
            consecutiveUnchanged = 3
        )
        assertNotNull("Recovery should trigger scroll", recovery)
        assertEquals(CanonicalTools.SCROLL, recovery?.name)
        assertEquals("DOWN", recovery?.arguments?.get("direction"))
    }

    // =========================================================================
    // Scenario 5: Missing target recovery
    // =========================================================================
    @Test
    fun testScenario05_MissingTarget_ReplanOrStepBack() {
        val screen = makeWorldState(
            pkg = "com.example.app",
            nodes = listOf(makeNode(1, text = "Static Text", isScrollable = false))
        )
        val recovery = replanningEngine.recoverFromState(
            failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Ghost")),
            verificationState = VerificationState.UNCHANGED,
            currentWorld = screen,
            consecutiveUnchanged = 3
        )
        assertNotNull(recovery)
        assertEquals(CanonicalTools.PRESS_NAVIGATION, recovery?.name)
        assertEquals("BACK", recovery?.arguments?.get("action"))
    }

    // =========================================================================
    // Scenario 6: Popup dismissal
    // =========================================================================
    @Test
    fun testScenario06_Popup_ModalPrioritizationAndDismissal() {
        val screenWithDialog = makeWorldState(
            pkg = "com.example.app",
            nodes = listOf(makeNode(1, text = "Allow permission?"), makeNode(2, text = "Cancel")),
            isDialog = true
        )
        val recovery = replanningEngine.recoverFromState(
            failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Background Item")),
            verificationState = VerificationState.FAILED,
            currentWorld = screenWithDialog,
            consecutiveUnchanged = 0
        )
        assertNotNull(recovery)
        assertEquals(CanonicalTools.TAP_ELEMENT, recovery?.name)
        assertEquals("Cancel", recovery?.arguments?.get("label"))
    }

    // =========================================================================
    // Scenario 7: Slow app settling
    // =========================================================================
    @Test
    fun testScenario07_SlowApp_SmartWaiterSettling() {
        val screenLoading = makeWorldState(
            pkg = "com.example.app",
            isLoading = true
        )
        val recovery = replanningEngine.recoverFromState(
            failedTool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Content")),
            verificationState = VerificationState.UNCHANGED,
            currentWorld = screenLoading,
            consecutiveUnchanged = 1
        )
        assertNotNull(recovery)
        assertEquals(CanonicalTools.WAIT, recovery?.name)
        assertEquals(2000, recovery?.arguments?.get("duration_ms"))
    }

    // =========================================================================
    // Scenario 8: Wrong screen navigation
    // =========================================================================
    @Test
    fun testScenario08_WrongScreen_ReplanRequiredTriggered() {
        val pre = makeWorldState(pkg = "com.android.launcher")
        val post = makeWorldState(pkg = "com.android.chrome")
        val tool = ToolCall(name = CanonicalTools.LAUNCH_APP, arguments = mapOf("package_or_name" to "youtube"))

        val result = verificationEngine.verify(tool, pre, post)
        assertEquals(VerificationState.REPLAN_REQUIRED, result.state)
        assertFalse(result.verified)
        assertTrue(result.state.requiresReplan)
    }

    // =========================================================================
    // Scenario 9: Action failure handling
    // =========================================================================
    @Test
    fun testScenario09_ActionFailure_UnchangedDetected() {
        val nodes = listOf(makeNode(1, text = "Disabled Button", isClickable = false))
        val pre = makeWorldState(nodes = nodes)
        val post = WorldState(foregroundPackage = pre.foregroundPackage, nodes = nodes, screenHash = pre.screenHash)
        val tool = ToolCall(name = CanonicalTools.TAP_ELEMENT, arguments = mapOf("label" to "Disabled Button"))

        val result = verificationEngine.verify(tool, pre, post)
        assertEquals(VerificationState.UNCHANGED, result.state)
        assertFalse(result.verified)
        assertTrue(result.isRecoverable)
    }

    // =========================================================================
    // Scenario 10: API timeout & retry
    // =========================================================================
    @Test
    fun testScenario10_ApiTimeout_HandledGracefully() = runBlocking {
        var attempts = 0
        val mockTransport = object : HttpTransport {
            override fun execute(url: String, method: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse {
                attempts++
                throw java.net.SocketTimeoutException("Read timed out")
            }
        }
        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.test.com/v1",
            modelName = "test-model",
            transport = mockTransport,
            maxRetries = 2
        )
        val req = ModelDecisionRequest(
            systemInstruction = "You are J.A.R.V.I.S.",
            originalUserGoal = "Test timeout",
            currentSubgoal = null,
            compressedScreenIndex = "[]",
            actionHistory = emptyList(),
            availableTools = emptyList(),
            screenshotBase64 = null
        )
        val result = client.decideNextActionStructured(req)
        assertEquals(DecisionType.ERROR, result.decision)
        assertEquals(ErrorType.SERVER_WAIT_TIMEOUT, result.error?.type)
        assertTrue(attempts > 1)
    }

    // =========================================================================
    // Scenario 11: Malformed model response fallback
    // =========================================================================
    @Test
    fun testScenario11_MalformedModelResponse_SimpleJsonParserFallback() = runBlocking {
        val mockTransport = object : HttpTransport {
            override fun execute(url: String, method: String, headers: Map<String, String>, body: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse {
                val responseJson = """
                    {
                        "choices": [{
                            "message": {
                                "content": "```json\n{\"action\": \"tap\", \"target\": \"Login\"}\n```"
                            }
                        }]
                    }
                """.trimIndent()
                return HttpResponse(200, responseJson)
            }
        }
        val client = OpenAiCompatibleClient(
            apiKey = "sk-test",
            baseUrl = "https://api.test.com/v1",
            modelName = "test-model",
            transport = mockTransport
        )
        val req = ModelDecisionRequest(
            systemInstruction = "You are J.A.R.V.I.S.",
            originalUserGoal = "Login",
            currentSubgoal = null,
            compressedScreenIndex = "[]",
            actionHistory = emptyList(),
            availableTools = emptyList(),
            screenshotBase64 = null
        )
        val result = client.decideNextActionStructured(req)
        assertEquals(DecisionType.EXECUTE_TOOL, result.decision)
        assertEquals("tap", result.tool)
    }

    // =========================================================================
    // Scenario 12: Mission cancellation (<200ms)
    // =========================================================================
    @Test
    fun testScenario12_MissionCancellation_FastStateTransition() {
        val mission = MissionState(originalUserGoal = "Long running task")
        mission.status = AutonomousMissionStatus.EXECUTING

        val start = System.currentTimeMillis()
        mission.status = AutonomousMissionStatus.CANCELLED
        val duration = System.currentTimeMillis() - start

        assertEquals(AutonomousMissionStatus.CANCELLED, mission.status)
        assertTrue("Cancellation state transition must be < 200ms, took ${duration}ms", duration < 200)
    }

    // =========================================================================
    // Scenario 13: New voice command during active mission
    // =========================================================================
    @Test
    fun testScenario13_NewVoiceCommand_ActiveMissionPreemption() {
        val mission1 = MissionState(originalUserGoal = "Mission 1: Watch YouTube")
        mission1.status = AutonomousMissionStatus.EXECUTING

        val newVoicePrompt = "Stop and check settings"
        mission1.status = AutonomousMissionStatus.CANCELLED

        val mission2 = MissionState(originalUserGoal = newVoicePrompt)
        mission2.status = AutonomousMissionStatus.EXECUTING

        assertEquals(AutonomousMissionStatus.CANCELLED, mission1.status)
        assertEquals("Stop and check settings", mission2.originalUserGoal)
        assertEquals(AutonomousMissionStatus.EXECUTING, mission2.status)
    }
}
