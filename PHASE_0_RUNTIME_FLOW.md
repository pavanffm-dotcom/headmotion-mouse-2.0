# Phase 0 Forensic Report: Runtime Architecture & Data Flow

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Classification Mode:** FORENSIC AUDIT ONLY (Zero Production Source Code Modifications)  
**Date:** 2026-09-17  

---

## 1. End-to-End Runtime Execution Call Graph

Below is the verified end-to-end trace from user utterance to execution, verification, and next-step decision.

```
USER SPOKEN / TEXT INPUT
  │
  ▼
[1] JarvisBackgroundVoiceService.onResults(results: Bundle?)
    ├── File: app/src/main/java/com/assistive/headmouse/agent/jarvis/service/JarvisBackgroundVoiceService.kt:170
    ├── Thread: Main Thread (Android UI Looper)
    └── Calls: handleSpokenCommand(spokenText: String) (Line 275)
          │
          ▼
[2] JarvisBackgroundVoiceService.handleSpokenCommand(prompt: String)
    ├── Checks Emergency Interruption: "stop", "cancel", "ruk ja" (Line 280)
    │     └── If matched: calls missionExecutorProvider()?.stopMission() + tts.stop() -> returns immediately
    ├── Checks Standby / Sleep: "goodbye", "so jao", "standby" (Line 290)
    └── Normal / Mission Dispatch: Launches Coroutine on serviceScope (Dispatchers.Main) (Line 303)
          │
          ▼
[3] JarvisBrain.processUserPrompt(cleanPrompt, apiKey, isCloudEnabled, provider, modelName, customBaseUrl)
    ├── File: app/src/main/java/com/assistive/headmouse/agent/jarvis/JarvisBrain.kt:92
    ├── Context: withContext(Dispatchers.IO) (Line 99)
    ├── [Step 3A] evaluateOfflineCommands(cleanPrompt, lowerPrompt) (Line 104)
    │     ├── Checks isCompoundTask(raw, lower) (Line 217 -> 317)
    │     │     └── If true: Returns JarvisResponse(actionType = ActionType.START_MISSION, actionData = raw)
    │     ├── Checks Direct App Launching: "open youtube", "open settings" (Line 327, 345)
    │     ├── Checks Single Element Click: "Shorts mein click karna hai", "click search" (Line 371-417)
    │     └── Checks Home, Back, Recents, Files, Memory (Lines 231-284, 500-547)
    ├── [Step 3B] Cloud API Fallback (Lines 111-151)
    │     └── Calls callCustomOpenRouterApi / callGeminiApi / callOpenAiApi
    └── [Step 3C] Conversational Fallback: evaluateConversationalFallback (Line 154)
          │
          ▼ (Returns JarvisResponse to JarvisBackgroundVoiceService)
[4] JarvisBackgroundVoiceService - Post Processing
    ├── Context: withContext(Dispatchers.Main) (Line 319)
    └── Checks response.actionType == ActionType.START_MISSION (Line 324)
          │
          ▼ Calls missionExecutorProvider()?.startMission(...) (Line 328)
[5] JarvisMissionExecutor.startMission(missionGoal, apiKey, isCloudEnabled, provider, modelName, customBaseUrl)
    ├── File: app/src/main/java/com/assistive/headmouse/agent/jarvis/JarvisMissionExecutor.kt:136
    ├── Cancels prior active mission: stopMission() (Line 144)
    ├── Ignores passed-in provider, apiKey, modelName, and baseUrl!
    └── Calls: agentOrchestrator.startMission(missionGoal) (Line 151)
          │
          ▼
[6] AgentOrchestrator.startMission(userGoal: String)
    ├── File: app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/AgentOrchestrator.kt:61
    ├── Thread: Main -> orchestratorScope.launch (Line 75)
    ├── Subsystem Resets:
    │     ├── timeline.clear() (Line 64)
    │     ├── missionMemory.clear() (Line 65)
    │     ├── loopGuard.startMission() (Line 66)
    │     ├── navigationTracker.reset() (Line 67)
    │     └── goalManager.initializeGoal(userGoal) (Line 68)
    └── Spawns Mission Loop Coroutine: executeMissionLoop() (Line 77)
          │
          ▼
[7] AgentOrchestrator.executeMissionLoop()
    ├── Context: withContext(Dispatchers.Default) (Line 96)
    └── Outer Loop: while (isActive && !goalManager.isAllComplete()) (Line 97)
          │
          ├── [Step 7A] Observe Screen:
          │     val initialScreen = screenContextManager.refreshLiveScreen() (Line 101)
          │     └── Calls: screenObserver.getLiveScreenState() -> HeadMouseAccessibilityService.refreshSpatialCacheSync()
          │
          ├── [Step 7B] Handle Blocking Dialogs:
          │     DialogHandler.attemptSafeDismissal(initialScreen.nodes) (Line 107)
          │
          ├── [Step 7C] Initial Plan Generation:
          │     if (objective.steps.isEmpty()) {
          │         plannedSteps = dynamicPlanner.createPlanForObjective(objective, screenState, breadcrumbs) (Line 116)
          │         objective.steps.addAll(plannedSteps)
          │     }
          │
          └── [Step 7D] Sequential Step Execution Loop:
                while (isActive && objective.currentStepIndex < objective.steps.size) (Line 126)
                  │
                  ├── [7D.1] LoopGuard Check:
                  │     loopGuard.checkPreExecution(step.action, step.target?.value, latestDiff) (Line 131)
                  │
                  ├── [7D.2] SafetyGate Check:
                  │     safetyGate.checkSafety(step) (Line 175)
                  │
                  ├── [7D.3] Physical Action Execution:
                  │     val result = actionExecutor.executeStep(step) (Line 192)
                  │     └── (See Action Execution Pipeline below)
                  │
                  ├── [7D.4] UI Settling:
                  │     val postScreen = SmartWaiter.waitForScreenChange(screenObserver, previousState, 2500L) (Line 196)
                  │
                  ├── [7D.5] Verification:
                  │     val verified = result.success && result.verified (Line 202)
                  │
                  └── [7D.6] NEXT DECISION LOGIC (CRITICAL FAILURE POINT):
                        ├── IF verified:
                        │     objective.currentStepIndex++  <-- [STATIC ADVANCEMENT, NO AI QUERY]
                        │     stateMachine.transitionTo(ACTION_SUCCESS)
                        │     (Next iteration picks objective.steps[currentStepIndex] from the OLD static list!)
                        │
                        └── IF NOT verified:
                              stateMachine.transitionTo(ACTION_FAILED)
                              val replacementSteps = replanningEngine.replan(...) (Line 219)
                              ├── If replacementSteps != null:
                              │     objective.steps.addAll(objective.currentStepIndex, replacementSteps)
                              └── If replacementSteps == null:
                                    goalManager.markCurrentObjectiveFailed(result.reason)
                                    Aborts with: "Objective could not be achieved" (Line 245)
```

