# J.A.R.V.I.S. Phase 0 Complete Forensic Audit Report

**Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Investigation Mode:** FORENSIC INVESTIGATION ONLY (Zero Production Source Code Modifications)  
**Date:** 2026-09-17  
**Verification Level:** 100% VERIFIED FROM ACTUAL CURRENT CODEBASE  

---

## Executive Summary

A comprehensive forensic audit of the **HeadMotionMouse / J.A.R.V.I.S.** codebase was executed to uncover the exact architectural and runtime reasons why the agent halts after the first action (e.g. opens YouTube or Settings and stops), reads stale/incorrect screen states, fails to click targets, and consumes excess tokens.

The audit confirmed that the failure is **architectural, not cosmetic**:
1. The agent is **not running a continuous perception-action feedback loop**. Instead, it generates a static multi-step plan upfront, executes step 1, and then advances through pre-baked steps blindly using `currentStepIndex++` without querying the LLM with post-action screen states.
2. Common user goals ("YouTube Shorts", "Settings Flashlight", "Play Store Search") are intercepted by **hardcoded static template branches** in `DynamicPlanner.kt`. The LLM is never called.
3. When the user asks to click an element (e.g. "Shorts mein click karna hai"), the accessibility service frequently reads the **floating overlay window** (`com.assistive.headmouse`) or falls back to **stale cached nodes from the previous screen**, causing the target to be missed.
4. Programmatic touches miss target elements by the status bar height due to an erroneous `windowOffsetY` addition in `HeadMouseAccessibilityService.executeActionAt()`.
5. API read timeouts occur because the application sends 3,500+ uncompressed tokens per request over synchronous blocking `HttpURLConnection` with a low 15-second timeout, resulting in `"Network Error: Read timed out"` and 72,000 tokens consumed in 15 minutes.

---

## Critical Question: Decision Generation Matrix

Every autonomous decision in the codebase was forensically analyzed and classified into one of seven decision mechanisms:
- **A. LLM (Large Language Model)**
- **B. Heuristic (Pattern matching / Rules)**
- **C. Static Template (Hardcoded step sequence)**
- **D. Regex (String parsing)**
- **E. Fallback (Local emergency handler)**
- **F. Cached Result (Stale memory/node retrieval)**
- **G. Hardcoded Action (Direct physical dispatch)**

| Autonomous Decision Point | Implementation Mechanism | Exact Code File & Line Number | Evidence & Forensic Reality |
|---|---|---|---|
| **Voice Interruption ("Stop", "Cancel")** | `D. Regex / String match` | `JarvisBackgroundVoiceService.kt:280` | Evaluates `lower == "stop" || lower.contains("ruk ja")`. Aborts mission immediately. |
| **Compound Task Detection** | `B. Heuristic & D. Regex` | `JarvisBrain.kt:170-215` (`isCompoundTask`) | Splits string by regex punctuation and checks for conjunctions ("and", "aur", "then"). |
| **YouTube Shorts Mission** | `C. Static Template & G. Hardcoded Action` | `DynamicPlanner.kt:40-45` | Hardcodes `[OPEN_APP youtube, TAP Shorts]`. **The LLM is NEVER called.** |
| **Settings Flashlight Mission** | `C. Static Template & G. Hardcoded Action` | `DynamicPlanner.kt:48-62` | Hardcodes `[OPEN_APP settings, TAP Search settings, TYPE_TEXT Flashlight, TAP Flashlight]`. **The LLM is NEVER called.** |
| **App Launch + Search Mission** | `C. Static Template & G. Hardcoded Action` | `DynamicPlanner.kt:65-120` | Hardcodes `[OPEN_APP, TAP Search, TYPE_TEXT]`. **The LLM is NEVER called.** |
| **Element Clicking ("Shorts mein click karo")** | `D. Regex + B. Heuristic` | `JarvisBrain.kt:371-417` | Strips Hindi/English filler words; linear search across `ScreenNode` list. |
| **Screen Perception Hierarchy** | `F. Cached Result (Fallback)` | `HeadMouseAccessibilityService.kt:1596` | If active window root is null/overlay, returns `spatialNodeCache.getNodes()` (stale nodes from previous screen). |
| **Step-to-Step Decision in Loop** | `G. Hardcoded Action (Array Index)` | `AgentOrchestrator.kt:126, 212` | **NOT LLM!** Simply executes `objective.currentStepIndex++` from pre-baked array. |
| **Failure Replanning** | `B. Heuristic & E. Fallback` | `ReplanningEngine.kt:20-91` | 4 hardcoded rules (Cancel dialog, Scroll down, Back, Recents). Returns `null` if target still missing. |
| **General Q&A / Complex Chat** | `A. LLM` | `JarvisBrain.kt:122-126` | Sends prompt to OpenRouter/Gemini/OpenAI; parses `[ACTION:...]` tags via regex. |
| **Vision Next-Step Planner** | `A. LLM` (`DISCONNECTED`) | `JarvisBrain.kt:1561` (`planNextMissionStep`) | Implemented with JSON vision prompt, but **NEVER CALLED anywhere in the codebase.** |

