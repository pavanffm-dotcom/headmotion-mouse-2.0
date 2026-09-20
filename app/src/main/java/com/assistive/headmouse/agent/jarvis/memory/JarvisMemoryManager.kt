package com.assistive.headmouse.agent.jarvis.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Data model for a single dialogue turn in conversation memory.
 */
data class MemoryTurn(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val role: String, // "user" or "assistant"
    val content: String,
    val actionExecuted: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("role", role)
        put("content", content)
        if (actionExecuted != null) put("actionExecuted", actionExecuted)
    }

    companion object {
        fun fromJson(json: JSONObject): MemoryTurn = MemoryTurn(
            id = json.optString("id", UUID.randomUUID().toString()),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            role = json.optString("role", "user"),
            content = json.optString("content", ""),
            actionExecuted = if (json.has("actionExecuted")) json.optString("actionExecuted") else null
        )
    }
}

/**
 * Persistent Long-Term & Multi-Turn Conversational Memory for J.A.R.V.I.S.
 * Stores conversation history and persistent user facts on disk (JSON in app files).
 * Ensures JARVIS retains conversational context across app closures and reboots.
 */
class JarvisMemoryManager(private val context: Context) {

    private val memoryFile: File by lazy {
        File(context.filesDir, "jarvis_chat_memory.json")
    }

    private val lock = Any()
    private val turns = mutableListOf<MemoryTurn>()
    private val userFacts = mutableMapOf<String, String>()
    private var userName: String? = null

    init {
        loadFromDisk()
    }

