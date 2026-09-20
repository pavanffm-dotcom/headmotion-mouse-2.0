package com.assistive.headmouse.agent.jarvis.autonomous

import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelDecisionRequest
import com.assistive.headmouse.agent.jarvis.autonomous.model.StructuredModelResult
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ActionResult
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationResult

/**
 * Phase 17 Mission Step Record.
 *
 * Implements the strict multi-field logging requirement:
 * GOAL, OBSERVATION, WORLD STATE, MODEL REQUEST, MODEL DECISION,
 * TOOL CALL, ACTION, WAIT, VERIFICATION, NEXT OBSERVATION,
 * REPLANNING, FINAL RESULT, FAILURE REASON, TOKEN USAGE, TIME.
 */
data class MissionStepRecord(
    val stepIndex: Int,
    val goal: String,
    val observation: String,
    val worldState: WorldState,
    val modelRequest: ModelDecisionRequest?,
    val modelDecision: StructuredModelResult?,
    val toolCall: ToolCall,
    val action: String,
    val waitDurationMs: Long,
    val verification: VerificationResult,
    val nextObservation: WorldState,
    val replanning: String? = null,
    val finalResult: String? = null,
    val failureReason: String? = null,
    val tokenUsage: Int = 0,
    val timeMs: Long = 0L
) {
    fun toFormattedLog(): String {
        return buildString {
            appendLine("=== MISSION STEP #$stepIndex ===")
            appendLine("GOAL: $goal")
            appendLine("OBSERVATION: $observation")
            appendLine("WORLD STATE: Package=${worldState.foregroundPackage}, Activity=${worldState.foregroundActivity}, Nodes=${worldState.nodes.size}, ScreenHash=${worldState.screenHash}")
            appendLine("MODEL REQUEST: Subgoal='${modelRequest?.currentSubgoal}', SystemPromptChars=${modelRequest?.systemInstruction?.length ?: 0}, ScreenIndexChars=${modelRequest?.compressedScreenIndex?.length ?: 0}")
            appendLine("MODEL DECISION: ${modelDecision?.decision ?: "HeuristicFallback"} | Reason=${modelDecision?.reason}")
            appendLine("TOOL CALL: ${toolCall.name} args=${toolCall.arguments}")
            appendLine("ACTION: $action")
            appendLine("WAIT: ${waitDurationMs}ms (settled)")
            appendLine("VERIFICATION: State=${verification.state} [verified=${verification.verified}] Explanation=${verification.explanation}")
            appendLine("NEXT OBSERVATION: Package=${nextObservation.foregroundPackage}, Nodes=${nextObservation.nodes.size}, ScreenHash=${nextObservation.screenHash}")
            appendLine("REPLANNING: ${replanning ?: "None (Progressing as planned)"}")
            appendLine("FINAL RESULT: ${finalResult ?: "In-Progress"}")
            appendLine("FAILURE REASON: ${failureReason ?: "None"}")
            appendLine("TOKEN USAGE: $tokenUsage")
            appendLine("TIME: ${timeMs}ms")
            appendLine("================================")
        }
    }
}

/**
 * Encapsulates the complete validated execution trail for a multi-step mission.
 */
data class MissionExecutionTrail(
    val missionGoal: String,
    val level: Int,
    val steps: MutableList<MissionStepRecord> = mutableListOf(),
    var isSuccessful: Boolean = false,
    var finalStateVerified: Boolean = false,
    var terminationReason: String = "",
    var totalDurationMs: Long = 0L,
    var totalTokenUsage: Int = 0
) {
    fun addStep(record: MissionStepRecord) {
        steps.add(record)
        totalTokenUsage += record.tokenUsage
        totalDurationMs += record.timeMs
    }

    fun generateReport(): String {
        return buildString {
            appendLine("#################################################################")
            appendLine("# LEVEL $level MISSION REPORT: $missionGoal")
            appendLine("# FINAL SUCCESS: $isSuccessful | FINAL STATE VERIFIED: $finalStateVerified")
            appendLine("# TOTAL STEPS: ${steps.size} | TOTAL TOKENS: $totalTokenUsage | DURATION: ${totalDurationMs}ms")
            appendLine("# TERMINATION: $terminationReason")
            appendLine("#################################################################")
            steps.forEach { step ->
                appendLine(step.toFormattedLog())
            }
        }
    }
}
