package com.assistive.headmouse.agent.jarvis.action

import android.util.Log
import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.service.HeadMouseAccessibilityService
import kotlinx.coroutines.delay

/**
 * Local Recovery Engine.
 * Attempts intelligent local recovery before escalating to Cloud AI replanning:
 * 1. UI settling wait (500ms) + target re-resolution
 * 2. Small contextual scroll to bring off-screen targets into viewport
 * 3. Dismissing modal popups or back navigation
 */
class RecoveryEngine(
    private val screenObserver: ScreenObserver,
    private val actionExecutor: ActionExecutor
) {

    private val service: HeadMouseAccessibilityService?
        get() = HeadMouseAccessibilityService.instance

    companion object {
        private const val TAG = "RecoveryEngine"
        const val MAX_LOCAL_RETRIES = 2
    }

    suspend fun attemptRecovery(
        failedStep: ActionStep,
        retryAttempt: Int,
        activeNodes: List<ScreenNode>
    ): ActionResult {
        Log.i(TAG, "Attempting local recovery for Step ${failedStep.id} (${failedStep.action}), attempt $retryAttempt")

        when (retryAttempt) {
            1 -> {
                // Strategy 1: Wait 500ms for animations/network to settle and retry target resolution
                delay(500L)
                val refreshedNodes = screenObserver.getActiveNodes()
                val resolved = TargetResolver.resolveTarget(failedStep.target, refreshedNodes)
                if (resolved != null) {
                    Log.i(TAG, "Recovery Strategy 1 succeeded: Target re-resolved on screen.")
                    return actionExecutor.executeStep(failedStep)
                }
            }
            2 -> {
                // Strategy 2: Scroll slightly down to bring off-screen targets into view
                Log.i(TAG, "Recovery Strategy 2: Scrolling slightly down to discover target.")
                service?.scrollDown()
                delay(800L)
                val refreshedNodes = screenObserver.getActiveNodes()
                val resolved = TargetResolver.resolveTarget(failedStep.target, refreshedNodes)
                if (resolved != null) {
                    Log.i(TAG, "Recovery Strategy 2 succeeded: Target visible after scrolling.")
                    return actionExecutor.executeStep(failedStep)
                }

                // Strategy 2b: Check if an unwanted dialog/popup blocked the view
                if (refreshedNodes.any { it.label.contains("cancel", ignoreCase = true) || it.label.contains("close", ignoreCase = true) || it.label.contains("dismiss", ignoreCase = true) }) {
                    Log.i(TAG, "Detected blocking popup. Performing BACK to dismiss.")
                    service?.performBack()
                    delay(500L)
                }
            }
        }

        return ActionResult(
            success = false,
            action = failedStep.action.name,
            target = failedStep.target?.value,
            reason = "LOCAL_RECOVERY_EXHAUSTED",
            verified = false,
            retryable = false
        )
    }
}
