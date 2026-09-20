# Phase 0 Forensic Report: Deep Failure Mode Analysis

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Classification Mode:** FORENSIC AUDIT ONLY (Zero Production Source Code Modifications)  
**Date:** 2026-09-17  

---

## 1. Complete Failure Catalog

| Failure Scenario | Exact Symptom Observed by User | Direct Root Cause in Code | Exact Source Code Reference | Classification |
|---|---|---|---|---|
| **YouTube Shorts Halting** | *"YouTube open karke chhod de raha hai"* | `DynamicPlanner` static template emits 2 steps (`OPEN_APP`, `TAP Shorts`). Step 1 succeeds. Step 2 attempts to tap "Shorts" before YouTube renders its bottom bar or while `spatialNodeCache` is empty/stale. `ReplanningEngine` fails to resolve, aborting mission. | `DynamicPlanner.kt:40-45`, `AgentOrchestrator.kt:233`, `ReplanningEngine.kt:89` | `VERIFIED FROM CURRENT CODE` |
| **Current Screen Misreading** | *"Woh pata nahi kaunsi screen read karne lagta hai"* | `HeadMouseAccessibilityService.refreshSpatialCacheSync()` grabs `rootInActiveWindow`, which defaults to the floating overlay window or returns stale cached nodes from the previous screen via `spatialNodeCache.getNodes()`. | `HeadMouseAccessibilityService.kt:1541, 1596` | `VERIFIED FROM CURRENT CODE` |
| **Settings Flashlight Failure** | *"Settings on kar diya aur kuch bhi nie bas udhar hi chhod gaya"* | `DynamicPlanner` static template for flashlight looks for "Search settings". If the device uses a different manufacturer label (e.g. realme UI "Search" or icon-only search button), resolution fails. `ReplanningEngine` scrolls once, fails again, and aborts. | `DynamicPlanner.kt:48-62`, `ReplanningEngine.kt:44-59` | `VERIFIED FROM CURRENT CODE` |
| **API Timeout / Network Error** | *"Network Error: Read timed out"* | `HttpURLConnection.setReadTimeout(15000)` triggers before OpenRouter / Nex / DeepSeek finishes generating 250 tokens across a 3,500+ token uncompressed prompt context. | `JarvisBrain.kt:826`, `JarvisBrain.kt:146` | `VERIFIED FROM CURRENT CODE` |
| **Goal Replacement** | High-level user goal forgotten after step 1 | `GoalManager.decomposeGoalString()` splits the mission into sub-strings. The orchestrator titles the objective as the sub-string (e.g. "YouTube open karo"), discarding the compound context. | `GoalManager.kt:141-170`, `AgentOrchestrator.kt:98` | `VERIFIED FROM CURRENT CODE` |
| **Touch Offset / Inaccurate Taps** | Cursor/tap misses UI element by ~50-80px | `HeadMouseAccessibilityService.executeActionAt` adds `windowOffsetY` to `target.centerY`, even though `ScreenNode` coordinates are already absolute screen pixels. | `HeadMouseAccessibilityService.kt:903-904` | `VERIFIED FROM CURRENT CODE` |
| **Silent Click Drop** | Taps fail to register completely | `HeadMouseAccessibilityService.lastGlobalClickTimestamp` refractory gate drops any programmatic click that occurs within 600ms of any previous touch. | `HeadMouseAccessibilityService.kt:891-895` | `VERIFIED FROM CURRENT CODE` |
| **Uncompressed Token Bandwidth** | 72,000 tokens consumed in 15 minutes | `JarvisMemoryManager` sends up to 16 full past dialogue turns + full uncompressed accessibility tree (30 nodes) on every single request. | `JarvisBrain.kt:829`, `JarvisMemoryManager.kt:46` | `VERIFIED FROM CURRENT CODE` |

---

## 2. Failure Case Studies: Step-by-Step Code Execution

### Case Study 1: "YouTube open karo. Shorts ke upar click karo"

1. **Input Normalization**:
   - Spoken input received: `"YouTube open karo. Shorts ke upar click karo"`.
   - Handled in `JarvisBackgroundVoiceService.handleSpokenCommand()`.
   - `JarvisBrain.isCompoundTask()` returns `true` (matches `.` and conjunctions).
   - `JarvisBrain` returns `ActionType.START_MISSION`.
