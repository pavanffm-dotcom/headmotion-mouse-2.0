# Phase 2 — Changed Files & Implementation Manifest

This document provides a comprehensive inventory of all source files created and modified during **Phase 2 — Core Closed-Loop Agent Implementation**.

---

## I. Newly Created Files

### 1. `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/state/MissionState.kt`
- **Role**: Single authoritative runtime state for the autonomous agent.
- **Key Fields**:
  - `missionId`: Unique UUID for tracking.
  - `originalUserGoal`: Strictly immutable original user intent (e.g. *"Open Play Store and search WhatsApp"*).
  - `currentSubgoal`: Active sub-objective currently being solved.
  - `completedObjectives`: Chronological record of accomplished subgoals.
  - `remainingObjectives`: Pending subgoals.
  - `currentObservation` & `previousObservation`: Live and previous normalized `WorldState`.
  - `lastAction`: The most recent `ToolCall` dispatched.
  - `lastActionResult`: The empirical execution result.
  - `expectedPostcondition`: What the agent expects to happen before acting.
  - `verifiedState`: Postcondition verification outcome.
  - `failureCount` & `consecutiveFailedActions`: Circuit-breaker counters.
  - `modelDecisionCount`: Atomic decision iterations count.
  - `status`: Lifecycle state (`IDLE`, `PLANNING`, `EXECUTING`, `WAITING`, `VERIFYING`, `COMPLETED`, `FAILED`, `ABORTED`).
- **Methods**: `advanceSubgoal()`, `recordFailure()`, `resetFailureStreak()`, `isCircuitBreakerTripped()`.

### 2. `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/state/WorldState.kt`
- **Role**: Clean UI semantic representation decoupled from Android platform classes for 100% JVM testability.
- **Key Classes**:
  - `SemanticNode`: Normalized interactive UI element (index, text, contentDescription, resourceId, className, bounds with primitive floats `left, top, right, bottom`, `isClickable`, `isEditable`, `isScrollable`, `isFocused`, `isEnabled`, `confidenceScore`).
  - `WorldState`: Full snapshot containing `foregroundPackage`, `foregroundActivity`, `screenDimensions`, `screenHash`, `accessibilityHash`, `isKeyboardVisible`, `focusedNode`, and helper accessors (`clickableNodes`, `editableNodes`, `scrollableNodes`).
  - `toCompressedSemanticIndex()`: Produces a high-density, low-token (< 300 tokens) tabular index formatted as `[idx] CLASS | "text" | "desc" | bounds | clickable/editable/focused`.
- **Dual Hash Engine**:
  - `screenHash`: MD5 hash of bounding boxes and class names (detects layout/geometry mutations).
  - `accessibilityHash`: MD5 hash of text and content descriptions (detects data/text mutations).

### 3. `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/tools/ToolProtocol.kt`
- **Role**: The canonical 12-tool contract and OpenAI-compatible tool schemas.
- **Tools Declared**:
  1. `observe_screen`
  2. `tap_element`
  3. `tap_coordinates`
  4. `type_text`
  5. `scroll`
  6. `swipe`
  7. `long_press`
  8. `press_navigation`
  9. `launch_app`
  10. `wait`
  11. `take_screenshot`
  12. `finish_task`
- **Classes**: `ToolCall`, `ActionResult`, `ToolDefinition`, `ToolParameter`, `CanonicalTools`.
- **Schema Generator**: `toOpenAiToolsJsonArray()` outputs standard tool definitions for LLM tool-calling APIs.

### 4. `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/tools/ToolDispatcher.kt`
- **Role**: Execution engine mapping canonical `ToolCall` into concrete Android accessibility gestures and system actions.
- **Features**:
  - Direct coordinate and node index dispatch.
  - Text entry with auto-focus and IME action / enter support.
  - Directional scrolling and gesture swipes.
  - System key injection (BACK, HOME, RECENTS).
  - Visual cursor feedback synchronization with `CursorOverlayView`.

