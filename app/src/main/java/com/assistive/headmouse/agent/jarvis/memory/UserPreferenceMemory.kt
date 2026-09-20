package com.assistive.headmouse.agent.jarvis.memory

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * User Preferences Memory (Phase 16 - Memory 2.0).
 * Stores long-term user preferences and behavioral hints on disk.
 * Allows J.A.R.V.I.S. to personalize actions (e.g. preferred apps, language style) across missions.
 */
class UserPreferenceMemory(private val context: Context) {

    private val prefFile: File by lazy {
        File(context.filesDir, "jarvis_user_preferences.json")
    }

    private val lock = Any()
    private val preferences = mutableMapOf<String, String>()

    init {
        loadFromDisk()
    }

    fun loadFromDisk() {
        synchronized(lock) {
            try {
                preferences.clear()
                if (!prefFile.exists()) return
                val rawJson = prefFile.readText()
                if (rawJson.isBlank()) return

                var parsedSuccessfully = false
                try {
                    val root = JSONObject(rawJson)
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        preferences[k] = root.getString(k)
                    }
                    parsedSuccessfully = true
                } catch (_: Throwable) {
                    // Fallback
                }

                if (!parsedSuccessfully) {
                    val entryRegex = Regex(""""((?:\\.|[^"\\])*)":\s*"((?:\\.|[^"\\])*)"""")
                    for (m in entryRegex.findAll(rawJson)) {
                        preferences[unescapeJson(m.groupValues[1])] = unescapeJson(m.groupValues[2])
                    }
                }

                Log.d(TAG, "Loaded ${preferences.size} user preferences from disk.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load user preferences: ", e)
            }
        }
    }

    private fun saveToDisk() {
        synchronized(lock) {
            try {
                val sb = StringBuilder()
                sb.append("{\n")
                val entries = preferences.entries.toList()
                for (i in entries.indices) {
                    val (k, v) = entries[i]
                    sb.append("  \"${escapeJson(k)}\": \"${escapeJson(v)}\"")
                    if (i < entries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("}\n")
                prefFile.writeText(sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save user preferences: ", e)
            }
        }
    }

    fun setPreference(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) return
        if (MemorySecuritySanitizer.containsSensitiveData(value)) return

        synchronized(lock) {
            preferences[key.trim().lowercase()] = MemorySecuritySanitizer.sanitize(value.trim())
            saveToDisk()
        }
    }

    fun getPreference(key: String): String? = synchronized(lock) {
        preferences[key.trim().lowercase()]
    }

    fun getAllPreferences(): Map<String, String> = synchronized(lock) {
        preferences.toMap()
    }

    /**
     * Formats preferences into a concise string for LLM system prompt context (≤ 50 tokens).
     */
    fun formatPromptSummary(): String = synchronized(lock) {
        if (preferences.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("User Preferences: ")
        val items = preferences.entries.take(5).map { "${it.key}=${it.value}" }
        sb.append(items.joinToString(", "))
        return sb.toString()
    }

    fun clearPreferences() {
        synchronized(lock) {
            preferences.clear()
            if (prefFile.exists()) {
                prefFile.delete()
            }
        }
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun unescapeJson(s: String): String = s.replace("\\\"", "\"")
        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\")

    companion object {
        private const val TAG = "UserPreferenceMemory"
    }
}
