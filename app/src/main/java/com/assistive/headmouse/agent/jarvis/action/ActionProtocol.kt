package com.assistive.headmouse.agent.jarvis.action

import com.assistive.headmouse.agent.model.ScreenNode

/**
 * Standard Action Protocol for J.A.R.V.I.S. Autonomous Mobile GUI Agent.
 */
enum class AutonomousActionType {
    OPEN_APP,
    CLOSE_APP,
    BACK,
    HOME,
    RECENTS,
    TAP,
    LONG_PRESS,
    DOUBLE_TAP,
    TYPE_TEXT,
    CLEAR_TEXT,
    SWIPE,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    WAIT,
    FIND_ELEMENT,
    LIKE,
    UNLIKE,
    OPEN_URL,
    END_TASK,
    ASK_USER,
    REPLAN
}

enum class TargetType {
    TEXT,
    CONTENT_DESCRIPTION,
    RESOURCE_ID,
    CLASS_NAME,
    SEMANTIC_ROLE,
    COORDINATES,
    UNKNOWN
}

data class ActionTarget(
    val type: TargetType,
    val value: String,
    val extra: Map<String, String>? = null
)

data class ActionStep(
    val id: Int,
    val action: AutonomousActionType,
    val target: ActionTarget? = null,
    val text: String? = null,
    val confidence: Float = 1.0f,
    val waitAfterMs: Long = 600L,
    val spokenUpdate: String? = null,
    val expectedState: String? = null
)

data class ActionPlan(
    val taskId: String,
    val taskGoal: String,
    val steps: List<ActionStep>
)

data class ActionResult(
    val success: Boolean,
    val action: String,
    val target: String? = null,
    val reason: String = "",
    val verified: Boolean = false,
    val retryable: Boolean = false,
    val durationMs: Long = 0L
)

data class ScreenObservation(
    val packageName: String,
    val activityName: String? = null,
    val visibleTexts: List<String> = emptyList(),
    val clickableElements: List<String> = emptyList(),
    val isKeyboardVisible: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ScreenState(
    val packageName: String,
    val activityName: String? = null,
    val nodes: List<ScreenNode> = emptyList(),
    val visibleTexts: List<String> = emptyList(),
    val clickableElements: List<String> = emptyList(),
    val isKeyboardVisible: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isAvailable: Boolean get() = packageName.isNotBlank() && packageName != "android" && nodes.isNotEmpty()

    fun toPromptSummary(maxElements: Int = 25): String {
        if (nodes.isEmpty()) {
            return if (packageName.isNotBlank() && packageName != "android") {
                "Current App: $packageName (No interactive elements detected on active window)."
            } else {
                "Screen state unavailable (Accessibility Service active window not attached)."
            }
        }
        val appFriendlyName = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        val sb = StringBuilder()
        sb.append("Current App: $appFriendlyName ($packageName)\n")
        sb.append("Visible Elements (${nodes.size} detected):\n")
        val displayedNodes = nodes.take(maxElements)
        for ((idx, node) in displayedNodes.withIndex()) {
            val num = idx + 1
            val label = node.label.replace("\n", " ").trim()
            val type = when {
                node.className?.contains("EditText", ignoreCase = true) == true || (node.isFocusable && !node.isClickable) -> "input field"
                node.isClickable -> "button"
                node.isScrollable -> "scroll container"
                node.isCheckable -> "checkbox/switch"
                else -> "text label"
            }
            sb.append("  [#$num] \"$label\" ($type)\n")
        }
        if (nodes.size > maxElements) {
            sb.append("  ... and ${nodes.size - maxElements} more elements.\n")
        }
        return sb.toString().trim()
    }
}

data class TaskContext(
    val taskId: String,
    val originalCommand: String,
    val language: String = "Hinglish",
    var currentApp: String = "",
    var status: String = "IDLE",
    var currentStepIndex: Int = 0,
    val totalSteps: Int,
    val steps: MutableList<ActionStep> = mutableListOf(),
    val completedSteps: MutableList<ActionStep> = mutableListOf(),
    val failedSteps: MutableList<ActionStep> = mutableListOf(),
    var retryCount: Int = 0,
    var lastObservation: ScreenObservation? = null,
    val history: MutableList<String> = mutableListOf()
) {
    fun isComplete(): Boolean = currentStepIndex >= steps.size
    fun currentStep(): ActionStep? = steps.getOrNull(currentStepIndex)
}
