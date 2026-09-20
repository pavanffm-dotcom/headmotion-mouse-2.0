package com.assistive.headmouse.agent.jarvis.autonomous

import android.content.Context
import android.util.Log
import com.assistive.headmouse.agent.jarvis.JarvisScreenCaptureManager
import com.assistive.headmouse.agent.jarvis.JarvisVoiceEngine
import com.assistive.headmouse.agent.jarvis.action.*
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelClient
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelClientFactory
import com.assistive.headmouse.agent.jarvis.autonomous.model.ModelVisionCapabilities
import com.assistive.headmouse.agent.jarvis.autonomous.state.AutonomousMissionStatus
import com.assistive.headmouse.agent.jarvis.autonomous.state.MissionState
import com.assistive.headmouse.agent.jarvis.autonomous.state.Subgoal
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.jarvis.autonomous.tools.CanonicalTools
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDispatcher
import com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationEngine
import com.assistive.headmouse.preferences.AppSettings
import com.assistive.headmouse.service.HeadMouseAccessibilityService
import com.assistive.headmouse.agent.jarvis.memory.JarvisMemoryHub
import com.assistive.headmouse.agent.jarvis.memory.MissionOutcome
import kotlinx.coroutines.*

/**
 * Central Autonomous Agent Orchestrator (Phase 2 Closed-Loop Execution Owner).
 *
 * Implements the continuous perceptual closed loop:
 *   OBSERVE -> DECIDE NEXT SINGLE ACTION -> ACT -> WAIT/SETTLE -> OBSERVE -> VERIFY -> UPDATE -> DECIDE AGAIN -> COMPLETE
 *
 * Guaranteed Invariants:
 * 1. Exactly ONE authoritative [MissionState] per mission with immutable [originalUserGoal].
 * 2. NO blind multi-action script execution. Every physical action is followed by empirical observation.
 * 3. Structured telemetry logging without sensitive API credentials.
 */