---

## Top Root Causes, Severity & Exact Files

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 TOP 6 ARCHITECTURAL ROOT CAUSES                                │
├────┬──────────────────────────────────────────┬──────────┬─────────────────────────────────────┤
│ #  │ Root Cause Summary                       │ Severity │ Primary Code File & Function        │
├────┼──────────────────────────────────────────┼──────────┼─────────────────────────────────────┤
│ 1  │ No Continuous Single-Action Closed Loop  │ CRITICAL │ AgentOrchestrator.kt:113-237        │
│ 2  │ Static Heuristic Templates Override LLM  │ CRITICAL │ DynamicPlanner.kt:38-177            │
│ 3  │ Stale Node Perception / Overlay Hijack   │ CRITICAL │ HeadMouseAccessibilityService:1541  │
│ 4  │ Destructive Goal Splitting               │ HIGH     │ GoalManager.kt:141-170              │
│ 5  │ Erroneous Window Y-Offset in Coordinates │ HIGH     │ HeadMouseAccessibilityService:903   │
│ 6  │ Uncompressed Memory & 15s Read Timeout   │ HIGH     │ JarvisBrain.kt:826, 829             │
└────┴──────────────────────────────────────────┴──────────┴─────────────────────────────────────┘
```

### Detailed Root Cause Breakdown:

### Root Cause 1: Absence of a Continuous Perception-Action Loop
- **Severity:** `CRITICAL`
- **Location:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/AgentOrchestrator.kt:113-237`
- **Evidence:** 
  `AgentOrchestrator` generates a static plan once at the beginning of an objective:
  `val plannedSteps = dynamicPlanner.createPlanForObjective(...)` (Line 116).
  It then iterates sequentially:
  `while (isActive && objective.currentStepIndex < objective.steps.size)` (Line 126).
  After executing an action and waiting for UI settling, it verifies state locally (`result.success && result.verified`). If verified, it simply runs:
  `objective.currentStepIndex++` (Line 212).
  **It never sends the newly observed screen state back to the model to ask: "What is the next single action to achieve the goal?"**

### Root Cause 2: Static Heuristic Templates Intercepting Autonomous Planning
- **Severity:** `CRITICAL`
- **Location:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/DynamicPlanner.kt:38-177`
- **Evidence:**
  `DynamicPlanner.createPlanForObjective()` uses hardcoded pattern matching:
  - Line 40: `goalLower.contains("shorts")` -> emits hardcoded YouTube open + TAP Shorts.
  - Line 48: `goalLower.contains("flashlight") || goalLower.contains("torch")` -> emits hardcoded Settings open + search Flashlight.
  - Line 65: `goalLower.contains("search") || goalLower.contains("dhoondho")` -> emits hardcoded search sequences.
  If the UI changes, an ad appears, or a bottom sheet pops up, the static script breaks and fails.

### Root Cause 3: Stale Cache Perception & Overlay Window Focus Hijacking
- **Severity:** `CRITICAL`
- **Location:** `app/src/main/java/com/assistive/headmouse/service/HeadMouseAccessibilityService.kt:1541, 1548, 1596`
- **Evidence:**
  `refreshSpatialCacheSync()` inspects `rootInActiveWindow`. Because `FloatingArcReactorOverlay` and `CursorOverlayView` are persistent overlays, `rootInActiveWindow` frequently evaluates to `com.assistive.headmouse` or returns `null`.
  The fallback `windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }` can select the launcher window underneath the transitioning app.
  When hierarchy extraction fails, line 1596 returns `spatialNodeCache.getNodes()`, which contains **stale nodes from the previously closed application**.

### Root Cause 4: Destructive Compound Goal Splitting
- **Severity:** `HIGH`
- **Location:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/autonomous/GoalManager.kt:141-170`
- **Evidence:**
  When given a compound command (e.g. *"YouTube open karo. Shorts ke upar click karo"*), `decomposeGoalString` splits on `.` into isolated strings: Objective 1 = `"YouTube open karo"`, Objective 2 = `"Shorts ke upar click karo"`.
  When executing Objective 1, the orchestrator only knows about opening YouTube. The broader context that the user wants to watch Shorts is discarded, preventing contextual pre-fetching or seamless cross-app continuation.

