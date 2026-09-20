# J.A.R.V.I.S. Autonomous Agent — Independent Red Team Review (Phase 7)
**Date:** September 18, 2026  
**Auditor:** Independent Red Team Review Board  
**Target Codebase:** HeadMotionMouse / J.A.R.V.I.S. Autonomous Mobile Agent  
**Audit Mode:** Adversarial Forensic Source & Test Code Inspection (Read-Only)

---

## Executive Summary

An independent, adversarial Red Team audit was conducted across the J.A.R.V.I.S. autonomous mobile GUI agent and assistive head-mouse engine in `HeadMotionMouse`. All Phase 0 through Phase 6 reports, current production source files (`app/src/main`), and test suites (`app/src/test`) were inspected.

### Core Audit Verdict: **PREVIOUS ACCEPTANCE CLAIMS ARE SUBSTANTIALLY COMPROMISED**
While unit test suites pass (67/67 tests passing) and the project builds cleanly, adversarial forensics reveal that previous phase reports made assertions based on **mocking artifacts, dead code, silent heuristic fallbacks, and fake test paths**.

### Critical Forensic Highlights:
1. **The LLM Never Executes Tools in Gemini Mode (Default Provider):** `GoogleGeminiClient` never emits function tool definitions in its payload and unconditionally hardcodes `decision = DecisionType.CONVERSATIONAL`. In `DynamicPlanner`, conversational decisions are treated as unhandled, silently routing **100% of all Gemini actions into local regex heuristics**.
2. **Critical Integration Tests Faked Model Decision-Making:** In both `JarvisClosedLoopAgentTest.kt` and `JarvisPhase6SystemIntegrationTest.kt`, the tests that allegedly proved "LLM closed-loop action decisions" deliberately injected a mock model that returned `Result.failure(RuntimeException("Use closed-loop heuristic"))`. The tests passed by exercising hardcoded fallback regexes, not AI model decisions.
3. **Configuration Crash on Clean Install:** `AppSettings.kt` defaults `aiModelName` to `"deepseek/deepseek-chat"` while defaulting `aiProvider` to `AiProvider.GEMINI`. `ModelClientFactory` combines these to query Google Generative Language API for model `deepseek/deepseek-chat`, resulting in immediate HTTP 404 errors.
4. **Multimodal Vision Pipeline is Dead Code:** `WorldState.screenshotBase64` is permanently `null`. `DynamicPlanner` never requests screenshots. `JarvisScreenCaptureManager` is completely decoupled and never called by the autonomous agent loop.
5. **Event-Driven UI Settling is an Illusion:** In `SmartWaiter.waitForUiSettle`, the coroutine flow subscribing to `AccessibilityEventBus` is instantiated as a local variable and never collected. The system settles purely via 150ms polling loops.
6. **Replanning Engine, Loop Guard, and Model Router are Dead Code:** `ReplanningEngine`, `LoopGuard.checkPreExecution`, and `ModelFallbackRouter` are instantiated in `AgentOrchestrator` but never called.
7. **Severe Cross-Contamination with Assistive Mouse:** Autonomous clicks call `clickAt(x, y)` which routes through `executeActionAt(x, y)`. Autonomous taps are contaminated by the user's active dwell click mode (`SCROLL_DOWN`, `LONG_PRESS`), click on the head-mouse floating dock if coordinates overlap, and are dropped if dispatched within 600ms of any head-mouse click.

---

## Adversarial Evaluation of the 14 Target Claims

