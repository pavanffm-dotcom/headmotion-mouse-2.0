package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.action.*
import com.assistive.headmouse.agent.jarvis.autonomous.DynamicPlanner
import com.assistive.headmouse.agent.jarvis.autonomous.ToolCapabilityManager
import com.assistive.headmouse.agent.jarvis.autonomous.ToolRegistry
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelClient
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelResponse
import com.assistive.headmouse.agent.jarvis.autonomous.state.AutonomousMissionStatus
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.Subgoal
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDefinition
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2 Verification Suite for J.A.R.V.I.S. Closed-Loop Agent.
 *
 * Verifies:
 * 1. Multi-step missions execute strictly in a closed loop:
 *    OBSERVE -> DECIDE -> ACT -> WAIT -> OBSERVE -> VERIFY -> UPDATE -> DECIDE AGAIN -> COMPLETE.
 * 2. Decision #2 is grounded in the *new* screen state produced after step #1.
 * 3. Immutable originalUserGoal is preserved throughout all steps.
 * 4. Dual hash system correctly detects screen state mutations.
 * 5. VerificationEngine empirical pre/post state validation.
 */
class JarvisClosedLoopAgentTest {

    // =========================================================================
    // Test 1: Core Closed-Loop Verification: "Open Play Store and search WhatsApp"
    // Proves that Decision #2 is grounded in the NEW screen state produced by Step #1!
    // =========================================================================
    @Test
    fun testOpenPlayStoreAndSearchWhatsAppClosedLoop() = runBlocking {
        val userGoal = "Open Play Store and search WhatsApp"
        val missionState = MissionState(originalUserGoal = userGoal)

        // Decompose compound subgoals
        missionState.remainingObjectives.add(Subgoal(description = "Open Play Store"))
        missionState.remainingObjectives.add(Subgoal(description = "Search WhatsApp"))
        missionState.advanceSubgoal()

        assertEquals("Open Play Store and search WhatsApp", missionState.originalUserGoal)
        assertEquals("Open Play Store", missionState.currentSubgoal?.description)

        val planner = DynamicPlanner(
            toolRegistry = ToolRegistry(ToolCapabilityManager()),
            jarvisBrain = null,
            appSettings = null
        )

        // Mock ModelClient tracking prompt inputs and decisions
        val recordedScreens = mutableListOf<String>()
        val mockModel = object : ModelClient {
            override suspend fun decideNextAction(
                systemInstruction: String,
                originalUserGoal: String,
                currentSubgoal: String?,
                compressedScreenIndex: String,
                actionHistory: List<String>,
                availableTools: List<ToolDefinition>,
                screenshotBase64: String?
            ): Result<ModelResponse> {
                recordedScreens.add(compressedScreenIndex)
                return Result.failure(RuntimeException("Fallback to heuristic perception"))
            }
        }

        // ---------------------------------------------------------------------
        // CYCLE 1: OBSERVE Screen 0 (Device Home / Launcher)
        // ---------------------------------------------------------------------
        val screen0Launcher = WorldState(
            foregroundPackage = "com.android.launcher",
            foregroundActivity = "com.android.launcher3.uioverrides.QuickstepLauncher",
            nodes = listOf(
                SemanticNode(index = 1, text = "Phone", left = 100f, top = 2000f, right = 300f, bottom = 2200f, isClickable = true),
                SemanticNode(index = 2, text = "Messages", left = 350f, top = 2000f, right = 550f, bottom = 2200f, isClickable = true),
                SemanticNode(index = 3, text = "Camera", left = 600f, top = 2000f, right = 800f, bottom = 2200f, isClickable = true)
            )
        )
        missionState.currentObservation = screen0Launcher

        // DECIDE 1:
        val decision1 = planner.decideNextAction(missionState, mockModel)

        // ASSERT 1: Must launch Play Store because active app is launcher!
        assertEquals(CanonicalTools.LAUNCH_APP, decision1.name)
        assertEquals("com.android.vending", decision1.arguments["package_or_name"])
        assertEquals("Open Play Store and search WhatsApp", missionState.originalUserGoal) // Immutable!

        // SIMULATE ACT 1: Play Store is launched and UI settles
        val preStateForVerification = screen0Launcher

        // ---------------------------------------------------------------------
        // CYCLE 2: OBSERVE Screen 1 (Play Store is now OPEN!)
        // ---------------------------------------------------------------------
        val screen1PlayStore = WorldState(
            foregroundPackage = "com.android.vending",
            foregroundActivity = "com.google.android.finsky.activities.MainActivity",
            nodes = listOf(
                SemanticNode(index = 1, text = "Search", contentDescription = "Search Google Play", left = 850f, top = 120f, right = 1000f, bottom = 220f, isClickable = true),
                SemanticNode(index = 2, text = "For you", left = 50f, top = 280f, right = 250f, bottom = 360f, isClickable = true),
                SemanticNode(index = 3, text = "Top charts", left = 280f, top = 280f, right = 480f, bottom = 360f, isClickable = true)
            )
        )

        // VERIFY 1: VerificationEngine checks pre vs post state
        val verificationEngine = VerificationEngine()
        val verification1 = verificationEngine.verify(decision1, preStateForVerification, screen1PlayStore)
        assertTrue("Launch app must be verified on package transition", verification1.verified)

        // UPDATE STATE 1: Advance to Subgoal 2
        missionState.previousObservation = preStateForVerification
        missionState.currentObservation = screen1PlayStore
        missionState.lastAction = decision1
        missionState.advanceSubgoal()

        assertEquals("Search WhatsApp", missionState.currentSubgoal?.description)
        assertEquals(1, missionState.completedObjectives.size)
        assertEquals("Open Play Store and search WhatsApp", missionState.originalUserGoal) // Strict Goal Invariant!

        // DECIDE 2: Grounded in the NEW screen state (screen1PlayStore)
        val decision2 = planner.decideNextAction(missionState, mockModel)

        // ASSERT 2: Decision 2 MUST NOT be launch_app! It must be grounded in the new screen!
        assertNotEquals(CanonicalTools.LAUNCH_APP, decision2.name)
        assertEquals("Decision 2 must target Search button on Play Store display", CanonicalTools.TAP_ELEMENT, decision2.name)
        val targetLabel = decision2.arguments["label"]?.toString() ?: ""
        assertTrue("Target label should be 'Search', was '$targetLabel'", targetLabel.contains("Search", ignoreCase = true))

        // ---------------------------------------------------------------------
        // CYCLE 3: OBSERVE Screen 2 (Search Bar is now FOCUSED and EDITABLE!)
        // ---------------------------------------------------------------------
        val screen2SearchFocused = WorldState(
            foregroundPackage = "com.android.vending",
            foregroundActivity = "com.google.android.finsky.search.SearchActivity",
            nodes = listOf(
                SemanticNode(
                    index = 1,
                    text = "",
                    contentDescription = "Search Google Play",
                    resourceId = "com.android.vending:id/search_box",
                    className = "android.widget.EditText",
                    left = 120f, top = 100f, right = 950f, bottom = 220f,
                    isClickable = true,
                    isEditable = true,
                    isFocused = true
                )
            ),
            isKeyboardVisible = true
        )

        // VERIFY 2:
        val verification2 = verificationEngine.verify(decision2, screen1PlayStore, screen2SearchFocused)
        assertTrue("Tap on search must mutate UI state and verify", verification2.verified)

        // UPDATE STATE 2:
        missionState.previousObservation = screen1PlayStore
        missionState.currentObservation = screen2SearchFocused
        missionState.lastAction = decision2

        // DECIDE 3: Grounded in the focused edit field of Screen 2
        val decision3 = planner.decideNextAction(missionState, mockModel)

        // ASSERT 3: Decision 3 must type "WhatsApp" into the active input!
        assertEquals("Decision 3 must inject text", CanonicalTools.TYPE_TEXT, decision3.name)
        val typedText = decision3.arguments["text"]?.toString() ?: ""
        assertEquals("WhatsApp", typedText)

        // Strict Invariant Check: Goal must NEVER have been altered by intermediate steps
        assertEquals("Open Play Store and search WhatsApp", missionState.originalUserGoal)
    }

