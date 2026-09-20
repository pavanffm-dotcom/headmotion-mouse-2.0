package com.assistive.headmouse.agent.jarvis.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Reusable procedural interaction pattern learned for a specific application.
 */
data class AppInteractionPattern(
    val patternId: String = UUID.randomUUID().toString(),
    val packageName: String,
    val intentType: String, // e.g. "search", "navigate_settings", "compose", "play"
    val description: String,
    val recommendedAction: String, // e.g. "tap", "type_text"
    val targetSelector: String, // e.g. "Search", "com.google.android.youtube:id/menu_item_0"
    val confidence: Float = 0.8f,
    val successCount: Int = 1,
    val failureCount: Int = 0,
    val lastUsedTimestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("patternId", patternId)
        put("packageName", packageName)
        put("intentType", intentType)
        put("description", description)
        put("recommendedAction", recommendedAction)
        put("targetSelector", targetSelector)
        put("confidence", confidence.toDouble())
        put("successCount", successCount)
        put("failureCount", failureCount)
        put("lastUsedTimestamp", lastUsedTimestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): AppInteractionPattern = AppInteractionPattern(
            patternId = json.optString("patternId", UUID.randomUUID().toString()),
            packageName = json.optString("packageName", ""),
            intentType = json.optString("intentType", "general"),
            description = json.optString("description", ""),
            recommendedAction = json.optString("recommendedAction", "tap"),
            targetSelector = json.optString("targetSelector", ""),
            confidence = json.optDouble("confidence", 0.8).toFloat(),
            successCount = json.optInt("successCount", 1),
            failureCount = json.optInt("failureCount", 0),
            lastUsedTimestamp = json.optLong("lastUsedTimestamp", System.currentTimeMillis())
        )
    }
}

/**
 * Learned App Patterns Manager (Phase 16 - Memory 2.0).
 * Stores verified, reusable GUI interaction patterns for third-party applications.
 * Only stores patterns when JUSTIFIED by empirical successful execution.
 * Bounded by [MAX_PATTERNS_PER_APP] and [MAX_APPS] to keep memory footprint minimal.
 */
class AppPatternManager(private val context: Context) {

    private val patternFile: File by lazy {
        File(context.filesDir, "jarvis_app_patterns.json")
    }

    private val lock = Any()
    // Map of packageName -> list of learned patterns
    private val patterns = mutableMapOf<String, MutableList<AppInteractionPattern>>()

    init {
        loadFromDisk()
    }

    fun loadFromDisk() {
        synchronized(lock) {
            try {
                patterns.clear()
                if (!patternFile.exists()) return
                val rawJson = patternFile.readText()
                if (rawJson.isBlank()) return

                var parsedSuccessfully = false
                try {
                    val root = JSONObject(rawJson)
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val pkg = keys.next()
                        val arr = root.optJSONArray(pkg) ?: continue
                        val list = mutableListOf<AppInteractionPattern>()
                        for (i in 0 until arr.length()) {
                            list.add(AppInteractionPattern.fromJson(arr.getJSONObject(i)))
                        }
                        patterns[pkg] = list
                    }
                    parsedSuccessfully = true
                } catch (_: Throwable) {
                    // Fallback to pure regex parsing for JVM test environments
                }

                if (!parsedSuccessfully) {
                    val patternBlockRegex = Regex(
                        """\{\s*"patternId":\s*"([^"]*)",\s*"packageName":\s*"([^"]*)",\s*"intentType":\s*"([^"]*)",\s*"description":\s*"((?:\\.|[^"\\])*)",\s*"recommendedAction":\s*"([^"]*)",\s*"targetSelector":\s*"((?:\\.|[^"\\])*)",\s*"confidence":\s*([0-9.]+),\s*"successCount":\s*(\d+),\s*"failureCount":\s*(\d+),\s*"lastUsedTimestamp":\s*(\d+)\s*\}""",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    for (m in patternBlockRegex.findAll(rawJson)) {
                        val pid = unescapeJson(m.groupValues[1])
                        val pkg = unescapeJson(m.groupValues[2])
                        val intent = unescapeJson(m.groupValues[3])
                        val desc = unescapeJson(m.groupValues[4])
                        val action = unescapeJson(m.groupValues[5])
                        val selector = unescapeJson(m.groupValues[6])
                        val conf = m.groupValues[7].toFloatOrNull() ?: 0.8f
                        val succ = m.groupValues[8].toIntOrNull() ?: 1
                        val fail = m.groupValues[9].toIntOrNull() ?: 0
                        val ts = m.groupValues[10].toLongOrNull() ?: System.currentTimeMillis()

                        val pattern = AppInteractionPattern(
                            patternId = pid,
                            packageName = pkg,
                            intentType = intent,
                            description = desc,
                            recommendedAction = action,
                            targetSelector = selector,
                            confidence = conf,
                            successCount = succ,
                            failureCount = fail,
                            lastUsedTimestamp = ts
                        )
                        patterns.getOrPut(pkg) { mutableListOf() }.add(pattern)
                    }
                }

                Log.d(TAG, "Loaded app patterns for ${patterns.size} packages.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load app patterns from disk: ", e)
            }
        }
    }