| # | Claim | Red Team Verdict | Primary Defect & Evidence |
|---|---|---|---|
| 1 | Agent actually observes before deciding | **PARTIALLY VALID / DEFECTIVE** | If `AccessibilityService` is killed or detached, `ScreenObserver` supplies a dummy empty state; `DynamicPlanner` cold-app launch bypasses observation nodes. |
| 2 | Latest observation reaches next decision | **CONFIRMED WITH DEFECTS** | WorldState is updated post-action, but `SmartWaiter` settling is purely polling; complex UI state changes are ignored by heuristic fallback. |
| 3 | LLM actually decides the next action | **DISPROVED (CRITICAL)** | In Gemini mode, `GoogleGeminiClient` never declares tools and unconditionally returns `CONVERSATIONAL`, silently falling back to heuristics 100% of the time. Cold app launches fast-path without model. |
| 4 | Static templates do not hijack complex tasks | **DISPROVED (HIGH)** | `DynamicPlanner` contains 9 static templates; `checkAndAdvanceSubgoals` hardcodes 3 exact keywords (`shorts`, `search`, package aliases), breaking all other missions. |
| 5 | Tool execution returns accurate results | **DISPROVED (HIGH)** | `ToolDispatcher` returns `success = true` immediately before gesture execution; returns `success = true` when `service == null`; drops clicks during 600ms head-mouse refractory period. |
| 6 | Verification is real | **DISPROVED (HIGH)** | False positives: `computeScreenHash` hashes pixel bounds of all screen nodes; any background banner, blinking cursor, or progress bar triggers `screenChanged = true` and falsely confirms tap success. |
| 7 | Original goal is preserved | **CONFIRMED** | `MissionState.originalUserGoal` is immutable `val` and preserved in prompt builder. |
| 8 | Replanning uses current state | **DISPROVED (HIGH)** | `ReplanningEngine` is dead code (never called in `AgentOrchestrator`). Replan on failure only logs a timeline event and re-loops without recovery action. |
| 9 | Model selection matches runtime | **DISPROVED (CRITICAL)** | Out-of-the-box configuration mismatch: `AiProvider.GEMINI` paired with model name `deepseek/deepseek-chat` triggers HTTP 404 in `GoogleGeminiClient`. |
| 10 | API timeout handling is correct | **DISPROVED (MEDIUM)** | `OpenAiCompatibleClient` cannot distinguish read timeouts: `RESPONSE_TIMEOUT` is unreachable because `totalConnectTime` is 0L on exception, always reporting `SERVER_WAIT_TIMEOUT`. |
| 11 | Screenshot/vision claims are accurate | **DISPROVED (CRITICAL)** | Screenshot vision is 100% dead code. `screenshotBase64` is always null; `JarvisScreenCaptureManager` is never called by the agent loop. |
| 12 | Head-mouse functionality has no regression | **CONFIRMED WITH DEFECTS** | Head-mouse tracking algorithms are untouched, but shared runtime execution causes autonomous taps to mutate into dwell scrolls/long-presses or click the floating dock. |
| 13 | Safety remains active | **DISPROVED (HIGH)** | `SafetyGate.onRequestUserConfirmation` is `null` everywhere; destructive actions are silently denied into an infinite replanning loop. `ToolDispatcher` and `JarvisFileManager` bypass SafetyGate. |
| 14 | Concurrent missions cannot corrupt state | **DISPROVED (HIGH)** | `startMission()` cancels previous job cooperatively without awaiting termination; two coroutines run concurrently, corrupting timeline, memory, and dispatching overlapping gestures. |

---

## Forensic Role-by-Role Audit Reports

### Role 1: Architecture Reviewer
- **Architectural Orphanage:** The autonomous module in `app/src/main/.../autonomous` contains numerous instantiated subsystems that are never invoked:
  - `AgentOrchestrator.kt:64`: `val replanningEngine = ReplanningEngine()` is never called.
  - `AgentOrchestrator.kt:58`: `val loopGuard = LoopGuard()` is armed via `startMission()` on line 96, but `checkPreExecution()` is never called during execution.
  - `AgentOrchestrator.kt:66`: `val modelRouter = ModelFallbackRouter(...)` is never called.
  - `AgentOrchestrator.kt:35, 65`: `actionExecutor` and `recoveryEngine` are constructor arguments never used for action dispatch.
  - `JarvisMissionExecutor.kt:62`: `private val taskOrchestrator = TaskOrchestrator(...)` maintains active listeners but is never started by `startMission()`.
- **Duplicate State Drift:** `GoalManager` maintains `objectives` and `activeObjectiveIndex`. `MissionState` maintains `remainingObjectives` and `completedObjectives`. In `AgentOrchestrator.kt`, `checkAndAdvanceSubgoals` advances `missionState.advanceSubgoal()`, but NEVER advances `goalManager`. `GoalManager` remains permanently stuck on objective 0.
- **Provider Decoupling Bypass:** `JarvisBrain.kt:602-750` still maintains legacy direct `HttpURLConnection` routines (`callGeminiApi`, `callOpenAiApi`, `callCustomOpenRouterApi`), bypassing `ModelClient`.

