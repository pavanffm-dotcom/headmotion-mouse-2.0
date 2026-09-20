package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import com.assistive.headmouse.agent.jarvis.action.*
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState

/**
 * Phase 5: Replanning Engine.
 *
 * Detects plan drift, unexpected dialogs, and verification failures,
 * synthesizing localized recovery decisions instead of blindly repeating failed actions.
 *
 * API: Uses [ToolCall] and [WorldState] (Phase 5 model).
 * Legacy [ActionStep]-based API retained for backward compatibility.
 */
class ReplanningEngine {

    companion object {
        private const val TAG = "ReplanningEngine"
        const val MAX_RECOVERY_ATTEMPTS = 3

        private val DIALOG_DISMISS_LABELS = listOf(
            "cancel", "dismiss", "close", "not now", "skip", "ok", "no thanks", "later", "got it"
        )
        private val LOADING_KEYWORDS = listOf(
            "loading", "please wait", "buffering", "connecting", "processing"
        )
    }

    // =========================================================================
    // Phase 11: Comprehensive Recovery Engine (PRIMARY API)
    // =========================================================================

    /**
     * Comprehensive recovery analysis feeding the full perceptual execution context:
     * - current WorldState
     * - previous WorldState
     * - goal
     * - current action (failedTool)
     * - action result
     * - verification result
     * - recent action history
     * - consecutive recovery attempts (for infinite loop prevention)
     *
     * @return A valid canonical [ToolCall] to execute next, or null to escalate to ModelClient.
     */
    fun recoverFromState(
        failedTool: ToolCall,
        verificationResult: com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationResult,
        currentWorld: WorldState,
        previousWorld: WorldState? = null,
        goal: String = "",
        actionResult: ActionResult? = null,
        recentHistory: List<String> = emptyList(),
        consecutiveRecoveryAttempts: Int = 0,
        consecutiveUnchanged: Int = 0
    ): ToolCall? {
        Log.i(
            TAG,
            "[RECOVERY_ENGINE] Analyzing failure for goal '$goal': tool=${failedTool.name}, state=${verificationResult.state}, " +
                "attempts=$consecutiveRecoveryAttempts, unchanged=$consecutiveUnchanged, pkg=${currentWorld.foregroundPackage}"
        )

        // 0. Infinite Loop Prevention: Escalate to remote model if local recovery is exhausted
        if (consecutiveRecoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.w(
                TAG,
                "[RECOVERY_ENGINE] Max local recovery attempts ($MAX_RECOVERY_ATTEMPTS) reached. Escalating to ModelClient."
            )
            return null
        }

        // 1. Blocking Dialog / Popup Dismissal
        val dialogNode = currentWorld.nodes.firstOrNull { node ->
            DIALOG_DISMISS_LABELS.any { label -> node.label.equals(label, ignoreCase = true) }
        }
        if (currentWorld.isDialogBlocking || dialogNode != null) {
            val dismissLabel = dialogNode?.label ?: "Cancel"
            Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Dialog blocking detected. Dismissing with '$dismissLabel'.")
            return ToolCall(
                name = CanonicalTools.TAP_ELEMENT,
                arguments = mapOf("label" to dismissLabel),
                thought = "Dismissing popup dialog, Sir."
            )
        }

        // 2. Soft Keyboard Obstruction
        if (currentWorld.isKeyboardVisible && failedTool.name != CanonicalTools.TYPE_TEXT) {
            Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Soft keyboard obstructing screen. Pressing BACK to hide.")
            return ToolCall(
                name = CanonicalTools.PRESS_NAVIGATION,
                arguments = mapOf("action" to "BACK"),
                thought = "Hiding keyboard to view screen content, Sir."
            )
        }

        // 3. Loading State / Spinner / Buffering
        val hasLoadingIndicator = currentWorld.isLoadingIndicatorPresent ||
            verificationResult.explanation.contains("loading", ignoreCase = true) ||
            currentWorld.nodes.any { node ->
                LOADING_KEYWORDS.any { kw -> node.label.contains(kw, ignoreCase = true) }
            }
        if (hasLoadingIndicator) {
            Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Screen is loading. Waiting for content to settle.")
            return ToolCall(
                name = CanonicalTools.WAIT,
                arguments = mapOf("duration_ms" to 2000),
                thought = "Waiting for screen to finish loading, Sir."
            )
        }

        // 4. Timeout / Unsettled UI
        val isTimeout = actionResult?.reason?.contains("timeout", ignoreCase = true) == true ||
            verificationResult.explanation.contains("timeout", ignoreCase = true)
        if (isTimeout) {
            Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Action timed out. Waiting for UI to settle.")
            return ToolCall(
                name = CanonicalTools.WAIT,
                arguments = mapOf("duration_ms" to 1500),
                thought = "Waiting for interface to settle after timeout, Sir."
            )
        }

        // 5. Error Dialog or "Try Again" / "Retry" present on screen
        val retryNode = currentWorld.nodes.firstOrNull { node ->
            node.label.contains("retry", ignoreCase = true) || node.label.contains("try again", ignoreCase = true)
        }
        if (retryNode != null) {
            Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Transient error detected with retry button. Tapping '${retryNode.label}'.")
            return ToolCall(
                name = CanonicalTools.TAP_ELEMENT,
                arguments = mapOf("label" to retryNode.label),
                thought = "Retrying action after transient error, Sir."
            )
        }

        // 6. Unexpected Screen / Dropped to Launcher
        val isUnexpectedLauncher = currentWorld.foregroundPackage.contains("launcher", ignoreCase = true) &&
            failedTool.name != CanonicalTools.LAUNCH_APP
        val isUnexpectedNavigation = verificationResult.explanation.contains("Unexpected navigation", ignoreCase = true)

        if (isUnexpectedLauncher || isUnexpectedNavigation) {
            val prevPkg = previousWorld?.foregroundPackage?.takeIf { !it.contains("launcher", ignoreCase = true) }
            if (prevPkg != null && recentHistory.any { it.contains("RECENTS", ignoreCase = true) }) {
                Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Unexpected screen after RECENTS. Re-launching '$prevPkg'.")
                return ToolCall(
                    name = CanonicalTools.LAUNCH_APP,
                    arguments = mapOf("package_or_name" to prevPkg),
                    thought = "Re-opening previous application, Sir."
                )
            } else {
                Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Unexpected screen detected. Switching back via RECENTS.")
                return ToolCall(
                    name = CanonicalTools.PRESS_NAVIGATION,
                    arguments = mapOf("action" to "RECENTS"),
                    thought = "Switching back to target application, Sir."
                )
            }
        }

        // 7. Target Not Found / Element Not in Viewport
        val targetLabel = failedTool.arguments["label"]?.toString()
            ?: failedTool.arguments["text"]?.toString()
        val isTargetNotFound = verificationResult.explanation.contains("not found", ignoreCase = true) ||
            actionResult?.reason?.contains("not found", ignoreCase = true) == true ||
            (targetLabel != null && currentWorld.nodes.none { it.label.contains(targetLabel, ignoreCase = true) })

        val isTapOrClick = failedTool.name in listOf(
            CanonicalTools.TAP_ELEMENT,
            CanonicalTools.TAP_COORDINATES,
            CanonicalTools.LONG_PRESS
        )

        if (isTapOrClick && isTargetNotFound) {
            val hasScrollable = currentWorld.scrollableNodes.isNotEmpty()
            val hasScrolledDownRecently = recentHistory.any { it.contains("DOWN", ignoreCase = true) }

            return when {
                hasScrollable && !hasScrolledDownRecently -> {
                    Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Target '$targetLabel' not in viewport. Scrolling down.")
                    ToolCall(
                        name = CanonicalTools.SCROLL,
                        arguments = mapOf("direction" to "DOWN"),
                        thought = "Target not visible. Scrolling down to locate target, Sir."
                    )
                }
                hasScrollable && hasScrolledDownRecently -> {
                    Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Target '$targetLabel' not found below. Scrolling up.")
                    ToolCall(
                        name = CanonicalTools.SCROLL,
                        arguments = mapOf("direction" to "UP"),
                        thought = "Scrolling back up to search for target, Sir."
                    )
                }
                else -> {
                    Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Target not found and screen not scrollable. Stepping back.")
                    ToolCall(
                        name = CanonicalTools.PRESS_NAVIGATION,
                        arguments = mapOf("action" to "BACK"),
                        thought = "Target element not found. Stepping back to retry, Sir."
                    )
                }
            }
        }

        // 8. Action Failure / Unfocused Input for TYPE_TEXT
        if (failedTool.name == CanonicalTools.TYPE_TEXT) {
            val editableNode = currentWorld.editableNodes.firstOrNull()
            if (editableNode != null && !editableNode.isFocused) {
                val focusLabel = editableNode.label.ifBlank { "Search or text input" }
                Log.i(TAG, "[RECOVERY_ENGINE] Strategy: Text input failed because field was not focused. Tapping '$focusLabel'.")
                return ToolCall(
                    name = CanonicalTools.TAP_ELEMENT,
                    arguments = mapOf("label" to focusLabel),
                    thought = "Focusing input field before typing, Sir."
                )
            }
        }

        // 9. Consecutive UNCHANGED states (3x threshold)
        if (consecutiveUnchanged >= 3 && verificationResult.state == VerificationState.UNCHANGED) {
            val hasScrollable = currentWorld.scrollableNodes.isNotEmpty()
            return if (hasScrollable) {
                Log.i(TAG, "[RECOVERY_ENGINE] Strategy: 3x UNCHANGED + scrollable. Scrolling down.")
                ToolCall(
                    name = CanonicalTools.SCROLL,
                    arguments = mapOf("direction" to "DOWN"),
                    thought = "Scrolling to locate target after repeated unchanged states, Sir."
                )
            } else {
                Log.i(TAG, "[RECOVERY_ENGINE] Strategy: 3x UNCHANGED, no scroll. Pressing BACK.")
                ToolCall(
                    name = CanonicalTools.PRESS_NAVIGATION,
                    arguments = mapOf("action" to "BACK"),
                    thought = "Stepping back to retry after repeated unchanged states, Sir."
                )
            }
        }

        // 10. No automated strategy applies — escalate to ModelClient
        Log.w(TAG, "[RECOVERY_ENGINE] No automated local recovery strategy applies. Escalating to ModelClient.")
        return null
    }

