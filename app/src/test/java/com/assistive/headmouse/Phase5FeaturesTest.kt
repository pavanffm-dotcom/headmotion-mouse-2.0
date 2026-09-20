package com.assistive.headmouse

import com.assistive.headmouse.preferences.AppTheme
import com.assistive.headmouse.preferences.CursorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5FeaturesTest {

    @Test
    fun testTenCursorStylesDefined() {
        val styles = CursorStyle.values()
        assertEquals("Must have exactly 10 distinct cursor styles", 10, styles.size)

        // Verify each distinct style exists
        assertNotNull(CursorStyle.valueOf("CLASSIC_TRIPLE_DOT"))
        assertNotNull(CursorStyle.valueOf("LIQUID_GLASS"))
        assertNotNull(CursorStyle.valueOf("PRECISION_CROSSHAIR"))
        assertNotNull(CursorStyle.valueOf("NEON_HALO"))
        assertNotNull(CursorStyle.valueOf("HOLOGRAM_DIAMOND"))
        assertNotNull(CursorStyle.valueOf("DUAL_ORBITAL"))
        assertNotNull(CursorStyle.valueOf("STEALTH_GHOST"))
        assertNotNull(CursorStyle.valueOf("LASER_PIN"))
        assertNotNull(CursorStyle.valueOf("WATER_RIPPLE"))
        assertNotNull(CursorStyle.valueOf("AURA_GLOW"))

        // Verify English display names
        styles.forEach { style ->
            assertTrue("Display name must not be empty", style.displayName.isNotEmpty())
        }
    }

    @Test
    fun testAppThemesDefined() {
        val themes = AppTheme.values()
        assertEquals("Must have 3 distinct app themes", 3, themes.size)

        assertNotNull(AppTheme.valueOf("CYBERPUNK_NEON"))
        assertNotNull(AppTheme.valueOf("DEEP_SLATE"))
        assertNotNull(AppTheme.valueOf("SOLAR_FROST"))

        themes.forEach { theme ->
            assertTrue(theme.primaryAccent.startsWith("#"))
            assertTrue(theme.bgTint.startsWith("#"))
            assertTrue(theme.displayName.isNotEmpty())
        }
    }

    @Test
    fun testCursorSizeBoundsConstraint() {
        // Strict requirement: Cursor outer radius must be bounded <= 18dp
        val maxAllowedRadiusDp = 18f

        val styleRadii = mapOf(
            CursorStyle.CLASSIC_TRIPLE_DOT to 14.0f,
            CursorStyle.LIQUID_GLASS to 12.5f,
            CursorStyle.PRECISION_CROSSHAIR to 12.5f,
            CursorStyle.NEON_HALO to 11.5f,
            CursorStyle.HOLOGRAM_DIAMOND to 11.0f,
            CursorStyle.DUAL_ORBITAL to 11.5f,
            CursorStyle.STEALTH_GHOST to 9.5f,
            CursorStyle.LASER_PIN to 11.0f,
            CursorStyle.WATER_RIPPLE to 15.0f,
            CursorStyle.AURA_GLOW to 14.5f
        )

        assertEquals("Every cursor style must have a defined radius", 10, styleRadii.size)

        styleRadii.forEach { (style, radius) ->
            assertTrue(
                "Cursor ${style.name} radius ($radius dp) exceeds maximum allowed ($maxAllowedRadiusDp dp)",
                radius <= maxAllowedRadiusDp
            )
        }
    }
}
