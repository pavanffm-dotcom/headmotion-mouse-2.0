package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.action.*
import com.assistive.headmouse.agent.jarvis.autonomous.*
import com.assistive.headmouse.agent.model.ScreenNode
import org.junit.Assert.*
import org.junit.Test

class JarvisHeavyAutonomousAgentTest {

    // ==========================================
    // 1. Task State Machine (R12)
    // ==========================================
    @Test
    fun testTaskStateMachineHappyPath() {
        val transitions = mutableListOf<String>()
        val sm = TaskStateMachine { oldState, newState, _ ->
            transitions.add("$oldState->$newState")
        }

        assertEquals(TaskState.MISSION_STARTED, sm.currentState)

        assertTrue(sm.transitionTo(TaskState.PLANNING, "Plan"))
        assertTrue(sm.transitionTo(TaskState.OBSERVING, "Observe"))
        assertTrue(sm.transitionTo(TaskState.ACTION_SELECTED, "Select"))
        assertTrue(sm.transitionTo(TaskState.ACTION_EXECUTING, "Execute"))
        assertTrue(sm.transitionTo(TaskState.ACTION_SUCCESS, "Success"))
        assertTrue(sm.transitionTo(TaskState.VERIFYING, "Verify"))
        assertTrue(sm.transitionTo(TaskState.OBJECTIVE_COMPLETED, "Done Obj"))
        assertTrue(sm.transitionTo(TaskState.COMPLETED, "Mission Complete"))

        assertEquals(TaskState.COMPLETED, sm.currentState)
        assertEquals(8, transitions.size)
    }

    @Test
    fun testTaskStateMachineRecoveryFlow() {
        val sm = TaskStateMachine()

        sm.transitionTo(TaskState.PLANNING)
        sm.transitionTo(TaskState.OBSERVING)
        sm.transitionTo(TaskState.ACTION_SELECTED)
        sm.transitionTo(TaskState.ACTION_EXECUTING)

        // Failure & Recovery path
        assertTrue(sm.transitionTo(TaskState.ACTION_FAILED, "Target missing"))
        assertTrue(sm.transitionTo(TaskState.RECOVERY, "Attempting retry"))
        assertTrue(sm.transitionTo(TaskState.REOBSERVE, "Refreshing screen"))
        assertTrue(sm.transitionTo(TaskState.REPLAN, "Generating alternative step"))
        assertTrue(sm.transitionTo(TaskState.ACTION_SELECTED, "New step ready"))

        assertEquals(TaskState.ACTION_SELECTED, sm.currentState)
    }

    @Test
    fun testTaskStateMachineGuardedTransition() {
        val sm = TaskStateMachine()
        assertEquals(TaskState.MISSION_STARTED, sm.currentState)

        // Cannot jump directly from MISSION_STARTED to ACTION_SUCCESS
        assertFalse(sm.transitionTo(TaskState.ACTION_SUCCESS))
        assertEquals(TaskState.MISSION_STARTED, sm.currentState)
    }

    // ==========================================
    // 2. Goal Manager (R9)
    // ==========================================
    @Test
    fun testGoalManagerDecomposition() {
        val gm = GoalManager()
        gm.initializeGoal("Open Play Store and search WhatsApp")

        assertEquals("Open Play Store and search WhatsApp", gm.userGoal)
        assertEquals(MissionStatus.PLANNING, gm.missionStatus)
        assertEquals(2, gm.allObjectives.size)

        // Objective 1: Open Play Store
        val obj1 = gm.currentObjective()
        assertNotNull(obj1)
        assertEquals("Open Play Store", obj1?.title)

        gm.markCurrentObjectiveComplete()
        assertEquals(MissionStatus.COMPLETED, obj1?.status)
        assertEquals(1, gm.completedObjectives().size)

        // Objective 2: Search WhatsApp
        val nextObj = gm.advanceToNextObjective()
        assertNotNull(nextObj)
        assertEquals("Search WhatsApp", nextObj?.title)

        gm.markCurrentObjectiveComplete()
        assertTrue(gm.isAllComplete())
    }

    // ==========================================
    // 3. Screen Diff Engine (R19)
    // ==========================================
    @Test
    fun testScreenDiffEngineDetection() {
        val node1 = ScreenNode(
            id = "node_btn_1",
            left = 100f, top = 200f, right = 300f, bottom = 250f,
            text = "Search",
            isClickable = true
        )
        val node2Old = ScreenNode(
            id = "node_status",
            left = 0f, top = 0f, right = 500f, bottom = 50f,
            text = "Loading..."
        )
        val node2New = ScreenNode(
            id = "node_status",
            left = 0f, top = 0f, right = 500f, bottom = 50f,
            text = "Results Ready"
        )
        val node3New = ScreenNode(
            id = "node_result_1",
            left = 50f, top = 300f, right = 800f, bottom = 450f,
            text = "WhatsApp Messenger",
            isClickable = true
        )

        val beforeState = ScreenState(
            packageName = "com.android.vending",
            activityName = "MainActivity",
            nodes = listOf(node1, node2Old)
        )

        val afterState = ScreenState(
            packageName = "com.android.vending",
            activityName = "SearchResultsActivity",
            nodes = listOf(node2New, node3New)
        )

        val diff = ScreenDiffEngine.computeDiff(beforeState, afterState)

        assertFalse(diff.isPackageChanged)
        assertTrue(diff.isActivityChanged)
        assertEquals(1, diff.addedNodes.size)
        assertEquals("node_result_1", diff.addedNodes[0].id)
        assertEquals(1, diff.removedNodes.size)
        assertEquals("node_btn_1", diff.removedNodes[0].id)
        assertEquals(1, diff.textChanges.size)
        assertEquals("Loading...", diff.textChanges[0].first)
        assertEquals("Results Ready", diff.textChanges[0].second)
        assertTrue(diff.hasMeaningfulChange)
    }