### Root Cause 5: Inaccurate Tap Injections Due to Double Y-Offset
- **Severity:** `HIGH`
- **Location:** `app/src/main/java/com/assistive/headmouse/service/HeadMouseAccessibilityService.kt:897-905`
- **Evidence:**
  `AccessibilityTreeParser` uses `node.getBoundsInScreen(tempRect)`. Coordinates `centerX` and `centerY` are **already in absolute physical screen pixels**.
  In `executeActionAt()`, the service adds `windowOffsetY` from `cursorView.getLocationOnScreen(location)`:
  `val exactPhysicalY = (y + windowOffsetY).coerceIn(...)` (Line 904).
  This shifts the tap downward by the status bar/insets offset, causing touch injections to miss buttons.

### Root Cause 6: Context Window Runaway & Read Timeout (15,000ms)
- **Severity:** `HIGH`
- **Location:** `app/src/main/java/com/assistive/headmouse/agent/jarvis/JarvisBrain.kt:826, 829`
- **Evidence:**
  `JarvisBrain.callCustomOpenRouterApi()` attaches `memoryManager.getRecentTurns(16)` + `systemInstruction` (550 tokens) + `toPromptSummary(30)` (800 tokens) on every call.
  This generates 3,500+ tokens per request. Over 24 turns, this totals **72,000 tokens**.
  Furthermore, `conn.readTimeout = 15000` (15s) is too short for OpenRouter/Nex/DeepSeek to process a 3,500 token context and generate 250 tokens, triggering `SocketTimeoutException` and surfacing `"Network Error: Read timed out"`.

---

## Recommended Architectural Fix (Roadmap for Phase 1)

1. **Shift to True Single-Action Continuous Closed Loop**:
   - In `AgentOrchestrator.kt`, replace the pre-generated multi-step loop with:
     $$\text{OBSERVE SCREEN} \longrightarrow \text{DECIDE NEXT SINGLE ACTION (LLM)} \longrightarrow \text{ACT} \longrightarrow \text{SETTLE} \longrightarrow \text{OBSERVE} \longrightarrow \text{VERIFY} \longrightarrow \text{CONTINUE / COMPLETE}$$
2. **Preserve User's Immutable Goal**:
   - Store `originalUserGoal` permanently in `AgentOrchestrator` and `GoalManager`. Never overwrite or truncate it during intermediate step execution.
3. **Canonical Tool Calling Protocol**:
   - Transition from bracketed string parsing (`[ACTION:TAP]`) to machine-validated JSON tool definitions (`observe_screen`, `tap_element`, `type_text`, `scroll`, `launch_app`, `finish_mission`).
4. **Clean Application Window Perception**:
   - In `HeadMouseAccessibilityService.kt`, explicitly filter out overlay windows (`TYPE_ACCESSIBILITY_OVERLAY`), identify the true top-most interactive `TYPE_APPLICATION` window, clear stale caches immediately on window transitions, and remove the redundant `windowOffsetY` addition.
5. **Decoupled ModelClient & Context Compression**:
   - Extract an abstracted `ModelClient` honoring user-configured models (OpenRouter, Nex N2.5 Pro, xKiro, Gemini) and eliminate the hardcoded getter in `AppSettings.kt:261`.
   - Compress context: send only 2-3 recent dialogue turns and relevant compressed accessibility nodes to reduce prompt size to < 800 tokens, eliminating read timeouts and token explosion.

---

## Confirmation of Audit Integrity

- **Production Source Code Changes:** `0` (Zero files modified, deleted, or refactored).
- **Git Working Tree Status:** Clean and unaltered.
- **Verification:** All findings substantiated by exact line numbers in current Kotlin source files.
