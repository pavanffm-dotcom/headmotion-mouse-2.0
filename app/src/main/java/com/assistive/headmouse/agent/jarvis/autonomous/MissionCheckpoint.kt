package com.assistive.headmouse.agent.jarvis.autonomous

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Checkpoint and Resume Engine (R15).
 * Serializes active mission progress to internal app storage so execution
 * can safely recover from unexpected process stops, crashes, or timeouts.
 */
data class MissionCheckpoint(
    val taskId: String,
    val userGoal: String,
    val currentObjectiveIndex: Int,
    val completedObjectiveTitles: List<String>,
    val currentPackage: String,
    val retryCount: Int,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("taskId", taskId)
        put("userGoal", userGoal)
        put("currentObjectiveIndex", currentObjectiveIndex)
        put("completedObjectiveTitles", JSONArray(completedObjectiveTitles))
        put("currentPackage", currentPackage)
        put("retryCount", retryCount)
        put("timestampMs", timestampMs)
    }

    companion object {
        private const val TAG = "MissionCheckpoint"
        private const val FILE_NAME = "jarvis_mission_checkpoint.json"

        fun fromJson(json: JSONObject): MissionCheckpoint {
            val completedList = mutableListOf<String>()
            val arr = json.optJSONArray("completedObjectiveTitles")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    completedList.add(arr.optString(i))
                }
            }

            return MissionCheckpoint(
                taskId = json.optString("taskId", ""),
                userGoal = json.optString("userGoal", ""),
                currentObjectiveIndex = json.optInt("currentObjectiveIndex", 0),
                completedObjectiveTitles = completedList,
                currentPackage = json.optString("currentPackage", ""),
                retryCount = json.optInt("retryCount", 0),
                timestampMs = json.optLong("timestampMs", System.currentTimeMillis())
            )
        }

        fun save(context: Context, checkpoint: MissionCheckpoint) {
            try {
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(checkpoint.toJson().toString())
                Log.d(TAG, "Saved checkpoint for task ${checkpoint.taskId}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save mission checkpoint: ", e)
            }
        }

        fun load(context: Context): MissionCheckpoint? {
            return try {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return null
                val text = file.readText()
                if (text.isBlank()) return null
                fromJson(JSONObject(text))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load mission checkpoint: ", e)
                null
            }
        }

        fun clear(context: Context) {
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists()) file.delete()
            } catch (_: Exception) {}
        }
    }
}