    // ==========================================
    // 4. Loop Guard (R17, R34)
    // ==========================================
    @Test
    fun testLoopGuardDetectsRepetition() {
        val guard = LoopGuard(
            budget = ActionBudget(maxActions = 100, maxRepeatedActions = 3)
        )
        guard.startMission()

        // 1st action
        val r1 = guard.checkPreExecution(AutonomousActionType.TAP, "Search", null)
        assertTrue(r1 is LoopCheckResult.Allowed)

        // 2nd identical action without screen change
        val r2 = guard.checkPreExecution(AutonomousActionType.TAP, "Search", null)
        assertTrue(r2 is LoopCheckResult.Allowed)

        // 3rd identical action
        val r3 = guard.checkPreExecution(AutonomousActionType.TAP, "Search", null)
        assertTrue(r3 is LoopCheckResult.Allowed)

        // 4th identical action: must trigger loop detected!
        val r4 = guard.checkPreExecution(AutonomousActionType.TAP, "Search", null)
        assertTrue("Expected LoopDetected after 3 consecutive identical actions", r4 is LoopCheckResult.LoopDetected)
    }

    @Test
    fun testLoopGuardBudgetExceeded() {
        val guard = LoopGuard(
            budget = ActionBudget(maxActions = 3)
        )
        guard.startMission()

        assertTrue(guard.checkPreExecution(AutonomousActionType.TAP, "A", null) is LoopCheckResult.Allowed)
        assertTrue(guard.checkPreExecution(AutonomousActionType.TAP, "B", null) is LoopCheckResult.Allowed)
        assertTrue(guard.checkPreExecution(AutonomousActionType.TAP, "C", null) is LoopCheckResult.Allowed)

        // 4th action exceeds budget of 3
        val r4 = guard.checkPreExecution(AutonomousActionType.TAP, "D", null)
        assertTrue("Expected ActionBudgetExceeded", r4 is LoopCheckResult.ActionBudgetExceeded)
    }

    // ==========================================
    // 5. Safety Gate (R33)
    // ==========================================
    @Test
    fun testSafetyGateClassification() {
        val gate = SafetyGate(isEnabled = true)

        // Safe actions
        val homeStep = ActionStep(1, AutonomousActionType.HOME)
        assertEquals(SafetyLevel.SAFE, gate.classifySafety(homeStep))

        val scrollStep = ActionStep(2, AutonomousActionType.SCROLL_DOWN)
        assertEquals(SafetyLevel.SAFE, gate.classifySafety(scrollStep))

        // Destructive action: deleting
        val deleteStep = ActionStep(
            id = 3,
            action = AutonomousActionType.TAP,
            target = ActionTarget(TargetType.TEXT, "Delete photo")
        )
        assertEquals(SafetyLevel.DESTRUCTIVE, gate.classifySafety(deleteStep))

        // Destructive action: purchasing
        val payStep = ActionStep(
            id = 4,
            action = AutonomousActionType.TAP,
            target = ActionTarget(TargetType.TEXT, "Confirm purchase")
        )
        assertEquals(SafetyLevel.DESTRUCTIVE, gate.classifySafety(payStep))

        // Sensitive action: granting permission
        val permStep = ActionStep(
            id = 5,
            action = AutonomousActionType.TAP,
            target = ActionTarget(TargetType.TEXT, "Allow all permissions")
        )
        assertEquals(SafetyLevel.SENSITIVE, gate.classifySafety(permStep))
    }

    @Test
    fun testSafetyGateBlocksDestructiveActionWithoutApproval() {
        val gate = SafetyGate(isEnabled = true)
        val deleteStep = ActionStep(
            id = 1,
            action = AutonomousActionType.TAP,
            target = ActionTarget(TargetType.TEXT, "Erase all data")
        )

        var decisionReceived: Boolean? = null
        val allowedImmediately = gate.checkSafety(deleteStep) { decision ->
            decisionReceived = decision
        }

        // Without confirmation listener, destructive action must not proceed immediately
        assertFalse(allowedImmediately)
        assertEquals(false, decisionReceived)
    }

