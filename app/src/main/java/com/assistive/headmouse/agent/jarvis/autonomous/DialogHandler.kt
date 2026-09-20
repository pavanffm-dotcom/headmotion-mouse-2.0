package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.service.HeadMouseAccessibilityService

/**
 * Interruption and Dialog Handler (R24).
 * Detects common UI blockers (permission dialogs, update alerts, cookie prompts, system popups)
 * and safely dismisses or routes them for user decision.
 */
object DialogHandler {

    private const val TAG = "DialogHandler"

    private val DISMISS_KEYWORDS = listOf("not now", "cancel", "dismiss", "close", "skip", "later", "no thanks")
    private val AGREE_KEYWORDS = listOf("agree", "accept", "got it", "continue", "ok", "done")
    private val PERMISSION_KEYWORDS = listOf("allow", "while using the app", "only this time")

    /**
     * Checks if the active nodes represent an interruption modal/dialog.
     */
    fun isDialogPresent(nodes: List<ScreenNode>): Boolean {
        return nodes.any { node ->
            node.className?.contains("Dialog", ignoreCase = true) == true ||
            node.className?.contains("PopupWindow", ignoreCase = true) == true ||
            node.className?.contains("BottomSheet", ignoreCase = true) == true ||
            node.label.contains("permission", ignoreCase = true) ||
            node.label.contains("update available", ignoreCase = true) ||
            (node.isClickable && DISMISS_KEYWORDS.any { node.label.equals(it, ignoreCase = true) })
        }
    }

    /**
     * Inspects active nodes and automatically handles safe dismissable dialogs.
     * Returns true if a dialog was detected and an interaction was dispatched.
     */
    fun attemptSafeDismissal(nodes: List<ScreenNode>): Boolean {
        if (!isDialogPresent(nodes)) return false

        // 1. Check for standard safe dismiss buttons
        val dismissNode = nodes.find { node ->
            node.isClickable && DISMISS_KEYWORDS.any { node.label.equals(it, ignoreCase = true) }
        }
        if (dismissNode != null) {
            Log.i(TAG, "Dismissing popup via '${dismissNode.label}'")
            HeadMouseAccessibilityService.instance?.updateCursorPositionExplicit(dismissNode.centerX, dismissNode.centerY)
            HeadMouseAccessibilityService.instance?.clickScreenNode(dismissNode)
            return true
        }

        // 2. Check for terms/update consent "Got it" / "OK"
        val agreeNode = nodes.find { node ->
            node.isClickable && AGREE_KEYWORDS.any { node.label.equals(it, ignoreCase = true) }
        }
        if (agreeNode != null) {
            Log.i(TAG, "Acknowledging benign prompt via '${agreeNode.label}'")
            HeadMouseAccessibilityService.instance?.updateCursorPositionExplicit(agreeNode.centerX, agreeNode.centerY)
            HeadMouseAccessibilityService.instance?.clickScreenNode(agreeNode)
            return true
        }

        return false
    }
}