### Role 2: Android Reviewer
- **AccessibilityNodeInfo Binder Leaks:**
  - `HeadMouseAccessibilityService.kt:1563-1574`: In `refreshSpatialCacheSync()`, `win.root` is called in a filter predicate (`win.root != null`), then in a find predicate (`win.root?.packageName`), and then in assignment (`targetWindow?.root`). Every call to `AccessibilityWindowInfo.getRoot()` allocates an unmanaged native Binder node. On Android < 14, un-recycled nodes leak and exhaust the IPC Binder pool.
  - `ToolDispatcher.kt:474`: In `injectTextDirect()`, `service?.rootInActiveWindow` is fetched and never recycled.
- **Asynchronous Gesture Blindness:** `ToolDispatcher.kt:125-130` dispatches gestures by calling `svc.clickAt()`. `svc.clickAt()` posts to the main Looper and calls `service.dispatchGesture()`. `ToolDispatcher` returns `ActionResult(success = true)` synchronously without waiting for `GestureResultCallback.onCompleted()` or `onCancelled()`.
- **Dwell Mode and Floating Dock Interception:** `HeadMouseAccessibilityService.executeActionAt()` checks if coordinates hit `dockView`. If an autonomous action clicks on the dock area, the assistive menu button is clicked instead of the app. Furthermore, `executeActionAt()` uses `when (activeMode)`: if the user left the head-mouse in `ClickMode.SCROLL_DOWN`, an autonomous click becomes a scroll.

### Role 3: AI/API Reviewer
- **Google Gemini Functional Blindness:** `GoogleGeminiClient.kt:809-861` does not attach `tools` declarations to the JSON payload and parses candidates solely into `DecisionType.CONVERSATIONAL`. In `DynamicPlanner.kt:281`, conversational decisions fall through to `decideNextActionHeuristic`. Therefore, all missions running under the default Gemini provider run purely on local regex heuristics.
- **Configuration Crash:** Default `aiProvider` is `AiProvider.GEMINI` while default `aiModelName` is `"deepseek/deepseek-chat"`. When `ModelClientFactory.create()` runs, it sends requests to `https://generativelanguage.googleapis.com/v1beta/models/deepseek/deepseek-chat:generateContent`, returning HTTP 404.
- **Socket Timeout Classification Defect:** In `OpenAiCompatibleClient.kt:488`, `isConnect` checks `totalConnectTime == 0L`. When `transport.execute` throws a `SocketTimeoutException`, `totalConnectTime` has not yet been incremented. It is always 0L, causing read timeouts to be misclassified as `SERVER_WAIT_TIMEOUT`.

### Role 4: Security Reviewer
- **Safety Gate Disconnected (Denial of Service Loop):** `SafetyGate.kt:15` defines `var onRequestUserConfirmation = null`. No UI dialog or handler is ever registered. When `classifySafety()` detects `DESTRUCTIVE` keywords (`delete`, `uninstall`, `pay`), it returns false. In `AgentOrchestrator.kt:224-230`, this triggers an unhandled replan loop until budget exhaustion.
- **API Key Leakage via URL Parameter:** `JarvisBrain.kt:604` appends the Gemini API key as a plaintext URL query parameter (`?key=$apiKey`). URL parameters are logged in Android logcat during network exception stack traces.
- **Unrestricted Recursive Storage Deletion:** `JarvisFileManager.kt:149-157` implements `deleteFileOrDirectory()` using `File.deleteRecursively()`. If invoked with an empty string or `"root"`, it resolves to `/storage/emulated/0` and attempts to delete user data without safety gate validation.

### Role 5: Runtime/Test Reviewer
- **Mocking Leaks in Core Integration Tests:**
  - `JarvisPhase6SystemIntegrationTest.kt:115` & `JarvisClosedLoopAgentTest.kt:71`: Both test suites mock `ModelClient` to throw `RuntimeException("Use closed-loop heuristic")`. The test assertions verify the heuristic fallback, NOT autonomous model reasoning.
  - `JarvisPhase6SystemIntegrationTest.kt:204`: In `testScenario01_SimpleSingleStep_LaunchesTargetApp`, the mock model returned a valid `LAUNCH_APP` tool call, but the test passed because `DynamicPlanner`'s cold-app launch fast-path intercepted the call before the model was even invoked.