    /**
     * Loads saved memories from disk.
     */
    fun loadFromDisk() {
        synchronized(lock) {
            try {
                if (!memoryFile.exists()) {
                    turns.clear()
                    userFacts.clear()
                    return
                }
                val rawJson = memoryFile.readText()
                if (rawJson.isBlank()) return

                // First attempt: Standard JSONObject (native Android ART runtime)
                var parsedSuccessfully = false
                try {
                    val root = JSONObject(rawJson)
                    userName = if (root.has("userName") && !root.isNull("userName")) root.getString("userName") else null

                    userFacts.clear()
                    val factsObj = root.optJSONObject("facts")
                    if (factsObj != null) {
                        val keys = factsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            userFacts[k] = factsObj.getString(k)
                        }
                    }

                    turns.clear()
                    val turnsArray = root.optJSONArray("turns")
                    if (turnsArray != null && turnsArray.length() > 0) {
                        for (i in 0 until turnsArray.length()) {
                            val turnObj = turnsArray.getJSONObject(i)
                            turns.add(MemoryTurn.fromJson(turnObj))
                        }
                        parsedSuccessfully = true
                    }
                } catch (_: Throwable) {
                    // Fallback to pure regex parser below (e.g. for desktop JVM unit tests)
                }

                // Second attempt: Fallback pure parser if JVM unit test stubs empty JSONObject
                if (!parsedSuccessfully) {
                    val nameMatch = Regex(""""userName":\s*"((?:\\.|[^"\\])*)"""").find(rawJson)
                    if (nameMatch != null) {
                        userName = unescapeJson(nameMatch.groupValues[1])
                    }

                    val factMatch = Regex(""""facts":\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).find(rawJson)
                    if (factMatch != null) {
                        val entryRegex = Regex(""""((?:\\.|[^"\\])*)":\s*"((?:\\.|[^"\\])*)"""")
                        for (m in entryRegex.findAll(factMatch.groupValues[1])) {
                            userFacts[unescapeJson(m.groupValues[1])] = unescapeJson(m.groupValues[2])
                        }
                    }

                    turns.clear()
                    val turnBlockRegex = Regex(
                        """\{\s*"id":\s*"([^"]*)",\s*"timestamp":\s*(\d+),\s*"role":\s*"([^"]*)",\s*"content":\s*"((?:\\.|[^"\\])*)"(?:,\s*"actionExecuted":\s*"((?:\\.|[^"\\])*)")?\s*\}""",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    for (m in turnBlockRegex.findAll(rawJson)) {
                        val id = unescapeJson(m.groupValues[1])
                        val timestamp = m.groupValues[2].toLongOrNull() ?: System.currentTimeMillis()
                        val role = unescapeJson(m.groupValues[3])
                        val content = unescapeJson(m.groupValues[4])
                        val action = if (m.groupValues.size > 5 && m.groupValues[5].isNotEmpty()) unescapeJson(m.groupValues[5]) else null
                        turns.add(MemoryTurn(id = id, timestamp = timestamp, role = role, content = content, actionExecuted = action))
                    }
                }

                Log.d(TAG, "Loaded ${turns.size} conversation turns from persistent memory.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load memory from disk: ", e)
            }
        }
    }

    /**
     * Flushes memory state to disk.
     */
    private fun saveToDisk() {
        synchronized(lock) {
            try {
                // Keep a maximum of 50 turns in permanent disk storage
                val turnsToSave = if (turns.size > 50) turns.takeLast(50) else turns
                val sb = StringBuilder()
                sb.append("{\n")
                if (userName != null) {
                    sb.append("  \"userName\": \"${escapeJson(userName!!)}\",\n")
                }
                sb.append("  \"facts\": {\n")
                val factEntries = userFacts.entries.toList()
                for (i in factEntries.indices) {
                    val e = factEntries[i]
                    sb.append("    \"${escapeJson(e.key)}\": \"${escapeJson(e.value)}\"")
                    if (i < factEntries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("  },\n")
                sb.append("  \"turns\": [\n")
                for (i in turnsToSave.indices) {
                    val t = turnsToSave[i]
                    sb.append("    {\n")
                    sb.append("      \"id\": \"${escapeJson(t.id)}\",\n")
                    sb.append("      \"timestamp\": ${t.timestamp},\n")
                    sb.append("      \"role\": \"${escapeJson(t.role)}\",\n")
                    sb.append("      \"content\": \"${escapeJson(t.content)}\"")
                    if (t.actionExecuted != null) {
                        sb.append(",\n      \"actionExecuted\": \"${escapeJson(t.actionExecuted)}\"\n")
                    } else {
                        sb.append("\n")
                    }
                    sb.append("    }")
                    if (i < turnsToSave.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("  ]\n")
                sb.append("}\n")
                memoryFile.writeText(sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save memory to disk: ", e)
            }
        }
    }

    /**
     * Records a new dialogue turn and extracts any facts (e.g. user name).
     */
    fun addTurn(role: String, content: String, actionExecuted: String? = null) {
        if (content.isBlank()) return
        val sanitized = MemorySecuritySanitizer.sanitize(content.trim())
        synchronized(lock) {
            turns.add(MemoryTurn(role = role, content = sanitized, actionExecuted = actionExecuted))
            extractFactsFromUtterance(role, sanitized)
            saveToDisk()
        }
    }

    /**
     * Extracts persistent facts like user name or preferences.
     */
    private fun extractFactsFromUtterance(role: String, content: String) {
        if (role != "user") return

        // Name extraction: "Mera naam Rahul Verma hai", "My name is Tony Stark", "Call me Bruce"
        val nameRegex1 = Regex(
            """(?:mera\s+naam|my\s+name\s+is|call\s+me)\s+([A-Za-z\u0900-\u097F\s]+?)(?:\s+hai|\s+hu|\s+hoon|[.,!?]|$)""",
            RegexOption.IGNORE_CASE
        )
        val match1 = nameRegex1.find(content)
        if (match1 != null && match1.groupValues.size > 1) {
            val name = match1.groupValues[1].replace(Regex("""[.,!?]"""), "").trim().capitalizeWords()
            if (name.length > 1 && !name.equals("hai", ignoreCase = true) && !name.equals("is", ignoreCase = true)) {
                userName = name
                userFacts["name"] = name
                Log.i(TAG, "Learned user name: $userName")
            }
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun unescapeJson(s: String): String {
        return s.replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    /**
     * Returns the sliding window of recent conversation turns for LLM API payloads.
     */
    fun getRecentTurns(limit: Int = 16): List<MemoryTurn> {
        synchronized(lock) {
            return if (turns.size > limit) {
                turns.takeLast(limit)
            } else {
                turns.toList()
            }
        }
    }

    /**
     * Returns a concise system prompt memory context string.
     */
    fun getMemoryContextSummary(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            if (!userName.isNullOrBlank()) {
                sb.append("You are speaking with Sir ($userName). Address him with respect as Sir.\n")
            } else {
                sb.append("You are speaking with Sir. Address him with respect as Sir.\n")
            }
            if (userFacts.isNotEmpty()) {
                sb.append("Known facts about Sir:\n")
                for ((k, v) in userFacts) {
                    sb.append("- $k: $v\n")
                }
            }
            return sb.toString()
        }
    }

    fun getUserName(): String? = userName

    /**
     * Clears all stored conversation memory.
     */
    fun clearMemory() {
        synchronized(lock) {
            turns.clear()
            userFacts.clear()
            userName = null
            if (memoryFile.exists()) {
                memoryFile.delete()
            }
            Log.i(TAG, "All JARVIS conversation memory cleared.")
        }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    companion object {
        private const val TAG = "JarvisMemoryManager"
    }
}
