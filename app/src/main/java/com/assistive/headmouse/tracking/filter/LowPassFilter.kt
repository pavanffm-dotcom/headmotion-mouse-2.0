package com.assistive.headmouse.tracking.filter

/**
 * Standard Exponential Moving Average Low-Pass Filter
 */
class LowPassFilter(private var alpha: Float = 0.5f) {
    private var lastValue: Float? = null

    fun setAlpha(alpha: Float) {
        this.alpha = alpha.coerceIn(0.01f, 1.0f)
    }

    fun filter(current: Float): Float {
        val prev = lastValue ?: current
        val filtered = prev + alpha * (current - prev)
        lastValue = filtered
        return filtered
    }

    fun reset() {
        lastValue = null
    }
}