- **Fake Tool Execution Success:** `JarvisToolSystemTest.kt:30` initializes `ToolDispatcher(context, serviceProvider = { null })`. Tests assert `result.success == true` because `ToolDispatcher` contains `else { true // in test environment without live service }`. Real failure modes when the accessibility service is detached are completely untested.
- **Concurrent Coroutine Race Condition:** `AgentOrchestrator.startMission()` calls `cancelMission()` which invokes `activeMissionJob?.cancel()`. This does not await cancellation. A new coroutine is launched immediately while the previous mission coroutine continues to execute in `executeMissionLoop()`, creating data races on `MissionState`, `timeline`, and gesture dispatchers.

---

## Detailed Forensic Catalog of Issues

### Finding F-01: Gemini Model Client Silently Drops All Tool Calls into Heuristic Fallback
- **Severity:** CRITICAL
- **File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/model/ModelClient.kt`
- **Function:** `GoogleGeminiClient.decideNextActionStructured()` / `buildGeminiPayloadJson()` / `parseGeminiResponse()`
- **Evidence:** Lines 809-861. `buildGeminiPayloadJson()` only populates `contents` and `system_instruction`. `parseGeminiResponse()` returns `DecisionType.CONVERSATIONAL`. In `DynamicPlanner.kt:281-294`, `decision == DecisionType.CONVERSATIONAL` causes immediate fallthrough to `decideNextActionHeuristic()`.
- **Reproduction Path:** Set provider to Gemini in Settings. Start mission "Open YouTube and search Music". Observe logcat: `ModelClient Diagnostics: Decision=CONVERSATIONAL`, followed by `DynamicPlanner: decideNextActionHeuristic`.
- **Risk:** Complete failure of autonomous reasoning in default app configuration.
- **Recommended Fix:** Implement Gemini Function Declarations (`tools` array with `function_declarations`) in `buildGeminiPayloadJson()`, parse `functionCall` parts in `parseGeminiResponse()`, and return `DecisionType.EXECUTE_TOOL`.

### Finding F-02: Configuration Mismatch Triggers HTTP 404 Out-of-the-Box
- **Severity:** CRITICAL
- **File:** `app/src/main/java/com/assistive/headmouse/preferences/AppSettings.kt` & `ModelClient.kt`
- **Function:** `AppSettings.aiModelName` & `ModelClientFactory.create()`
- **Evidence:** `AppSettings.kt:270` defaults provider to `AiProvider.GEMINI`. `AppSettings.kt:276` defaults model name to `"deepseek/deepseek-chat"`. `ModelClientFactory.create()` invokes `GoogleGeminiClient(apiKey, modelName = "deepseek/deepseek-chat")`.
- **Reproduction Path:** Clean install app, enter a Gemini API key, leave model defaults untouched, run a cloud query.
- **Risk:** Total crash/failure of cloud AI reasoning on first launch.
- **Recommended Fix:** Decouple default model names per provider: if `AiProvider.GEMINI`, default to `"gemini-1.5-flash"`; if `AiProvider.CUSTOM_OPENROUTER`, default to `"deepseek/deepseek-chat"`.

### Finding F-03: Faked Integration Tests Mask Inoperable Model Client
- **Severity:** CRITICAL
- **File:** `app/src/test/java/com/assistive/headmouse/JarvisPhase6SystemIntegrationTest.kt`
- **Function:** `testCritical_OpenPlayStoreAndSearchWhatsApp_ClosedLoopProof()`
- **Evidence:** Lines 110-116: `override suspend fun decideNextAction(...) = Result.failure(RuntimeException("Use closed-loop heuristic"))`.
- **Reproduction Path:** Inspect test code line 115. Run `./gradlew.bat testDebugUnitTest --tests *JarvisPhase6SystemIntegrationTest*`.
- **Risk:** False confidence in closed-loop system verification when model integration is completely broken.
- **Recommended Fix:** Refactor integration tests to inject mock models returning valid `StructuredModelResult` with `ToolCall` and assert that `DynamicPlanner` correctly processes and returns the model's tool call.

### Finding F-04: SmartWaiter Event-Driven Settling is Dead Code (Pure Polling)
- **Severity:** HIGH
- **File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/SmartWaiter.kt`
- **Function:** `waitForUiSettle()`
- **Evidence:** Lines 68-72: `val collector = kotlinx.coroutines.flow.flow { events.collect { emit(signal) } }` is instantiated as a local val inside `withTimeoutOrNull` and never collected. Execution proceeds directly into `while (System.currentTimeMillis() - start < timeoutMs) { delay(150L) ... }`.
- **Reproduction Path:** Place a breakpoint or log inside `collector.collect {}` in `SmartWaiter.kt`. Run any mission; the breakpoint is never hit.
- **Risk:** Latency penalties of 150-300ms on every action; complete invalidation of Phase 5 event-driven settling claims.
- **Recommended Fix:** Launch a concurrent coroutine to collect from `AccessibilityEventBus.events` and use `select` or a channel to wake the wait loop immediately upon event reception.

