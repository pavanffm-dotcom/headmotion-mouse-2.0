package com.assistive.headmouse.agent.jarvis.autonomous

import com.assistive.headmouse.agent.jarvis.action.ActionResult
import com.assistive.headmouse.agent.jarvis.action.ActionStep
import com.assistive.headmouse.agent.jarvis.action.AutonomousActionType

/**
 * Short-term, mission-scoped agent memory (R13 & Phase 16 Memory 2.0).
 * Tracks attempted actions, results, recorded failures, and strategies
 * within the lifecycle of a single mission to prevent repetitive mistakes.
 * Summarizes the mission for archival into TaskHistoryManager upon completion.
 */
class MissionMemory {

    private val lock = Any()

    private val executedActions = mutableListOf<ActionStep>()
    private val actionResults = mutableListOf<ActionResult>()
    private val failureRecords = mutableListOf<Pair<ActionStep, String>>()
    private val discoveredElements = mutableSetOf<String>()
    private val recordedStrategies = mutableListOf<String>()
    private val recordedPackages = mutableSetOf<String>()

    /**
     * Records an executed step and its verified result.
     */
    fun recordExecution(step: ActionStep, result: ActionResult) {
        synchronized(lock) {
            executedActions.add(step)
            actionResults.add(result)

            step.target?.value?.let { discoveredElements.add(it) }

            // Track packages involved
            if (step.action == AutonomousActionType.OPEN_APP) {
                step.target?.value?.let { recordedPackages.add(it) }
            }

            if (!result.success || !result.verified) {
                failureRecords.add(Pair(step, result.reason))
            }
        }
    }

    /**
     * Records a package name encountered or interacted with during the mission.
     */
    fun recordPackage(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            recordedPackages.add(packageName)
        }
    }

    /**
     * Checks if an action on this target failed recently.
     */
    fun hasFailedRecently(action: AutonomousActionType, targetValue: String?): Boolean = synchronized(lock) {
        failureRecords.any { (failedStep, _) ->
            failedStep.action == action &&
            (targetValue == null || failedStep.target?.value?.equals(targetValue, ignoreCase = true) == true)
        }
    }

    /**
     * Records a strategic approach attempted by the planner or recovery engine.
     */
    fun recordStrategy(strategyDescription: String) {
        synchronized(lock) {
            recordedStrategies.add(strategyDescription)
        }
    }

    /**
     * Builds a condensed summary of past steps for inclusion in the AI reasoning prompt.
     */
    fun getPromptSummary(maxPastActions: Int = 4): String = synchronized(lock) {
        if (executedActions.isEmpty()) return "Past Actions: None yet."

        val sb = StringBuilder()
        sb.append("Recent Mission History:\n")
        val recentSteps = executedActions.takeLast(maxPastActions)
        val recentResults = actionResults.takeLast(maxPastActions)

        for (i in recentSteps.indices) {
            val step = recentSteps[i]
            val res = recentResults.getOrNull(i)
            val status = if (res?.success == true && res.verified) "SUCCESS" else "FAILED (${res?.reason ?: "unverified"})"
            sb.append("- Step: ${step.action}${step.target?.let { " on '${it.value}'" } ?: ""} -> $status\n")
        }

        if (failureRecords.isNotEmpty()) {
            val lastFail = failureRecords.last()
            sb.append("Note: Last failure was ${lastFail.first.action} on '${lastFail.first.target?.value}' reason: '${lastFail.second}'. Avoid repeating this failure.\n")
        }

        return sb.toString().trim()
    }

    /**
     * Generates a high-signal, concise mission summary suitable for persistent Task History.
     */
    fun generateMissionSummary(userGoal: String): String = synchronized(lock) {
        if (executedActions.isEmpty()) return "Executed goal: \"$userGoal\" (no actions dispatched)."

        val actionsSummary = executedActions.mapIndexed { idx, step ->
            val res = actionResults.getOrNull(idx)
            val targetStr = step.target?.value?.let { " '$it'" } ?: ""
            val statusStr = if (res?.success == true) "OK" else "FAIL"
            "${step.action}$targetStr ($statusStr)"
        }.joinToString(" -> ")

        return "Goal: \"$userGoal\". Steps (${executedActions.size}): $actionsSummary."
    }

    fun getInvolvedPackages(): List<String> = synchronized(lock) {
        recordedPackages.toList()
    }

    fun clear() {
        synchronized(lock) {
            executedActions.clear()
            actionResults.clear()
            failureRecords.clear()
            discoveredElements.clear()
            recordedStrategies.clear()
            recordedPackages.clear()
        }
    }

    val actionHistory: List<ActionStep>
        get() = synchronized(lock) { executedActions.toList() }

    val resultsHistory: List<ActionResult>
        get() = synchronized(lock) { actionResults.toList() }
}
