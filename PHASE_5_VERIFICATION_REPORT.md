# Phase 5 — Verification and Replanning Report

## 1. VerificationState Outcomes
Every canonical tool invocation resolves to one of six explicit states:
- SUCCESS: Postcondition verified (UI mutated, target disappeared, or field populated).
- FAILED: Definitive failure without expected side-effects.
- PARTIAL: Focus or state mutated, but target text/node not yet confirmed.
- UNCHANGED: Zero state or hash change pre vs. post action.
- UNKNOWN: Unverifiable passive action or baseline unavailable.
- REPLAN_REQUIRED: Screen diverged from expected package/activity.

## 2. Replanning & Recovery
- AgentOrchestrator immediately aborts the current plan upon REPLAN_REQUIRED and re-observes without delay.
- 3 consecutive UNCHANGED outcomes trigger a forced recovery sequence (scrolling down to expose occluded targets or stepping back via PRESS_NAVIGATION).
- Automated handling for soft keyboards, loading progress bars, and modal interruptions.