### 5. `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/model/ModelClient.kt`
- **Role**: Provider-neutral model interface and OpenAI-compatible client adapter.
- **Features**:
  - Standalone `ModelClient` interface with `decideNextAction()`.
  - `OpenAiCompatibleClient` supporting OpenRouter, Nex, and xKiro.
  - Enforces 30,000ms read/connect timeouts.
  - Automatic retry with exponential backoff on HTTP 429/5xx errors.
  - Fallback JSON extractor when models output JSON in standard message content instead of tool calls.
  - **Zero credential logging**: Authorization headers and API keys are strictly excluded from all log statements.
  - `ModelClientFactory`: Resolves active provider and credentials from `AppSettings`.

### 6. `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/verification/VerificationEngine.kt`
- **Role**: Empirical pre/post state validation engine.
- **Evaluations**:
  - `launch_app`: Verifies foreground package matches expected target package.
  - `tap_element`: Checks target element dismissal, focus change, or screen hash mutation.
  - `type_text`: Confirms target text appears in focused/editable node or accessibility hash changes.
  - `scroll` / `swipe`: Verifies screen content or geometry mutations via dual hash changes.
  - `press_navigation`: Validates package or activity transition.

### 7. `app/src/test/java/com/assistive/headmouse/JarvisClosedLoopAgentTest.kt`
- **Role**: Comprehensive JVM unit test suite validating the closed-loop agent architecture.
- **Test Scenarios**:
  - `testOpenPlayStoreAndSearchWhatsAppClosedLoop`: Verifies Step 1 (launcher -> launch Play Store), Step 2 (Play Store -> tap Search), Step 3 (Search focused -> type WhatsApp).
  - `testModelClientPromptReceivesNewScreenObservation`: Asserts that `ModelClient` receives the updated screen index reflecting the active foreground UI.
  - `testAuthoritativeMissionStateGoalImmutability`: Proves `originalUserGoal` remains invariant throughout multiple subgoals.
  - `testWorldStateDualHashDetection`: Verifies geometry vs text mutation detection.
  - `testVerificationEngineEvaluations`: Validates postcondition checking across all tool types.

---

## II. Modified Production Files

### 1. `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/AgentOrchestrator.kt`
- **Changes**:
  - Removed blind multi-action script execution loop (`plan.steps.forEach` / `stepIndex++`).
  - Implemented single-action closed loop:
    `OBSERVE -> DECIDE SINGLE ACTION -> ACT -> WAIT/SETTLE -> OBSERVE -> VERIFY -> UPDATE -> DECIDE AGAIN -> COMPLETE`.
  - Integrated `MissionState`, `ToolDispatcher`, and `VerificationEngine`.
  - Added failure counter and circuit-breaker handling (stops mission after 3 consecutive failures).

### 2. `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/DynamicPlanner.kt`
- **Changes**:
  - Added `decideNextAction(missionState, modelClient): ToolCall` deciding ONE atomic tool call per iteration.
  - Grounded heuristic fallback in live `WorldState` elements.
  - Fixed `extractSearchQuery()` with case-insensitive tokenization and prefix stripping.
  - Preserved legacy `createPlanForObjective()` for backwards compatibility.

### 3. `app/src/main/java/com/assistive/headmouse/agent/jarvis/action/ScreenObserver.kt`
- **Changes**:
  - Added `observeWorldState(): WorldState` capturing live screen hierarchy into normalized `SemanticNode` objects.
  - Implemented dual hash calculation (`screenHash` & `accessibilityHash`).
  - Maintained backward compatibility with legacy `ScreenHierarchy` and `ScreenState`.

### 4. `app/src/main/java/com/assistive/headmouse/service/HeadMouseAccessibilityService.kt`
- **Changes**:
  - Fixed double `windowOffsetY` touch offset bug in `executeActionAt()`.
  - Added cache invalidation (`spatialNodeCache.clear()`) on `TYPE_WINDOW_STATE_CHANGED`.
  - Filtered out `TYPE_ACCESSIBILITY_OVERLAY` and assistant windows in `refreshSpatialCacheSync()` so only user application elements are indexed.
  - Added `captureCurrentWorldState(): WorldState` for unified perception.
  - **Zero impact**: Assistive head-mouse tracking and dwell-clicking pipelines preserved completely intact.

### 5. `app/src/main/java/com/assistive/headmouse/preferences/AppSettings.kt`
- **Changes**:
  - Fixed hardcoded `aiProvider` getter at line 261 to read from `SharedPreferences` instead of defaulting to `CUSTOM_OPENROUTER`.
