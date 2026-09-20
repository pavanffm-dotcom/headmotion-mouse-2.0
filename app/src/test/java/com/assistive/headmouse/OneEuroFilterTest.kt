package com.assistive.headmouse

import com.assistive.headmouse.tracking.filter.OneEuroFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class OneEuroFilterTest {

    private lateinit var filter: OneEuroFilter

    @Before
    fun setUp() {
        filter = OneEuroFilter(minCutoff = 1.0, beta = 0.05, dCutoff = 1.0)
    }

    @Test
    fun `test initial value passes through without distortion`() {
        val initial = 100.0
        val result = filter.filter(initial, 0.0)
        assertEquals(initial, result, 0.0001)
    }

    @Test
    fun `test tremor attenuation on stationary input`() {
        val baseSignal = 500.0
        var currentTime = 0.0
        val dt = 1.0 / 30.0 // 30 FPS

        var sumNoise = 0.0
        var sumFilteredNoise = 0.0
        var count = 0

        for (i in 0 until 100) {
            currentTime += dt
            // Physiological tremor: 6Hz oscillation + 8Hz micro-tremor
            val tremor = 4.0 * kotlin.math.sin(2.0 * Math.PI * 6.0 * currentTime) + 2.0 * kotlin.math.sin(2.0 * Math.PI * 8.0 * currentTime)
            val noisyInput = baseSignal + tremor
            val filtered = filter.filter(noisyInput, currentTime)

            if (i > 15) { // After warm up
                sumNoise += abs(tremor)
                sumFilteredNoise += abs(filtered - baseSignal)
                count++
            }
        }

        val avgNoise = sumNoise / count
        val avgFilteredNoise = sumFilteredNoise / count

        // Filtered noise should be significantly smaller than raw jitter (>60% reduction)
        assertTrue("Filtered average jitter ($avgFilteredNoise) must be < raw jitter ($avgNoise * 0.4)", avgFilteredNoise < avgNoise * 0.4)
    }

    @Test
    fun `test fast movement adapts dynamically without lag`() {
        var currentTime = 0.0
        val dt = 1.0 / 30.0

        // Warm up at 100.0
        for (i in 0 until 10) {
            currentTime += dt
            filter.filter(100.0, currentTime)
        }

        // Fast jump to 800.0
        var filteredVal = 100.0
        for (i in 0 until 15) {
            currentTime += dt
            filteredVal = filter.filter(800.0, currentTime)
        }

        // Should rapidly converge close to 800.0
        assertTrue("Should reach close to target 800.0 within 15 frames", filteredVal > 750.0)
    }

    @Test
    fun `test filter reset restores initial state`() {
        filter.filter(100.0, 0.0)
        filter.filter(200.0, 0.1)
        filter.reset()

        val freshResult = filter.filter(500.0, 1.0)
        assertEquals(500.0, freshResult, 0.0001)
    }
}
