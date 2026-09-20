package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log

/**
 * Lightweight App Navigation Model & Breadcrumb Tracker (R25).
 * Maintains breadcrumb trails of app navigation so the agent never loses spatial context.
 */
class NavigationTracker {

    private val lock = Any()
    private val history = mutableListOf<String>()

    var currentApp: String = ""
        private set

    var previousApp: String = ""
        private set

    companion object {
        private const val TAG = "NavigationTracker"
        private const val MAX_HISTORY = 30
    }

    /**
     * Records a new observed screen or app package.
     */
    fun recordNavigation(packageName: String, screenTitle: String? = null) {
        synchronized(lock) {
            if (packageName.isBlank() || packageName == "android") return

            val friendlyName = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            val entry = if (!screenTitle.isNullOrBlank()) "$friendlyName ($screenTitle)" else friendlyName

            if (packageName != currentApp) {
                previousApp = currentApp
                currentApp = packageName
                Log.i(TAG, "Navigation package change: $previousApp -> $currentApp")
            }

            if (history.isEmpty() || history.last() != entry) {
                history.add(entry)
                if (history.size > MAX_HISTORY) {
                    history.removeAt(0)
                }
            }
        }
    }

    fun getBreadcrumbTrail(): String = synchronized(lock) {
        if (history.isEmpty()) "Home" else history.joinToString(" ➔ ")
    }

    fun getHistory(): List<String> = synchronized(lock) { history.toList() }

    fun reset() {
        synchronized(lock) {
            history.clear()
            currentApp = ""
            previousApp = ""
        }
    }
}
