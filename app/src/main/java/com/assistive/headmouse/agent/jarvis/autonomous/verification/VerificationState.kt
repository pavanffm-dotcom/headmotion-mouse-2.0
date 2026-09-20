package com.assistive.headmouse.agent.jarvis.autonomous.verification

/**
 * Phase 5: Authoritative verification outcome for every agent action.
 *
 * Replaces the coarse Boolean erified with a structured state that allows
 * the orchestrator to make precise recovery and replanning decisions.
 */
enum class VerificationState {

    /** Post-action screen matches the expected postcondition. Mission can continue. */
    SUCCESS,

    /** Action executed but the screen is in a definitively wrong state. Recovery needed. */
    FAILED,

    /** Screen changed but the specific expected element/state was not confirmed.
     *  Agent should proceed cautiously — one more observation may resolve ambiguity. */
    PARTIAL,

    /** Pre- and post-action screen hashes are identical. Action had zero effect.
     *  Three consecutive UNCHANGED results trigger a forced recovery strategy. */
    UNCHANGED,

    /** Cannot determine outcome: no baseline state, timeout, or service unavailable. */
    UNKNOWN,

    /** Screen diverged from the expected path (wrong package / unexpected dialog).
     *  Agent MUST stop the current plan, re-observe, and decide again from scratch. */
    REPLAN_REQUIRED;

    /** Convenience: states that allow the agent to continue without replanning. */
    val allowsContinue: Boolean
        get() = this == SUCCESS || this == PARTIAL

    /** Convenience: states that require the agent to stop and re-decide. */
    val requiresReplan: Boolean
        get() = this == REPLAN_REQUIRED

    /** Convenience: states that consume a failure budget slot. */
    val isFailure: Boolean
        get() = this == FAILED || this == UNCHANGED || this == REPLAN_REQUIRED
}