class AgentOrchestrator(
    private val context: Context,
    private val screenObserver: ScreenObserver,
    private val actionExecutor: ActionExecutor,
    private val voiceEngine: JarvisVoiceEngine?,
    private val appSettings: AppSettings,
    @Deprecated("Autonomous missions route through ModelClient; JarvisBrain is preserved for conversational Q&A.")
    private val jarvisBrain: com.assistive.headmouse.agent.jarvis.JarvisBrain? = null
) {

    companion object {
        private const val TAG = "AgentOrchestrator"
    }

    private val orchestratorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeMissionJob: Job? = null

    // Modular subsystem instances
    val stateMachine = TaskStateMachine { oldState, newState, reason ->
        timeline.record("STATE_TRANSITION", "$oldState -> $newState ${if (reason != null) "[$reason]" else ""}")
        onStateChanged?.invoke(newState, reason)
    }

    val goalManager = GoalManager()
    val screenContextManager = ScreenContextManager(screenObserver)
    val navigationTracker = NavigationTracker()
    val missionMemory = MissionMemory()
    val memoryHub: JarvisMemoryHub? = context?.let { JarvisMemoryHub.getInstance(it) }
    val loopGuard = LoopGuard()
    val safetyGate = SafetyGate(isEnabled = true)
    val timeline = MissionTimeline()
    val capabilityManager = ToolCapabilityManager(context)
    val toolRegistry = ToolRegistry(capabilityManager)
    val dynamicPlanner = DynamicPlanner(toolRegistry, jarvisBrain, appSettings)
    val replanningEngine = ReplanningEngine()
    val recoveryEngine = RecoveryEngine(screenObserver, actionExecutor)
    val modelRouter = ModelFallbackRouter(appSettings, timeline)

    // Phase 2 Closed-Loop Core Components
    val toolDispatcher = ToolDispatcher(context) { HeadMouseAccessibilityService.instance }
    val verificationEngine = VerificationEngine()
    var modelClient: ModelClient = ModelClientFactory.create(appSettings)

    // Authoritative Mission State
    var currentMissionState: MissionState? = null
        private set

    // Callbacks for UI updates
    var onStatusUpdate: ((String, String) -> Unit)? = null
    var onStateChanged: ((TaskState, String?) -> Unit)? = null
    var onStepExecuted: ((ActionStep, ActionResult) -> Unit)? = null
    var onMissionFinished: ((Boolean, String) -> Unit)? = null

    /**
     * Starts an autonomous mission from a user goal string.
     */
    fun startMission(userGoal: String) {
        cancelMission() // Abort any ongoing mission safely

        // Dynamically refresh modelClient from latest AppSettings
        if (appSettings != null) {
            modelClient = ModelClientFactory.create(appSettings)
        }

        timeline.clear()
        missionMemory.clear()
        loopGuard.startMission()
        navigationTracker.reset()
        goalManager.initializeGoal(userGoal)

        // Initialize the authoritative MissionState (originalUserGoal is IMMUTABLE)
        val mission = MissionState(originalUserGoal = userGoal)
        goalManager.allObjectives.forEach { obj ->
            mission.remainingObjectives.add(Subgoal(description = obj.title))
        }
        mission.currentSubgoal = if (mission.remainingObjectives.isNotEmpty()) {
            mission.remainingObjectives.removeAt(0)
        } else null
        currentMissionState = mission

        stateMachine.transitionTo(TaskState.MISSION_STARTED, "New mission initialized: \"$userGoal\"")
        timeline.record("MISSION_STARTED", userGoal)
        onStatusUpdate?.invoke("PLANNING", "Formulating closed-loop execution for: \"$userGoal\"")
        voiceEngine?.speak("Autonomous mission engaged, Sir. $userGoal")

        activeMissionJob = orchestratorScope.launch {
            try {
                executeMissionLoop()
            } catch (e: CancellationException) {
                timeline.record("MISSION_CANCELLED", "Interrupted by user")
                stateMachine.transitionTo(TaskState.CANCELLED, "Cancelled by user")
                currentMissionState?.status = AutonomousMissionStatus.CANCELLED
                archiveCurrentMission(MissionOutcome.CANCELLED, "Interrupted by user")
                onMissionFinished?.invoke(false, "Mission cancelled by user, Sir.")
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled exception in autonomous mission: ", e)
                timeline.record("MISSION_FAILED", "Exception: ${e.message}")
                stateMachine.transitionTo(TaskState.FAILED, e.message)
                currentMissionState?.status = AutonomousMissionStatus.FAILED
                archiveCurrentMission(MissionOutcome.FAILED, e.message)
                voiceEngine?.speak("Mission halted due to an unexpected error, Sir.")
                onMissionFinished?.invoke(false, "Error: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Core continuous perceptual closed loop:
     * OBSERVE → DECIDE SINGLE ACTION → ACT → WAIT/SETTLE → OBSERVE → VERIFY → UPDATE → DECIDE AGAIN → COMPLETE
     */
    private suspend fun executeMissionLoop() = withContext(Dispatchers.Default) {
        val mission = currentMissionState ?: return@withContext
        mission.status = AutonomousMissionStatus.EXECUTING

        Log.i(TAG, "Starting continuous closed-loop execution. Mission ID: ${mission.missionId}")
        Log.i(TAG, "[CANONICAL_AI_ROUTE] Autonomous mission engaged: Mission -> DynamicPlanner -> ModelClient (${modelClient.javaClass.simpleName}) -> ToolDispatcher -> Android")

        // Phase 5 & 11: Track consecutive UNCHANGED states and local recovery attempts
        var consecutiveUnchanged = 0
        var consecutiveRecoveryAttempts = 0

        while (isActive && mission.status != AutonomousMissionStatus.COMPLETED && mission.status != AutonomousMissionStatus.FAILED) {
            // 1. OBSERVE: Capture live WorldState + Optional Multimodal Visual Screen
            stateMachine.transitionTo(TaskState.OBSERVING, "Observing screen for ${mission.currentSubgoal?.description ?: mission.originalUserGoal}")

            val activeModelName = appSettings.getActiveCustomModel()?.modelId?.takeIf { it.isNotBlank() } ?: appSettings.aiModelName
            val isVisionModel = ModelVisionCapabilities.supportsVision(appSettings.aiProvider, activeModelName)
            val captureMgr = JarvisScreenCaptureManager.instance
            val isCaptureActive = captureMgr?.isCapturing == true

            var screenshotBase64: String? = null
            if (isVisionModel && isCaptureActive) {
                Log.i(TAG, "[CAPTURE_STARTED] Capturing screen for vision model: $activeModelName")
                timeline.record("CAPTURE_STARTED", "Capturing live screen for $activeModelName")
                try {
                    screenshotBase64 = captureMgr?.captureScreenshotBase64()
                    if (!screenshotBase64.isNullOrBlank()) {
                        Log.i(TAG, "[CAPTURE_SUCCESS] Captured screenshot (${screenshotBase64.length} chars base64)")
                        timeline.record("CAPTURE_SUCCESS", "Captured frame (${screenshotBase64.length} chars)")
                    } else {
                        Log.w(TAG, "[CAPTURE_FAILED] Screenshot capture returned null")
                        timeline.record("CAPTURE_FAILED", "Frame acquisition returned null")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[CAPTURE_FAILED] Screenshot capture threw exception: ${e.message}")
                    timeline.record("CAPTURE_FAILED", "Exception: ${e.message}")
                }
            } else {
                val omitReason = if (!isVisionModel) "Model '$activeModelName' does not support vision" else "MediaProjection capture not active"
                Log.i(TAG, "[SCREENSHOT_OMITTED] $omitReason")
                timeline.record("SCREENSHOT_OMITTED", omitReason)
            }

            var liveWorldState = screenObserver.observeWorldState()
            if (!screenshotBase64.isNullOrBlank()) {
                liveWorldState = liveWorldState.copy(screenshotBase64 = screenshotBase64)
            }
            mission.currentObservation = liveWorldState
            screenContextManager.refreshLiveScreen()
            if (!screenshotBase64.isNullOrBlank()) {
                screenContextManager.updateState(screenContextManager.latestScreenState, screenshotBase64)
            }
            navigationTracker.recordNavigation(liveWorldState.foregroundPackage, liveWorldState.foregroundActivity)

            // Auto-handle blocking dialog interruptions
            if (liveWorldState.isDialogBlocking || DialogHandler.isDialogPresent(screenObserver.getActiveNodes())) {
                timeline.record("DIALOG_DETECTED", "Dismissing interruption dialog")
                if (DialogHandler.attemptSafeDismissal(screenObserver.getActiveNodes())) {
                    SmartWaiter.waitForIdle(400L)
                    continue
                }
            }

            // 2. Budget & Loop Watchdog Checks
            if (mission.isBudgetExceeded()) {
                val msg = "Mission budget exceeded (${mission.elapsedDurationMs}ms, ${mission.modelDecisionCount} decisions)."
                timeline.record("BUDGET_EXCEEDED", msg)
                stateMachine.transitionTo(TaskState.FAILED, msg)
                mission.status = AutonomousMissionStatus.FAILED
                mission.statusMessage = msg
                voiceEngine?.speak("Mission budget limit reached, Sir.")
                onMissionFinished?.invoke(false, msg)
                return@withContext
            }

            if (mission.isUnrecoverable()) {
                val msg = "Execution stopped: ${mission.consecutiveFailedActions} consecutive unrecoverable action failures."
                timeline.record("LOOP_GUARD", msg)
                stateMachine.transitionTo(TaskState.FAILED, msg)
                mission.status = AutonomousMissionStatus.FAILED
                mission.statusMessage = msg
                voiceEngine?.speak("Execution halted due to repeated action failures, Sir.")
                onMissionFinished?.invoke(false, msg)
                return@withContext
            }

            // 3. DECIDE NEXT SINGLE ATOMIC ACTION
            val activeGoalDesc = mission.currentSubgoal?.description ?: mission.originalUserGoal
            stateMachine.transitionTo(TaskState.PLANNING, "Deciding next single action for: $activeGoalDesc")
            mission.modelDecisionCount++

            val screenFrame = liveWorldState.screenshotBase64
            if (!screenFrame.isNullOrBlank()) {
                Log.i(TAG, "[SCREENSHOT_ATTACHED_TO_MODEL_REQUEST] Step #${mission.modelDecisionCount}: Attaching visual frame (${screenFrame.length} chars)")
                timeline.record("SCREENSHOT_ATTACHED_TO_MODEL_REQUEST", "Attached visual frame to model request")
            } else {
                Log.i(TAG, "[SCREENSHOT_OMITTED] Step #${mission.modelDecisionCount}: Model request without visual frame")
            }

            val memoryContext = memoryHub?.buildContextualMemoryInjection(
                userGoal = mission.originalUserGoal,
                activePackage = liveWorldState.foregroundPackage
            )

            val toolCall = dynamicPlanner.decideNextAction(
                missionState = mission,
                modelClient = modelClient,
                memoryContext = memoryContext
            )
            mission.lastAction = toolCall

            // Structured logging (Never logs API keys)
            Log.i(TAG, "[MISSION_STEP] Mission=${mission.missionId} Step=${mission.modelDecisionCount} Tool=${toolCall.name} Args=${toolCall.arguments}")

            // Check if model explicitly signaled task completion
            if (toolCall.name == CanonicalTools.FINISH_TASK) {
                val isSuccess = (toolCall.arguments["success"] as? Boolean) ?: true
                val summary = toolCall.arguments["spoken_summary"]?.toString() ?: "Task completed, Sir."
                timeline.record("TASK_FINISHED", summary)
                mission.status = if (isSuccess) AutonomousMissionStatus.COMPLETED else AutonomousMissionStatus.FAILED
                stateMachine.transitionTo(if (isSuccess) TaskState.COMPLETED else TaskState.FAILED, summary)
                archiveCurrentMission(if (isSuccess) MissionOutcome.SUCCESS else MissionOutcome.FAILED, summary)
                voiceEngine?.speak(summary)
                onMissionFinished?.invoke(isSuccess, summary)
                return@withContext
            }

            // 4. Convert ToolCall to ActionStep for SafetyGate & UI listeners
            val stepId = mission.modelDecisionCount
            val actionStep = convertToolCallToActionStep(toolCall, stepId)

            // Safety Gate Check (Phase 15 Action Risk & Confirmation)
            var isApproved = false
            val approvedDeferred = CompletableDeferred<Boolean>()
            val safe = safetyGate.checkSafety(toolCall) { decision ->
                approvedDeferred.complete(decision)
            }

            if (!safe) {
                stateMachine.transitionTo(TaskState.WAITING_CONFIRMATION, "Awaiting approval for ${toolCall.name}")
                isApproved = approvedDeferred.await()
                if (!isApproved) {
                    timeline.record("SAFETY_GATE", "Action ${toolCall.name} blocked or denied by user. Replanning.")
                    continue
                }
            }

            stateMachine.transitionTo(TaskState.ACTION_SELECTED, "Action #$stepId: ${toolCall.name}")
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke("EXECUTING", "Step #$stepId: ${toolCall.name}")
                if (!toolCall.thought.isNullOrBlank()) {
                    voiceEngine?.speak(toolCall.thought)
                }
            }

            // 5. ACT: Execute Single Atomic Tool via ToolDispatcher
            stateMachine.transitionTo(TaskState.ACTION_EXECUTING, "Executing ${toolCall.name}")
            val preObservation = liveWorldState
            captureMgr?.invalidateCache()
            val executionResult = toolDispatcher.dispatch(toolCall, preObservation)
            mission.lastActionResult = executionResult
            timeline.record("ACTION_EXECUTED", "${toolCall.name} -> success: ${executionResult.success}")

            // 6. WAIT/SETTLE: Phase 5 Event-Driven UI Settling (SmartWaiter.waitForUiSettle)
            timeline.record("WAITING_SETTLING", "Settling UI post-${toolCall.name}")
            val expectedPkg = toolCall.arguments["package_or_name"]?.toString()
            var postObservation = SmartWaiter.waitForUiSettle(
                screenObserver = screenObserver,
                preState = preObservation,
                expectedPkg = expectedPkg,
                timeoutMs = 2500L
            )
            // Acquire fresh settled post-action screenshot if vision is active
            if (isVisionModel && isCaptureActive) {
                Log.i(TAG, "[CAPTURE_STARTED] Capturing post-action settled screen for $activeModelName")
                try {
                    val postBase64 = captureMgr?.captureScreenshotBase64(maxWaitMs = 300L)
                    if (!postBase64.isNullOrBlank()) {
                        Log.i(TAG, "[CAPTURE_SUCCESS] Post-action screenshot captured (${postBase64.length} chars)")
                        postObservation = postObservation.copy(screenshotBase64 = postBase64)
                    } else {
                        Log.w(TAG, "[CAPTURE_FAILED] Post-action screenshot capture returned null")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[CAPTURE_FAILED] Post-action screenshot capture threw exception: ${e.message}")
                }
            }
            mission.previousObservation = preObservation
            mission.currentObservation = postObservation

            // 7. VERIFY: Empirical Validation via VerificationEngine → VerificationState
            stateMachine.transitionTo(TaskState.VERIFYING, "Verifying ${toolCall.name}")
            val verification = verificationEngine.verify(toolCall, preObservation, postObservation)
            mission.verifiedState = verification.verified

            // Phase 5: Track consecutive UNCHANGED for forced recovery
            if (verification.state == com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState.UNCHANGED) {
                consecutiveUnchanged++
            } else {
                consecutiveUnchanged = 0
            }

            val verifiedActionResult = executionResult.copy(
                stateChanged = verification.stateChanged,
                focusChanged = verification.focusChanged,
                screenChanged = verification.screenChanged,
                verification = "[${verification.state}] ${verification.explanation}",
                verified = verification.verified
            )
            mission.lastActionResult = verifiedActionResult

            val uiActionResult = ActionResult(
                success = executionResult.success && verification.verified,
                action = toolCall.name,
                target = toolCall.arguments["label"]?.toString() ?: toolCall.arguments["package_or_name"]?.toString() ?: toolCall.arguments["text"]?.toString(),
                reason = "[${verification.state}] ${verification.explanation}",
                verified = verification.verified,
                durationMs = executionResult.durationMs
            )
            missionMemory.recordExecution(actionStep, uiActionResult)
            if (liveWorldState.foregroundPackage.isNotBlank() && liveWorldState.foregroundPackage != "unknown") {
                missionMemory.recordPackage(liveWorldState.foregroundPackage)
            }
            if (uiActionResult.success && uiActionResult.verified && actionStep.action == AutonomousActionType.TAP) {
                val target = actionStep.target?.value
                val pkg = liveWorldState.foregroundPackage
                if (!target.isNullOrBlank() && pkg.isNotBlank() && pkg != "unknown") {
                    val intent = if (target.contains("search", ignoreCase = true)) "search" else "navigation"
                    memoryHub?.appPatterns?.recordPatternOutcome(
                        packageName = pkg,
                        intentType = intent,
                        description = "Tap on $target",
                        action = "tap",
                        targetSelector = target,
                        succeeded = true
                    )
                }
            }

            withContext(Dispatchers.Main) {
                onStepExecuted?.invoke(actionStep, uiActionResult)
            }

            // 8. UPDATE MISSION STATE & SUBGOALS
            val isSuccess = verification.state == com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState.SUCCESS ||
                verification.state == com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState.PARTIAL

            if (isSuccess) {
                consecutiveRecoveryAttempts = 0
                consecutiveUnchanged = 0
                mission.recordActionSuccess()
                timeline.record("VERIFICATION_${verification.state}", "${toolCall.name}: ${verification.explanation}")
                stateMachine.transitionTo(TaskState.ACTION_SUCCESS, "${toolCall.name} verified [${verification.state}]")
                checkAndAdvanceSubgoals(mission, postObservation)
            } else {
                mission.recordActionFailure(verification.explanation)
                timeline.record("VERIFICATION_${verification.state}", "${toolCall.name}: ${verification.explanation}")
                stateMachine.transitionTo(TaskState.ACTION_FAILED, "${verification.state}: ${verification.explanation}")

                Log.w(TAG, "[REPLAN_TRIGGERED] Action '${toolCall.name}' failed verification [${verification.state}]: ${verification.explanation}")

                // Feed comprehensive context to ReplanningEngine
                val recoveryToolCall = replanningEngine.recoverFromState(
                    failedTool = toolCall,
                    verificationResult = verification,
                    currentWorld = postObservation,
                    previousWorld = preObservation,
                    goal = activeGoalDesc,
                    actionResult = uiActionResult,
                    recentHistory = missionMemory.actionHistory.takeLast(5).map { "${it.action}${it.target?.value?.let { v -> " on '$v'" } ?: ""}" },
                    consecutiveRecoveryAttempts = consecutiveRecoveryAttempts,
                    consecutiveUnchanged = consecutiveUnchanged
                )

                if (recoveryToolCall != null) {
                    consecutiveRecoveryAttempts++
                    Log.i(TAG, "[RECOVERY_ENGINE] Strategy selected: ${recoveryToolCall.name} (attempt $consecutiveRecoveryAttempts)")
                    timeline.record("RECOVERY_ENGINE", "Strategy: ${recoveryToolCall.name} - ${recoveryToolCall.thought}")

                    // Convert to ActionStep for LoopGuard & SafetyGate checks
                    val recoveryStep = convertToolCallToActionStep(recoveryToolCall, mission.modelDecisionCount + 1000 + consecutiveRecoveryAttempts)

                    val loopCheck = loopGuard.checkPreExecution(recoveryStep.action, recoveryStep.target?.value, null)
                    if (loopCheck is LoopCheckResult.Allowed) {
                        var isRecoveryApproved = false
                        val recoveryApprovedDeferred = CompletableDeferred<Boolean>()
                        val isSafe = safetyGate.checkSafety(recoveryToolCall) { decision ->
                            recoveryApprovedDeferred.complete(decision)
                        }

                        if (!isSafe) {
                            stateMachine.transitionTo(TaskState.WAITING_CONFIRMATION, "Awaiting approval for recovery ${recoveryToolCall.name}")
                            isRecoveryApproved = recoveryApprovedDeferred.await()
                        } else {
                            isRecoveryApproved = true
                        }

                        if (isRecoveryApproved) {
                            stateMachine.transitionTo(TaskState.ACTION_EXECUTING, "Recovery: ${recoveryToolCall.name}")
                            withContext(Dispatchers.Main) {
                                onStatusUpdate?.invoke("RECOVERING", "Recovery: ${recoveryToolCall.name}")
                                if (!recoveryToolCall.thought.isNullOrBlank()) {
                                    voiceEngine?.speak(recoveryToolCall.thought)
                                }
                            }

                            // ACT: Execute recovery tool
                            val recoveryExecResult = toolDispatcher.dispatch(recoveryToolCall, postObservation)
                            timeline.record("RECOVERY_EXECUTED", "${recoveryToolCall.name} -> success: ${recoveryExecResult.success}")
                            Log.i(TAG, "[RECOVERY_EXECUTED] ${recoveryToolCall.name} -> success: ${recoveryExecResult.success}")

                            // WAIT: Settle UI post recovery
                            val settledAfterRecovery = SmartWaiter.waitForUiSettle(
                                screenObserver = screenObserver,
                                preState = postObservation,
                                timeoutMs = 1500L
                            )

                            // VERIFY: Recovery action outcome
                            val recoveryVerification = verificationEngine.verify(recoveryToolCall, postObservation, settledAfterRecovery)
                            Log.i(TAG, "[RECOVERY_VERIFIED] state: ${recoveryVerification.state} (${recoveryVerification.explanation})")

                            mission.previousObservation = postObservation
                            mission.currentObservation = settledAfterRecovery

                            val recoveryUiResult = ActionResult(
                                success = recoveryExecResult.success && recoveryVerification.verified,
                                action = recoveryToolCall.name,
                                target = recoveryToolCall.arguments["label"]?.toString() ?: recoveryToolCall.arguments["package_or_name"]?.toString(),
                                reason = "[${recoveryVerification.state}] ${recoveryVerification.explanation}",
                                verified = recoveryVerification.verified,
                                durationMs = recoveryExecResult.durationMs
                            )
                            missionMemory.recordExecution(recoveryStep, recoveryUiResult)

                            if (recoveryVerification.state == com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState.SUCCESS ||
                                recoveryVerification.state == com.assistive.headmouse.agent.jarvis.autonomous.verification.VerificationState.PARTIAL) {
                                Log.i(TAG, "[RECOVERY_SUCCESS] Recovery action restored valid state. Resuming mission.")
                                timeline.record("RECOVERY_SUCCESS", "Restored state via ${recoveryToolCall.name}")
                                consecutiveRecoveryAttempts = 0
                                consecutiveUnchanged = 0
                            }
                        } else {
                            timeline.record("SAFETY_GATE", "User denied recovery action ${recoveryStep.action}")
                        }
                    } else {
                        Log.w(TAG, "[LOOP_GUARD] Recovery action blocked by LoopGuard: $loopCheck")
                    }
                    continue
                } else {
                    Log.i(TAG, "[REPLAN_ESCALATED] No local recovery strategy — escalating to ModelClient on next iteration")
                    timeline.record("REPLAN_ESCALATED", "Escalating failure to model")
                    consecutiveRecoveryAttempts = 0
                }
            }

            // Small settle delay between perceptual loop iterations
            delay(250L)
        }

        // Check if mission fulfilled
        if (mission.status == AutonomousMissionStatus.COMPLETED || (mission.currentSubgoal == null && mission.remainingObjectives.isEmpty())) {
            mission.status = AutonomousMissionStatus.COMPLETED
            stateMachine.transitionTo(TaskState.COMPLETED, "All objectives fulfilled")
            timeline.record("MISSION_COMPLETED", mission.originalUserGoal)
            archiveCurrentMission(MissionOutcome.SUCCESS)
            val finishMsg = "Mission completed successfully, Sir."
            voiceEngine?.speak(finishMsg)
            onMissionFinished?.invoke(true, finishMsg)
        } else if (mission.status == AutonomousMissionStatus.FAILED) {
            archiveCurrentMission(MissionOutcome.FAILED, mission.lastActionResult?.errorMessage ?: "Mission failed")
        }
    }

    private fun archiveCurrentMission(outcome: MissionOutcome, failureReason: String? = null) {
        val mission = currentMissionState ?: return
        val summary = missionMemory.generateMissionSummary(mission.originalUserGoal)
        val duration = mission.elapsedDurationMs
        val packages = (missionMemory.getInvolvedPackages() + listOfNotNull(mission.currentObservation?.foregroundPackage?.takeIf { it != "unknown" })).distinct()
        memoryHub?.archiveMission(
            missionId = mission.missionId,
            goal = mission.originalUserGoal,
            outcome = outcome,
            stepsCount = mission.modelDecisionCount,
            durationMs = duration,
            summary = summary,
            failureReason = failureReason,
            involvedPackages = packages
        )
    }


    private fun checkAndAdvanceSubgoals(missionState: MissionState, worldState: WorldState) {
        val current = missionState.currentSubgoal ?: return
        val currentDesc = current.description.lowercase()
        val pkg = worldState.foregroundPackage.lowercase()

        val isAppLaunchSubgoal = (currentDesc.startsWith("open ") || currentDesc.contains("kholo") || currentDesc.startsWith("launch ")) &&
            (pkg.contains("youtube") || pkg.contains("vending") || pkg.contains("settings") || pkg.contains("whatsapp") || pkg.contains("chrome"))

        val isActionComplete = when {
            isAppLaunchSubgoal -> true
            currentDesc.contains("shorts") && worldState.nodes.any { it.label.contains("shorts", ignoreCase = true) } -> {
                missionState.lastAction?.arguments?.get("label")?.toString()?.contains("shorts", ignoreCase = true) == true
            }
            currentDesc.contains("search") && missionState.lastAction?.name == CanonicalTools.TYPE_TEXT -> true
            else -> false
        }

        if (isActionComplete) {
            missionState.advanceSubgoal()
            Log.i(TAG, "Subgoal completed: ${current.description}. Next subgoal: ${missionState.currentSubgoal?.description ?: "NONE"}")
            if (missionState.currentSubgoal == null && missionState.remainingObjectives.isEmpty()) {
                missionState.status = AutonomousMissionStatus.COMPLETED
            }
        }
    }


    private fun convertToolCallToActionStep(toolCall: ToolCall, id: Int): ActionStep {
        val actionType = when (toolCall.name) {
            CanonicalTools.LAUNCH_APP -> AutonomousActionType.OPEN_APP
            CanonicalTools.TAP_ELEMENT, CanonicalTools.TAP_COORDINATES -> AutonomousActionType.TAP
            CanonicalTools.TYPE_TEXT -> AutonomousActionType.TYPE_TEXT
            CanonicalTools.SCROLL -> {
                val dir = toolCall.arguments["direction"]?.toString()?.uppercase()
                if (dir == "UP") AutonomousActionType.SCROLL_UP else AutonomousActionType.SCROLL_DOWN
            }
            CanonicalTools.SWIPE -> AutonomousActionType.SWIPE
            CanonicalTools.LONG_PRESS -> AutonomousActionType.LONG_PRESS
            CanonicalTools.PRESS_NAVIGATION -> {
                when (toolCall.arguments["action"]?.toString()?.uppercase()) {
                    "HOME" -> AutonomousActionType.HOME
                    "RECENTS" -> AutonomousActionType.RECENTS
                    else -> AutonomousActionType.BACK
                }
            }
            else -> AutonomousActionType.TAP
        }

        val targetVal = toolCall.arguments["label"]?.toString()
            ?: toolCall.arguments["package_or_name"]?.toString()
            ?: toolCall.arguments["text"]?.toString()

        return ActionStep(
            id = id,
            action = actionType,
            target = targetVal?.let { ActionTarget(TargetType.TEXT, it) },
            text = toolCall.arguments["text"]?.toString(),
            spokenUpdate = toolCall.thought
        )
    }

    /**
     * User Interruption (R32): Safely halts execution within <200ms.
     */
    fun cancelMission() {
        if (activeMissionJob?.isActive == true) {
            activeMissionJob?.cancel()
            activeMissionJob = null
            currentMissionState?.status = AutonomousMissionStatus.CANCELLED
            goalManager.setStatus(MissionStatus.CANCELLED)
            stateMachine.transitionTo(TaskState.CANCELLED, "Interrupted by user command")
            timeline.record("MISSION_CANCELLED", "User interrupt")
            voiceEngine?.speak("Autonomous mission cancelled, Sir.")
            onStatusUpdate?.invoke("CANCELLED", "Mission cancelled by user.")
            onMissionFinished?.invoke(false, "Cancelled by user.")
            Log.i(TAG, "Mission cancelled immediately via user interrupt.")
        }
    }

    val isRunning: Boolean
        get() = activeMissionJob?.isActive == true
}