### Finding F-05: Premature Gesture Success Reporting in ToolDispatcher
- **Severity:** HIGH
- **File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/tools/ToolDispatcher.kt`
- **Function:** `dispatch()`
- **Evidence:** Lines 125-130: `svc.clickAt()` is called, and `dispatched = true` is assigned synchronously. The asynchronous `GestureResultCallback` in `GestureDispatcher.kt` is never observed.
- **Reproduction Path:** Trigger an action while screen has an active modal transition. The OS calls `onCancelled()`. `ToolDispatcher` returns `ActionResult(success = true)`.
- **Risk:** Agent believes action succeeded when touch was discarded by Android OS.
- **Recommended Fix:** Pass a suspendable continuation or `CompletableDeferred<Boolean>` into `clickAt()` and await `onCompleted()` / `onCancelled()`.

### Finding F-06: False Positive Verification from Unconstrained Screen Hashing
- **Severity:** HIGH
- **File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/verification/VerificationEngine.kt`
- **Function:** `verify()`
- **Evidence:** Lines 51-57 & 102-109: `screenChanged` is computed from `computeScreenHash()`, which hashes bounding box coordinates of all nodes. Any background clock, battery indicator, or animation triggers `screenChanged = true`, causing `VerificationEngine` to declare tap success.
- **Reproduction Path:** Tap an unclickable static TextView while a progress bar or clock updates on screen. `VerificationEngine` returns `VerificationState.SUCCESS`.
- **Risk:** Agent assumes misclicks succeeded, leading to plan drift and mission failure.
- **Recommended Fix:** Exclude system bars and non-interactive animated views from `computeScreenHash()`; verify specific target postconditions rather than global screen hash changes.

### Finding F-07: Subgoal Progression Blocked on Unmatched Tasks
- **Severity:** HIGH
- **File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/AgentOrchestrator.kt`
- **Function:** `checkAndAdvanceSubgoals()`
- **Evidence:** Lines 354-378: `isActionComplete` only evaluates to true for `shorts`, `search` with `TYPE_TEXT`, or app launch of 5 hardcoded packages (`youtube`, `vending`, `settings`, `whatsapp`, `chrome`). Any other subgoal (e.g. "tap send", "add to cart", "toggle wifi") never completes.
- **Reproduction Path:** Run a mission: "Open Telegram and message Bob". Subgoal "message Bob" will never advance, causing the agent to loop until budget exhaustion.
- **Risk:** Complete inability to complete arbitrary or generalized user missions.
- **Recommended Fix:** Allow model-driven subgoal completion via `CanonicalTools.FINISH_TASK` or subgoal verification feedback rather than hardcoded string filters.

### Finding F-08: Head-Mouse Mode Contamination of Autonomous Actions
- **Severity:** HIGH
- **File:** `app/src/main/java/com/assistive/headmouse/service/HeadMouseAccessibilityService.kt`
- **Function:** `executeActionAt()`
- **Evidence:** Lines 893-940: `clickAt()` calls `executeActionAt()`. If the user has `dockView` on screen, touch coordinates hitting the dock trigger dock menu actions. If `activeMode` is `SCROLL_DOWN` or `LONG_PRESS`, autonomous clicks are converted into scrolls or long presses. Clicks within 600ms of head movement are discarded.
- **Reproduction Path:** In head-mouse dock, select "Scroll Down". Issue voice command "Click Search". Observe that the click is executed as a scroll down.
- **Risk:** Severe user interaction disruption, wrong actions performed on device.
- **Recommended Fix:** Have autonomous actions call a dedicated `dispatchAutonomousClick()` method that explicitly dispatches a single click directly via `GestureDispatcher` without touching `dockView` or `activeMode`.

### Finding F-09: Unhandled Safety Gate Rejection Infinite Loop
- **Severity:** HIGH
- **File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/SafetyGate.kt` & `AgentOrchestrator.kt`
- **Function:** `SafetyGate.checkSafety()` / `AgentOrchestrator.executeMissionLoop()`
- **Evidence:** `SafetyGate.kt:71-78`: `onRequestUserConfirmation` is null, so it calls `onDecision(false)`. In `AgentOrchestrator.kt:224-230`, `isApproved == false` logs `timeline.record("SAFETY_GATE", ...)` and calls `continue`.
- **Reproduction Path:** Issue command: "Delete file test.txt". The planner selects `delete` action. SafetyGate denies it. Orchestrator calls `continue`. The loop repeats indefinitely until max decision budget is reached.
- **Risk:** Silent hanging missions with zero feedback or confirmation prompt to the user.
- **Recommended Fix:** Wire `onRequestUserConfirmation` to an interactive dialog overlay in `MainActivity` or float an interactive confirmation bubble via accessibility service.

