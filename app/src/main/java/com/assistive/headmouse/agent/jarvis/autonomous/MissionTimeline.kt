package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single entry in the mission audit timeline.
 */
data class TimelineEntry(
    val timestampMs: Long,
    val timeFormatted: String,
    val eventTag: String,
    val details: String
)

/**
 * Chronological Autonomous Mission Timeline & Audit Logger (R36).
 * Maintains a traceable, timestamped ledger of all perception, planning, tool execution,
 * and verification milestones for debugging and transparency.
 */
class MissionTimeline {

    private val lock = Any()
    private val entries = mutableListOf<TimelineEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    companion object {
        private const val TAG = "MissionTimeline"
        private const val MAX_ENTRIES = 200
    }

    fun record(eventTag: String, details: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val formatted = timeFormat.format(Date(now))
            val entry = TimelineEntry(now, formatted, eventTag, details)
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            Log.d(TAG, "[$formatted] $eventTag: $details")
        }
    }

    fun getEntries(): List<TimelineEntry> = synchronized(lock) { entries.toList() }

    fun toFormattedString(): String = synchronized(lock) {
        if (entries.isEmpty()) return "Timeline empty."
        val sb = StringBuilder()
        for (e in entries) {
            sb.append("${e.timeFormatted} ${e.eventTag.padEnd(22)} ${e.details}\n")
        }
        sb.toString().trim()
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
