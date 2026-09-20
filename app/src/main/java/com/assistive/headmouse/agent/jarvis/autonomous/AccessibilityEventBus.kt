package com.assistive.headmouse.agent.jarvis.autonomous

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase 5: Event-Driven Settling Bus.
 *
 * Decouples the HeadMouseAccessibilityService event callback from the agent's
 * waiting logic. The service emits [AccessibilityEventSignal] instances here;
 * SmartWaiter subscribes and reacts immediately instead of polling blindly.
 *
 * Covered event types:
 *   TYPE_WINDOW_STATE_CHANGED   -- Activity/dialog appears
 *   TYPE_WINDOWS_CHANGED        -- Window stack mutated
 *   TYPE_VIEW_SCROLLED          -- Scroll completed
 *   TYPE_VIEW_TEXT_CHANGED      -- EditText content changed
 *   TYPE_WINDOW_CONTENT_CHANGED -- Subtree invalidated (e.g. progress bar gone)
 */
object AccessibilityEventBus {

    data class AccessibilityEventSignal(
        val eventType: Int,
        val packageName: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val isWindowTransition: Boolean
            get() = eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED

        val isContentChange: Boolean
            get() = eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                    eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    }

    private val _events = MutableSharedFlow<AccessibilityEventSignal>(
        replay = 1,
        extraBufferCapacity = 32
    )

    val events: SharedFlow<AccessibilityEventSignal> = _events.asSharedFlow()

    fun emit(signal: AccessibilityEventSignal) {
        _events.tryEmit(signal)
    }
}
