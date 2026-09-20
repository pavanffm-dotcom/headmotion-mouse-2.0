package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import com.assistive.headmouse.agent.jarvis.action.ActionTarget
import com.assistive.headmouse.agent.jarvis.action.ResolvedTarget
import com.assistive.headmouse.agent.jarvis.action.TargetResolver
import com.assistive.headmouse.agent.model.ScreenNode

/**
 * Perception Fusion Layer (R26).
 * Unifies Accessibility Tree parsing with Vision coordinates and spatial bounding.
 * Enforces strict priority (Accessibility > Vision > Coordinates) and detects ambiguity.
 */
object PerceptionFusion {

    private const val TAG = "PerceptionFusion"

    /**
     * Resolves a target using the unified fusion hierarchy.
     */
    fun resolve(
        target: ActionTarget,
        accessibilityNodes: List<ScreenNode>,
        visionCandidates: List<ScreenNode>? = null
    ): ResolvedTarget? {
        // 1. Primary: Deterministic Accessibility Tree Resolution (Priority 0 to 5)
        val a11yResult = TargetResolver.resolveTarget(target, accessibilityNodes)
        val hasA11y = a11yResult != null && a11yResult.confidence >= 0.6f

        // 2. Secondary: Vision Model OCR / Coordinates
        if (!visionCandidates.isNullOrEmpty()) {
            val visionResult = TargetResolver.resolveTarget(target, visionCandidates)
            if (visionResult != null) {
                if (hasA11y && a11yResult != null) {
                    // Both Accessibility and Vision matched!
                    if (a11yResult.matchedNode?.id != visionResult.matchedNode?.id &&
                        !a11yResult.matchedNode?.label.equals(visionResult.matchedNode?.label, ignoreCase = true)) {
                        Log.w(TAG, "Ambiguity detected: Accessibility target '${a11yResult.matchedNode?.label}' conflicts with Vision '${visionResult.matchedNode?.label}'.")
                        // Prefer accessibility when reliable (Rule 9)
                        return a11yResult.copy(source = com.assistive.headmouse.agent.jarvis.autonomous.state.PerceptionSource.ACCESSIBILITY)
                    }
                    Log.d(TAG, "Resolved target via ACCESSIBILITY_AND_VISION fusion: ${a11yResult.matchedNode?.label ?: target.value}")
                    return a11yResult.copy(
                        confidence = 0.99f,
                        source = com.assistive.headmouse.agent.jarvis.autonomous.state.PerceptionSource.ACCESSIBILITY_AND_VISION
                    )
                }
                Log.d(TAG, "Resolved target via Vision fallback: ${visionResult.matchedNode?.label ?: target.value}")
                return visionResult.copy(source = com.assistive.headmouse.agent.jarvis.autonomous.state.PerceptionSource.VISION)
            }
        }

        // 3. Fallback: Accessibility match
        return a11yResult?.copy(source = com.assistive.headmouse.agent.jarvis.autonomous.state.PerceptionSource.ACCESSIBILITY)
    }
}
