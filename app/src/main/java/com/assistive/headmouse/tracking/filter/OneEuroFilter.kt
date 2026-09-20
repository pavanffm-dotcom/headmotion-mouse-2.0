package com.assistive.headmouse.tracking.filter

import kotlin.math.PI

/**
 * One Euro (1€) Filter
 * An adaptive low-pass filter designed to eliminate physical tremors and jitter
 * during slow/resting movements while maintaining zero lag during fast movements.
 */
class OneEuroFilter(
    private var minCutoff: Double = 1.0,
    private var beta: Double = 0.05,
    private var dCutoff: Double = 1.0
) {
    private var xPrevious: Double? = null
    private var dxPrevious: Double = 0.0
    private var tPrevious: Double? = null

    fun setParameters(minCutoff: Double, beta: Double, dCutoff: Double = 1.0) {
        this.minCutoff = minCutoff
        this.beta = beta
        this.dCutoff = dCutoff
    }

    fun filter(x: Double, timestampSeconds: Double): Double {
        if (xPrevious == null || tPrevious == null) {
            xPrevious = x
            dxPrevious = 0.0
            tPrevious = timestampSeconds
            return x
        }

        val rawDt = timestampSeconds - tPrevious!!
        if (rawDt <= 0.0) {
            return xPrevious!!
        }
        val dt = rawDt.coerceIn(0.008, 0.1)

        // 1. Calculate derivative (velocity)
        val dx = (x - xPrevious!!) / dt
        val edx = exponentialSmoothing(dx, dxPrevious, alpha(dt, dCutoff))
        dxPrevious = edx

        // 2. Compute dynamic cutoff based on speed
        val cutoff = minCutoff + beta * kotlin.math.abs(edx)

        // 3. Filter position
        val xFiltered = exponentialSmoothing(x, xPrevious!!, alpha(dt, cutoff))
        xPrevious = xFiltered
        tPrevious = timestampSeconds

        return xFiltered
    }

    fun reset() {
        xPrevious = null
        dxPrevious = 0.0
        tPrevious = null
    }

    fun adaptToFps(targetFps: Int) {
        when (targetFps) {
            30 -> setParameters(0.08, 0.003)
            120 -> setParameters(0.035, 0.0055)
            else -> setParameters(0.05, 0.004)
        }
    }

    private fun alpha(dt: Double, cutoff: Double): Double {
        val te = 2.0 * PI * cutoff * dt
        return te / (te + 1.0)
    }

    private fun exponentialSmoothing(current: Double, previous: Double, alpha: Double): Double {
        return alpha * current + (1.0 - alpha) * previous
    }
}