    // =========================================================================
    // Test 2: Verify ModelClient receives compressed index of the NEW screen
    // =========================================================================
    @Test
    fun testModelClientPromptReceivesNewScreenObservation() = runBlocking {
        val userGoal = "Settings mein jao aur Display check karo"
        val missionState = MissionState(originalUserGoal = userGoal)

        val receivedPrompts = mutableListOf<String>()
        val mockModel = object : ModelClient {
            override suspend fun decideNextAction(
                systemInstruction: String,
                originalUserGoal: String,
                currentSubgoal: String?,
                compressedScreenIndex: String,
                actionHistory: List<String>,
                availableTools: List<ToolDefinition>,
                screenshotBase64: String?
            ): Result<ModelResponse> {
                receivedPrompts.add(compressedScreenIndex)
                return Result.success(
                    ModelResponse(
                        toolCall = ToolCall(
                            name = CanonicalTools.TAP_ELEMENT,
                            arguments = mapOf("label" to "Display"),
                            thought = "Tapping Display setting option."
                        )
                    )
                )
            }
        }

        val planner = DynamicPlanner(
            toolRegistry = ToolRegistry(ToolCapabilityManager()),
            jarvisBrain = null,
            appSettings = null
        )

        // Step 1: In Settings app
        val settingsState = WorldState(
            foregroundPackage = "com.android.settings",
            nodes = listOf(
                SemanticNode(index = 1, text = "Network & internet", left = 50f, top = 300f, right = 800f, bottom = 400f, isClickable = true),
                SemanticNode(index = 2, text = "Display", left = 50f, top = 420f, right = 800f, bottom = 520f, isClickable = true)
            )
        )
        missionState.currentObservation = settingsState

        val toolCall = planner.decideNextAction(missionState, mockModel)

        // Assert ModelClient was called with the live screen index
        assertEquals(1, receivedPrompts.size)
        assertTrue(receivedPrompts[0].contains("APP: com.android.settings"))
        assertTrue(receivedPrompts[0].contains("Display"))
        assertEquals(CanonicalTools.TAP_ELEMENT, toolCall.name)
        assertEquals("Display", toolCall.arguments["label"])
    }

