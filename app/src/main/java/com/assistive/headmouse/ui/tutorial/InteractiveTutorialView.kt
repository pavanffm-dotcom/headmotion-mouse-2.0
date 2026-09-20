package com.assistive.headmouse.ui.tutorial

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.assistive.headmouse.tracking.model.TrackingMode
import kotlin.math.*

/**
 * Interactive visual canvas rendering animated motion trails, simulated sensors,
 * and live target dwell clicks for all 4 tracking modes.
 */
class InteractiveTutorialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentMode: TrackingMode = TrackingMode.HEAD_ONLY

    private var progress: Float = 0f
    private var animator: ValueAnimator? = null

    // Motion Trail Points
    private val trailPoints = ArrayList<PointF>()
    private val maxTrailLength = 22

    // Target Click Counter
    private var target1Clicks = 0
    private var target2Clicks = 0
    private var lastTarget1ClickFrame = false
    private var lastTarget2ClickFrame = false

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#16181F")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#262938")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12FFFFFF")
        strokeWidth = 1f
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val cursorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val dwellProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val targetBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222533")
        style = Paint.Style.FILL
    }

    private val targetActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E3838")
        style = Paint.Style.FILL
    }

    private val targetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B4252")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val sensorBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1D27")
        style = Paint.Style.FILL
    }

    private val sensorAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val voiceWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    init {
        // Animation starts on demand when Tab 5 (Settings/Tutorial) becomes visible
    }

    fun setTutorialMode(mode: TrackingMode) {
        currentMode = mode
        trailPoints.clear()
        invalidate()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                updateSimulation()
                invalidate()
            }
            start()
        }
    }

    fun pauseTutorial() {
        animator?.cancel()
    }

    fun resumeTutorial() {
        if (animator?.isRunning != true) {
            startAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Do not auto-start indefinitely; wait for tab visibility
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            resumeTutorial()
        } else {
            pauseTutorial()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            resumeTutorial()
        } else {
            pauseTutorial()
        }
    }

    // Interactive Touch to add points
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
            trailPoints.add(PointF(event.x, event.y))
            if (trailPoints.size > maxTrailLength) {
                trailPoints.removeAt(0)
            }
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    private var currentCursorX = 0f
    private var currentCursorY = 0f
    private var dwellRatio = 0f
    private var isTarget1Hovered = false
    private var isTarget2Hovered = false
    private var voiceActive = false

    private fun updateSimulation() {
        val w = width.toFloat().coerceAtLeast(300f)
        val h = height.toFloat().coerceAtLeast(300f)

        // Target coordinates
        val t1X = w * 0.28f
        val t1Y = h * 0.44f
        val t2X = w * 0.72f
        val t2Y = h * 0.74f
        val centerX = w * 0.50f
        val centerY = h * 0.58f

        isTarget1Hovered = false
        isTarget2Hovered = false
        dwellRatio = 0f
        voiceActive = false

        // Progress Timeline [0..1]
        // 0.00 - 0.15: Start at Center -> Move to Target 1
        // 0.15 - 0.40: Dwell at Target 1 (Click occurs at 0.38)
        // 0.40 - 0.60: Move Target 1 -> Target 2
        // 0.60 - 0.85: Dwell at Target 2 (Click occurs at 0.83)
        // 0.85 - 1.00: Return Target 2 -> Center

        when {
            progress < 0.15f -> {
                val t = progress / 0.15f
                val ease = easeInOut(t)
                currentCursorX = lerp(centerX, t1X, ease)
                currentCursorY = lerp(centerY, t1Y, ease)
                lastTarget1ClickFrame = false
            }
            progress < 0.40f -> {
                currentCursorX = t1X
                currentCursorY = t1Y
                isTarget1Hovered = true
                val dwellT = (progress - 0.15f) / 0.25f
                dwellRatio = dwellT.coerceIn(0f, 1f)
                if (currentMode == TrackingMode.HEAD_AND_VOICE || currentMode == TrackingMode.EYE_AND_VOICE) {
                    if (dwellT > 0.4f) voiceActive = true
                }
                if (dwellT >= 0.95f && !lastTarget1ClickFrame) {
                    target1Clicks++
                    lastTarget1ClickFrame = true
                }
            }
            progress < 0.60f -> {
                val t = (progress - 0.40f) / 0.20f
                val ease = easeInOut(t)
                // Add natural curve
                val arc = sin(t * Math.PI.toFloat()) * (h * 0.08f)
                currentCursorX = lerp(t1X, t2X, ease)
                currentCursorY = lerp(t1Y, t2Y, ease) - arc
                lastTarget2ClickFrame = false
            }
            progress < 0.85f -> {
                currentCursorX = t2X
                currentCursorY = t2Y
                isTarget2Hovered = true
                val dwellT = (progress - 0.60f) / 0.25f
                dwellRatio = dwellT.coerceIn(0f, 1f)
                if (currentMode == TrackingMode.HEAD_AND_VOICE || currentMode == TrackingMode.EYE_AND_VOICE) {
                    if (dwellT > 0.4f) voiceActive = true
                }
                if (dwellT >= 0.95f && !lastTarget2ClickFrame) {
                    target2Clicks++
                    lastTarget2ClickFrame = true
                }
            }
            else -> {
                val t = (progress - 0.85f) / 0.15f
                val ease = easeInOut(t)
                currentCursorX = lerp(t2X, centerX, ease)
                currentCursorY = lerp(t2Y, centerY, ease)
            }
        }

        trailPoints.add(PointF(currentCursorX, currentCursorY))
        if (trailPoints.size > maxTrailLength) {
            trailPoints.removeAt(0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Base Dark Card Canvas
        val cardRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, 24f, 24f, bgPaint)
        canvas.drawRoundRect(cardRect, 24f, 24f, borderPaint)

        // Subtle background grid
        val step = 40f
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }

        // 2. Simulated Sensor & Avatar Header Bar (Top 70dp)
        drawSensorHeader(canvas, w)

        // 3. Targets
        drawTarget(canvas, w * 0.28f, h * 0.44f, "Target 1", target1Clicks, isTarget1Hovered)
        drawTarget(canvas, w * 0.72f, h * 0.74f, "Target 2", target2Clicks, isTarget2Hovered)

        // 4. Motion Trail
        drawMotionTrail(canvas)

        // 5. Simulated Cursor with Dwell Ring
        drawCursor(canvas)

        // 6. Voice Wave / Speech Bubble (if Voice Mode active)
        if (voiceActive) {
            drawVoiceBubble(canvas, currentCursorX, currentCursorY - 50f)
        }
    }

    private fun drawSensorHeader(canvas: Canvas, w: Float) {
        val headerRect = RectF(0f, 0f, w, 90f)
        canvas.drawRoundRect(headerRect, 24f, 24f, sensorBarPaint)

        // Draw Avatar on Left
        val avatarCenterX = 50f
        val avatarCenterY = 45f

        when (currentMode) {
            TrackingMode.HEAD_ONLY, TrackingMode.HEAD_AND_VOICE -> {
                // 3D Head Avatar
                avatarPaint.color = Color.parseColor("#00E5FF")
                canvas.drawCircle(avatarCenterX, avatarCenterY, 22f, avatarPaint)
                // Head tilt indicator arrow
                val tiltX = ((currentCursorX / width.toFloat()) - 0.5f) * 20f
                val tiltY = ((currentCursorY / height.toFloat()) - 0.5f) * 20f
                val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                }
                canvas.drawLine(avatarCenterX, avatarCenterY, avatarCenterX + tiltX, avatarCenterY + tiltY, arrowPaint)
                canvas.drawCircle(avatarCenterX + tiltX, avatarCenterY + tiltY, 4f, arrowPaint.apply { style = Paint.Style.FILL })

                // Text status
                sensorAccentPaint.color = Color.parseColor("#00E5FF")
                canvas.drawText("HEAD SENSOR: 3D Tilt Active", 90f, 40f, sensorAccentPaint)

                val pitch = ((currentCursorY / height.toFloat()) - 0.5f) * 40f
                val yaw = ((currentCursorX / width.toFloat()) - 0.5f) * 50f
                subtitlePaint.textAlign = Paint.Align.LEFT
                canvas.drawText(String.format("Tilt Pitch: %+.1f°  Yaw: %+.1f°", pitch, yaw), 90f, 68f, subtitlePaint)
            }
            TrackingMode.EYE_ONLY, TrackingMode.EYE_AND_VOICE -> {
                // Eye Avatar (Pair of Eyes)
                avatarPaint.color = Color.WHITE
                canvas.drawRoundRect(RectF(avatarCenterX - 22f, avatarCenterY - 12f, avatarCenterX - 4f, avatarCenterY + 12f), 10f, 10f, avatarPaint)
                canvas.drawRoundRect(RectF(avatarCenterX + 4f, avatarCenterY - 12f, avatarCenterX + 22f, avatarCenterY + 12f), 10f, 10f, avatarPaint)

                // Irises following cursor
                val gazeOffset = ((currentCursorX / width.toFloat()) - 0.5f) * 10f
                avatarPaint.color = Color.parseColor("#00E5FF")
                canvas.drawCircle(avatarCenterX - 13f + gazeOffset, avatarCenterY, 5f, avatarPaint)
                canvas.drawCircle(avatarCenterX + 13f + gazeOffset, avatarCenterY, 5f, avatarPaint)

                sensorAccentPaint.color = Color.parseColor("#00E5FF")
                canvas.drawText("EYE SENSOR: Camera Pupil Gaze", 90f, 40f, sensorAccentPaint)

                val gx = (currentCursorX / width.toFloat()).coerceIn(0f, 1f)
                val gy = (currentCursorY / height.toFloat()).coerceIn(0f, 1f)
                subtitlePaint.textAlign = Paint.Align.LEFT
                canvas.drawText(String.format("Gaze Point: (%.2f, %.2f)", gx, gy), 90f, 68f, subtitlePaint)
            }
        }

        // Voice Badge on Top Right
        if (currentMode == TrackingMode.HEAD_AND_VOICE || currentMode == TrackingMode.EYE_AND_VOICE) {
            val micBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (voiceActive) Color.parseColor("#44FFD600") else Color.parseColor("#22FFD600")
                style = Paint.Style.FILL
            }
            val micBadgeRect = RectF(w - 180f, 20f, w - 20f, 70f)
            canvas.drawRoundRect(micBadgeRect, 14f, 14f, micBadgeBg)

            val micTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD600")
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val label = if (voiceActive) "VOICE: \"CLICK!\"" else "MIC: READY"
            canvas.drawText(label, micBadgeRect.centerX(), micBadgeRect.centerY() + 7f, micTextPaint)
        }
    }

    private fun drawTarget(canvas: Canvas, cx: Float, cy: Float, title: String, clicks: Int, hovered: Boolean) {
        val tw = 120f
        val th = 70f
        val rect = RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)

        canvas.drawRoundRect(rect, 14f, 14f, if (hovered) targetActivePaint else targetBgPaint)
        targetBorderPaint.color = if (hovered) Color.parseColor("#00E5FF") else Color.parseColor("#3B4252")
        canvas.drawRoundRect(rect, 14f, 14f, targetBorderPaint)

        textPaint.color = if (hovered) Color.parseColor("#00E5FF") else Color.WHITE
        textPaint.textSize = 24f
        canvas.drawText(title, cx, cy - 6f, textPaint)

        subtitlePaint.textAlign = Paint.Align.CENTER
        subtitlePaint.color = if (hovered) Color.parseColor("#00E676") else Color.parseColor("#9E9E9E")
        val statusText = if (hovered && dwellRatio >= 0.9f) "✓ Clicked!" else "Clicks: $clicks"
        canvas.drawText(statusText, cx, cy + 22f, subtitlePaint)
    }

    private fun drawMotionTrail(canvas: Canvas) {
        val count = trailPoints.size
        if (count < 2) return

        val trailColor = if (currentMode == TrackingMode.EYE_ONLY || currentMode == TrackingMode.EYE_AND_VOICE) {
            Color.parseColor("#00E5FF")
        } else {
            Color.parseColor("#00E5FF")
        }

        for (i in 0 until count - 1) {
            val p1 = trailPoints[i]
            val p2 = trailPoints[i + 1]
            val ratio = (i.toFloat() / count.toFloat())
            val alpha = (ratio * 200).toInt().coerceIn(10, 220)
            val strokeW = 2f + (ratio * 6f)

            trailPaint.color = trailColor
            trailPaint.alpha = alpha
            trailPaint.strokeWidth = strokeW
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, trailPaint)
        }
    }

    private fun drawCursor(canvas: Canvas) {
        // Outer Dwell Ring
        val ringRadius = 26f
        val ringRect = RectF(
            currentCursorX - ringRadius,
            currentCursorY - ringRadius,
            currentCursorX + ringRadius,
            currentCursorY + ringRadius
        )

        // Background Ring
        val ringBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(currentCursorX, currentCursorY, ringRadius, ringBg)

        // Progress Arc
        if (dwellRatio > 0f) {
            val sweepAngle = dwellRatio * 360f
            canvas.drawArc(ringRect, -90f, sweepAngle, false, dwellProgressPaint)
        }

        // Center Pointer Dot
        cursorPaint.color = if (dwellRatio >= 0.95f) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")
        canvas.drawCircle(currentCursorX, currentCursorY, 8f, cursorPaint)
        canvas.drawCircle(currentCursorX, currentCursorY, 8f, cursorRingPaint)
    }

    private fun drawVoiceBubble(canvas: Canvas, bx: Float, by: Float) {
        val bubbleRect = RectF(bx - 60f, by - 36f, bx + 60f, by)
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD600")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bubbleRect, 10f, 10f, bubblePaint)

        // Arrow pointing down
        val arrowPath = Path().apply {
            moveTo(bx - 8f, by)
            lineTo(bx + 8f, by)
            lineTo(bx, by + 10f)
            close()
        }
        canvas.drawPath(arrowPath, bubblePaint)

        val bTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("“CLICK”", bx, by - 12f, bTextPaint)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun easeInOut(t: Float): Float {
        return if (t < 0.5f) 2f * t * t else -1f + (4f - 2f * t) * t
    }
}
