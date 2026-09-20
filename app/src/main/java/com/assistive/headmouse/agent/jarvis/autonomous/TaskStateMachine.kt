package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log

/**
 * Persistent, thread-safe Task State Machine (R12).
 * Strictly guards state transitions across autonomous missions.
 */
class TaskStateMachine(
    private val onStateTransition: ((TaskState, TaskState, String?) -> Unit)? = null
) {
    private val lock = Any()

    var currentState: TaskState = TaskState.MISSION_STARTED
        private set

    private val transitionHistory = mutableListOf<Pair<Long, TaskState>>()

    companion object {
        private const val TAG = "TaskStateMachine"
    }

    /**
     * Attempts to transition to a target state with an optional context reason.
     */
    fun transitionTo(newState: TaskState, reason: String? = null): Boolean {
        synchronized(lock) {
            val oldState = currentState
            if (oldState == newState) return true

            // Verify valid state transitions
            val isValid = when (oldState) {
                TaskState.MISSION_STARTED -> newState in listOf(TaskState.PLANNING, TaskState.OBSERVING, TaskState.CANCELLED, TaskState.FAILED)
                TaskState.PLANNING -> newState in listOf(TaskState.OBSERVING, TaskState.ACTION_SELECTED, TaskState.FAILED, TaskState.CANCELLED)
                TaskState.OBSERVING -> newState in listOf(TaskState.ACTION_SELECTED, TaskState.PLANNING, TaskState.REPLAN, TaskState.WAITING_CONFIRMATION, TaskState.FAILED, TaskState.CANCELLED)
                TaskState.ACTION_SELECTED -> newState in listOf(TaskState.ACTION_EXECUTING, TaskState.WAITING_CONFIRMATION, TaskState.FAILED, TaskState.CANCELLED)
                TaskState.WAITING_CONFIRMATION -> newState in listOf(TaskState.ACTION_EXECUTING, TaskState.REPLAN, TaskState.CANCELLED, TaskState.FAILED)
                TaskState.ACTION_EXECUTING -> newState in listOf(TaskState.ACTION_SUCCESS, TaskState.ACTION_FAILED, TaskState.VERIFYING, TaskState.CANCELLED)
                TaskState.ACTION_SUCCESS -> newState in listOf(TaskState.VERIFYING, TaskState.OBJECTIVE_COMPLETED, TaskState.OBSERVING, TaskState.COMPLETED)
                TaskState.VERIFYING -> newState in listOf(TaskState.OBJECTIVE_COMPLETED, TaskState.NEXT_OBJECTIVE, TaskState.ACTION_FAILED, TaskState.RECOVERY, TaskState.COMPLETED, TaskState.REPLAN)
                TaskState.OBJECTIVE_COMPLETED -> newState in listOf(TaskState.NEXT_OBJECTIVE, TaskState.COMPLETED, TaskState.PLANNING, TaskState.OBSERVING)
                TaskState.NEXT_OBJECTIVE -> newState in listOf(TaskState.PLANNING, TaskState.OBSERVING, TaskState.ACTION_SELECTED, TaskState.COMPLETED)
                TaskState.ACTION_FAILED -> newState in listOf(TaskState.RECOVERY, TaskState.REOBSERVE, TaskState.REPLAN, TaskState.FAILED, TaskState.CANCELLED)
                TaskState.RECOVERY -> newState in listOf(TaskState.REOBSERVE, TaskState.ACTION_EXECUTING, TaskState.REPLAN, TaskState.FAILED)
                TaskState.REOBSERVE -> newState in listOf(TaskState.REPLAN, TaskState.PLANNING, TaskState.ACTION_SELECTED, TaskState.FAILED)
                TaskState.REPLAN -> newState in listOf(TaskState.PLANNING, TaskState.OBSERVING, TaskState.ACTION_SELECTED, TaskState.FAILED)
                TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED -> {
                    // Terminal states - only allow reset to MISSION_STARTED
                    newState == TaskState.MISSION_STARTED
                }
            }

            if (!isValid) {
                Log.w(TAG, "Illegal state transition attempted: $oldState -> $newState ($reason). Transition blocked.")
                return false
            }

            currentState = newState
            transitionHistory.add(Pair(System.currentTimeMillis(), newState))
            Log.d(TAG, "State transition: $oldState -> $newState ${if (reason != null) "[$reason]" else ""}")
            onStateTransition?.invoke(oldState, newState, reason)
            return true
        }
    }

    val isTerminal: Boolean
        get() = currentState in listOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED)

    val isActive: Boolean
        get() = !isTerminal

    fun getHistory(): List<Pair<Long, TaskState>> = synchronized(lock) { transitionHistory.toList() }

    fun reset() {
        synchronized(lock) {
            currentState = TaskState.MISSION_STARTED
            transitionHistory.clear()
        }
    }
}
