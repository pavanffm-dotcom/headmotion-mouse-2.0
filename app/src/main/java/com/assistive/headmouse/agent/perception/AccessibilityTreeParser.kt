package com.assistive.headmouse.agent.perception

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.assistive.headmouse.agent.model.ScreenNode

/**
 * High-performance background parser that recursively traverses the active
 * AccessibilityNodeInfo tree and extracts interactive, visible ScreenNodes.
 *
 * Implements strict privacy protection: password fields are flagged and their
 * textual content is completely stripped.
 */
class AccessibilityTreeParser(
    private val screenWidth: Int = 1080,
    private val screenHeight: Int = 2400
) {

    private val tempRect = Rect()

    fun parseTree(rootNode: AccessibilityNodeInfo?): List<ScreenNode> {
        if (rootNode == null) return emptyList()

        val results = mutableListOf<ScreenNode>()
        try {
            traverseNode(rootNode, results, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error traversing accessibility tree: ", e)
        }
        return results
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        collector: MutableList<ScreenNode>,
        depth: Int
    ) {
        if (depth > 40) return

        // 1. Extract bounds
        node.getBoundsInScreen(tempRect)
        val left = tempRect.left.toFloat()
        val top = tempRect.top.toFloat()
        val right = tempRect.right.toFloat()
        val bottom = tempRect.bottom.toFloat()
        val width = right - left
        val height = bottom - top

        // 2. Validate visibility on screen
        val isVisible = width > 10f &&
            height > 10f &&
            right > 0f &&
            bottom > 0f &&
            left < screenWidth &&
            top < screenHeight

        if (isVisible) {
            val isClickable = node.isClickable
            val isScrollable = node.isScrollable
            val isFocusable = node.isFocusable
            val isCheckable = node.isCheckable
            val isPassword = node.isPassword

            val text = if (isPassword) null else node.text?.toString()?.trim()
            val desc = if (isPassword) null else node.contentDescription?.toString()?.trim()
            val viewId = node.viewIdResourceName
            val className = node.className?.toString()

            // Include if interactive or has semantic text/description label
            val hasLabel = !text.isNullOrBlank() || !desc.isNullOrBlank()
            val isInteractive = isClickable || isScrollable || isCheckable || isFocusable || hasLabel

            if (isInteractive && collector.size < 120) {
                val uniqueId = "${viewId ?: className ?: "node"}_${tempRect.left.toInt()}_${tempRect.top.toInt()}"
                collector.add(
                    ScreenNode(
                        id = uniqueId,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        text = text,
                        contentDescription = desc,
                        viewIdResourceName = viewId,
                        className = className,
                        packageName = node.packageName?.toString(),
                        isClickable = isClickable,
                        isScrollable = isScrollable,
                        isFocusable = isFocusable,
                        isCheckable = isCheckable,
                        isPassword = isPassword,
                        isFocused = node.isFocused
                    )
                )
            }
        }

        // 3. Recurse children
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            if (child != null) {
                traverseNode(child, collector, depth + 1)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }
    }

    companion object {
        private const val TAG = "A11yTreeParser"
    }
}
