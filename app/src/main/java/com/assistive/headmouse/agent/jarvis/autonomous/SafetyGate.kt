package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import com.assistive.headmouse.agent.jarvis.action.ActionStep
import com.assistive.headmouse.agent.jarvis.action.AutonomousActionType
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall

/**
 * Three-tier action risk classification (Phase 15).
 */
enum class ActionRisk {
    /** Low impact, read-only, navigation, or basic interaction; runs autonomously. */
    LOW,

    /** Moderate impact, typing, ordinary content, or non-destructive settings. */
    MEDIUM,

    /** High impact, financial, destructive, sensitive, or irreversible actions. Requires confirmation. */
    HIGH
}

/**
 * Configurable safety policy for autonomous execution.
 */
data class SafetyPolicy(
    /** Whether high-risk actions mandate user confirmation. Defaults to true. */
    val requireConfirmationForHighRisk: Boolean = true,

    /** Whether medium-risk actions mandate user confirmation. Defaults to false. */
    val requireConfirmationForMediumRisk: Boolean = false,

    /** If true and no confirmation listener is attached, high-risk actions are blocked automatically. */
    val autoBlockWithoutListener: Boolean = true,

    /** Custom keywords that escalate an action to HIGH risk. */
    val customHighRiskKeywords: List<String> = emptyList(),

    /** Custom keywords that escalate an action to MEDIUM risk. */
    val customMediumRiskKeywords: List<String> = emptyList()
)

/**
 * Human-in-the-loop Safety Gate (Phase 15).
 *
 * Classifies actions into LOW, MEDIUM, and HIGH risk.
 * Mandates user confirmation before executing HIGH (and optionally MEDIUM) risk actions.
 * Guarantees that neither model output nor malformed arguments can bypass safety.
 */
