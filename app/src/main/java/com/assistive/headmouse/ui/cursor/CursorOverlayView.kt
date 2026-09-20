package com.assistive.headmouse.ui.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.assistive.headmouse.preferences.CursorStyle

/**
 * Custom overlay canvas view rendering the 3-dot moving gesture cursor
 * and the circular Dwell-Click countdown indicator.
 */
class CursorOverlayView(context: Context) : View(context) {

    // Active Cursor Style & Accent Color
    var cursorStyle: CursorStyle = CursorStyle.CLASSIC_TRIPLE_DOT
        private set

    var cursorColor: Int = Color.parseColor("#00E5FF")
        private set

    fun setCursorStyle(style: CursorStyle) {
        this.cursorStyle = style
        postInvalidateOnAnimation()
    }

    fun setCursorColorHex(colorHex: String) {
        try {
            this.cursorColor = Color.parseColor(colorHex)
            centerTargetPaint.color = this.cursorColor
            postInvalidateOnAnimation()
        } catch (e: Exception) {
            // Keep current color if parse fails
        }
    }

    // Cursor position in screen coordinates
    var cursorX: Float = 540f
        private set
    var cursorY: Float = 1200f
        private set

    // Dwell timer progress [0.0f .. 1.0f]
    var dwellProgress: Float = 0.0f
        private set

    var isPaused: Boolean = false
        private set

    var isTriggerFlash: Boolean = false
        private set

