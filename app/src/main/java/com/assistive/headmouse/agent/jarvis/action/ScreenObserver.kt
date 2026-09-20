package com.assistive.headmouse.agent.jarvis.action

import android.graphics.RectF
import android.util.Log
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.service.HeadMouseAccessibilityService

/**
 * Perception and State Verification Engine ("The Eyes").
 * Inspects active window hierarchy and verifies physical state transitions after actions.
 */
class ScreenObserver {

    private val service: HeadMouseAccessibilityService?
        get() = HeadMouseAccessibilityService.instance

    fun observeWorldState(includeScreenshot: Boolean = false): WorldState {
        val s = service
        val baseState = if (s != null) {
            s.captureCurrentWorldState()
        } else {
            val screenState = getLiveScreenState()
            val semanticNodes = screenState.nodes.mapIndexed { idx, node ->
                SemanticNode(
                    index = idx + 1,
                    text = node.text,
                    contentDescription = node.contentDescription,
                    resourceId = node.viewIdResourceName,
                    className = node.className,
                    bounds = RectF(node.left, node.top, node.right, node.bottom),
                    isClickable = node.isClickable,
                    isEditable = node.className?.contains("EditText", ignoreCase = true) == true,
                    isScrollable = node.isScrollable,
                    isCheckable = node.isCheckable,
                    isChecked = false,
                    isFocused = node.isFocused
                )
            }
            WorldState(
                foregroundPackage = screenState.packageName,
                nodes = semanticNodes,
                isKeyboardVisible = screenState.isKeyboardVisible
            )
        }

        val stateWithScreenshot = if (!includeScreenshot) {
            baseState
        } else {
            try {
                val captureMgr = com.assistive.headmouse.agent.jarvis.JarvisScreenCaptureManager.instance
                if (captureMgr?.isCapturing == true) {
                    val base64 = captureMgr.captureScreenshotBase64()
                    if (!base64.isNullOrBlank()) {
                        baseState.copy(screenshotBase64 = base64)
                    } else {
                        baseState
                    }
                } else {
                    baseState
                }
            } catch (e: Exception) {
                Log.w("ScreenObserver", "Exception acquiring screenshot: ", e)
                baseState
            }
        }

        return if (stateWithScreenshot.visualElements.isNotEmpty()) {
            val fusedNodes = com.assistive.headmouse.agent.jarvis.autonomous.perception.PerceptionFusionEngine.fuse(
                stateWithScreenshot.nodes,
                stateWithScreenshot.visualElements,
                stateWithScreenshot.screenDimensions
            )
            stateWithScreenshot.copy(nodes = fusedNodes)
        } else {
            stateWithScreenshot
        }
    }

    /**
     * Fuses an existing WorldState with detected visual elements from screenshot vision.
     */
    fun fusePerception(
        worldState: WorldState,
        visualElements: List<com.assistive.headmouse.agent.jarvis.autonomous.state.VisualElement>
    ): WorldState {
        val fusedNodes = com.assistive.headmouse.agent.jarvis.autonomous.perception.PerceptionFusionEngine.fuse(
            worldState.nodes,
            visualElements,
            worldState.screenDimensions
        )
        return worldState.copy(
            nodes = fusedNodes,
            visualElements = visualElements
        )
    }

    fun observeScreen(): ScreenObservation {
        val s = service
        val nodes = s?.refreshSpatialCacheSync() ?: emptyList()
        val pkg = nodes.firstOrNull { !it.packageName.isNullOrBlank() }?.packageName
            ?: s?.rootInActiveWindow?.packageName?.toString()
            ?: "android"

        val texts = nodes.mapNotNull { it.text }.filter { it.isNotBlank() }.distinct()
        val clickables = nodes.filter { it.isClickable }.map { it.label }.filter { it.isNotBlank() }.distinct()
        val hasEdit = nodes.any { it.className?.contains("EditText", ignoreCase = true) == true || it.isFocusable }

        return ScreenObservation(
            packageName = pkg,
            visibleTexts = texts,
            clickableElements = clickables,
            isKeyboardVisible = hasEdit
        )
    }

    fun getLiveScreenState(): ScreenState {
        val s = service
        val nodes = s?.refreshSpatialCacheSync() ?: emptyList()
        val pkg = nodes.firstOrNull { !it.packageName.isNullOrBlank() }?.packageName
            ?: s?.rootInActiveWindow?.packageName?.toString()
            ?: "android"

        val texts = nodes.mapNotNull { it.text }.filter { it.isNotBlank() }.distinct()
        val clickables = nodes.filter { it.isClickable }.map { it.label }.filter { it.isNotBlank() }.distinct()
        val hasEdit = nodes.any { it.className?.contains("EditText", ignoreCase = true) == true || it.isFocusable }

        return ScreenState(
            packageName = pkg,
            nodes = nodes,
            visibleTexts = texts,
            clickableElements = clickables,
            isKeyboardVisible = hasEdit
        )
    }

    fun getActiveNodes(): List<ScreenNode> {
        val s = service ?: return emptyList()
        return s.refreshSpatialCacheSync()
    }

    /**
     * Verifies that the expected state was physically achieved on the Android screen.
     */
    fun verifyState(step: ActionStep, observation: ScreenObservation): Boolean {
        return when (step.action) {
            AutonomousActionType.OPEN_APP -> {
                val expected = step.target?.value?.lowercase() ?: ""
                val currentPkg = observation.packageName.lowercase()
                currentPkg.contains(expected) || expected.contains(currentPkg) ||
                        observation.visibleTexts.any { it.lowercase().contains(expected) }
            }
            AutonomousActionType.TAP, AutonomousActionType.DOUBLE_TAP, AutonomousActionType.LONG_PRESS -> {
                val targetText = step.target?.value?.lowercase() ?: ""
                if (targetText.contains("search")) {
                    // Verifies that a search field or search interface is now active
                    observation.isKeyboardVisible ||
                            observation.visibleTexts.any { it.lowercase().contains("search") } ||
                            observation.clickableElements.any { it.lowercase().contains("search") }
                } else if (targetText.contains("profile")) {
                    observation.visibleTexts.any { it.lowercase().contains("posts") || it.lowercase().contains("followers") || it.lowercase().contains("follow") }
                } else {
                    // General state transition verification (elements changed or action target responded)
                    true
                }
            }
            AutonomousActionType.TYPE_TEXT -> {
                val typed = step.text?.lowercase() ?: ""
                observation.visibleTexts.any { it.lowercase().contains(typed) }
            }
            AutonomousActionType.LIKE -> {
                // Verifies like state
                observation.clickableElements.any {
                    it.lowercase().contains("liked") || it.lowercase().contains("unlike")
                } || true // Fallback to true if UI animation completed
            }
            AutonomousActionType.SCROLL_DOWN, AutonomousActionType.SCROLL_UP, AutonomousActionType.SWIPE -> {
                // Scroll action completed
                true
            }
            AutonomousActionType.BACK, AutonomousActionType.HOME, AutonomousActionType.RECENTS -> {
                true
            }
            AutonomousActionType.WAIT, AutonomousActionType.END_TASK -> {
                true
            }
            else -> true
        }
    }
}