### Finding F-10: Memory Leak in AccessibilityNodeInfo Traversal
- **Severity:** MEDIUM
- **File:** `app/src/main/java/com/assistive/headmouse/service/HeadMouseAccessibilityService.kt`
- **Function:** `refreshSpatialCacheSync()`
- **Evidence:** Lines 1563-1574: Multiple accesses to `win.root` allocate `AccessibilityNodeInfo` instances that are not tracked or recycled.
- **Reproduction Path:** Run continuous missions for 30 minutes on an Android 11-13 device. Monitor logcat for `AccessibilityNodeInfo` allocation warnings and binder pool exhaustion.
- **Risk:** Crashes of `HeadMouseAccessibilityService` or system accessibility framework.
- **Recommended Fix:** Assign `val r = win.root` to a local variable and call `r.recycle()` in a `try/finally` block when done.

### Finding F-11: Unreachable RESPONSE_TIMEOUT Error Classification
- **Severity:** MEDIUM
- **File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/model/ModelClient.kt`
- **Function:** `OpenAiCompatibleClient.decideNextActionStructured()`
- **Evidence:** Lines 487-495: `val isConnect = totalConnectTime == 0L`. `totalConnectTime` is only updated after `transport.execute` completes. When an exception occurs, `totalConnectTime` is always 0L.
- **Reproduction Path:** Mock `HttpTransport` to sleep past read timeout after connection. Observe resulting `ModelError.type` is `SERVER_WAIT_TIMEOUT`, not `RESPONSE_TIMEOUT`.
- **Risk:** Distorted telemetry and incorrect retry policies for slow servers vs connection drops.
- **Recommended Fix:** Track connection establishment separately inside `HttpTransport` or catch connection timeout specifically at socket creation.

### Finding F-12: Non-Atomic Job Cancellation in Concurrent Missions
- **Severity:** MEDIUM
- **File:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/AgentOrchestrator.kt`
- **Function:** `startMission()`
- **Evidence:** Lines 86-131: `cancelMission()` calls `activeMissionJob?.cancel()`, but does not wait for it to join before resetting state and launching a new coroutine.
- **Reproduction Path:** Call `agentOrchestrator.startMission("Goal A")` and immediately call `agentOrchestrator.startMission("Goal B")` within 50ms.
- **Risk:** State corruption, simultaneous gesture dispatches, race conditions in mission memory.
- **Recommended Fix:** Make `startMission` a suspend function or use a `Mutex` to join `activeMissionJob` before initializing new mission state.

### Finding F-13: Full Regression Test Suite Failure Masked by Selective Sub-Suite Reporting
- **Severity:** HIGH
- **File:** `app/src/test/java/com/assistive/headmouse/JarvisPhase7FeaturesTest.kt` & `app/src/test/java/com/assistive/headmouse/e2e/*`
- **Function:** Full Gradle test task `:app:testDebugUnitTest`
- **Evidence:** Running full `./gradlew.bat testDebugUnitTest` executes 270 unit tests and results in **BUILD FAILED with 9 test failures** (1 in `Tier1FeatureCoverageTest`, 3 in `Tier2BoundaryAndCornerTest`, 3 in `Tier4RealWorldScenariosTest`, 2 in `JarvisPhase7FeaturesTest`). Phase 6 reported 100% pass rate (67/67) by selectively executing only the 5 autonomous test suites while obscuring the fact that full project regression testing fails.
- **Reproduction Path:** Run `./gradlew.bat testDebugUnitTest` without class filters. Inspect `app/build/reports/tests/testDebugUnitTest/index.html`.
- **Risk:** Silent regression in core conversational, screen inspection, and e2e test harnesses; false claim of passing global build validation.
- **Recommended Fix:** Update legacy tests in `JarvisPhase7FeaturesTest` and `Tier1-4` to provide mock API keys or set `isCloudEnabled = false` when testing local conversational and screen-reading intents.

