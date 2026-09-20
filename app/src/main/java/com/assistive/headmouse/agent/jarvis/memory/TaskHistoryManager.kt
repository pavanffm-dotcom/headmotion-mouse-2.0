package com.assistive.headmouse.agent.jarvis.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Outcome of an autonomous mission.
 */
enum class MissionOutcome {
    SUCCESS,
    FAILED,
    CANCELLED
}

/**
 * Metadata record for a past autonomous mission.
 */
data class MissionRecord(
    val missionId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val goal: String,
    val outcome: MissionOutcome,
    val summary: String,
    val stepsCount: Int = 0,
    val durationMs: Long = 0L,
    val failureReason: String? = null,
    val involvedPackages: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("missionId", missionId)
        put("timestamp", timestamp)
        put("goal", goal)
        put("outcome", outcome.name)
        put("summary", summary)
        put("stepsCount", stepsCount)
        put("durationMs", durationMs)
        if (failureReason != null) put("failureReason", failureReason)
        put("involvedPackages", JSONArray(involvedPackages))
    }

    companion object {
        fun fromJson(json: JSONObject): MissionRecord {
            val pkgs = mutableListOf<String>()
            val pkgArray = json.optJSONArray("involvedPackages")
            if (pkgArray != null) {
                for (i in 0 until pkgArray.length()) {
                    pkgs.add(pkgArray.optString(i))
                }
            }
            val outcomeStr = json.optString("outcome", "FAILED")
            val outcome = try {
                MissionOutcome.valueOf(outcomeStr)
            } catch (_: Exception) {
                MissionOutcome.FAILED
            }
            return MissionRecord(
                missionId = json.optString("missionId", UUID.randomUUID().toString()),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                goal = json.optString("goal", ""),
                outcome = outcome,
                summary = json.optString("summary", ""),
                stepsCount = json.optInt("stepsCount", 0),
                durationMs = json.optLong("durationMs", 0L),
                failureReason = if (json.has("failureReason")) json.optString("failureReason") else null,
                involvedPackages = pkgs
            )
        }
    }
}

/**
 * Task History / Mission Archive Manager (Phase 16 - Memory 2.0).
 * Persists historical records of executed autonomous missions on disk.
 * Enables J.A.R.V.I.S. to reference past successful strategies and avoid past failure modes.
 * Strictly bounds storage (max 50 missions) with LRU/FIFO eviction to prevent unbounded growth.
 */
class TaskHistoryManager(private val context: Context) {

    private val historyFile: File by lazy {
        File(context.filesDir, "jarvis_task_history.json")
    }

    private val lock = Any()
    private val records = mutableListOf<MissionRecord>()

    init {
        loadFromDisk()
    }

    /**
     * Loads past mission records from disk.
     */
    fun loadFromDisk() {
        synchronized(lock) {
            try {
                records.clear()
                if (!historyFile.exists()) return
                val rawJson = historyFile.readText()
                if (rawJson.isBlank()) return

                var parsedSuccessfully = false
                try {
                    val root = JSONObject(rawJson)
                    val array = root.optJSONArray("records")
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            records.add(MissionRecord.fromJson(array.getJSONObject(i)))
                        }
                        parsedSuccessfully = true
                    }
                } catch (_: Throwable) {
                    // Fallback to regex parsing for desktop JVM test environments
                }

                if (!parsedSuccessfully) {
                    val recordBlockRegex = Regex(
                        """\{\s*"missionId":\s*"([^"]*)",\s*"timestamp":\s*(\d+),\s*"goal":\s*"((?:\\.|[^"\\])*)",\s*"outcome":\s*"([^"]*)",\s*"summary":\s*"((?:\\.|[^"\\])*)",\s*"stepsCount":\s*(\d+),\s*"durationMs":\s*(\d+)(?:,\s*"failureReason":\s*"((?:\\.|[^"\\])*)")?(?:,\s*"involvedPackages":\s*\[(.*?)\])?\s*\}""",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    for (m in recordBlockRegex.findAll(rawJson)) {
                        val mid = unescapeJson(m.groupValues[1])
                        val ts = m.groupValues[2].toLongOrNull() ?: System.currentTimeMillis()
                        val goal = unescapeJson(m.groupValues[3])
                        val outcomeStr = unescapeJson(m.groupValues[4])
                        val summary = unescapeJson(m.groupValues[5])
                        val steps = m.groupValues[6].toIntOrNull() ?: 0
                        val dur = m.groupValues[7].toLongOrNull() ?: 0L
                        val fail = if (m.groupValues.size > 8 && m.groupValues[8].isNotEmpty()) unescapeJson(m.groupValues[8]) else null
                        val pkgsRaw = if (m.groupValues.size > 9) m.groupValues[9] else ""
                        val pkgs = Regex(""""([^"]*)"""").findAll(pkgsRaw).map { it.groupValues[1] }.toList()

                        val outcome = try { MissionOutcome.valueOf(outcomeStr) } catch (_: Exception) { MissionOutcome.FAILED }
                        records.add(
                            MissionRecord(
                                missionId = mid,
                                timestamp = ts,
                                goal = goal,
                                outcome = outcome,
                                summary = summary,
                                stepsCount = steps,
                                durationMs = dur,
                                failureReason = fail,
                                involvedPackages = pkgs
                            )
                        )
                    }
                }

                Log.d(TAG, "Loaded ${records.size} task history records from disk.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load task history from disk: ", e)
            }
        }
    }

