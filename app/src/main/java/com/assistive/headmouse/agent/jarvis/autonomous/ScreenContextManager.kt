package com.assistive.headmouse.agent.jarvis.autonomous

import com.assistive.headmouse.agent.jarvis.action.ScreenObserver
import com.assistive.headmouse.agent.jarvis.action.ScreenState
import com.assistive.headmouse.agent.model.ScreenNode

/**
 * Encapsulated screen context for AI reasoning and verification (R18).
 */
data class ScreenContextSummary(
    val packageName: String,
    val activityName: String?,
    val formattedSummary: String,
    val nodeCount: Int,
    val focusedNodeLabel: String?,
    val editableNodeLabel: String?,
    val hasActiveDialog: Boolean,
    val timestampMs: Long
)

/**
 * Screen Context Manager (R18).
 * Tracks the live active screen state, historical diffs, dialog presence,
 * focused/editable nodes, and generates lean context summaries for LLM turns.
 */
class ScreenContextManager(
    private val screenObserver: ScreenObserver
) {
    private val lock = Any()

    var latestScreenState: ScreenState = ScreenState("")
        private set

    var previousScreenState: ScreenState? = null
        private set

    var latestDiff: ScreenDiff? = null
        private set

    var currentScreenshotBase64: String? = null
        private set

    val currentPackage: String get() = latestScreenState.packageName
    val currentActivity: String? get() = latestScreenState.activityName

    val focusedElement: ScreenNode?
        get() = synchronized(lock) { latestScreenState.nodes.find { it.isFocused } }

    val editableElement: ScreenNode?
        get() = synchronized(lock) {
            latestScreenState.nodes.find {
                it.className?.contains("EditText", ignoreCase = true) == true ||
                (it.isFocusable && !it.isClickable)
            }
        }

    val isDialogVisible: Boolean
        get() = synchronized(lock) {
            latestScreenState.nodes.any {
                it.className?.contains("Dialog", ignoreCase = true) == true ||
                it.className?.contains("PopupWindow", ignoreCase = true) == true ||
                it.label.contains("Allow", ignoreCase = true) ||
                it.label.contains("Permission", ignoreCase = true)
            }
        }

    /**
     * Refreshes the active screen state synchronously from the accessibility tree.
     */
    fun refreshLiveScreen(): ScreenState {
        val freshState = screenObserver.getLiveScreenState()
        updateState(freshState)
        return freshState
    }

    /**
     * Updates internal tracking with a newly observed ScreenState.
     */
    fun updateState(newState: ScreenState, screenshotBase64: String? = null) {
        synchronized(lock) {
            previousScreenState = latestScreenState
            latestScreenState = newState
            if (screenshotBase64 != null) {
                currentScreenshotBase64 = screenshotBase64
            }
            latestDiff = ScreenDiffEngine.computeDiff(previousScreenState, newState)
        }
    }

    /**
     * Produces a clean, concise screen context summary for the cognitive AI brain.
     */
    fun getCurrentScreenContext(maxElements: Int = 25): ScreenContextSummary = synchronized(lock) {
        val summaryText = latestScreenState.toPromptSummary(maxElements)
        ScreenContextSummary(
            packageName = latestScreenState.packageName,
            activityName = latestScreenState.activityName,
            formattedSummary = summaryText,
            nodeCount = latestScreenState.nodes.size,
            focusedNodeLabel = focusedElement?.label,
            editableNodeLabel = editableElement?.label,
            hasActiveDialog = isDialogVisible,
            timestampMs = latestScreenState.timestampMs
        )
    }

    fun reset() {
        synchronized(lock) {
            latestScreenState = ScreenState("")
            previousScreenState = null
            latestDiff = null
            currentScreenshotBase64 = null
        }
    }
}
