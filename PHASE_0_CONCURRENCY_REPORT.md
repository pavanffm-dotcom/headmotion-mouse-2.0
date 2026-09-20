# Phase 0 Forensic Report: Concurrency, Reliability & Failure Modes

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Classification Mode:** FORENSIC AUDIT ONLY (Zero Production Source Code Modifications)  
**Date:** 2026-09-17  

---

## 1. Concurrency Architecture & Threading Map

| Subsystem / Class | Coroutine Scope / Thread | Dispatcher | Concurrency Primitive | Risk / Vulnerability Identified |
|---|---|---|---|---|
| **Voice Service** (`JarvisBackgroundVoiceService`) | `serviceScope` | `Dispatchers.Main` | `SupervisorJob()` | Speech recognition callbacks arrive on Main Looper. Heavy processing launched on Main before switching to IO. |
| **Cognitive Brain** (`JarvisBrain`) | Suspend functions | `Dispatchers.IO` | None | Synchronous blocking HTTP calls (`HttpURLConnection`) hold threads in the IO pool for up to 15 seconds. |
| **Autonomous Loop** (`AgentOrchestrator`) | `orchestratorScope` (`activeMissionJob`) | `Dispatchers.Default` | `SupervisorJob()` | Dispatches gestures via `mainHandler.post`. If `executeMissionLoop` is cancelled, coroutine aborts cleanly. |
| **Action Execution** (`ActionExecutor`) | Suspend functions | Inherited from caller | `Mutex()` (`executionMutex`) | Enforces a single-mission lock, but gesture dispatch is asynchronous: does not wait for `GestureResultCallback.onCompleted`. |
| **Accessibility Gestures** (`GestureDispatcher`) | Main UI Thread | Android Main Looper | Async callback | `service.dispatchGesture()` is non-blocking. `ActionExecutor` uses naive `delay(waitAfterMs)` instead of awaiting callback completion. |
| **Dwell Click Refractory** (`HeadMouseAccessibilityService`) | Main UI Thread | Android Main Looper | Timestamp comparison | `if (now - lastGlobalClickTimestamp < 600L) return`. Silently drops programmatic clicks executed within 600ms of any previous click! |

---

## 2. Forensic Trace of Common Error Strings

### A. "Could not be achieved"
- **Exact File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/AgentOrchestrator.kt`
- **Exact Function:** `executeMissionLoop()`
- **Exact Lines:** Lines 244–251
  ```kotlin
  } else if (objective.status == MissionStatus.FAILED) {
      val failMsg = "Objective \"${objective.title}\" could not be achieved."
      timeline.record("MISSION_FAILED", failMsg)
      stateMachine.transitionTo(TaskState.FAILED, failMsg)
      voiceEngine?.speak(failMsg)
      onMissionFinished?.invoke(false, failMsg)
      return@withContext
  }
  ```
- **Trigger Sequence:**
  1. `actionExecutor.executeStep(step)` fails to find target (`TARGET_NOT_FOUND`) or state verification returns `false`.
  2. `replanningEngine.replan(failedStep, ...)` is called (Line 219).
  3. Because `ReplanningEngine` only has 4 rigid heuristic rules and no cognitive LLM loop, it returns `null` (Line 89).
  4. `goalManager.markCurrentObjectiveFailed(result.reason)` sets `objective.status = MissionStatus.FAILED`.
  5. The orchestrator speaks `"Objective [Name] could not be achieved"` and terminates the mission immediately.

---

### B. "Mission failed" / `MISSION_FAILED`
- **Exact Files & Functions:**
  - `AgentOrchestrator.kt:85` (Unhandled exception catch block in `startMission`)
  - `JarvisMissionExecutor.kt:103` (`agentOrchestrator.onMissionFinished` maps `success=false` to `MissionStatus.FAILED`)
  - `TaskOrchestrator.kt:127` (Legacy orphaned callback)
- **Trigger Sequence:**
  - Triggered when any unhandled runtime exception propagates out of `executeMissionLoop()` or when an objective terminates in failure.

---

### C. "Network Error"
- **Exact File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/JarvisBrain.kt`
- **Exact Function:** `processUserPrompt()`
- **Exact Lines:** Lines 142–149
  ```kotlin
  } catch (e: Exception) {
      Log.w("JarvisBrain", "Cloud API call threw exception: ", e)
      val errResponse = JarvisResponse(
          spokenText = "Unable to connect to the cloud AI server, Sir. Please check your internet connection or API settings.",
          displayText = "Network Error: ${e.localizedMessage ?: "Connection failed"}"
      )
      recordTurn(cleanPrompt, errResponse)
      return@withContext errResponse
  }
  ```
- **Trigger Sequence:**
  - When `callCustomOpenRouterApi` hits `conn.readTimeout = 15000`, `HttpURLConnection` throws `java.net.SocketTimeoutException: Read timed out`.
  - The generic catch block masks the timeout as `"Network Error: Read timed out"`.

---

### D. "Timeout"
- **Exact File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/LoopGuard.kt`
- **Exact Function:** `checkPreExecution()`
- **Exact Lines:** Lines 59–62
  ```kotlin
  if (missionStartTime > 0 && elapsed > budget.maxMissionDurationMs) {
      Log.w(TAG, "Mission timeout exceeded ($elapsed ms > ${budget.maxMissionDurationMs} ms)")
      return LoopCheckResult.Timeout(elapsed, budget.maxMissionDurationMs)
  }
  ```
- **Trigger Sequence:**
  - Handled in `AgentOrchestrator.kt:153-159`:
    `voiceEngine?.speak("Mission timed out, Sir.")`
  - Triggered if a mission takes longer than 120,000 ms (2 minutes).

---

## 3. Race Conditions & Vulnerabilities

1. **Fire-and-Forget Touch Injection Race**:
   - `ActionExecutor.kt:105` calls `service?.clickScreenNode(matchedNode)`.
   - `clickScreenNode` posts to `mainHandler` -> `executeActionAt` -> `gestureDispatcher.dispatchSingleClick`.
   - Meanwhile, `ActionExecutor` immediately sleeps for `delay(step.waitAfterMs)` and verifies the screen.
   - If the main thread is busy rendering or the app animation takes longer than `waitAfterMs`, `ActionExecutor` checks the screen BEFORE the tap actually registered, leading to false-negative verification failures.
2. **Global Click Refractory Suppression**:
   - In `HeadMouseAccessibilityService.kt:892`:
     ```kotlin
     if (now - lastGlobalClickTimestamp < 600L) return
     ```
   - If a previous head-mouse dwell click or prior step occurred within 600ms, the autonomous action is **silently ignored with zero feedback**.
3. **Emergency Interruption Latency**:
   - `JarvisBackgroundVoiceService.handleSpokenCommand` checks `"stop"` or `"cancel"` synchronously in < 5ms.
   - Calls `missionExecutor.stopMission()`, which calls `activeMissionJob?.cancel()`.
   - Cancellation takes effect on the next suspend point (< 100ms).
   - This interruption path is clean and robust.
