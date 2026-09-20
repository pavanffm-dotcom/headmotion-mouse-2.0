package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import java.util.UUID

/**
 * Goal Manager (R9).
 * Maintains high-level user goal, breaks it down into sequential objectives,
 * tracks pending/completed/failed objectives, and establishes overall completion.
 */
class GoalManager {

    private val lock = Any()

    var userGoal: String = ""
        private set

    var missionStatus: MissionStatus = MissionStatus.IDLE
        private set

    private val objectives = mutableListOf<MissionObjective>()
    private var activeObjectiveIndex: Int = -1

    companion object {
        private const val TAG = "GoalManager"
    }

    /**
     * Initializes a new mission goal and performs initial objective breakdown.
     */
    fun initializeGoal(goal: String, decomposedObjectives: List<MissionObjective>? = null) {
        synchronized(lock) {
            userGoal = goal.trim()
            objectives.clear()
            missionStatus = MissionStatus.PLANNING

            if (!decomposedObjectives.isNullOrEmpty()) {
                objectives.addAll(decomposedObjectives)
            } else {
                // Rule-based compound goal decomposition fallback
                val subGoals = decomposeGoalString(userGoal)
                for ((idx, title) in subGoals.withIndex()) {
                    objectives.add(
                        MissionObjective(
                            id = "obj_${idx + 1}_${UUID.randomUUID().toString().take(6)}",
                            title = title,
                            status = if (idx == 0) MissionStatus.PLANNING else MissionStatus.WAITING
                        )
                    )
                }
            }

            activeObjectiveIndex = if (objectives.isNotEmpty()) 0 else -1
            Log.i(TAG, "Initialized goal: \"$userGoal\" with ${objectives.size} objectives.")
        }
    }

    val allObjectives: List<MissionObjective>
        get() = synchronized(lock) { objectives.toList() }

    fun currentObjective(): MissionObjective? = synchronized(lock) {
        if (activeObjectiveIndex in objectives.indices) objectives[activeObjectiveIndex] else null
    }

    fun completedObjectives(): List<MissionObjective> = synchronized(lock) {
        objectives.filter { it.status == MissionStatus.COMPLETED }
    }

    fun pendingObjectives(): List<MissionObjective> = synchronized(lock) {
        objectives.filter { it.status == MissionStatus.WAITING || it.status == MissionStatus.PLANNING }
    }

    fun failedObjectives(): List<MissionObjective> = synchronized(lock) {
        objectives.filter { it.status == MissionStatus.FAILED }
    }

    val totalSteps: Int
        get() = synchronized(lock) { objectives.sumOf { it.steps.size } }

    val currentStep: Int
        get() = synchronized(lock) {
            val completed = objectives.take(activeObjectiveIndex.coerceAtLeast(0)).sumOf { it.steps.size }
            val inCurrent = currentObjective()?.currentStepIndex ?: 0
            completed + inCurrent + 1
        }

    fun markCurrentObjectiveComplete() {
        synchronized(lock) {
            val curr = currentObjective()
            if (curr != null) {
                curr.status = MissionStatus.COMPLETED
                Log.i(TAG, "Objective completed: \"${curr.title}\" (${completedObjectives().size}/${objectives.size})")
            }
        }
    }

    fun advanceToNextObjective(): MissionObjective? {
        synchronized(lock) {
            activeObjectiveIndex++
            val next = currentObjective()
            if (next != null) {
                next.status = MissionStatus.PLANNING
                Log.i(TAG, "Advanced to objective ${activeObjectiveIndex + 1}/${objectives.size}: \"${next.title}\"")
                return next
            }
            return null
        }
    }

    fun markCurrentObjectiveFailed(reason: String) {
        synchronized(lock) {
            val curr = currentObjective()
            if (curr != null) {
                curr.status = MissionStatus.FAILED
                curr.failedAttempts++
                Log.w(TAG, "Objective failed: \"${curr.title}\" (Reason: $reason)")
            }
        }
    }

    fun setStatus(status: MissionStatus) {
        synchronized(lock) {
            missionStatus = status
            currentObjective()?.status = status
        }
    }

    fun isAllComplete(): Boolean = synchronized(lock) {
        objectives.isNotEmpty() && objectives.all { it.status == MissionStatus.COMPLETED }
    }

    fun reset() {
        synchronized(lock) {
            userGoal = ""
            missionStatus = MissionStatus.IDLE
            objectives.clear()
            activeObjectiveIndex = -1
        }
    }

    private fun decomposeGoalString(goal: String): List<String> {
        val clauseList = mutableListOf<String>()
        val sentenceDelims = Regex("[.;\n]+")
        val rawSentences = goal.split(sentenceDelims).map { it.trim() }.filter { it.isNotBlank() }

        val splitWords = listOf(
            " -> ", "->", " and then ", " then ", " and ", 
            " aur fir ", " aur phir ", " aur ", 
            " phir ", " fir ", " ke baad ", " uske baad ",
            " karke ", " to ", " so "
        )

        for (sent in rawSentences) {
            var parts = listOf(sent)
            for (delimiter in splitWords) {
                val nextParts = mutableListOf<String>()
                for (p in parts) {
                    nextParts.addAll(p.split(delimiter, ignoreCase = true))
                }
                parts = nextParts
            }
            clauseList.addAll(parts)
        }

        val cleanParts = clauseList.map {
            it.trim().replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(java.util.Locale.ROOT) else c.toString() }
        }.filter { it.isNotBlank() }

        return if (cleanParts.isNotEmpty()) cleanParts else listOf(goal.trim().replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(java.util.Locale.ROOT) else c.toString() })
    }
}
