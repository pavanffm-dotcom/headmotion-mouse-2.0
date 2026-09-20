# Phase 1 Specification: Continuous Closed-Loop Agent Execution

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Document:** `PHASE_1_AGENT_LOOP.md`  
**Date:** 2026-09-17  

---

## 1. The Closed-Loop Perceptual Algorithm

The target agent architecture strictly eliminates the pre-generated multi-step plan. Instead, every single physical action undergoes the complete cycle:

$$\text{OBSERVE} \longrightarrow \text{DECIDE NEXT SINGLE ACTION} \longrightarrow \text{ACT} \longrightarrow \text{WAIT/SETTLE} \longrightarrow \text{OBSERVE} \longrightarrow \text{VERIFY} \longrightarrow \text{UPDATE STATE} \longrightarrow \text{REPLAN / CONTINUE} \longrightarrow \text{COMPLETE}$$

```mermaid
flowchart TD
    START(["Start Mission (originalUserGoal)"]) --> OBS1["1. OBSERVE: Capture WorldState_T0\n(Active App, Semantic Nodes, Screen Hash)"]
    OBS1 --> DECIDE{"2. DECIDE: Is Model Query Needed?"}
    
    DECIDE -->|Heuristic Shortcut Available\n(e.g. Target App Not Open)| FAST_ACT["Synthesize Launch Tool: launch_app(targetPackage)"]
    DECIDE -->|Cognitive Action Required| LLM_CALL["Query ModelClient with:\n- originalUserGoal\n- currentSubgoal\n- Compressed Screen Index\n- Last Action Result"]
    
    LLM_CALL --> PARSE["Parse Canonical ToolCall\n(tap_element, type_text, scroll, etc.)"]
    FAST_ACT --> EXEC
    PARSE --> EXEC["3. ACT: Execute Single Atomic ToolCall via ToolDispatcher"]
    
    EXEC --> SETTLE["4. WAIT/SETTLE: SmartWaiter Polling\n(Wait for window transition / hash delta / max 2500ms)"]
    SETTLE --> OBS2["5. OBSERVE: Capture WorldState_T1"]
    
    OBS2 --> VERIFY{"6. VERIFY: Did State Mutate As Expected?\n(VerificationEngine: pre vs. post WorldState)"}
    
    VERIFY -->|YES: Success| UPDATE["7. UPDATE: Update MissionState\nRecord Action in History\nAdvance Subgoal if fulfilled"]
    UPDATE --> CHECK_DONE{"Goal Completed?"}
    CHECK_DONE -->|YES| FINISH(["Mission Complete: Spoken Confirmation"])
    CHECK_DONE -->|NO| OBS1
    
    VERIFY -->|NO: Verification Failed| REPLAN{"Consecutive Failures < 3?"}
    REPLAN -->|YES| REPLAN_LOOP["Feed Failure Reason into next prompt\n(Model adjusts strategy: scroll, dismiss dialog)"]
    REPLAN_LOOP --> OBS1
    REPLAN -->|NO| FAIL(["Mission Failed: 'Goal could not be achieved'"])
```

---

## 2. Decision Rules: When the Model is Called vs. Not Called

To maintain ultra-low latency while preserving dynamic cognitive adaptability:

### A. When the Model is NOT Called (Deterministic Local Execution):
1. **Initial Cold App Launch:**
   - If `originalUserGoal` specifies an app (e.g. *"YouTube open karo aur Shorts pe click karo"*), and `WorldState.foregroundPackage` is NOT `com.google.android.youtube`, the agent **locally issues `launch_app("com.google.android.youtube")` without waiting 3 seconds for an LLM call**.
   - After YouTube opens and settles, the loop captures the new screen and immediately calls the model for the next action.
2. **Blocking System Dialog Dismissal:**
   - If `WorldState.isDialogBlocking` is true (e.g., "Allow notifications", "Google Terms popup"), `DialogHandler` attempts safe dismissal locally (`tap_element("Cancel")` or `"Not now"`).
3. **Emergency Abort / User Barge-in:**
   - Speech phrases like *"stop"*, *"cancel"*, *"ruk ja"* cancel execution immediately (< 50ms) without LLM intervention.

