package com.assistive.headmouse.agent.jarvis.action

import android.content.Context
import android.util.Log
import com.assistive.headmouse.agent.jarvis.JarvisVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Central Autonomous Task Orchestrator ("The Nervous System").
 * Manages TaskContext, drives the execution loop, triggers screen verification,
 * routes to local recovery, and enforces user interruption safety.
 */
class TaskOrchestrator(
    private val context: Context,
    private val screenObserver: ScreenObserver,
    private val actionExecutor: ActionExecutor,
    private val recoveryEngine: RecoveryEngine,
    private val voiceEngine: JarvisVoiceEngine?,
    private val replanHandler: (suspend (TaskContext, ActionStep, String) -> List<ActionStep>?)? = null
) {

    private val orchestratorScope = CoroutineScope(Dispatchers.Main)
    private var activeJob: Job? = null

    var currentTaskContext: TaskContext? = null
        private set

    var onStatusUpdate: ((String, String) -> Unit)? = null
    var onStepCompleted: ((Int, Int, ActionStep) -> Unit)? = null
    var onTaskFinished: ((Boolean, String) -> Unit)? = null

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    /**
     * Initiates execution of an ActionPlan.
     */
    fun startPlanExecution(plan: ActionPlan) {
        cancelExecution() // Abort any running plan

        val taskCtx = TaskContext(
            taskId = plan.taskId,
            originalCommand = plan.taskGoal,
            totalSteps = plan.steps.size,
            steps = plan.steps.toMutableList()
        )
        currentTaskContext = taskCtx
        taskCtx.status = "EXECUTING"

        onStatusUpdate?.invoke("EXECUTING", "Starting: ${plan.taskGoal}")
        voiceEngine?.speak("Autonomous execution engaged, Sir. ${plan.taskGoal}")

        activeJob = orchestratorScope.launch {
            try {
                while (isActive && !taskCtx.isComplete()) {
                    val step = taskCtx.currentStep() ?: break
                    val stepNum = taskCtx.currentStepIndex + 1

                    onStatusUpdate?.invoke("EXECUTING", "Step $stepNum/${taskCtx.totalSteps}: ${step.action}")
                    if (!step.spokenUpdate.isNullOrBlank()) {
                        voiceEngine?.speak(step.spokenUpdate)
                    }

                    // 1. Observe current screen
                    val obs = screenObserver.observeScreen()
                    taskCtx.lastObservation = obs

                    // 2. Pre-execution Idempotency check: Is desired state already achieved?
                    if (screenObserver.verifyState(step, obs) && step.action != AutonomousActionType.TAP && step.action != AutonomousActionType.TYPE_TEXT) {
                        Log.i(TAG, "Step $stepNum (${step.action}) is already satisfied by screen state.")
                        taskCtx.completedSteps.add(step)
                        taskCtx.currentStepIndex++
                        onStepCompleted?.invoke(stepNum, taskCtx.totalSteps, step)
                        continue
                    }

                    // 3. Execute step with ActionExecutor
                    val result = actionExecutor.executeStep(step)

                    // 4. Verification Check
                    if (result.success && result.verified) {
                        taskCtx.completedSteps.add(step)
                        taskCtx.currentStepIndex++
                        taskCtx.retryCount = 0
                        onStepCompleted?.invoke(stepNum, taskCtx.totalSteps, step)
                        taskCtx.history.add("Step $stepNum (${step.action}): Success")
                    } else {
                        Log.w(TAG, "Step $stepNum failed verification: ${result.reason}. Attempting local recovery...")
                        taskCtx.retryCount++

                        var recovered = false
                        if (taskCtx.retryCount <= RecoveryEngine.MAX_LOCAL_RETRIES) {
                            val recResult = recoveryEngine.attemptRecovery(step, taskCtx.retryCount, screenObserver.getActiveNodes())
                            if (recResult.success && recResult.verified) {
                                taskCtx.completedSteps.add(step)
                                taskCtx.currentStepIndex++
                                taskCtx.retryCount = 0
                                recovered = true
                                onStepCompleted?.invoke(stepNum, taskCtx.totalSteps, step)
                            }
                        }

                        if (!recovered) {
                            // Local recovery failed -> Targeted Replanning
                            Log.i(TAG, "Local recovery failed for step $stepNum. Requesting targeted replan...")
                            onStatusUpdate?.invoke("REPLANNING", "Replanning step $stepNum...")
                            val newSteps = replanHandler?.invoke(taskCtx, step, result.reason)

                            if (!newSteps.isNullOrEmpty()) {
                                Log.i(TAG, "Targeted replan yielded ${newSteps.size} replacement steps.")
                                taskCtx.steps.removeAt(taskCtx.currentStepIndex)
                                taskCtx.steps.addAll(taskCtx.currentStepIndex, newSteps)
                                taskCtx.retryCount = 0
                                continue
                            } else {
                                // Task cannot proceed
                                taskCtx.status = "FAILED"
                                taskCtx.failedSteps.add(step)
                                val errMsg = "Step $stepNum (${step.action}) could not be completed."
                                voiceEngine?.speak(errMsg)
                                onTaskFinished?.invoke(false, errMsg)
                                return@launch
                            }
                        }
                    }
                }

                // Final task completion verification
                if (taskCtx.isComplete()) {
                    taskCtx.status = "COMPLETED"
                    val finishMsg = "Task completed successfully, Sir."
                    voiceEngine?.speak(finishMsg)
                    onTaskFinished?.invoke(true, finishMsg)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Task orchestrator exception: ", e)
                taskCtx.status = "FAILED"
                val failMsg = "Task halted due to an unexpected exception, Sir."
                voiceEngine?.speak(failMsg)
                onTaskFinished?.invoke(false, failMsg)
            }
        }
    }

    /**
     * User Interruption: Immediately cancels execution and releases all locks within <200ms.
     */
    fun cancelExecution() {
        if (activeJob?.isActive == true) {
            activeJob?.cancel()
            activeJob = null
            currentTaskContext?.status = "CANCELLED"
            voiceEngine?.speak("Execution paused, Sir.")
            onStatusUpdate?.invoke("CANCELLED", "Execution stopped by user.")
            onTaskFinished?.invoke(false, "Cancelled by user.")
            Log.i(TAG, "Task execution immediately cancelled by user interruption.")
        }
    }

    fun isRunning(): Boolean = activeJob?.isActive == true
}