    /**
     * Saves task history to disk, bounding the count to [MAX_RECORDS].
     */
    private fun saveToDisk() {
        synchronized(lock) {
            try {
                val toSave = if (records.size > MAX_RECORDS) records.takeLast(MAX_RECORDS) else records
                val sb = StringBuilder()
                sb.append("{\n  \"records\": [\n")
                for (i in toSave.indices) {
                    val r = toSave[i]
                    sb.append("    {\n")
                    sb.append("      \"missionId\": \"${escapeJson(r.missionId)}\",\n")
                    sb.append("      \"timestamp\": ${r.timestamp},\n")
                    sb.append("      \"goal\": \"${escapeJson(r.goal)}\",\n")
                    sb.append("      \"outcome\": \"${r.outcome.name}\",\n")
                    sb.append("      \"summary\": \"${escapeJson(r.summary)}\",\n")
                    sb.append("      \"stepsCount\": ${r.stepsCount},\n")
                    sb.append("      \"durationMs\": ${r.durationMs}")
                    if (r.failureReason != null) {
                        sb.append(",\n      \"failureReason\": \"${escapeJson(r.failureReason)}\"")
                    }
                    if (r.involvedPackages.isNotEmpty()) {
                        val pkgsStr = r.involvedPackages.joinToString(", ") { "\"${escapeJson(it)}\"" }
                        sb.append(",\n      \"involvedPackages\": [$pkgsStr]\n")
                    } else {
                        sb.append("\n")
                    }
                    sb.append("    }")
                    if (i < toSave.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("  ]\n}\n")
                historyFile.writeText(sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save task history to disk: ", e)
            }
        }
    }

    /**
     * Records a completed, failed, or cancelled mission.
     * Sanitizes sensitive information automatically before storage.
     */
    fun recordMission(record: MissionRecord) {
        synchronized(lock) {
            val sanitized = record.copy(
                goal = MemorySecuritySanitizer.sanitize(record.goal),
                summary = MemorySecuritySanitizer.sanitize(record.summary),
                failureReason = record.failureReason?.let { MemorySecuritySanitizer.sanitize(it) }
            )
            records.add(sanitized)
            if (records.size > MAX_RECORDS) {
                // Remove oldest records (FIFO/LRU pruning)
                while (records.size > MAX_RECORDS) {
                    records.removeAt(0)
                }
            }
            saveToDisk()
        }
    }

    /**
     * Retrieves recent mission history.
     */
    fun getRecentMissions(limit: Int = 10): List<MissionRecord> = synchronized(lock) {
        if (records.size > limit) records.takeLast(limit) else records.toList()
    }

    /**
     * Finds historical missions relevant to the user's current goal or target package.
     * Returns high-signal matches (max [limit]) to avoid token bloat.
     */
    fun findRelevantMissions(queryGoal: String, activePackage: String? = null, limit: Int = 2): List<MissionRecord> = synchronized(lock) {
        if (records.isEmpty()) return emptyList()

        val cleanQuery = queryGoal.lowercase().trim()
        val queryWords = cleanQuery.split(Regex("""\s+""")).filter { it.length > 2 }
        val targetPkg = activePackage?.lowercase()?.trim()

        val scored = records.map { record ->
            var score = 0
            val recordGoal = record.goal.lowercase()
            val recordSummary = record.summary.lowercase()

            // Keyword overlap
            for (word in queryWords) {
                if (recordGoal.contains(word)) score += 3
                if (recordSummary.contains(word)) score += 1
            }

            // Target package match
            if (targetPkg != null && record.involvedPackages.any { it.contains(targetPkg, ignoreCase = true) }) {
                score += 4
            }

            // Slight boost for recent successful missions
            if (record.outcome == MissionOutcome.SUCCESS) score += 2

            Pair(record, score)
        }

        return scored
            .filter { it.second > 2 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Formats relevant past missions into a compact, token-bounded prompt string (≤ 100 tokens).
     */
    fun formatContextSummary(relevantMissions: List<MissionRecord>): String {
        if (relevantMissions.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("RELEVANT PAST TASK HISTORY:\n")
        for (m in relevantMissions) {
            val statusTag = if (m.outcome == MissionOutcome.SUCCESS) "SUCCESS" else "FAILED"
            val shortSummary = if (m.summary.length > 120) m.summary.take(117) + "..." else m.summary
            sb.append("- [$statusTag] \"${m.goal}\": $shortSummary\n")
            if (m.outcome == MissionOutcome.FAILED && !m.failureReason.isNullOrBlank()) {
                val shortFail = if (m.failureReason.length > 80) m.failureReason.take(77) + "..." else m.failureReason
                sb.append("  (Note: Failed due to: $shortFail. Avoid repeating this error.)\n")
            }
        }
        return sb.toString().trim()
    }

    fun clearHistory() {
        synchronized(lock) {
            records.clear()
            if (historyFile.exists()) {
                historyFile.delete()
            }
        }
    }

    val totalRecordsCount: Int
        get() = synchronized(lock) { records.size }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun unescapeJson(s: String): String = s.replace("\\\"", "\"")
        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\")

    companion object {
        private const val TAG = "TaskHistoryManager"
        const val MAX_RECORDS = 50
    }
}