### B. When the Model IS Called (Cognitive Deliberation):
1. **Every In-App Action Selection:**
   - After the target app is foregrounded, **every subsequent tap, text typing, scroll, or tab switch is decided by the model**.
   - The model is supplied with:
     - `originalUserGoal`: *"YouTube open karo aur Shorts pe click karo"*
     - `currentObservation`: Compact semantic node index of the live foreground app.
     - `lastActionResult`: Outcome of the previous step.
2. **Ambiguous or Changed Screens:**
   - If an unexpected banner, search suggestion list, or drawer appears, the model reasons about the new screen state rather than failing on a hardcoded assumption.

---

## 3. Multimodal Vision Policy: When to Capture Screenshots

Accessibility trees represent 95% of standard native Android interfaces. However, custom games, Canvas views, video surfaces (like YouTube video feeds), and non-standard web views omit accessibility nodes.

```
                                  ┌───────────────────────────┐
                                  │   Capture WorldState      │
                                  └─────────────┬─────────────┘
                                                │
                 ┌──────────────────────────────┴──────────────────────────────┐
                 ▼                                                             ▼
     [Interactive Nodes >= 3]                                     [Interactive Nodes < 3]
  AND Relevant Labels Detected                                   OR Canvas / Custom View Detected
                 │                                                             │
                 ▼                                                             ▼
     [ACCESSIBILITY ONLY MODE]                                    [SCREENSHOT TRIGGERED]
  • Send Text Semantic Index                                   • Request 720p compressed JPEG
  • 0 bytes image bandwidth                                    • Attach image_url to Model request
  • Latency: ~1.2s                                             • Latency: ~3.5s
  • Tokens: ~350                                               • Tokens: ~1,200
```

### Strict Screenshot Exclusion:
1. **NEVER** take or send screenshots when standard clickable text nodes for the target are present in the accessibility tree (conserves 1,000+ tokens and 2+ seconds per turn).
2. **NEVER** take screenshots on secure screens (`isPassword == true` or `FLAG_SECURE`).

---

## 4. Event-Driven UI Settling Protocol (`SmartWaiter`)

Phase 0 showed that static `Thread.sleep(600)` creates race conditions on slow devices while causing unnecessary lag on fast devices.

The target architecture uses **Event-Driven Settling**:

```kotlin
suspend fun waitForSettling(
    service: HeadMouseAccessibilityService,
    initialHash: String,
    expectedPackage: String? = null,
    maxTimeoutMs: Long = 2500L,
    pollIntervalMs: Long = 100L
): WorldState {
    val startTime = System.currentTimeMillis()
    var lastState = service.captureCurrentWorldState()

    while (System.currentTimeMillis() - startTime < maxTimeoutMs) {
        delay(pollIntervalMs)
        val currentState = service.captureCurrentWorldState()

        // Condition 1: Package switched to target app
        if (expectedPackage != null && currentState.foregroundPackage == expectedPackage) {
            // Allow 200ms brief settling for UI animations
            delay(200L)
            return service.captureCurrentWorldState()
        }

        // Condition 2: Screen hash mutated and stabilized
        if (currentState.screenHash != initialHash && currentState.screenHash == lastState.screenHash) {
            return currentState
        }

        lastState = currentState
    }

    // Fallback on timeout: return latest live state
    return service.captureCurrentWorldState()
}
```

---

## 5. Verification Protocol (`VerificationEngine`)

Every executed tool call is validated against empirical screen deltas:

| Tool Executed | Required Post-Condition Verification | Failure Action |
|---|---|---|
| `launch_app(pkg)` | `WorldState.foregroundPackage == pkg` | Retry launch; if app not installed, mark unrecoverable. |
| `tap_element(#id, label)` | `targetNode.isFocused == true` OR target disappeared OR `screenHash` changed | Re-observe; if unchanged, retry with coordinate fallback or scroll. |
| `type_text(text)` | `focusedNode.text.contains(text)` | Re-focus target node and retry text injection. |
| `scroll(direction)` | `WorldState.accessibilityHash != previousHash` | End of scrollable container reached; inform model. |
| `press_navigation(BACK)` | `screenHash` changed OR `foregroundPackage` changed | Verify previous screen restored. |