---

## 2. Action Execution Sub-Pipeline (`ActionExecutor`)

```
[ActionExecutor.executeStep(step)]
  │ (Mutex: executionMutex.withLock) (Line 38)
  │
  ├── Idempotency Check:
  │     screenObserver.observeScreen() -> isActionIdempotent(step, observation) (Line 42)
  │
  ├── Case 1: AutonomousActionType.OPEN_APP (Line 56)
  │     ├── AppLauncher.launchApp(context, targetApp)
  │     ├── delay(step.waitAfterMs)
  │     └── screenObserver.verifyState(step, newObservation)
  │
  ├── Case 2: AutonomousActionType.TAP (Line 75)
  │     ├── TargetResolver.resolveTarget(step.target, activeNodes) (Line 77)
  │     │     └── 7-Tier Resolution: Node ID -> Text -> contentDescription -> resourceId -> Semantic Role -> Fuzzy -> Vision Fallback
  │     ├── If null: delay(600ms) and retry once (Line 80)
  │     ├── If still null: Returns ActionResult(success=false, reason="TARGET_NOT_FOUND", verified=false) (Line 89)
  │     ├── Cursor Positioning: HeadMouseAccessibilityService.instance?.updateCursorPositionExplicit(x, y) (Line 100)
  │     ├── Dispatch Click:
  │     │     ├── If matchedNode != null: service?.clickScreenNode(matchedNode) (Line 105)
  │     │     └── Else: service?.clickAt(x, y) (Line 107)
  │     ├── delay(step.waitAfterMs) (Line 115)
  │     └── Verify: screenObserver.verifyState(step, newObservation) (Line 118)
  │
  └── Case 3: AutonomousActionType.TYPE_TEXT / SCROLL / NAVIGATION (Lines 130-220)
```

