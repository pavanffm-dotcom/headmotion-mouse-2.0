package com.assistive.headmouse.agent.jarvis.autonomous

import com.assistive.headmouse.agent.jarvis.action.ActionStep
import com.assistive.headmouse.agent.jarvis.action.ScreenState
import com.assistive.headmouse.agent.model.ScreenNode

/**
 * High-level lifecycle status of an autonomous mission.
 */
enum class MissionStatus {
    IDLE,
    PLANNING,
    OBSERVING,
    EXECUTING,
    VERIFYING,
    WAITING,
    RECOVERING,
    WAITING_USER_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Granular persistent task state machine transitions (R12).
 */
enum class TaskState {
    MISSION_STARTED,
    PLANNING,
    OBSERVING,
    ACTION_SELECTED,
    ACTION_EXECUTING,
    ACTION_SUCCESS,
    VERIFYING,
    OBJECTIVE_COMPLETED,
    NEXT_OBJECTIVE,
    ACTION_FAILED,
    RECOVERY,
    REOBSERVE,
    REPLAN,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * A discrete objective within a user mission (R9).
 */
data class MissionObjective(
    val id: String,
    val title: String,
    val description: String = "",
    val expectedOutcome: String = "",
    var status: MissionStatus = MissionStatus.WAITING,
    val steps: MutableList<ActionStep> = mutableListOf(),
    var currentStepIndex: Int = 0,
    var failedAttempts: Int = 0
) {
    val isComplete: Boolean get() = status == MissionStatus.COMPLETED
}

/**
 * Hard operational boundaries and loop guards (R17, R34).
 */
data class ActionBudget(
    val maxActions: Int = 100,
    val maxRetriesPerAction: Int = 2,
    val maxRepeatedActions: Int = 3,
    val maxPlannerIterations: Int = 25,
    val maxMissionDurationMs: Long = 300_000L, // 5 minutes
    val maxModelCalls: Int = 40,
    val maxToolCalls: Int = 150
)

/**
 * Structural differences detected between two consecutive screen states (R19).
 */
data class ScreenDiff(
    val previousPackage: String,
    val currentPackage: String,
    val isPackageChanged: Boolean,
    val isActivityChanged: Boolean,
    val addedNodes: List<ScreenNode>,
    val removedNodes: List<ScreenNode>,
    val textChanges: List<Pair<String, String>>,
    val isFocusChanged: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val hasMeaningfulChange: Boolean
        get() = isPackageChanged || isActivityChanged || addedNodes.isNotEmpty() || removedNodes.isNotEmpty() || textChanges.isNotEmpty() || isFocusChanged
}

/**
 * Safety categorization for human-in-the-loop protection (R33).
 */
enum class SafetyLevel {
    SAFE,            // Navigation, read, query
    REVERSIBLE,      // Ordinary clicks, text typing
    SENSITIVE,       // Permissions, account settings, drafts
    DESTRUCTIVE      // Send message, delete files, purchase, uninstall
}

/**
 * Model roles for multi-model specialization (R29).
 */
enum class ModelRole {
    MAIN_AGENT,
    VISION_MODEL,
    PLANNER_MODEL,
    FAST_MODEL,
    FALLBACK_MODEL
}

/**
 * Structured search result item for real-time web search (R30, R31).
 */
data class WebSearchResult(
    val query: String = "",
    val title: String,
    val snippet: String,
    val url: String,
    val source: String = "Web"
)
