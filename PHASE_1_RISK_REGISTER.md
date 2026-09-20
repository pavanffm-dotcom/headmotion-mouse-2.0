# Phase 1 Specification: Architectural Risk Register

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Document:** `PHASE_1_RISK_REGISTER.md`  
**Date:** 2026-09-17  

---

## 1. Risk Matrix Overview

| Risk ID | Risk Description | Severity | Likelihood | Mitigation Strategy |
|---|---|---|---|---|
| **R-01** | Touch Injection vs. Head Mouse Gesture Collision | HIGH | MEDIUM | Strict Mutex Lock in `ToolDispatcher` + global click refractory management. |
| **R-02** | Accessibility Latency on Heavy Transitions (YouTube/Games) | HIGH | MEDIUM | Event-driven settling (`SmartWaiter`) with dynamic polling and 2500ms upper bound. |
| **R-03** | Android 14 Multi-Window Permission Strictness | HIGH | LOW | Fallback from `windows` enumeration to active root traversal with overlay filtering. |
| **R-04** | API Network Fluctuations & Proxy 502/504 Errors | MEDIUM | HIGH | Automatic 2x retry with exponential backoff and 30-second read timeout. |
| **R-05** | Infinite Action Loops on Dynamic WebViews | HIGH | LOW | `LoopGuard` tracking consecutive identical actions with zero hash delta. |
| **R-06** | Unexpected Blocking Popups / Permission Dialogs | MEDIUM | MEDIUM | Pre-execution `DialogHandler` scan before model invocation. |
| **R-07** | Context Window Inflation & Token Depletion | HIGH | LOW | Strict semantic index compression keeping prompts under 800 tokens. |
| **R-08** | Half-Duplex Speech Collision During Voice Feedback | MEDIUM | MEDIUM | Speech recognition muted while TTS is actively speaking (`jarvisVoiceEngine.isSpeaking()`). |
| **R-09** | MediaProjection Token Expiration on Reboot | LOW | MEDIUM | Fallback to accessibility-first hierarchy; only request projection when screen lacks nodes. |
| **R-10** | Aggressive OEM Battery Killers (ColorOS / realme UI) | MEDIUM | MEDIUM | Foreground service notification with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`. |

---

## 2. In-Depth Risk Mitigation & Rollback Strategies

### Risk R-01: Gesture Collision Between Autonomous Agent and Head Mouse
- **Failure Mode:** If the user moves their head while the autonomous agent injects a touch, two touches could fire concurrently, resulting in unpredictable gestures.
- **Mitigation:**
  1. `ToolDispatcher` acquires `ActionExecutor.executionMutex`.
  2. While the autonomous mission state is in `EXECUTING`, head-mouse dwell clicking is temporarily suspended for 300ms.
  3. The cursor HUD updates to show `"🤖 JARVIS ACTIVE"` so the user knows an autonomous touch is in progress.

### Risk R-02: Accessibility Hierarchy Latency During Splash Screens
- **Failure Mode:** Inspecting the accessibility tree before an app finishes its launch animation results in `TARGET_NOT_FOUND`.
- **Mitigation:**
  1. `SmartWaiter.waitForSettling` polls until `foregroundPackage` matches the expected target.
  2. Adds an intentional 200ms settling pause after window detection before parsing nodes.

### Risk R-04: API Timeouts on Reasoning Models (DeepSeek / Nex)
- **Failure Mode:** High latency on third-party OpenRouter providers throws `SocketTimeoutException`.
- **Mitigation:**
  1. Read timeout increased from 15s to 30s in `ModelClient`.
  2. If timeout occurs, `ModelClient` immediately retries once with a shorter compressed prompt before declaring network failure.

### Risk R-05: Infinite Loops on Non-Responsive Buttons
- **Failure Mode:** The model repeatedly issues `tap_element("Submit")`, but the button is disabled.
- **Mitigation:**
  1. `LoopGuard` tracks `(action, target, screenHash)`.
  2. If the same action on the same target results in zero `screenHash` change twice consecutively, `LoopGuard` aborts with `REPEATED_ACTION_LOOP` and triggers replanning.

### Risk R-07: Context Window / Token Runaway
- **Failure Mode:** Re-accumulating 72,000 tokens in a 15-minute session.
- **Mitigation:**
  1. Screen state is limited to 30 prioritized interactive nodes in tabular format (~240 tokens).
  2. Dialogue memory sends only the last 2-3 turns (~200 tokens).
  3. Total request size is strictly constrained to < 800 tokens, guaranteeing ultra-low API costs and fast response times.
