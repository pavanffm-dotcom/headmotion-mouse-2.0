package com.assistive.headmouse.agent.jarvis.autonomous

import com.assistive.headmouse.agent.jarvis.action.ScreenState
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.model.ScreenNode

/**
 * Phase 5: WorldState diff result.
 *
 * Produced by [ScreenDiffEngine.computeDiff(WorldState, WorldState)].
 * Used by SmartWaiter for event-driven settling and by VerificationEngine for
 * precise outcome classification.
 */
data class WorldStateDiff(
    val packageChanged: Boolean,
    val activityChanged: Boolean,
    val nodesAdded: List<SemanticNode>,
    val nodesRemoved: List<SemanticNode>,
    val textChanged: List<Pair<String, String>>,
    val focusChanged: Boolean,
    val keyboardChanged: Boolean,
    val hashChanged: Boolean
) {
    /** True when any meaningful UI mutation occurred. */
    val hasMeaningfulChange: Boolean
        get() = packageChanged || activityChanged || hashChanged || focusChanged ||
                nodesAdded.isNotEmpty() || nodesRemoved.isNotEmpty() || textChanged.isNotEmpty()
}

/**
 * Screen Diff Engine (R19).
 * Compares two ScreenState instances to detect additions, removals, text modifications,
 * and navigation transitions for rigorous action verification.
 *
 * Phase 5: Extended with WorldState diff support.
 */
object ScreenDiffEngine {

    // =========================================================================
    // Phase 5: WorldState-based diff (primary API for Phase 5 components)
    // =========================================================================

    /**
     * Computes a structured diff between two [WorldState] snapshots.
     * Used by SmartWaiter and VerificationEngine.
     */
    fun computeDiff(preState: WorldState, postState: WorldState): WorldStateDiff {
        val packageChanged = preState.foregroundPackage != postState.foregroundPackage
        val activityChanged = preState.foregroundActivity != postState.foregroundActivity
        val hashChanged = preState.screenHash != postState.screenHash ||
                preState.accessibilityHash != postState.accessibilityHash
        val focusChanged = preState.focusedNode != postState.focusedNode
        val keyboardChanged = preState.isKeyboardVisible != postState.isKeyboardVisible

        // Node-level diff by resource ID (best stable identifier in WorldState)
        val oldById = preState.nodes.associateBy { it.resourceId ?: "idx_${it.index}" }
        val newById = postState.nodes.associateBy { it.resourceId ?: "idx_${it.index}" }

        val nodesAdded = postState.nodes.filter { !oldById.containsKey(it.resourceId ?: "idx_${it.index}") }
        val nodesRemoved = preState.nodes.filter { !newById.containsKey(it.resourceId ?: "idx_${it.index}") }

        val textChanged = mutableListOf<Pair<String, String>>()
        for (newNode in postState.nodes) {
            val key = newNode.resourceId ?: "idx_${newNode.index}"
            val oldNode = oldById[key]
            if (oldNode != null && oldNode.label != newNode.label && newNode.label.isNotBlank()) {
                textChanged.add(oldNode.label to newNode.label)
            }
        }

        return WorldStateDiff(
            packageChanged = packageChanged,
            activityChanged = activityChanged,
            nodesAdded = nodesAdded,
            nodesRemoved = nodesRemoved,
            textChanged = textChanged,
            focusChanged = focusChanged,
            keyboardChanged = keyboardChanged,
            hashChanged = hashChanged
        )
    }

    // =========================================================================
    // Legacy ScreenState-based diff (kept for backward compatibility)
    // =========================================================================

    fun computeDiff(previousState: ScreenState?, currentState: ScreenState): ScreenDiff {
        if (previousState == null) {
            return ScreenDiff(
                previousPackage = "",
                currentPackage = currentState.packageName,
                isPackageChanged = true,
                isActivityChanged = true,
                addedNodes = currentState.nodes,
                removedNodes = emptyList(),
                textChanges = emptyList(),
                isFocusChanged = currentState.nodes.any { it.isFocused }
            )
        }

        val pkgChanged = previousState.packageName != currentState.packageName
        val actChanged = previousState.activityName != currentState.activityName

        val oldNodesById = previousState.nodes.associateBy { it.id }
        val newNodesById = currentState.nodes.associateBy { it.id }

        val addedNodes = currentState.nodes.filter { !oldNodesById.containsKey(it.id) }
        val removedNodes = previousState.nodes.filter { !newNodesById.containsKey(it.id) }

        val textChanges = mutableListOf<Pair<String, String>>()
        for (newNode in currentState.nodes) {
            val oldNode = oldNodesById[newNode.id]
            if (oldNode != null && oldNode.label != newNode.label) {
                textChanges.add(Pair(oldNode.label, newNode.label))
            }
        }

        val prevFocused = previousState.nodes.find { it.isFocused }?.id
        val currFocused = currentState.nodes.find { it.isFocused }?.id
        val focusChanged = prevFocused != currFocused

        return ScreenDiff(
            previousPackage = previousState.packageName,
            currentPackage = currentState.packageName,
            isPackageChanged = pkgChanged,
            isActivityChanged = actChanged,
            addedNodes = addedNodes,
            removedNodes = removedNodes,
            textChanges = textChanges,
            isFocusChanged = focusChanged
        )
    }
}