    /**
     * Backward-compatible overload for existing Phase 5/6 callers and tests.
     */
    fun recoverFromState(
        failedTool: ToolCall,
        verificationState: VerificationState,
        currentWorld: WorldState,
        consecutiveUnchanged: Int
    ): ToolCall? {
        return recoverFromState(
            failedTool = failedTool,
            verificationResult = com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationResult(
                state = verificationState,
                stateChanged = verificationState == VerificationState.SUCCESS,
                explanation = "Verification state: $verificationState"
            ),
            currentWorld = currentWorld,
            consecutiveUnchanged = consecutiveUnchanged
        )
    }

    // =========================================================================
    // Legacy ActionStep-based API (kept for backward compat with old callers)
    // =========================================================================

    fun replan(
        failedStep: ActionStep,
        failureReason: String,
        screenContext: ScreenContextSummary,
        missionMemory: MissionMemory
    ): List<ActionStep>? {
        Log.i(TAG, "Replanning triggered for step  on ''. Reason: ")

        if (screenContext.hasActiveDialog) {
            Log.i(TAG, "Replan Strategy: Detected dialog blocker. Dispatching dismissal.")
            return listOf(
                ActionStep(
                    id = failedStep.id,
                    action = AutonomousActionType.TAP,
                    target = ActionTarget(TargetType.TEXT, "Cancel"),
                    waitAfterMs = 600L,
                    spokenUpdate = "Dismissing popup, Sir."
                ),
                failedStep.copy(id = failedStep.id + 1)
            )
        }

        if (failureReason.contains("not found", ignoreCase = true) || failureReason.contains("TARGET_NOT_FOUND", ignoreCase = true)) {
            val hasScrolledRecently = missionMemory.hasFailedRecently(AutonomousActionType.SCROLL_DOWN, null)
            if (!hasScrolledRecently) {
                Log.i(TAG, "Replan Strategy: Target not visible in current viewport. Scrolling down.")
                return listOf(
                    ActionStep(
                        id = failedStep.id,
                        action = AutonomousActionType.SCROLL_DOWN,
                        waitAfterMs = 700L,
                        spokenUpdate = "Scrolling to locate target, Sir."
                    ),
                    failedStep.copy(id = failedStep.id + 1)
                )
            }
        }

        if (failureReason.contains("keyboard", ignoreCase = true)) {
            Log.i(TAG, "Replan Strategy: Soft keyboard obstruction. Hiding keyboard via BACK.")
            return listOf(
                ActionStep(
                    id = failedStep.id,
                    action = AutonomousActionType.BACK,
                    waitAfterMs = 400L,
                    spokenUpdate = "Adjusting view."
                ),
                failedStep.copy(id = failedStep.id + 1)
            )
        }

        if (failedStep.action != AutonomousActionType.OPEN_APP && screenContext.packageName == "com.android.launcher" || screenContext.packageName.contains("launcher")) {
            Log.i(TAG, "Replan Strategy: Screen dropped to launcher.")
            return listOf(
                ActionStep(
                    id = failedStep.id,
                    action = AutonomousActionType.RECENTS,
                    waitAfterMs = 800L,
                    spokenUpdate = "Switching back to target app, Sir."
                )
            )
        }

        Log.w(TAG, "No automated replan strategy found for failure: ")
        return null
    }
}