    // ==========================================
    // 6. Mission Memory (R13)
    // ==========================================
    @Test
    fun testMissionMemoryRecentActions() {
        val memory = MissionMemory()

        val step1 = ActionStep(1, AutonomousActionType.OPEN_APP, ActionTarget(TargetType.TEXT, "com.android.vending"))
        val res1 = ActionResult(success = true, action = "OPEN_APP", target = "com.android.vending", reason = "", verified = true)
        memory.recordExecution(step1, res1)

        val step2 = ActionStep(2, AutonomousActionType.TAP, ActionTarget(TargetType.TEXT, "Search"))
        val res2 = ActionResult(success = false, action = "TAP", target = "Search", reason = "TARGET_NOT_FOUND", verified = false)
        memory.recordExecution(step2, res2)

        assertTrue(memory.hasFailedRecently(AutonomousActionType.TAP, "Search"))
        assertFalse(memory.hasFailedRecently(AutonomousActionType.OPEN_APP, "com.android.vending"))
        assertEquals(2, memory.actionHistory.size)
    }

    // ==========================================
    // 7. Information Tools (R30)
    // ==========================================
    @Test
    fun testGetCurrentTimeToolReturnsRealData() {
        val timeString = InformationTools.getCurrentTime()
        assertNotNull(timeString)
        assertTrue(timeString.isNotBlank())
        // Should contain standard components like day/year/time
        assertTrue(timeString.contains("202"))
    }

    // ==========================================
    // 8. Dialog Handler (R24)
    // ==========================================
    @Test
    fun testDialogHandlerDetection() {
        val harmlessNodes = listOf(
            ScreenNode(id = "txt_prompt", left = 50f, top = 50f, right = 400f, bottom = 100f, text = "Rate this app?"),
            ScreenNode(id = "btn_cancel", left = 50f, top = 120f, right = 200f, bottom = 180f, text = "Not now", isClickable = true)
        )

        assertTrue(DialogHandler.isDialogPresent(harmlessNodes))

        val normalNodes = listOf(
            ScreenNode(id = "item_chat", left = 0f, top = 100f, right = 1080f, bottom = 200f, text = "Messages", isClickable = true)
        )
        assertFalse(DialogHandler.isDialogPresent(normalNodes))
    }

    // ==========================================
    // 9. Compound Goal Decomposition (R1)
    // ==========================================
    @Test
    fun testCompoundGoalDecompositionWithPunctuation() {
        val gm = GoalManager()

        // Test with full stop / period separator
        gm.initializeGoal("YouTube open karo. Shorts ke upar click karo")
        assertEquals(2, gm.allObjectives.size)
        assertEquals("YouTube open karo", gm.allObjectives[0].title)
        assertEquals("Shorts ke upar click karo", gm.allObjectives[1].title)

        // Test with Hindi conjunction
        val gm2 = GoalManager()
        gm2.initializeGoal("Settings mein jao and flashlight ka option dhoondho")
        assertEquals(2, gm2.allObjectives.size)
        assertEquals("Settings mein jao", gm2.allObjectives[0].title)
        assertEquals("Flashlight ka option dhoondho", gm2.allObjectives[1].title)
    }

    // ==========================================
    // 10. Dynamic Planner Shorts & Flashlight (R3)
    // ==========================================
    @Test
    fun testDynamicPlannerShortsAndFlashlight() = kotlinx.coroutines.runBlocking {
        val planner = DynamicPlanner(
            toolRegistry = ToolRegistry(ToolCapabilityManager()),
            jarvisBrain = null,
            appSettings = null
        )

        // Case 1: When screen is launcher and goal is Shorts
        val launcherState = ScreenState(packageName = "com.android.launcher")
        val objShorts = MissionObjective(id = "1", title = "Shorts ke upar click karo")
        val stepsShorts = planner.createPlanForObjective(objShorts, launcherState, "")

        assertTrue(stepsShorts.isNotEmpty())
        assertEquals(AutonomousActionType.OPEN_APP, stepsShorts[0].action)
        assertEquals("com.google.android.youtube", stepsShorts[0].target?.value)
        assertEquals(AutonomousActionType.TAP, stepsShorts[1].action)
        assertEquals("Shorts", stepsShorts[1].target?.value)

        // Case 2: When screen is already YouTube and goal is Shorts
        val ytState = ScreenState(packageName = "com.google.android.youtube")
        val stepsShortsInYt = planner.createPlanForObjective(objShorts, ytState, "")
        assertEquals(1, stepsShortsInYt.size)
        assertEquals(AutonomousActionType.TAP, stepsShortsInYt[0].action)
        assertEquals("Shorts", stepsShortsInYt[0].target?.value)

        // Case 3: Flashlight in settings
        val settingsState = ScreenState(
            packageName = "com.android.settings",
            nodes = listOf(ScreenNode(id = "torch_btn", left = 0f, top = 0f, right = 100f, bottom = 100f, text = "Flashlight", isClickable = true))
        )
        val objFlashlight = MissionObjective(id = "2", title = "Flashlight ka option dhoondho")
        val stepsFlashlight = planner.createPlanForObjective(objFlashlight, settingsState, "")
        assertTrue(stepsFlashlight.isNotEmpty())
        assertEquals(AutonomousActionType.TAP, stepsFlashlight[0].action)
        assertEquals("Flashlight", stepsFlashlight[0].target?.value)
    }
}
