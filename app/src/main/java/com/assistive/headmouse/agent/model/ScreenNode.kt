package com.assistive.headmouse.agent.model

import android.graphics.RectF

/**
 * Represents a semantic, interactive on-screen UI element extracted from Android's
 * AccessibilityNodeInfo tree.
 *
 * Stores coordinates as primitive floats (left, top, right, bottom) to guarantee
 * 100% JVM unit-test compatibility without mockable android.jar stub limitations,
 * while exposing a RectF bounds property for Android Canvas rendering.
 */
data class ScreenNode(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isFocusable: Boolean = false,
    val isCheckable: Boolean = false,
    val isPassword: Boolean = false,
    val isFocused: Boolean = false
) {
    // Secondary constructor accepting RectF for seamless interop with Accessibility APIs
    constructor(
        id: String,
        bounds: RectF,
        text: String? = null,
        contentDescription: String? = null,
        viewIdResourceName: String? = null,
        className: String? = null,
        packageName: String? = null,
        isClickable: Boolean = false,
        isScrollable: Boolean = false,
        isFocusable: Boolean = false,
        isCheckable: Boolean = false,
        isPassword: Boolean = false,
        isFocused: Boolean = false
    ) : this(
        id = id,
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        text = text,
        contentDescription = contentDescription,
        viewIdResourceName = viewIdResourceName,
        className = className,
        packageName = packageName,
        isClickable = isClickable,
        isScrollable = isScrollable,
        isFocusable = isFocusable,
        isCheckable = isCheckable,
        isPassword = isPassword,
        isFocused = isFocused
    )

    val bounds: RectF get() = RectF(left, top, right, bottom)

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height

    /**
     * Primary display label used for speech recognition matching and UI display.
     */
    val label: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.takeIf { it.isNotBlank() }
            ?: ""

    fun containsPoint(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }
}
