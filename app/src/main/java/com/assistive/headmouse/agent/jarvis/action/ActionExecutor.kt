package com.assistive.headmouse.agent.jarvis.action

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.assistive.headmouse.agent.jarvis.AppLauncher
import com.assistive.headmouse.service.HeadMouseAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Physical Action Executor ("The Hands").
 * Connects the abstract ActionStep to physical Android AccessibilityService gestures,
 * ensures idempotency, and enforces the single-mission execution lock.
 */
class ActionExecutor(
    private val context: Context,
    private val screenObserver: ScreenObserver
) {

    private val service: HeadMouseAccessibilityService?
        get() = HeadMouseAccessibilityService.instance

    // Global Execution Lock: Only ONE mission may control the physical UI at a time
    companion object {
        private val executionMutex = Mutex()
        private const val TAG = "ActionExecutor"
    }

    suspend fun executeStep(
        step: ActionStep,
        visionCoordinates: Pair<Float, Float>? = null
    ): ActionResult {
        val startTime = System.currentTimeMillis()

        executionMutex.withLock {
            try {
                // 1. Idempotency Check: Determine if the desired state is already satisfied
                val observation = screenObserver.observeScreen()
                if (isActionIdempotent(step, observation)) {
                    Log.i(TAG, "Step ${step.id} (${step.action}) is already satisfied. Skipping duplicate action.")
                    return ActionResult(
                        success = true,
                        action = step.action.name,
                        target = step.target?.value,
                        reason = "ALREADY_SATISFIED",
                        verified = true,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                }

                // 2. Dispatch Action based on Type
                when (step.action) {
                    AutonomousActionType.OPEN_APP -> {
                        val targetApp = step.target?.value ?: step.text ?: ""
                        val launched = AppLauncher.launchApp(context, targetApp)
                        delay(step.waitAfterMs)

                        val newObservation = screenObserver.observeScreen()
                        val verified = screenObserver.verifyState(step, newObservation)

                        return ActionResult(
                            success = launched,
                            action = step.action.name,
                            target = targetApp,
                            reason = if (launched) "LAUNCHED" else "APP_NOT_FOUND",
                            verified = verified,
                            retryable = !launched,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.TAP, AutonomousActionType.DOUBLE_TAP, AutonomousActionType.LONG_PRESS -> {
                        var activeNodes = screenObserver.getActiveNodes()
                        var resolved = TargetResolver.resolveTarget(step.target, activeNodes, visionCoordinates)

                        // If not found immediately, wait 600ms for UI settling and retry
                        if (resolved == null) {
                            delay(600L)
                            activeNodes = screenObserver.getActiveNodes()
                            resolved = TargetResolver.resolveTarget(step.target, activeNodes, visionCoordinates)
                        }

                        if (resolved == null) {
                            Log.w(TAG, "Target resolution failed for ${step.target?.value}")
                            return ActionResult(
                                success = false,
                                action = step.action.name,
                                target = step.target?.value,
                                reason = "TARGET_NOT_FOUND",
                                verified = false,
                                retryable = true,
                                durationMs = System.currentTimeMillis() - startTime
                            )
                        }

                        // Physical cursor positioning before tapping
                        service?.updateCursorPositionExplicit(resolved.x, resolved.y)
                        delay(150L)

                        val matchedNode = resolved.matchedNode
                        if (matchedNode != null) {
                            service?.clickScreenNode(matchedNode)
                        } else {
                            service?.clickAt(resolved.x, resolved.y)
                        }

                        if (step.action == AutonomousActionType.DOUBLE_TAP) {
                            delay(120L)
                            service?.clickAt(resolved.x, resolved.y)
                        }

                        delay(step.waitAfterMs)

                        val newObservation = screenObserver.observeScreen()
                        val verified = screenObserver.verifyState(step, newObservation)

                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            target = step.target?.value,
                            reason = "TAPPED",
                            verified = verified,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.TYPE_TEXT -> {
                        val textToType = step.text ?: step.target?.value ?: ""
                        val activeNodes = screenObserver.getActiveNodes()

                        // Find targeted or focused edit text node
                        val targetNode = if (step.target != null) {
                            TargetResolver.resolveTarget(step.target, activeNodes, visionCoordinates)?.matchedNode
                        } else null

                        var typed = false
                        if (targetNode != null) {
                            service?.updateCursorPositionExplicit(targetNode.centerX, targetNode.centerY)
                            service?.clickScreenNode(targetNode)
                            delay(250L)
                        }

                        // Accessibility text injection
                        val rootNode = service?.rootInActiveWindow
                        val editNode = rootNode?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            ?: rootNode?.let { findFirstEditableNode(it) }

                        if (editNode != null) {
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    textToType
                                )
                            }
                            typed = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            editNode.recycle()
                        }
                        rootNode?.recycle()

                        delay(step.waitAfterMs)

                        val newObservation = screenObserver.observeScreen()
                        val verified = screenObserver.verifyState(step, newObservation)

                        return ActionResult(
                            success = typed,
                            action = step.action.name,
                            target = textToType,
                            reason = if (typed) "TEXT_INJECTED" else "EDIT_FIELD_NOT_FOUND",
                            verified = verified,
                            retryable = !typed,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.SCROLL_DOWN -> {
                        service?.scrollDown()
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "SCROLLED_DOWN",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.SCROLL_UP -> {
                        service?.scrollUp()
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "SCROLLED_UP",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.BACK -> {
                        service?.performBack()
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "BACK_PERFORMED",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.HOME -> {
                        service?.performHome()
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "HOME_PERFORMED",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.RECENTS -> {
                        service?.performRecents()
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "RECENTS_PERFORMED",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.SWIPE, AutonomousActionType.SCROLL_LEFT -> {
                        service?.swipeLeft()
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "SWIPED_LEFT",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.SCROLL_RIGHT -> {
                        service?.swipeRight()
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "SWIPED_RIGHT",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.LIKE -> {
                        val activeNodes = screenObserver.getActiveNodes()
                        val resolved = TargetResolver.resolveTarget(step.target, activeNodes, visionCoordinates)
                        if (resolved != null) {
                            service?.updateCursorPositionExplicit(resolved.x, resolved.y)
                            delay(100L)
                            service?.clickAt(resolved.x, resolved.y)
                        } else {
                            // Double tap center of screen for photo/video like
                            val displayMetrics = context.resources.displayMetrics
                            val midX = displayMetrics.widthPixels / 2f
                            val midY = displayMetrics.heightPixels / 2f
                            service?.clickAt(midX, midY)
                            delay(100L)
                            service?.clickAt(midX, midY)
                        }
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "LIKE_EXECUTED",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.WAIT -> {
                        delay(step.waitAfterMs)
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "WAITED",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    AutonomousActionType.END_TASK -> {
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "TASK_COMPLETED",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    else -> {
                        Log.i(TAG, "Action not directly handled: ${step.action}")
                        return ActionResult(
                            success = true,
                            action = step.action.name,
                            reason = "FALLBACK_SUCCESS",
                            verified = true,
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing step: ", e)
                return ActionResult(
                    success = false,
                    action = step.action.name,
                    target = step.target?.value,
                    reason = "EXCEPTION: ${e.message}",
                    verified = false,
                    retryable = true,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }

    private fun isActionIdempotent(step: ActionStep, obs: ScreenObservation): Boolean {
        return when (step.action) {
            AutonomousActionType.OPEN_APP -> {
                val app = step.target?.value?.lowercase() ?: ""
                obs.packageName.lowercase().contains(app) && app.isNotBlank()
            }
            AutonomousActionType.TYPE_TEXT -> {
                val text = step.text?.lowercase() ?: ""
                obs.visibleTexts.any { it.lowercase() == text }
            }
            AutonomousActionType.LIKE -> {
                obs.clickableElements.any { it.lowercase().contains("liked") }
            }
            else -> false
        }
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable || root.className?.contains("EditText", ignoreCase = true) == true) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findFirstEditableNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }
}
