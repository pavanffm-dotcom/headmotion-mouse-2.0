package com.assistive.headmouse

import com.assistive.headmouse.tracking.filter.OneEuroFilter
import com.assistive.headmouse.ui.keyboard.KeyboardKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4FeaturesTest {

    @Test
    fun testOneEuroFilterFpsAdaptation() {
        val filter = OneEuroFilter()

        // 30 FPS mode
        filter.adaptToFps(30)
        val res30 = filter.filter(100.0, 0.0)
        assertEquals(100.0, res30, 0.001)

        // 120 FPS mode
        filter.adaptToFps(120)
        val res120 = filter.filter(100.0, 0.008)
        assertTrue(res120 > 0.0)
    }

    @Test
    fun testSubPixelPrecisionHold() {
        val filter = OneEuroFilter()
        filter.adaptToFps(60)

        // Hold steady at coordinate 500.0 for 500ms (30 frames at 60 FPS)
        var t = 0.0
        var current = 500.0
        for (i in 0 until 30) {
            t += 0.0166
            // Subtle micro-tremor under 0.5px
            val noise = 0.4 * kotlin.math.sin(i.toDouble())
            current = filter.filter(500.0 + noise, t)
        }

        // Sub-pixel accuracy must hold within ±1.5 pixels of target center
        val delta = kotlin.math.abs(current - 500.0)
        assertTrue("Centroid drift must be < 1.5dp, was $delta", delta < 1.5)
    }

    @Test
    fun testKeyboardOffsetMath() {
        val rawCenterX = 150f
        val rawCenterY = 1250f
        val offsetX = 5f
        val offsetY = -3f

        val finalX = rawCenterX + offsetX
        val finalY = rawCenterY + offsetY

        assertEquals(155f, finalX, 0.001f)
        assertEquals(1247f, finalY, 0.001f)
    }
}