class SafetyGate(
    var isEnabled: Boolean = true,
    var policy: SafetyPolicy = SafetyPolicy()
) {
    /** Callback for ToolCall confirmation: (toolCall, risk, prompt, onDecision) */
    var onConfirmToolCall: ((ToolCall, ActionRisk, String, (Boolean) -> Unit) -> Unit)? = null

    /** Legacy callback for ActionStep confirmation: (step, prompt, onDecision) */
    var onRequestUserConfirmation: ((ActionStep, String, (Boolean) -> Unit) -> Unit)? = null

    companion object {
        private const val TAG = "SafetyGate"

        // High-risk categories & keywords
        private val PURCHASE_KEYWORDS = listOf(
            "buy", "purchase", "pay", "payment", "checkout", "order", "subscribe",
            "subscription", "credit card", "debit card", "cvv", "billing"
        )

        private val DELETE_KEYWORDS = listOf(
            "delete", "remove", "erase", "trash", "clear data", "wipe", "format",
            "drop", "truncate", "destroy"
        )

        private val UNINSTALL_KEYWORDS = listOf(
            "uninstall", "deactivate", "disable app", "force stop"
        )

        private val SENSITIVE_KEYWORDS = listOf(
            "password", "pin", "otp", "passcode", "secret", "token", "credential",
            "social security", "ssn", "bank", "account number", "private key"
        )

        private val IMPORTANT_FORM_KEYWORDS = listOf(
            "submit order", "confirm order", "transfer funds", "send money",
            "grant permission", "allow permission", "permission", "permissions", "allow all", "grant all",
            "factory reset", "reset all", "change password", "update password", "sign in", "login"
        )

        private val SETTINGS_KEYWORDS = listOf(
            "settings", "configure", "toggle", "switch", "enable", "disable", "preference"
        )
    }

    // =========================================================================
    // 1. Action Risk Classification
    // =========================================================================

    /**
     * Classifies a [ToolCall] into LOW, MEDIUM, or HIGH risk.
     * Guaranteed anti-bypass: model-injected bypass arguments are ignored.
     */
    fun classifyRisk(toolCall: ToolCall): ActionRisk {
        val normalized = toolCall.normalize()
        val toolName = normalized.name.lowercase()
        val args = normalized.arguments

        // Extract textual elements to inspect for risk keywords
        val targetLabel = (args["label"] ?: args["target"] ?: "").toString().lowercase()
        val textValue = (args["text"] ?: args["query"] ?: "").toString().lowercase()
        val packageName = (args["package_or_name"] ?: "").toString().lowercase()
        val combinedText = "$targetLabel $textValue $packageName"

        // Rule 1: High-risk keywords in targets or text payload
        val allHighKeywords = PURCHASE_KEYWORDS + DELETE_KEYWORDS + UNINSTALL_KEYWORDS +
                SENSITIVE_KEYWORDS + IMPORTANT_FORM_KEYWORDS + policy.customHighRiskKeywords
        if (allHighKeywords.any { combinedText.contains(it) }) {
            return ActionRisk.HIGH
        }

        // Rule 2: Classification by canonical tool type
        return when (toolName) {
            // Low Risk: Navigation, scroll, wait, reading, launching apps
            CanonicalTools.OBSERVE_SCREEN,
            CanonicalTools.TAKE_SCREENSHOT,
            CanonicalTools.SCROLL,
            CanonicalTools.WAIT,
            CanonicalTools.PRESS_NAVIGATION,
            CanonicalTools.WEB_SEARCH,
            CanonicalTools.FINISH_TASK -> ActionRisk.LOW

            CanonicalTools.LAUNCH_APP -> {
                // Launching standard apps is LOW risk unless custom high-risk keyword matched above
                ActionRisk.LOW
            }

            CanonicalTools.TAP_ELEMENT,
            CanonicalTools.TAP_COORDINATES,
            CanonicalTools.SWIPE -> {
                // Taps are LOW risk unless target contains high-risk keywords (checked above)
                // or medium-risk settings keywords
                if (SETTINGS_KEYWORDS.any { combinedText.contains(it) }) {
                    ActionRisk.MEDIUM
                } else {
                    ActionRisk.LOW
                }
            }

            CanonicalTools.TYPE_TEXT -> {
                // Typing is MEDIUM risk by default (send ordinary content / input)
                // High risk sensitive text (passwords, OTP) caught above
                ActionRisk.MEDIUM
            }

            CanonicalTools.LONG_PRESS -> {
                // Long-press often invokes delete/context menus; treat as MEDIUM risk
                ActionRisk.MEDIUM
            }

            else -> {
                // Malformed, unknown, or unrecognized tool names fail-safe to HIGH
                Log.w(TAG, "[ACTION_CLASSIFICATION] Unknown/unrecognized tool '$toolName'. Failing-safe to HIGH risk.")
                ActionRisk.HIGH
            }
        }
    }

    /**
     * Classifies an [ActionStep] into LOW, MEDIUM, or HIGH risk.
     */
    fun classifyRisk(step: ActionStep): ActionRisk {
        val targetVal = step.target?.value?.lowercase() ?: ""
        val textVal = step.text?.lowercase() ?: ""
        val combined = "$targetVal $textVal"

        val allHighKeywords = PURCHASE_KEYWORDS + DELETE_KEYWORDS + UNINSTALL_KEYWORDS +
                SENSITIVE_KEYWORDS + IMPORTANT_FORM_KEYWORDS + policy.customHighRiskKeywords
        if (allHighKeywords.any { combined.contains(it) }) {
            return ActionRisk.HIGH
        }

        return when (step.action) {
            AutonomousActionType.HOME,
            AutonomousActionType.BACK,
            AutonomousActionType.RECENTS,
            AutonomousActionType.SCROLL_DOWN,
            AutonomousActionType.SCROLL_UP,
            AutonomousActionType.WAIT,
            AutonomousActionType.FIND_ELEMENT,
            AutonomousActionType.OPEN_APP -> ActionRisk.LOW

            AutonomousActionType.TYPE_TEXT -> ActionRisk.MEDIUM
            AutonomousActionType.LONG_PRESS -> ActionRisk.MEDIUM
            AutonomousActionType.TAP -> {
                if (SETTINGS_KEYWORDS.any { combined.contains(it) }) ActionRisk.MEDIUM else ActionRisk.LOW
            }
            else -> ActionRisk.LOW
        }
    }

    /**
     * Legacy classification for backward compatibility with existing callers.
     */
    fun classifySafety(step: ActionStep): SafetyLevel {
        val text = (step.text ?: step.target?.value ?: "").lowercase()
        val risk = classifyRisk(step)
        if (risk == ActionRisk.HIGH) {
            val sensitiveKeywords = listOf("permission", "allow", "grant", "camera", "microphone", "location", "account", "profile", "draft")
            if (sensitiveKeywords.any { text.contains(it) }) {
                return SafetyLevel.SENSITIVE
            }
            return SafetyLevel.DESTRUCTIVE
        }
        return when (risk) {
            ActionRisk.LOW -> SafetyLevel.SAFE
            ActionRisk.MEDIUM -> SafetyLevel.REVERSIBLE
            ActionRisk.HIGH -> SafetyLevel.DESTRUCTIVE
        }
    }

    // =========================================================================
    // 2. Safety Checking & Human Confirmation
    // =========================================================================

    /**
     * Inspects whether a [ToolCall] is safe to proceed immediately or requires confirmation.
     * Returns true if safe to proceed, or false if blocked awaiting confirmation.
     */
    fun checkSafety(toolCall: ToolCall, onDecision: (Boolean) -> Unit): Boolean {
        val normalized = toolCall.normalize()
        val risk = classifyRisk(normalized)
        val reason = "Tool '${normalized.name}' with args ${normalized.arguments} classified as $risk risk."

        Log.i(TAG, "[ACTION_CLASSIFICATION] tool=${normalized.name} risk=$risk reason=$reason")

        if (!isEnabled) {
            Log.i(TAG, "[SAFETY_DECISION] tool=${normalized.name} decision=ALLOWED (SafetyGate disabled)")
            onDecision(true)
            return true
        }

        val requiresConfirmation = when (risk) {
            ActionRisk.HIGH -> policy.requireConfirmationForHighRisk
            ActionRisk.MEDIUM -> policy.requireConfirmationForMediumRisk
            ActionRisk.LOW -> false
        }

        if (requiresConfirmation) {
            val prompt = "Confirm action '${normalized.name}' on '${normalized.arguments["label"] ?: normalized.arguments["text"] ?: "screen"}' (Risk: $risk)"
            Log.w(TAG, "[CONFIRMATION_REQUIRED] tool=${normalized.name} risk=$risk prompt=$prompt")

            val callback = onConfirmToolCall
            if (callback != null) {
                callback.invoke(normalized, risk, prompt) { granted ->
                    if (granted) {
                        Log.i(TAG, "[CONFIRMATION_GRANTED] tool=${normalized.name}")
                        Log.i(TAG, "[SAFETY_DECISION] tool=${normalized.name} decision=ALLOWED_BY_USER")
                    } else {
                        Log.w(TAG, "[ACTION_BLOCKED] tool=${normalized.name} reason=User denied confirmation")
                        Log.i(TAG, "[SAFETY_DECISION] tool=${normalized.name} decision=BLOCKED")
                    }
                    onDecision(granted)
                }
                return false
            }

            // Fallback to ActionStep listener if available
            val legacyCallback = onRequestUserConfirmation
            if (legacyCallback != null) {
                val step = ActionStep(
                    id = 0,
                    action = AutonomousActionType.TAP,
                    target = null,
                    text = normalized.arguments["text"]?.toString()
                )
                legacyCallback.invoke(step, prompt) { granted ->
                    if (granted) {
                        Log.i(TAG, "[CONFIRMATION_GRANTED] tool=${normalized.name}")
                        Log.i(TAG, "[SAFETY_DECISION] tool=${normalized.name} decision=ALLOWED_BY_USER")
                    } else {
                        Log.w(TAG, "[ACTION_BLOCKED] tool=${normalized.name} reason=User denied confirmation")
                        Log.i(TAG, "[SAFETY_DECISION] tool=${normalized.name} decision=BLOCKED")
                    }
                    onDecision(granted)
                }
                return false
            }

            // No confirmation listener attached
            if (policy.autoBlockWithoutListener) {
                Log.w(TAG, "[ACTION_BLOCKED] tool=${normalized.name} reason=No confirmation listener attached for $risk action")
                Log.i(TAG, "[SAFETY_DECISION] tool=${normalized.name} decision=BLOCKED")
                onDecision(false)
                return false
            }
        }

        Log.i(TAG, "[SAFETY_DECISION] tool=${normalized.name} decision=ALLOWED")
        onDecision(true)
        return true
    }

    /**
     * Inspects whether an [ActionStep] requires user confirmation (backward compatibility).
     */
    fun checkSafety(step: ActionStep, onDecision: (Boolean) -> Unit): Boolean {
        val risk = classifyRisk(step)
        val reason = "Action ${step.action} on '${step.target?.value ?: "device"}' classified as $risk."

        Log.i(TAG, "[ACTION_CLASSIFICATION] tool=${step.action} risk=$risk reason=$reason")

        if (!isEnabled) {
            Log.i(TAG, "[SAFETY_DECISION] tool=${step.action} decision=ALLOWED (SafetyGate disabled)")
            onDecision(true)
            return true
        }

        val requiresConfirmation = when (risk) {
            ActionRisk.HIGH -> policy.requireConfirmationForHighRisk
            ActionRisk.MEDIUM -> policy.requireConfirmationForMediumRisk
            ActionRisk.LOW -> false
        }

        if (requiresConfirmation) {
            val prompt = "Action ${step.action} on '${step.target?.value ?: "device"}' requires human approval."
            Log.w(TAG, "[CONFIRMATION_REQUIRED] tool=${step.action} risk=$risk prompt=$prompt")

            val listener = onRequestUserConfirmation
            if (listener != null) {
                listener.invoke(step, prompt) { granted ->
                    if (granted) {
                        Log.i(TAG, "[CONFIRMATION_GRANTED] tool=${step.action}")
                        Log.i(TAG, "[SAFETY_DECISION] tool=${step.action} decision=ALLOWED_BY_USER")
                    } else {
                        Log.w(TAG, "[ACTION_BLOCKED] tool=${step.action} reason=User denied confirmation")
                        Log.i(TAG, "[SAFETY_DECISION] tool=${step.action} decision=BLOCKED")
                    }
                    onDecision(granted)
                }
                return false
            } else {
                Log.w(TAG, "[ACTION_BLOCKED] tool=${step.action} reason=No confirmation listener attached")
                Log.i(TAG, "[SAFETY_DECISION] tool=${step.action} decision=BLOCKED")
                onDecision(false)
                return false
            }
        }

        Log.i(TAG, "[SAFETY_DECISION] tool=${step.action} decision=ALLOWED")
        onDecision(true)
        return true
    }
}