    // =========================================================================
    // Test 3: Authoritative MissionState Immutable Goal Invariant
    // =========================================================================
    @Test
    fun testAuthoritativeMissionStateGoalImmutability() {
        val immutableGoal = "YouTube open karo aur Shorts dekho"
        val mission = MissionState(originalUserGoal = immutableGoal)

        assertEquals(immutableGoal, mission.originalUserGoal)
        assertEquals(0, mission.modelDecisionCount)
        assertEquals(0, mission.failureCount)
        assertEquals(AutonomousMissionStatus.IDLE, mission.status)

        // Simulate 5 subgoal transitions
        for (i in 1..5) {
            mission.remainingObjectives.add(Subgoal(description = "Subgoal $i"))
            mission.advanceSubgoal()
            mission.recordActionSuccess()
            mission.modelDecisionCount++
        }

        // Original goal must NEVER be altered
        assertEquals(immutableGoal, mission.originalUserGoal)
        assertEquals(5, mission.modelDecisionCount)
        assertEquals(0, mission.consecutiveFailedActions)
    }

    // =========================================================================
    // Test 4: Dual Hash System for Deterministic Mutation Detection
    // =========================================================================
    @Test
    fun testWorldStateDualHashDetection() {
        val nodeA1 = SemanticNode(index = 1, text = "Search", left = 100f, top = 100f, right = 300f, bottom = 200f)
        val nodeA2 = SemanticNode(index = 2, text = "Results", left = 100f, top = 250f, right = 500f, bottom = 350f)

        val stateA = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(nodeA1, nodeA2)
        )

        // Identical state -> Hashes match
        val stateB = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(nodeA1, nodeA2)
        )
        assertFalse(stateA.hasStateChanged(stateB))
        assertEquals(stateA.screenHash, stateB.screenHash)
        assertEquals(stateA.accessibilityHash, stateB.accessibilityHash)

        // Content changed (text changed) -> accessibilityHash differs!
        val nodeC2 = SemanticNode(index = 2, text = "New Results Loaded", left = 100f, top = 250f, right = 500f, bottom = 350f)
        val stateC = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(nodeA1, nodeC2)
        )
        assertTrue(stateA.hasStateChanged(stateC))
        assertNotEquals(stateA.accessibilityHash, stateC.accessibilityHash)

        // Structural layout changed (bounds moved) -> screenHash differs!
        val nodeD2 = SemanticNode(index = 2, text = "Results", left = 100f, top = 600f, right = 500f, bottom = 700f)
        val stateD = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(nodeA1, nodeD2)
        )
        assertTrue(stateA.hasStateChanged(stateD))
        assertNotEquals(stateA.screenHash, stateD.screenHash)
    }

    // =========================================================================
    // Test 5: VerificationEngine Pre/Post State Validation
    // =========================================================================
    @Test
    fun testVerificationEngineEvaluations() {
        val engine = VerificationEngine()

        val preState = WorldState(
            foregroundPackage = "com.android.launcher",
            nodes = listOf(SemanticNode(index = 1, text = "App", left = 0f, top = 0f, right = 100f, bottom = 100f))
        )

        val postStateSuccess = WorldState(
            foregroundPackage = "com.google.android.youtube",
            nodes = listOf(SemanticNode(index = 1, text = "Subscriptions", left = 0f, top = 0f, right = 100f, bottom = 100f))
        )

        val launchTool = ToolCall(
            name = CanonicalTools.LAUNCH_APP,
            arguments = mapOf("package_or_name" to "YouTube")
        )

        val resSuccess = engine.verify(launchTool, preState, postStateSuccess)
        assertTrue(resSuccess.verified)
        assertTrue(resSuccess.stateChanged)

        // App launch failure: Package remains launcher
        val resFailed = engine.verify(launchTool, preState, preState)
        assertFalse(resFailed.verified)
        assertFalse(resFailed.stateChanged)
    }
}