---

## 3. Ownership Analysis: Who Owns What?

| Domain Component | Nominal / Apparent Owner | ACTUAL Runtime Owner in Code | Code Evidence (File & Line) |
|---|---|---|---|
| **Mission Lifecycle** | `JarvisMissionExecutor` | `AgentOrchestrator` (`activeMissionJob`) | `JarvisMissionExecutor.kt:151` delegates entirely to `agentOrchestrator.startMission()` |
| **High-Level Goal** | `JarvisBrain` | `GoalManager` (`userGoal`, `objectives`) | `GoalManager.kt:33` stores `userGoal`, but splits it destructively into clause strings |
| **Current Step** | Dynamic Cognitive Brain | `MissionObjective.currentStepIndex` | `AgentOrchestrator.kt:126, 212` advances via simple counter `currentStepIndex++` |
| **Next Action Decision** | LLM Perceptual Loop | Static Pre-Generated List (`objective.steps`) OR Hardcoded Heuristic | `DynamicPlanner.kt:38-177` hardcodes Shorts, Settings, Search; `AgentOrchestrator.kt:127` reads pre-baked array |
| **Verification** | Vision / Screen Difference | Regex / Boolean Text contains | `ScreenObserver.kt:63-102` (`verifyState`) returns hardcoded `true` for TAP, SCROLL, BACK |
| **Replanning** | Cognitive LLM | Static Heuristic Table | `ReplanningEngine.kt:20-91` only has 4 static rules (Cancel, Scroll, Back, Recents) |
| **Mission Completion** | Visual Goal Verifier | Array Exhaustion | `GoalManager.kt:128` (`isAllComplete` checks if all pre-planned objective steps finished) |
| **Mission Failure** | Recovery Engine | Hardcoded String Emission | `AgentOrchestrator.kt:245` ("Objective could not be achieved") |

---

## 4. Duplicate Orchestration Systems Uncovered

```
                    ┌────────────────────────────────────────┐
                    │       JarvisMissionExecutor.kt         │
                    └───────────────────┬────────────────────┘
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
    ┌─────────────────────────┐                   ┌─────────────────────────┐
    │    AgentOrchestrator    │ [ACTIVE]          │    TaskOrchestrator     │ [ORPHANED / DUPLICATE]
    │ Location:               │                   │ Location:               │
    │ .../autonomous/         │                   │ .../action/             │
    │ AgentOrchestrator.kt    │                   │ TaskOrchestrator.kt     │
    │ Status: Called at       │                   │ Status: Instantiated in │
    │ Line 151                │                   │ JarvisMissionExecutor   │
    │ State: TaskStateMachine │                   │ Line 62, NEVER called!  │
    └─────────────────────────┘                   └─────────────────────────┘
```

1. **`AgentOrchestrator.kt`** (`com.assistive.headmouse.agent.jarvis.autonomous`):
   - Created in `JarvisMissionExecutor.kt:53`.
   - Wired to `TaskStateMachine`, `GoalManager`, `DynamicPlanner`, `LoopGuard`, `SafetyGate`, `ReplanningEngine`.
   - **This is the actual active orchestrator.**
2. **`TaskOrchestrator.kt`** (`com.assistive.headmouse.agent.jarvis.action`):
   - Created in `JarvisMissionExecutor.kt:62`.
   - Has its own complete, duplicate autonomous execution loop (`startPlanExecution`, `TaskContext`, `replanHandler`, `onStepCompleted`).
   - **`startPlanExecution` is NEVER called anywhere in the codebase.**
   - It sits idle in memory as dead code, creating architectural confusion.
3. **`JarvisBrain.planNextMissionStep`** (`com.assistive.headmouse.agent.jarvis`):
   - Vision-based single-step planner in `JarvisBrain.kt:1561`.
   - Has vision prompt and structured JSON schema for next action.
   - **NEVER called anywhere in the entire project.**
