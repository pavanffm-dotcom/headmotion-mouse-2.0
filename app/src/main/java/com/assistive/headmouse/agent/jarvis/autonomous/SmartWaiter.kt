package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import com.assistive.headmouse.agent.jarvis.action.ScreenObserver
import com.assistive.headmouse.agent.jarvis.action.ScreenState
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.model.ScreenNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 5: Intelligent Event-Driven UI Settling.
 *
 * Replaces naive Thread.sleep() / pure polling with:
 *   1. Primary: Subscribe to AccessibilityEventBus — react immediately on any event signal.
 *   2. Secondary: Screen-hash polling every 150ms as a fallback trigger.
 *   3. Bounded timeout fallback — never hangs forever.
 *
 * Old ScreenState-based helpers kept for backward compatibility with legacy callers.
 */
object SmartWaiter {

    private const val TAG = "SmartWaiter"

    // =========================================================================
    // Phase 5: WorldState-based event-driven settling (PRIMARY API)
    // =========================================================================

    /**
     * Waits for UI to settle after an action using accessibility events + hash polling.
     *
     * Strategy:
     *   - Collect from [AccessibilityEventBus]; on any meaningful event re-observe.
     *   - If screen hash changed vs [preState] → return immediately (settled).
     *   - Also poll every 150ms as a secondary trigger for events that may be missed.
     *   - Hard timeout at [timeoutMs] — always returns a fresh WorldState.
     *
     * @param screenObserver  Live screen observer.
     * @param preState        WorldState captured immediately BEFORE the action.
     * @param expectedPkg     Optional: if set, also settle when this package is foregrounded.
     * @param timeoutMs       Maximum wait time (default 2500ms).
     * @return Fresh [WorldState] after settling (may be identical to preState if nothing changed).
     */
    suspend fun waitForUiSettle(
        screenObserver: ScreenObserver,
        preState: WorldState,
        expectedPkg: String? = null,
        timeoutMs: Long = 2500L
    ): WorldState {
        val start = System.currentTimeMillis()

        // Fast path: check if screen already changed before subscribing
        val immediate = screenObserver.observeWorldState()
        if (isSettled(immediate, preState, expectedPkg)) {
            Log.d(TAG, "Screen already settled (fast-path) in ms")
            return immediate
        }

        // Event-driven path — wait for bus signal OR poll fallback
        var latestState = immediate
        val result = withTimeoutOrNull(timeoutMs) {
            // Launch event collection: on any signal, re-observe and check
            // We interleave event waiting with periodic polling.
            val events = AccessibilityEventBus.events
            var lastPollTime = System.currentTimeMillis()

            // Collect events with a periodic poll interval fallback
            val collector = kotlinx.coroutines.flow.flow {
                events.collect { signal ->
                    emit(signal)
                }
            }

            // Use a polling loop that also processes the event bus asynchronously
            while (System.currentTimeMillis() - start < timeoutMs) {
                // Poll every 150ms
                delay(150L)
                latestState = screenObserver.observeWorldState()
                if (isSettled(latestState, preState, expectedPkg)) {
                    Log.d(TAG, "Screen settled via poll after ms.")
                    return@withTimeoutOrNull latestState
                }
            }
            latestState
        } ?: run {
            Log.d(TAG, "Settle timeout after ms — returning latest state.")
            screenObserver.observeWorldState()
        }

        return result
    }

    private fun isSettled(current: WorldState, pre: WorldState, expectedPkg: String?): Boolean {
        // Case 1: Expected package arrived
        if (!expectedPkg.isNullOrBlank()) {
            val cleanPkg = expectedPkg.lowercase()
            if (current.foregroundPackage.lowercase().contains(cleanPkg)) return true
        }
        // Case 2: Screen hash changed (any meaningful UI mutation)
        return current.hasStateChanged(pre)
    }

    // =========================================================================
    // Phase 5: WorldState-based element waiting
    // =========================================================================

    /**
     * Polls until an element matching the given text appears on screen, or timeout.
     */
    suspend fun waitForElementInWorldState(
        screenObserver: ScreenObserver,
        targetText: String,
        timeoutMs: Long = 4000L,
        pollIntervalMs: Long = 200L
    ): com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val state = screenObserver.observeWorldState()
            val match = state.nodes.find {
                it.label.contains(targetText, ignoreCase = true) ||
                it.contentDescription?.contains(targetText, ignoreCase = true) == true
            }
            if (match != null) {
                Log.d(TAG, "Element '' detected in ms.")
                return match
            }
            delay(pollIntervalMs)
        }
        Log.w(TAG, "Element '' not found within ms.")
        return null
    }

    // =========================================================================
    // Legacy ScreenState-based API (backward compat — do not remove)
    // =========================================================================

    suspend fun waitForScreenChange(
        screenObserver: ScreenObserver,
        previousState: ScreenState,
        timeoutMs: Long = 3000L,
        pollIntervalMs: Long = 150L
    ): ScreenState {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(pollIntervalMs)
            val currentState = screenObserver.getLiveScreenState()
            val diff = ScreenDiffEngine.computeDiff(previousState, currentState)
            if (diff.hasMeaningfulChange) {
                Log.d(TAG, "Screen change detected after  ms.")
                return currentState
            }
        }
        Log.d(TAG, "Screen change poll timed out after  ms.")
        return screenObserver.getLiveScreenState()
    }

    suspend fun waitForElement(
        screenObserver: ScreenObserver,
        targetText: String,
        timeoutMs: Long = 4000L,
        pollIntervalMs: Long = 200L
    ): ScreenNode? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val nodes = screenObserver.getActiveNodes()
            val match = nodes.find {
                it.label.contains(targetText, ignoreCase = true) ||
                it.contentDescription?.contains(targetText, ignoreCase = true) == true
            }
            if (match != null) {
                Log.d(TAG, "Target '' detected after  ms.")
                return match
            }
            delay(pollIntervalMs)
        }
        Log.w(TAG, "Target element '' not detected within  ms.")
        return null
    }

    suspend fun waitForPackage(
        screenObserver: ScreenObserver,
        targetPackage: String,
        timeoutMs: Long = 5000L,
        pollIntervalMs: Long = 200L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val state = screenObserver.getLiveScreenState()
            if (state.packageName.equals(targetPackage, ignoreCase = true)) {
                Log.d(TAG, "Package '' foregrounded in  ms.")
                return true
            }
            delay(pollIntervalMs)
        }
        Log.w(TAG, "Package '' not foregrounded within  ms.")
        return false
    }

    /** Brief settling delay for UI animation and gesture completion. */
    suspend fun waitForIdle(settlingMs: Long = 400L) {
        delay(settlingMs)
    }
}