---

## Inventory of Dead Code and Architectural Residue

| Component / File | Status | Location | Evidence / Description |
|---|---|---|---|
| `ReplanningEngine.kt` | **DEAD CODE** | `AgentOrchestrator.kt:64` | Instantiated as `val replanningEngine = ReplanningEngine()`, never called in orchestrator loop. |
| `LoopGuard.kt` (`checkPreExecution`) | **DEAD CODE** | `AgentOrchestrator.kt:58` | Instantiated and armed on line 96, but `checkPreExecution()` is never called in `executeMissionLoop()`. |
| `ModelFallbackRouter.kt` | **DEAD CODE** | `AgentOrchestrator.kt:66` | Instantiated as `val modelRouter = ModelFallbackRouter(...)`, never called in orchestrator loop. |
| `TaskOrchestrator.kt` | **DEAD CODE** | `JarvisMissionExecutor.kt:62` | Retained as private val with listeners, but never started by any mission entrypoint. |
| `ActionExecutor.kt` & `RecoveryEngine.kt` | **DEAD CODE** | `AgentOrchestrator.kt:35, 65` | Passed to constructor and instantiated, never called during action execution. |
| `WebSearchEngine.kt` & `InformationTools.kt` | **DEAD CODE** | `autonomous/WebSearchEngine.kt` | Web search and info tools are never referenced in the active decision loop. |
| `DynamicPlanner.createPlanForObjective()` | **DEAD / TEST-ONLY** | `DynamicPlanner.kt:32-220` | Multi-step static planning method with 9 templates; only called in unit tests, never in production. |
| `SmartWaiter.waitForUiSettle` (Event Bus) | **DEAD FLOW** | `SmartWaiter.kt:68-72` | `val collector = flow { events.collect { emit(...) } }` is created and never collected. |
| `JarvisScreenCaptureManager.kt` | **DISCONNECTED** | `agent/jarvis/JarvisScreenCaptureManager.kt` | Never called by `ScreenObserver`, `AgentOrchestrator`, or `DynamicPlanner`. |
| `JarvisFileManager.kt` | **DISCONNECTED** | `agent/jarvis/JarvisFileManager.kt` | Declared on `JarvisBrain.fileManager`, never called by any autonomous tool. |

---

## Red Team Remediation Roadmap

1. **Phase 7.1 — Model Client Integrity:**
   - Implement native Gemini function declarations in `GoogleGeminiClient.kt` so tool calls are actually returned by Gemini.
   - Decouple default model IDs in `AppSettings.kt` to prevent `deepseek/deepseek-chat` from being sent to Google Generative Language API.
   - Fix timeout classification in `OpenAiCompatibleClient.kt`.
2. **Phase 7.2 — Disconnect Assistive Mouse from Autonomous Actions:**
   - Create a dedicated, non-intrusive gesture dispatch path for autonomous tools that bypasses `executeActionAt()`, ignoring the floating dock bounds and dwell click modes.
   - Enforce asynchronous completion tracking on all accessibility gesture dispatches.
3. **Phase 7.3 — Real Event-Driven UI Settling & Grounded Replanning:**
   - Fix `SmartWaiter.kt` to genuinely collect from `AccessibilityEventBus`.
   - Wire `ReplanningEngine.recoverFromState()` into `AgentOrchestrator.kt` on `REPLAN_REQUIRED` and `UNCHANGED` conditions.
4. **Phase 7.4 — Test Suite Honesty & Cleanup:**
   - Remove mock model `Result.failure` tricks in `JarvisPhase6SystemIntegrationTest.kt` and `JarvisClosedLoopAgentTest.kt`; test real tool parsing.
   - Delete or deprecate dead code files (`TaskOrchestrator.kt`, `ModelFallbackRouter.kt`, `ActionExecutor.kt`, `RecoveryEngine.kt`).
5. **Phase 7.5 — Safety Gate Interactivity:**
   - Wire `onRequestUserConfirmation` to an on-screen dialog rather than silently rejecting destructive actions.

---
**Report compiled and verified by Antigravity Independent Red Team Audit Board.**