2. **Goal Decomposition**:
   - `GoalManager.initializeGoal()` splits on `.` into:
     - Objective 1: `"Youtube open karo"`
     - Objective 2: `"Shorts ke upar click karo"`
3. **Objective 1 Execution**:
   - `DynamicPlanner.createPlanForObjective("Youtube open karo")` emits `[OPEN_APP com.google.android.youtube]`.
   - `ActionExecutor.executeStep` launches YouTube.
   - `SmartWaiter.waitForScreenChange` detects screen change.
   - Objective 1 marked `COMPLETED`.
4. **Objective 2 Execution (THE BREAKDOWN)**:
   - `GoalManager.advanceToNextObjective()` sets current objective to `"Shorts ke upar click karo"`.
   - `DynamicPlanner.createPlanForObjective("Shorts ke upar click karo")`:
     - Matches `goalLower.contains("shorts")` (Line 40).
     - Emits:
       ```kotlin
       if (!screenState.packageName.contains("youtube")) {
           steps.add(ActionStep(..., OPEN_APP, "com.google.android.youtube"))
       }
       steps.add(ActionStep(..., TAP, ActionTarget(TEXT, "Shorts")))
       ```
     - Emits 1 step: `TAP "Shorts"`.
   - `ActionExecutor.executeStep`:
     - Calls `screenObserver.getActiveNodes()`.
     - `HeadMouseAccessibilityService.refreshSpatialCacheSync()` is called.
     - **BUG:** YouTube is still in cold-start loading/splash animation. `rootInActiveWindow` is either null or still points to Launcher.
     - `TargetResolver.resolveTarget("Shorts", nodes)` searches for "Shorts".
     - Result: `null`.
     - Retries after 600ms. Still `null`.
     - `ActionExecutor` returns `ActionResult(success=false, reason="TARGET_NOT_FOUND", verified=false)`.
   - `AgentOrchestrator`:
     - `verified == false`.
     - Calls `replanningEngine.replan()`.
     - `ReplanningEngine` checks if dialog present (false), checks `TARGET_NOT_FOUND` (emits 1 `SCROLL_DOWN` step).
     - Orchestrator executes `SCROLL_DOWN`.
     - Next step: retries `TAP "Shorts"`.
     - If still not found, `ReplanningEngine` returns `null`.
     - `AgentOrchestrator` marks objective `FAILED`.
     - Speaks: `"Objective Shorts ke upar click karo could not be achieved."`
     - **Mission aborts.** The user is left sitting on the YouTube home screen.

---

### Case Study 2: User is on YouTube Screen and says "Shorts mein click karna hai"

1. **Voice Input**:
   - User says `"Shorts mein click karna hai"`.
   - `JarvisBackgroundVoiceService` calls `jarvisBrain.processUserPrompt()`.
2. **Single-Intent Element Clicking**:
   - `JarvisBrain.kt:371` matches `isClickIntent` (`mein click` detected).
   - Line 379: `val liveNodes = screenObserver.getLiveScreenState().nodes`.
3. **The Perception Trap**:
   - `ScreenObserver` calls `HeadMouseAccessibilityService.refreshSpatialCacheSync()`.
   - Because the floating Arc Reactor overlay or Cursor overlay is on screen, `rootInActiveWindow` returns package `com.assistive.headmouse`.
   - Line 1548 attempts fallback: `windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }`.
   - On Android 14, `windows.firstOrNull` can return the launcher window underneath or throw a security exception, falling back to line 1596: `return spatialNodeCache.getNodes()`.
   - `spatialNodeCache` contains the **nodes from 30 seconds ago** before YouTube was opened!
   - `targetName` = `"shorts"`.
   - Line 397: `liveNodes.find { it.label.contains("shorts") }` finds **ZERO matches** because it searched stale launcher nodes.
   - `matched == null`.
   - Nothing is clicked!
   - J.A.R.V.I.S. falls through to conversational fallback and says:
     `"I am listening, Sir. You can ask me to open any app, click elements, or perform phone actions."`
   - The user is left confused and frustrated.