    private fun saveToDisk() {
        synchronized(lock) {
            try {
                val sb = StringBuilder()
                sb.append("{\n")
                val entries = patterns.entries.toList().take(MAX_APPS)
                for (i in entries.indices) {
                    val (pkg, list) = entries[i]
                    sb.append("  \"${escapeJson(pkg)}\": [\n")
                    val boundedList = if (list.size > MAX_PATTERNS_PER_APP) list.takeLast(MAX_PATTERNS_PER_APP) else list
                    for (j in boundedList.indices) {
                        val p = boundedList[j]
                        sb.append("    {\n")
                        sb.append("      \"patternId\": \"${escapeJson(p.patternId)}\",\n")
                        sb.append("      \"packageName\": \"${escapeJson(p.packageName)}\",\n")
                        sb.append("      \"intentType\": \"${escapeJson(p.intentType)}\",\n")
                        sb.append("      \"description\": \"${escapeJson(p.description)}\",\n")
                        sb.append("      \"recommendedAction\": \"${escapeJson(p.recommendedAction)}\",\n")
                        sb.append("      \"targetSelector\": \"${escapeJson(p.targetSelector)}\",\n")
                        sb.append("      \"confidence\": ${p.confidence},\n")
                        sb.append("      \"successCount\": ${p.successCount},\n")
                        sb.append("      \"failureCount\": ${p.failureCount},\n")
                        sb.append("      \"lastUsedTimestamp\": ${p.lastUsedTimestamp}\n")
                        sb.append("    }")
                        if (j < boundedList.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("  ]")
                    if (i < entries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("}\n")
                patternFile.writeText(sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save app patterns to disk: ", e)
            }
        }
    }

    /**
     * Records or updates an interaction pattern ONLY when justified by empirical success/failure.
     * Sanitizes inputs to prevent storing secrets or sensitive selectors.
     */
    fun recordPatternOutcome(
        packageName: String,
        intentType: String,
        description: String,
        action: String,
        targetSelector: String,
        succeeded: Boolean
    ) {
        if (packageName.isBlank() || targetSelector.isBlank()) return
        if (MemorySecuritySanitizer.containsSensitiveData(targetSelector)) return

        synchronized(lock) {
            val list = patterns.getOrPut(packageName) { mutableListOf() }
            val existingIndex = list.indexOfFirst {
                it.intentType.equals(intentType, ignoreCase = true) &&
                it.targetSelector.equals(targetSelector, ignoreCase = true)
            }

            if (existingIndex >= 0) {
                val existing = list[existingIndex]
                val newSuccess = if (succeeded) existing.successCount + 1 else existing.successCount
                val newFailure = if (!succeeded) existing.failureCount + 1 else existing.failureCount
                val newConfidence = if (succeeded) {
                    (existing.confidence + 0.1f).coerceAtMost(1.0f)
                } else {
                    (existing.confidence - 0.25f).coerceAtLeast(0.0f)
                }

                // If confidence degraded to zero, discard unreliable pattern
                if (newConfidence <= 0.15f) {
                    list.removeAt(existingIndex)
                } else {
                    list[existingIndex] = existing.copy(
                        confidence = newConfidence,
                        successCount = newSuccess,
                        failureCount = newFailure,
                        lastUsedTimestamp = System.currentTimeMillis()
                    )
                }
            } else if (succeeded) {
                // Only learn NEW patterns on verified SUCCESS
                if (list.size >= MAX_PATTERNS_PER_APP) {
                    // Evict oldest or lowest confidence
                    list.sortBy { it.confidence }
                    list.removeAt(0)
                }
                list.add(
                    AppInteractionPattern(
                        packageName = packageName,
                        intentType = intentType,
                        description = MemorySecuritySanitizer.sanitize(description),
                        recommendedAction = action,
                        targetSelector = MemorySecuritySanitizer.sanitize(targetSelector),
                        confidence = 0.8f,
                        successCount = 1,
                        failureCount = 0,
                        lastUsedTimestamp = System.currentTimeMillis()
                    )
                )
            }

            // Prune excess packages if needed
            if (patterns.size > MAX_APPS) {
                val oldestPkg = patterns.entries.minByOrNull { entry ->
                    entry.value.maxOfOrNull { it.lastUsedTimestamp } ?: 0L
                }?.key
                if (oldestPkg != null) patterns.remove(oldestPkg)
            }

            saveToDisk()
        }
    }

    /**
     * Retrieves relevant patterns for an app and intent type.
     */
    fun getRelevantPatterns(packageName: String, intentType: String? = null, minConfidence: Float = 0.5f): List<AppInteractionPattern> = synchronized(lock) {
        val list = patterns[packageName] ?: return emptyList()
        return list.filter { p ->
            p.confidence >= minConfidence &&
            (intentType == null || p.intentType.equals(intentType, ignoreCase = true))
        }.sortedByDescending { it.confidence }
    }

    /**
     * Formats a tight, high-signal hint for inclusion in the agent's prompt (≤ 60 tokens).
     */
    fun formatPromptHint(packageName: String, intentType: String? = null): String? = synchronized(lock) {
        val matches = getRelevantPatterns(packageName, intentType, minConfidence = 0.6f)
        if (matches.isEmpty()) return null

        val top = matches.first()
        return "Learned Pattern ($packageName): For '${top.intentType}', prefer ${top.recommendedAction} on '${top.targetSelector}' (Confidence: ${(top.confidence * 100).toInt()}%)."
    }

    fun clearAllPatterns() {
        synchronized(lock) {
            patterns.clear()
            if (patternFile.exists()) {
                patternFile.delete()
            }
        }
    }

    val totalPatternsCount: Int
        get() = synchronized(lock) { patterns.values.sumOf { it.size } }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun unescapeJson(s: String): String = s.replace("\\\"", "\"")
        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\")

    companion object {
        private const val TAG = "AppPatternManager"
        const val MAX_PATTERNS_PER_APP = 5
        const val MAX_APPS = 30
    }
}
