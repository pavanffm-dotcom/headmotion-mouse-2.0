package com.assistive.headmouse.agent.jarvis.autonomous.state

import com.assistive.headmouse.agent.jarvis.autonomous.tools.ActionResult
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import java.util.UUID

/**
 * High-level lifecycle status of an autonomous mission.
 */
enum class AutonomousMissionStatus {
    IDLE,
    INITIALIZING,
    OBSERVING,
    THINKING,
    EXECUTING,
    WAITING_SETTLING,
    VERIFYING,
    REPLANNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Represents a verifiable sub-goal in a compound mission.
 */
data class Subgoal(
    val id: String = UUID.randomUUID().toString().take(8),
    val description: String,
    val targetPackage: String? = null,
    val expectedOutcome: String? = null,
    var isCompleted: Boolean = false,
    var failureReason: String? = null
)

/**
 * The single authoritative source of truth for an active autonomous mission.
 * 
 * CORE INVARIANT:
 * [originalUserGoal] is strictly IMMUTABLE. It is set once upon initialization
 * and can NEVER be altered, truncated, or overwritten by intermediate steps or subgoals.
 */
data class MissionState(
    // 1. Mission Identifiers & Immutable Goal
    val missionId: String = "mission_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
    val originalUserGoal: String, // IMMUTABLE: Never altered after creation

    // 2. Goal Decomposition & Objectives
    var currentSubgoal: Subgoal? = null,
    val completedObjectives: MutableList<Subgoal> = mutableListOf(),
    val remainingObjectives: MutableList<Subgoal> = mutableListOf(),

    // 3. Perception State
    var currentObservation: WorldState? = null,
    var previousObservation: WorldState? = null,

    // 4. Action & Execution Tracking
    var lastAction: ToolCall? = null,
    var lastActionResult: ActionResult? = null,
    var expectedPostcondition: String? = null,
    var verifiedState: Boolean = false,

    // 5. Watchdog & Reliability Metrics
    var failureCount: Int = 0,
    var consecutiveFailedActions: Int = 0,
    var modelDecisionCount: Int = 0,
    val missionStartTime: Long = System.currentTimeMillis(),
    var lastActionTimestamp: Long = 0L,

    // 6. Overall Status
    var status: AutonomousMissionStatus = AutonomousMissionStatus.IDLE,
    var statusMessage: String = "Initialized"
) {
    /**
     * Total elapsed mission duration in milliseconds.
     */
    val elapsedDurationMs: Long
        get() = System.currentTimeMillis() - missionStartTime

    /**
     * Checks whether the mission duration or iteration budgets have been exceeded.
     */
    fun isBudgetExceeded(maxDurationMs: Long = 180_000L, maxModelDecisions: Int = 25): Boolean {
        return elapsedDurationMs > maxDurationMs || modelDecisionCount >= maxModelDecisions
    }

    /**
     * Safely advances from the current completed subgoal to the next pending subgoal.
     */
    fun advanceSubgoal() {
        currentSubgoal?.let { completed ->
            completed.isCompleted = true
            completedObjectives.add(completed)
        }
        currentSubgoal = if (remainingObjectives.isNotEmpty()) {
            remainingObjectives.removeAt(0)
        } else {
            null
        }
    }

    /**
     * Records a failed action and updates failure metrics.
     */
    fun recordActionFailure(reason: String) {
        failureCount++
        consecutiveFailedActions++
        statusMessage = "Action failed: $reason"
    }

    /**
     * Records a successful action and resets consecutive failure counters.
     */
    fun recordActionSuccess() {
        consecutiveFailedActions = 0
        lastActionTimestamp = System.currentTimeMillis()
    }

    /**
     * Checks if unrecoverable failure limit has been reached.
     */
    fun isUnrecoverable(maxConsecutiveFailures: Int = 3): Boolean {
        return consecutiveFailedActions >= maxConsecutiveFailures
    }
}
