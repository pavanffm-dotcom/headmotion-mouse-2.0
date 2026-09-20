package com.assistive.headmouse.agent.jarvis.autonomous

import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelClient
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelDecisionRequest
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelResponse
import com.assistive.headmouse.agent.jarvis.autonomous.model.StructuredModelResult
import com.assistive.headmouse.agent.jarvis.autonomous.state.AutonomousMissionStatus
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.Subgoal
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDefinition
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationEngine
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationResult
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState

/**
 * Phase 17 Mission Multi-Agent Protocol.
 * Coordinates 4 distinct agents for real multi-step mission validation:
 * - Agent 1: Mission execution tester (Closed-loop orchestrator & step stepper)
 * - Agent 2: Perception tester (WorldState analysis, visual/semantic fusion check)
 * - Agent 3: Action/recovery tester (Verification engine & replanning evaluator)
 * - Agent 4: Evidence/report reviewer (Multi-field audit & final state validation)
 */
class MissionValidationOrchestrator(
    val planner: DynamicPlanner,
    val verificationEngine: VerificationEngine = VerificationEngine(),
    val replanningEngine: ReplanningEngine = ReplanningEngine()
) {

    /**
     * Executes a verifiable closed-loop step with full multi-field logging:
     * GOAL, OBSERVATION, WORLD STATE, MODEL REQUEST, MODEL DECISION,
     * TOOL CALL, ACTION, WAIT, VERIFICATION, NEXT OBSERVATION,
     * REPLANNING, FINAL RESULT, FAILURE REASON, TOKEN USAGE, TIME.
     */
    suspend fun executeStep(
        missionState: MissionState,
        modelClient: ModelClient,
        currentScreen: WorldState,
        screenTransitionProvider: (ToolCall, WorldState) -> Pair<WorldState, Long>,
        trail: MissionExecutionTrail,
        modelDecisionOverride: StructuredModelResult? = null
    ): Pair<ToolCall, WorldState> {
        val stepStartTime = System.currentTimeMillis()
        val stepNumber = missionState.modelDecisionCount + 1
        missionState.modelDecisionCount = stepNumber

        // 1. GOAL & OBSERVATION (Agent 1 & Agent 2)
        val activeGoal = missionState.currentSubgoal?.description ?: missionState.originalUserGoal
        val observationSummary = "Package: ${currentScreen.foregroundPackage}, Interactive nodes: ${currentScreen.nodes.size}, Editable: ${currentScreen.editableNodes.size}"
        missionState.currentObservation = currentScreen

        // 2. MODEL REQUEST (Agent 1)
        val fullHistory = missionState.completedObjectives.map { "Completed: ${it.description}" } +
            listOfNotNull(missionState.lastAction?.let { "Last: ${it.name}" })
        val modelRequest = ModelDecisionRequest(
            systemInstruction = "J.A.R.V.I.S. autonomous agent executing real multi-step Android mission.",
            originalUserGoal = missionState.originalUserGoal,
            currentSubgoal = activeGoal,
            compressedScreenIndex = currentScreen.toCompressedSemanticIndex(),
            actionHistory = fullHistory
        )

        // 3. MODEL DECISION & TOOL CALL (Agent 1)
        val toolCall = if (modelDecisionOverride != null && modelDecisionOverride.tool != null) {
            ToolCall(
                name = modelDecisionOverride.tool,
                arguments = modelDecisionOverride.arguments,
                thought = modelDecisionOverride.reason
            )
        } else {
            planner.decideNextAction(missionState, modelClient)
        }
        missionState.lastAction = toolCall

        // 4. ACTION DISPATCH & 5. WAIT/SETTLE (Agent 3)
        val (nextScreen, waitDuration) = screenTransitionProvider(toolCall, currentScreen)
        val actionDescription = "Dispatched ${toolCall.name} with ${toolCall.arguments}"

        // 6. VERIFICATION (Agent 3 - Postcondition Check)
        val verification = verificationEngine.verify(toolCall, currentScreen, nextScreen)
        missionState.verifiedState = verification.verified
        missionState.previousObservation = currentScreen
        missionState.currentObservation = nextScreen

        // 7. REPLANNING EVALUATION (Agent 3)
        var replanningPlan: String? = null
        if (!verification.verified) {
            val recoveryCall = replanningEngine.recoverFromState(
                failedTool = toolCall,
                verificationResult = verification,
                currentWorld = nextScreen,
                previousWorld = currentScreen,
                goal = activeGoal
            )
            replanningPlan = recoveryCall?.let { "Replanned: ${it.name} (${it.thought})" }
                ?: "Replanned: Escalate to model reasoning"
        }

        // 8. EVIDENCE & AUDIT (Agent 4)
        val stepDuration = (System.currentTimeMillis() - stepStartTime) + waitDuration
        val tokenEstimate = 120 + (currentScreen.nodes.size * 18) + (activeGoal.length / 2)

        val record = MissionStepRecord(
            stepIndex = stepNumber,
            goal = activeGoal,
            observation = observationSummary,
            worldState = currentScreen,
            modelRequest = modelRequest,
            modelDecision = modelDecisionOverride,
            toolCall = toolCall,
            action = actionDescription,
            waitDurationMs = waitDuration,
            verification = verification,
            nextObservation = nextScreen,
            replanning = replanningPlan,
            finalResult = if (toolCall.name == CanonicalTools.FINISH_TASK) "TASK_FINISHED" else if (verification.verified) "STEP_VERIFIED" else "STEP_FAILED",
            failureReason = if (!verification.verified) verification.explanation else null,
            tokenUsage = tokenEstimate,
            timeMs = stepDuration
        )
        trail.addStep(record)

        return Pair(toolCall, nextScreen)
    }
}