    // Paints
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val centerTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val centerTargetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val outerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val dwellRingBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    private val dwellRingProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        strokeCap = Paint.Cap.ROUND
    }

    // Dedicated Keyboard Mode Dwell Ring: Full vibrant light blue (#00E5FF) instead of green
    private val keyboardDwellRingProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        strokeCap = Paint.Cap.ROUND
    }

    private val keyboardCenterTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    // Keyboard Key Snapping & Highlight Paints
    private val keyHighlightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3300E5FF")
    }

    private val keyHighlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#00E5FF")
    }

    private val keyDwellRingBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#33FFFFFF")
    }

    private val keyDwellRingProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        color = Color.parseColor("#00E5FF")
        strokeCap = Paint.Cap.ROUND
    }

    private val keyDwellBounds = RectF()

    var activeKeyBounds: RectF? = null
        private set
    var activeKeyProgress: Float = 0f
        private set
    var isKeyClickFlash: Boolean = false
        private set

    fun updateActiveKey(bounds: RectF?, progress: Float) {
        this.activeKeyBounds = bounds
        this.activeKeyProgress = progress.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    fun triggerKeyClickAnimation() {
        isKeyClickFlash = true
        postInvalidateOnAnimation()
        postDelayed({
            isKeyClickFlash = false
            postInvalidateOnAnimation()
        }, 130)
    }

    // Semantic Target Node Snapping & Tactical Reticle
    private val targetNodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A00E5FF")
    }

    private val targetNodeCornerBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#00E5FF")
        strokeCap = Paint.Cap.ROUND
    }

    var activeTargetNodeBounds: RectF? = null
        private set
    var isTargetNodeClickFlash: Boolean = false
        private set

    fun updateActiveTargetNode(bounds: RectF?) {
        if (this.activeTargetNodeBounds != bounds) {
            this.activeTargetNodeBounds = bounds
            postInvalidateOnAnimation()
        }
    }

    fun triggerTargetNodeClickAnimation() {
        isTargetNodeClickFlash = true
        postInvalidateOnAnimation()
        postDelayed({
            isTargetNodeClickFlash = false
            postInvalidateOnAnimation()
        }, 130)
    }

    // Voice Command HUD Feedback
    private var voiceFeedbackText: String? = null
    private var voiceFeedbackTime: Long = 0L
    private val voicePillBounds = RectF()
    private val voiceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val voiceBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E612121E")
        style = Paint.Style.FILL
    }
    private val voiceBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    fun showVoiceFeedback(text: String) {
        this.voiceFeedbackText = text
        this.voiceFeedbackTime = System.currentTimeMillis()
        postInvalidateOnAnimation()
        postDelayed({
            postInvalidateOnAnimation()
        }, 1200)
    }

    // Recenter 5-Second Countdown HUD Feedback
    private var recenterCountdownSeconds: Int = 0
    private var recenterFeedbackText: String? = null
    private var recenterFeedbackTime: Long = 0L
    private val recenterPillBounds = RectF()
    private val recenterTargetBounds = RectF()

    private val recenterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14.5f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }

    private val recenterBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE12121E")
        style = Paint.Style.FILL
    }

    private val recenterBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val recenterTargetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    private val recenterTargetCrosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    fun showRecenterCountdown(seconds: Int) {
        this.recenterCountdownSeconds = seconds
        if (seconds > 0) {
            this.recenterFeedbackText = "Recentering in ${seconds}s... Look at center"
            this.recenterBorderPaint.color = Color.parseColor("#00E5FF")
        } else {
            this.recenterFeedbackText = "Centered! ✓"
            this.recenterBorderPaint.color = Color.parseColor("#00E676")
            this.recenterFeedbackTime = System.currentTimeMillis()
        }
        postInvalidateOnAnimation()
    }

    fun clearRecenterCountdown() {
        this.recenterCountdownSeconds = 0
        this.recenterFeedbackText = null
        postInvalidateOnAnimation()
    }

    private val pausedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFAA00")
        style = Paint.Style.FILL
    }

    private val dwellBounds = RectF()
    // 40% smaller radius (19px instead of 32px)
    private val radius = 19f

    private var targetX: Float = 540f
    private var targetY: Float = 1200f
    private var targetDwellProgress: Float = 0f

    fun updatePosition(x: Float, y: Float) {
        this.targetX = x
        this.targetY = y
        postInvalidateOnAnimation()
    }

    fun updateDwellProgress(progress: Float) {
        this.targetDwellProgress = progress.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    fun setPausedState(paused: Boolean) {
        this.isPaused = paused
        postInvalidateOnAnimation()
    }

    private var targetFps: Int = 60
    private var currentLerpRate: Float = 0.50f

    fun setTargetFps(fps: Int) {
        this.targetFps = fps
        this.currentLerpRate = when (fps) {
            30 -> 0.65f
            120 -> 0.38f
            else -> 0.50f // 60 FPS
        }
        postInvalidateOnAnimation()
    }

    fun triggerClickAnimation() {
        isTriggerFlash = true
        postInvalidateOnAnimation()
        postDelayed({
            isTriggerFlash = false
            postInvalidateOnAnimation()
        }, 120)
    }

    var hasFace: Boolean = true
        private set

    var isDockModeActive: Boolean = false
        private set

    var isKeyboardModeActive: Boolean = false
        private set

    fun setFaceDetected(detected: Boolean) {
        if (this.hasFace != detected) {
            this.hasFace = detected
            postInvalidateOnAnimation()
        }
    }

    fun setDockModeActive(active: Boolean) {
        if (this.isDockModeActive != active) {
            this.isDockModeActive = active
            postInvalidateOnAnimation()
        }
    }

    fun setKeyboardModeActive(active: Boolean) {
        if (this.isKeyboardModeActive != active) {
            this.isKeyboardModeActive = active
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Voice Command HUD at top-center of screen
        val now = System.currentTimeMillis()
        if (voiceFeedbackText != null && now - voiceFeedbackTime < 1100L) {
            val density = resources.displayMetrics.density
            val text = voiceFeedbackText!!
            val textWidth = voiceTextPaint.measureText(text)
            val pillWidth = textWidth + 36f * density
            val pillHeight = 34f * density
            val cx = width / 2f
            val top = 80f * density
            voicePillBounds.set(cx - pillWidth / 2f, top, cx + pillWidth / 2f, top + pillHeight)

            canvas.drawRoundRect(voicePillBounds, 17f * density, 17f * density, voiceBgPaint)
            canvas.drawRoundRect(voicePillBounds, 17f * density, 17f * density, voiceBorderPaint)
            canvas.drawText(text, cx, top + 22f * density, voiceTextPaint)
            postInvalidateOnAnimation()
        }

        // Draw Recenter Countdown HUD & Center Bullseye Target
        val isRecentering = recenterCountdownSeconds > 0 || (recenterFeedbackText != null && now - recenterFeedbackTime < 1200L)
        if (isRecentering && recenterFeedbackText != null) {
            val density = resources.displayMetrics.density
            val text = recenterFeedbackText!!
            val textWidth = recenterTextPaint.measureText(text)
            val pillWidth = textWidth + 36f * density
            val pillHeight = 36f * density
            val cx = width / 2f
            val top = 120f * density
            recenterPillBounds.set(cx - pillWidth / 2f, top, cx + pillWidth / 2f, top + pillHeight)

            canvas.drawRoundRect(recenterPillBounds, 18f * density, 18f * density, recenterBgPaint)
            canvas.drawRoundRect(recenterPillBounds, 18f * density, 18f * density, recenterBorderPaint)
            canvas.drawText(text, cx, top + 23f * density, recenterTextPaint)

            // Draw center bullseye target reticle at center of screen so user has an exact focal point
            val cy = height / 2f
            val targetRadius = 36f * density
            recenterTargetBounds.set(cx - targetRadius, cy - targetRadius, cx + targetRadius, cy + targetRadius)
            canvas.drawOval(recenterTargetBounds, recenterTargetRingPaint)
            canvas.drawLine(cx - targetRadius - 10f * density, cy, cx + targetRadius + 10f * density, cy, recenterTargetCrosshairPaint)
            canvas.drawLine(cx, cy - targetRadius - 10f * density, cx, cy + targetRadius + 10f * density, recenterTargetCrosshairPaint)

            postInvalidateOnAnimation()
        }

        // Completely hide/turn off cursor when head is not detected or when directly focused in Dock Mode
        if (!hasFace || isDockModeActive) {
            return
        }

        // Silky smooth 30-120 FPS Native VSYNC Lerp Interpolation
        val lerpRate = currentLerpRate
        cursorX += (targetX - cursorX) * lerpRate
        cursorY += (targetY - cursorY) * lerpRate
        dwellProgress += (targetDwellProgress - dwellProgress) * 0.55f

        if (isPaused) {
            // Completely hide cursor when in sleep/pause mode for clean rest
            return
        }

        // 1. Dedicated Keyboard Snapping Mode:
        // Free cursor is completely HIDDEN! Key rectangle is highlighted in light blue with circular dwell ring!
        val keyBounds = activeKeyBounds
        if (keyBounds != null) {
            val density = resources.displayMetrics.density
            val insetBounds = RectF(keyBounds).apply {
                inset(2.5f * density, 2.5f * density)
            }
            val cornerRadius = 6f * density

            // Pura character light blue color ho jaye (fill)
            keyHighlightFillPaint.color = if (isKeyClickFlash) Color.parseColor("#B300E5FF") else Color.parseColor("#3300E5FF")
            canvas.drawRoundRect(insetBounds, cornerRadius, cornerRadius, keyHighlightFillPaint)

            // Key Border (Light blue outline / White on flash)
            keyHighlightBorderPaint.color = if (isKeyClickFlash) Color.WHITE else Color.parseColor("#00E5FF")
            canvas.drawRoundRect(insetBounds, cornerRadius, cornerRadius, keyHighlightBorderPaint)

            // Circular dwell countdown ring right on top of the key
            if (activeKeyProgress > 0.01f) {
                val cx = keyBounds.centerX()
                val cy = keyBounds.centerY()
                val r = (kotlin.math.min(keyBounds.width(), keyBounds.height()) * 0.36f).coerceIn(12f * density, 26f * density)
                keyDwellBounds.set(cx - r, cy - r, cx + r, cy + r)

                canvas.drawOval(keyDwellBounds, keyDwellRingBgPaint)
                canvas.drawArc(keyDwellBounds, -90f, activeKeyProgress * 360f, false, keyDwellRingProgressPaint)
            }

            if (isKeyClickFlash || activeKeyProgress > 0.01f) {
                postInvalidateOnAnimation()
            }
            return
        }

        // Dedicated Keyboard Mode Fallback: Hide 3-dot cursor when keyboard mode active but no specific key locked
        if (isKeyboardModeActive) {
            return
        }

        val density = resources.displayMetrics.density

        val ringRadius = 16f * density

        // 1. Draw Dwell Timer Sweep Ring exactly centered on cursor
        if (dwellProgress > 0.01f) {
            dwellBounds.set(
                cursorX - ringRadius,
                cursorY - ringRadius,
                cursorX + ringRadius,
                cursorY + ringRadius
            )
            // Background tracking ring
            canvas.drawOval(dwellBounds, dwellRingBackgroundPaint)
            // Progress arc
            val sweepAngle = dwellProgress * 360f
            canvas.drawArc(dwellBounds, -90f, sweepAngle, false, dwellRingProgressPaint)
        }

        // 2. Render Active Cursor Style (1 of 10 Distinct Styles)
        val orbitAngle = ((System.currentTimeMillis() % 2400L) / 2400f) * (2f * Math.PI.toFloat())
        CursorRenderer.drawCursor(
            canvas = canvas,
            cx = cursorX,
            cy = cursorY,
            style = cursorStyle,
            accentColor = cursorColor,
            isFlash = isTriggerFlash,
            density = density,
            orbitAngleRad = orbitAngle
        )

        // Continuous 60 FPS animation loop while moving, progressing, or orbiting
        val dx = kotlin.math.abs(targetX - cursorX)
        val dy = kotlin.math.abs(targetY - cursorY)
        val dp = kotlin.math.abs(targetDwellProgress - dwellProgress)
        val isOrbitalActive = (cursorStyle == CursorStyle.DUAL_ORBITAL)
        if (dx > 0.15f || dy > 0.15f || dp > 0.003f || isOrbitalActive) {
            postInvalidateOnAnimation()
        } else {
            cursorX = targetX
            cursorY = targetY
            dwellProgress = targetDwellProgress
        }
    }
}
