package com.assistive.headmouse.agent.jarvis.memory

import android.content.Context
import com.assistive.headmouse.agent.jarvis.autonomous.MissionMemory

/**
 * Unified Memory Hub for J.A.R.V.I.S. (Phase 16 - Memory 2.0).
 * Orchestrates the 5 decoupled memory systems:
 * 1. Conversation Memory (Multi-turn conversational dialogue with user)
 * 2. Mission Memory (Intra-mission working scratchpad)
 * 3. Task History (Cross-mission persistent outcome & strategy archive)
 * 4. User Preferences (Declared & inferred preferences)
 * 5. Learned App Patterns (Empirically verified UI interaction patterns)
 */
class JarvisMemoryHub(private val context: Context) {

    // 1. Conversation Memory (Preserves existing JarvisMemoryManager)
    val conversationMemory: JarvisMemoryManager = JarvisMemoryManager(context)

    // 2. Task History (Persistent cross-mission archive)
    val taskHistory: TaskHistoryManager = TaskHistoryManager(context)

    // 3. Learned App Patterns (Reusable verified interaction patterns)
    val appPatterns: AppPatternManager = AppPatternManager(context)

    // 4. User Preferences (Long-term user preferences & facts)
    val userPreferences: UserPreferenceMemory = UserPreferenceMemory(context)

    /**
     * Factory for creating a fresh, isolated Mission Memory instance for an autonomous mission.
     */
    fun createMissionMemory(): MissionMemory = MissionMemory()

    /**
     * Archives a completed or failed mission into persistent Task History,
     * and extracts any reusable app interaction patterns if the mission was successful.
     */
    fun archiveMission(
        missionId: String,
        goal: String,
        outcome: MissionOutcome,
        stepsCount: Int,
        durationMs: Long,
        summary: String,
        failureReason: String? = null,
        involvedPackages: List<String> = emptyList()
    ) {
        val record = MissionRecord(
            missionId = missionId,
            timestamp = System.currentTimeMillis(),
            goal = goal,
            outcome = outcome,
            summary = summary,
            stepsCount = stepsCount,
            durationMs = durationMs,
            failureReason = failureReason,
            involvedPackages = involvedPackages
        )
        taskHistory.recordMission(record)
    }

    /**
     * Synthesizes a compact, token-bounded memory context string for the AI planner (< 150 tokens).
     * Selectively injects only relevant task history, learned app pattern, and user preferences.
     */
    fun buildContextualMemoryInjection(
        userGoal: String,
        activePackage: String? = null,
        intentType: String? = null
    ): String {
        val sb = StringBuilder()

        // 1. Relevant Task History (max 2 past missions)
        val relevantMissions = taskHistory.findRelevantMissions(userGoal, activePackage, limit = 2)
        if (relevantMissions.isNotEmpty()) {
            val historySummary = taskHistory.formatContextSummary(relevantMissions)
            if (historySummary.isNotBlank()) {
                sb.append(historySummary).append("\n")
            }
        }

        // 2. Learned App Pattern Hint (if active package has a verified pattern)
        if (!activePackage.isNullOrBlank()) {
            val appHint = appPatterns.formatPromptHint(activePackage, intentType)
            if (!appHint.isNullOrBlank()) {
                sb.append(appHint).append("\n")
            }
        }

        // 3. User Preferences (if relevant)
        val prefSummary = userPreferences.formatPromptSummary()
        if (prefSummary.isNotBlank()) {
            sb.append(prefSummary).append("\n")
        }

        return sb.toString().trim()
    }

    /**
     * Wipes all long-term memory across all 5 systems.
     */
    fun clearAll() {
        conversationMemory.clearMemory()
        taskHistory.clearHistory()
        appPatterns.clearAllPatterns()
        userPreferences.clearPreferences()
    }

    companion object {
        @Volatile
        private var instance: JarvisMemoryHub? = null

        fun getInstance(context: Context): JarvisMemoryHub {
            return instance ?: synchronized(this) {
                instance ?: JarvisMemoryHub(context.applicationContext ?: context).also { instance = it }
            }
        }
    }
}
