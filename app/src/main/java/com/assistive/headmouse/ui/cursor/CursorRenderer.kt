package com.assistive.headmouse.ui.cursor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.assistive.headmouse.preferences.CursorStyle
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared high-performance rendering engine for all 10 distinct cursor styles.
 * Guarantees strict compact size constraint (outer radius <= 16-18dp)
 * across all styles to ensure precision and keyboard key compatibility.
 */
object CursorRenderer {

    // Shared reusable paints to prevent allocations on hot draw loop
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val whiteFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val whiteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    private val diamondPath = Path()
    private val chevronPath = Path()
    private val tempRect = RectF()

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }

    fun drawCursor(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        style: CursorStyle,
        accentColor: Int,
        isFlash: Boolean = false,
        density: Float = 2.5f,
        orbitAngleRad: Float = 0f
    ) {
        val activeColor = if (isFlash) Color.parseColor("#00E676") else accentColor

        when (style) {
            CursorStyle.CLASSIC_TRIPLE_DOT -> {
                // 1. Classic Triple-Dot
                val dotSpacing = 8.5f * density
                val dotRadius = 2.2f * density
                val centerDotRadius = 3.6f * density

                // Outer subtle halo
                strokePaint.color = withAlpha(activeColor, 0x33)
                strokePaint.strokeWidth = 1.5f * density
                canvas.drawCircle(cx, cy, 14f * density, strokePaint)

                // Lateral Dots
                canvas.drawCircle(cx - dotSpacing, cy, dotRadius, whiteFillPaint)
                canvas.drawCircle(cx + dotSpacing, cy, dotRadius, whiteFillPaint)

                // Center Middle Dot
                fillPaint.color = activeColor
                canvas.drawCircle(cx, cy, centerDotRadius, fillPaint)
                whiteStrokePaint.strokeWidth = 1.4f * density
                canvas.drawCircle(cx, cy, centerDotRadius, whiteStrokePaint)
            }

            CursorStyle.LIQUID_GLASS -> {
                // 2. Liquid Glass Drop (Frosted droplet with specular reflection)
                val glassRadius = 12.5f * density

                // Soft caustic shadow / outer boundary
                strokePaint.color = Color.parseColor("#4DFFFFFF")
                strokePaint.strokeWidth = 1.4f * density
                canvas.drawCircle(cx, cy, glassRadius, strokePaint)

                // Translucent liquid body
                fillPaint.color = withAlpha(activeColor, 0x38)
                canvas.drawCircle(cx, cy, glassRadius, fillPaint)

                // Inner refraction ring
                strokePaint.color = withAlpha(activeColor, 0x55)
                strokePaint.strokeWidth = 1.0f * density
                canvas.drawCircle(cx, cy, glassRadius * 0.72f, strokePaint)

                // Specular glare reflection bead at top-left
                whiteFillPaint.color = Color.parseColor("#E6FFFFFF")
                val glareX = cx - 4.5f * density
                val glareY = cy - 4.5f * density
                canvas.drawCircle(glareX, glareY, 2.3f * density, whiteFillPaint)

                // Center focal point
                fillPaint.color = activeColor
                canvas.drawCircle(cx, cy, 2.5f * density, fillPaint)
                whiteStrokePaint.strokeWidth = 1.2f * density
                canvas.drawCircle(cx, cy, 2.5f * density, whiteStrokePaint)
            }

            CursorStyle.PRECISION_CROSSHAIR -> {
                // 3. Precision Crosshair (Aviation tactical reticle)
                val gap = 3.5f * density
                val length = 12.5f * density

                strokePaint.color = activeColor
                strokePaint.strokeWidth = 1.5f * density

                // 4 Crosshair lines
                canvas.drawLine(cx, cy - gap, cx, cy - length, strokePaint)
                canvas.drawLine(cx, cy + gap, cx, cy + length, strokePaint)
                canvas.drawLine(cx - gap, cy, cx - length, cy, strokePaint)
                canvas.drawLine(cx + gap, cy, cx + length, cy, strokePaint)

                // 4 Outer corner ticks
                val cornerDist = 8.5f * density
                val tickLen = 2.5f * density
                whiteStrokePaint.strokeWidth = 1.0f * density
                // Top-left
                canvas.drawLine(cx - cornerDist, cy - cornerDist, cx - cornerDist + tickLen, cy - cornerDist, whiteStrokePaint)
                canvas.drawLine(cx - cornerDist, cy - cornerDist, cx - cornerDist, cy - cornerDist + tickLen, whiteStrokePaint)
                // Top-right
                canvas.drawLine(cx + cornerDist, cy - cornerDist, cx + cornerDist - tickLen, cy - cornerDist, whiteStrokePaint)
                canvas.drawLine(cx + cornerDist, cy - cornerDist, cx + cornerDist, cy - cornerDist + tickLen, whiteStrokePaint)
                // Bottom-left
                canvas.drawLine(cx - cornerDist, cy + cornerDist, cx - cornerDist + tickLen, cy + cornerDist, whiteStrokePaint)
                canvas.drawLine(cx - cornerDist, cy + cornerDist, cx - cornerDist, cy + cornerDist - tickLen, whiteStrokePaint)
                // Bottom-right
                canvas.drawLine(cx + cornerDist, cy + cornerDist, cx + cornerDist - tickLen, cy + cornerDist, whiteStrokePaint)
                canvas.drawLine(cx + cornerDist, cy + cornerDist, cx + cornerDist, cy + cornerDist - tickLen, whiteStrokePaint)

                // Sub-pixel center dot
                fillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, 1.8f * density, fillPaint)
            }

            CursorStyle.NEON_HALO -> {
                // 4. Neon Halo (High-energy glowing cyber ring)
                val haloRadius = 11.5f * density

                // Outer diffuse glow
                strokePaint.color = withAlpha(activeColor, 0x30)
                strokePaint.strokeWidth = 4.2f * density
                canvas.drawCircle(cx, cy, haloRadius, strokePaint)

                // Sharp luminous ring
                strokePaint.color = activeColor
                strokePaint.strokeWidth = 2.0f * density
                canvas.drawCircle(cx, cy, haloRadius, strokePaint)

                // Sharp sub-pixel center dot
                whiteFillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, 2.2f * density, whiteFillPaint)
            }

            CursorStyle.HOLOGRAM_DIAMOND -> {
                // 5. Hologram Diamond (Sci-Fi 45-degree diamond wireframe)
                val halfSize = 11.0f * density
                diamondPath.reset()
                diamondPath.moveTo(cx, cy - halfSize)
                diamondPath.lineTo(cx + halfSize, cy)
                diamondPath.lineTo(cx, cy + halfSize)
                diamondPath.lineTo(cx - halfSize, cy)
                diamondPath.close()

                // Translucent facet fill
                fillPaint.color = withAlpha(activeColor, 0x24)
                canvas.drawPath(diamondPath, fillPaint)

                // Diamond wireframe outline
                strokePaint.color = activeColor
                strokePaint.strokeWidth = 1.6f * density
                canvas.drawPath(diamondPath, strokePaint)

                // 4 Vertex node dots
                val nodeR = 1.6f * density
                whiteFillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy - halfSize, nodeR, whiteFillPaint)
                canvas.drawCircle(cx + halfSize, cy, nodeR, whiteFillPaint)
                canvas.drawCircle(cx, cy + halfSize, nodeR, whiteFillPaint)
                canvas.drawCircle(cx - halfSize, cy, nodeR, whiteFillPaint)

                // Center core
                fillPaint.color = activeColor
                canvas.drawCircle(cx, cy, 2.6f * density, fillPaint)
            }

            CursorStyle.DUAL_ORBITAL -> {
                // 6. Dual Orbital (Twin satellites revolving along circular track)
                val orbitRadius = 11.5f * density

                // Orbital guide ring
                strokePaint.color = Color.parseColor("#38FFFFFF")
                strokePaint.strokeWidth = 1.0f * density
                canvas.drawCircle(cx, cy, orbitRadius, strokePaint)

                // Central anchor dot
                fillPaint.color = activeColor
                canvas.drawCircle(cx, cy, 3.2f * density, fillPaint)
                whiteStrokePaint.strokeWidth = 1.2f * density
                canvas.drawCircle(cx, cy, 3.2f * density, whiteStrokePaint)

                // Twin orbiting satellites
                val sat1X = cx + orbitRadius * cos(orbitAngleRad)
                val sat1Y = cy + orbitRadius * sin(orbitAngleRad)
                val sat2X = cx - orbitRadius * cos(orbitAngleRad)
                val sat2Y = cy - orbitRadius * sin(orbitAngleRad)

                whiteFillPaint.color = Color.WHITE
                canvas.drawCircle(sat1X, sat1Y, 2.4f * density, whiteFillPaint)
                fillPaint.color = activeColor
                canvas.drawCircle(sat2X, sat2Y, 2.4f * density, fillPaint)
            }

            CursorStyle.STEALTH_GHOST -> {
                // 7. Stealth Ghost Dot (Ultra-clean translucent frosted dot)
                val ghostRadius = 9.5f * density

                // Frosted translucent fill
                fillPaint.color = Color.parseColor("#2EFFFFFF")
                canvas.drawCircle(cx, cy, ghostRadius, fillPaint)

                // Hairline border
                strokePaint.color = Color.parseColor("#55FFFFFF")
                strokePaint.strokeWidth = 1.0f * density
                canvas.drawCircle(cx, cy, ghostRadius, strokePaint)

                // Center high-transparency dot
                whiteFillPaint.color = Color.parseColor("#99FFFFFF")
                canvas.drawCircle(cx, cy, 2.2f * density, whiteFillPaint)
            }

            CursorStyle.LASER_PIN -> {
                // 8. Laser Precision Pin (High-contrast acute laser target)
                val bracketDist = 11.0f * density
                val bracketArm = 3.2f * density

                strokePaint.color = activeColor
                strokePaint.strokeWidth = 1.4f * density

                // 4 Inward chevrons pointing to center
                // Top-Left corner
                chevronPath.reset()
                chevronPath.moveTo(cx - bracketDist, cy - bracketDist + bracketArm)
                chevronPath.lineTo(cx - bracketDist, cy - bracketDist)
                chevronPath.lineTo(cx - bracketDist + bracketArm, cy - bracketDist)
                canvas.drawPath(chevronPath, strokePaint)

                // Top-Right corner
                chevronPath.reset()
                chevronPath.moveTo(cx + bracketDist - bracketArm, cy - bracketDist)
                chevronPath.lineTo(cx + bracketDist, cy - bracketDist)
                chevronPath.lineTo(cx + bracketDist, cy - bracketDist + bracketArm)
                canvas.drawPath(chevronPath, strokePaint)

                // Bottom-Left corner
                chevronPath.reset()
                chevronPath.moveTo(cx - bracketDist, cy + bracketDist - bracketArm)
                chevronPath.lineTo(cx - bracketDist, cy + bracketDist)
                chevronPath.lineTo(cx - bracketDist + bracketArm, cy + bracketDist)
                canvas.drawPath(chevronPath, strokePaint)

                // Bottom-Right corner
                chevronPath.reset()
                chevronPath.moveTo(cx + bracketDist - bracketArm, cy + bracketDist)
                chevronPath.lineTo(cx + bracketDist, cy + bracketDist)
                chevronPath.lineTo(cx + bracketDist, cy + bracketDist - bracketArm)
                canvas.drawPath(chevronPath, strokePaint)

                // Intense laser core
                fillPaint.color = Color.parseColor("#FF1744")
                canvas.drawCircle(cx, cy, 3.4f * density, fillPaint)
                whiteFillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, 1.6f * density, whiteFillPaint)
            }

            CursorStyle.WATER_RIPPLE -> {
                // 9. Water Ripple (Concentric liquid rings)
                val r1 = 6.0f * density
                val r2 = 11.5f * density
                val r3 = 15.0f * density

                // Outer boundary wave
                strokePaint.color = withAlpha(activeColor, 0x22)
                strokePaint.strokeWidth = 0.9f * density
                canvas.drawCircle(cx, cy, r3, strokePaint)

                // Mid ripple
                strokePaint.color = withAlpha(activeColor, 0x50)
                strokePaint.strokeWidth = 1.3f * density
                canvas.drawCircle(cx, cy, r2, strokePaint)

                // Inner ripple
                strokePaint.color = withAlpha(activeColor, 0x90)
                strokePaint.strokeWidth = 1.6f * density
                canvas.drawCircle(cx, cy, r1, strokePaint)

                // Center water bead
                fillPaint.color = activeColor
                canvas.drawCircle(cx, cy, 2.6f * density, fillPaint)
                whiteFillPaint.color = Color.WHITE
                canvas.drawCircle(cx - 0.8f * density, cy - 0.8f * density, 1.0f * density, whiteFillPaint)
            }

            CursorStyle.AURA_GLOW -> {
                // 10. Aura Glow Bloom (Multi-layered diffused radiant aura)
                val auraR1 = 14.5f * density
                val auraR2 = 9.5f * density
                val auraR3 = 5.5f * density

                // Layer 1
                fillPaint.color = withAlpha(activeColor, 0x15)
                canvas.drawCircle(cx, cy, auraR1, fillPaint)

                // Layer 2
                fillPaint.color = withAlpha(activeColor, 0x30)
                canvas.drawCircle(cx, cy, auraR2, fillPaint)

                // Layer 3
                fillPaint.color = withAlpha(activeColor, 0x55)
                canvas.drawCircle(cx, cy, auraR3, fillPaint)

                // Luminous solid center bead
                fillPaint.color = activeColor
                canvas.drawCircle(cx, cy, 2.8f * density, fillPaint)
                whiteStrokePaint.strokeWidth = 1.2f * density
                canvas.drawCircle(cx, cy, 2.8f * density, whiteStrokePaint)
            }
        }
    }
}
