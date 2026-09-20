package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import com.assistive.headmouse.agent.jarvis.action.AutonomousActionType

/**
 * Result of a loop guard check.
 */
sealed class LoopCheckResult {
    object Allowed : LoopCheckResult()
    data class LoopDetected(val action: AutonomousActionType, val target: String?, val repeatCount: Int) : LoopCheckResult()
    data class ActionBudgetExceeded(val actionCount: Int, val maxAllowed: Int) : LoopCheckResult()
    data class Timeout(val elapsedMs: Long, val maxMs: Long) : LoopCheckResult()
}

/**
 * Loop Guard and Action Watchdog (R17, R34).
 * Enforces hard operational boundaries, limits total actions, and detects
 * infinite action loops (e.g. repeated clicks with zero screen delta).
 */
class LoopGuard(
    val budget: ActionBudget = ActionBudget()
) {
    private val lock = Any()

    private var missionStartTime: Long = 0L
    private var actionCount: Int = 0
    private var plannerIterations: Int = 0

    private var lastAction: AutonomousActionType? = null
    private var lastTarget: String? = null
    private var consecutiveIdenticalCount: Int = 0

    companion object {
        private const val TAG = "LoopGuard"
    }

    fun startMission() {
        synchronized(lock) {
            missionStartTime = System.currentTimeMillis()
            actionCount = 0
            plannerIterations = 0
            lastAction = null
            lastTarget = null
            consecutiveIdenticalCount = 0
            Log.i(TAG, "LoopGuard armed. MaxActions: ${budget.maxActions}, MaxRepeated: ${budget.maxRepeatedActions}")
        }
    }

    /**
     * Checks whether an action can be performed safely before execution.
     */
    fun checkPreExecution(action: AutonomousActionType, targetValue: String?, latestDiff: ScreenDiff?): LoopCheckResult {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val elapsed = now - missionStartTime

            // 1. Mission Duration Timeout
            if (missionStartTime > 0 && elapsed > budget.maxMissionDurationMs) {
                Log.w(TAG, "Mission timeout exceeded ($elapsed ms > ${budget.maxMissionDurationMs} ms)")
                return LoopCheckResult.Timeout(elapsed, budget.maxMissionDurationMs)
            }

            // 2. Max Actions Budget
            if (actionCount >= budget.maxActions) {
                Log.w(TAG, "Action budget exceeded: $actionCount >= ${budget.maxActions}")
                return LoopCheckResult.ActionBudgetExceeded(actionCount, budget.maxActions)
            }

            // 3. Repeated Action Loop Detector
            val isIdentical = (action == lastAction) && (targetValue == lastTarget)
            val hadScreenChange = latestDiff?.hasMeaningfulChange == true

            if (isIdentical && !hadScreenChange) {
                consecutiveIdenticalCount++
                Log.w(TAG, "Repeated identical action detected: $action on '$targetValue' ($consecutiveIdenticalCount/${budget.maxRepeatedActions} with no screen delta)")
                if (consecutiveIdenticalCount > budget.maxRepeatedActions) {
                    return LoopCheckResult.LoopDetected(action, targetValue, consecutiveIdenticalCount)
                }
            } else {
                consecutiveIdenticalCount = 1
            }

            lastAction = action
            lastTarget = targetValue
            actionCount++

            return LoopCheckResult.Allowed
        }
    }

    fun recordPlannerIteration(): Boolean = synchronized(lock) {
        plannerIterations++
        plannerIterations <= budget.maxPlannerIterations
    }

    fun getActionCount(): Int = synchronized(lock) { actionCount }

    fun reset() {
        synchronized(lock) {
            missionStartTime = 0L
            actionCount = 0
            plannerIterations = 0
            lastAction = null
            lastTarget = null
            consecutiveIdenticalCount = 0
        }
    }
}
