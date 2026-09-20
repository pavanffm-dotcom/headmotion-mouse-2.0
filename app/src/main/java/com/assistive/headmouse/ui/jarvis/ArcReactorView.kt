package com.assistive.headmouse.ui.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class JarvisState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * Custom Arc Reactor HUD Visualizer inspired by Tony Stark's iconic J.A.R.V.I.S.
 * Features rotating mechanical segment rings, glowing energy arcs, an inner triangular
 * reactor core, and state-reactive pulsation animations.
 */
class ArcReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state: JarvisState = JarvisState.IDLE
    private var rotationAngle = 0f
    private var pulseScale = 1.0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val trianglePath = Path()
    private val arcBounds = RectF()

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0.92f, 1.08f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulseScale = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    private var isAnimating = false

    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        if (!rotationAnimator.isStarted) rotationAnimator.start() else rotationAnimator.resume()
        if (!pulseAnimator.isStarted) pulseAnimator.start() else pulseAnimator.resume()
    }

    fun stopAnimation() {
        if (!isAnimating) return
        isAnimating = false
        rotationAnimator.pause()
        pulseAnimator.pause()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE && isShown) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE && isShown) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    fun setJarvisState(newState: JarvisState) {
        if (state == newState) return
        state = newState
        when (state) {
            JarvisState.IDLE -> {
                rotationAnimator.duration = 6000
                pulseAnimator.duration = 1400
            }
            JarvisState.LISTENING -> {
                rotationAnimator.duration = 2000
                pulseAnimator.duration = 600
            }
            JarvisState.THINKING -> {
                rotationAnimator.duration = 1000
                pulseAnimator.duration = 400
            }
            JarvisState.SPEAKING -> {
                rotationAnimator.duration = 3000
                pulseAnimator.duration = 500
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) / 2f - 20f) * pulseScale

        val primaryColor = when (state) {
            JarvisState.IDLE -> Color.parseColor("#00E5FF")      // Cyan
            JarvisState.LISTENING -> Color.parseColor("#00E676") // Green
            JarvisState.THINKING -> Color.parseColor("#FFB300")  // Solar Amber
            JarvisState.SPEAKING -> Color.parseColor("#00E5FF")  // Electric Cyan
        }

        val glowColor = Color.argb(
            60,
            Color.red(primaryColor),
            Color.green(primaryColor),
            Color.blue(primaryColor)
        )

        // 1. Outer Luminescent Aura Ring
        glowPaint.color = glowColor
        glowPaint.strokeWidth = 14f
        canvas.drawCircle(cx, cy, radius, glowPaint)

        // 2. Primary Outer Boundary Ring
        ringPaint.color = primaryColor
        ringPaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 3. Rotating Mechanical Outer Segments
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        segmentPaint.color = primaryColor
        arcBounds.set(cx - radius * 0.85f, cy - radius * 0.85f, cx + radius * 0.85f, cy + radius * 0.85f)
        val numSegments = 8
        val sweepAngle = 26f
        for (i in 0 until numSegments) {
            val startAngle = i * (360f / numSegments)
            canvas.drawArc(arcBounds, startAngle, sweepAngle, false, segmentPaint)
        }
        canvas.restore()

        // 4. Counter-rotating Intermediate Ring with Tick Marks
        canvas.save()
        canvas.rotate(-rotationAngle * 1.5f, cx, cy)
        val tickRadius = radius * 0.68f
        ringPaint.strokeWidth = 1.5f
        for (i in 0 until 16) {
            val angle = Math.toRadians((i * (360.0 / 16.0)))
            val x1 = cx + (tickRadius * 0.90f * cos(angle)).toFloat()
            val y1 = cy + (tickRadius * 0.90f * sin(angle)).toFloat()
            val x2 = cx + (tickRadius * cos(angle)).toFloat()
            val y2 = cy + (tickRadius * sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, ringPaint)
        }
        canvas.restore()

        // 5. Inner Core Ring
        val coreRadius = radius * 0.50f
        ringPaint.color = primaryColor
        ringPaint.strokeWidth = 3f
        canvas.drawCircle(cx, cy, coreRadius, ringPaint)

        // 6. Central Triangular Arc Reactor Core
        canvas.save()
        canvas.rotate(rotationAngle * 0.5f, cx, cy)
        val triRadius = coreRadius * 0.70f
        trianglePath.reset()
        for (i in 0 until 3) {
            val angle = Math.toRadians((i * 120.0) - 90.0)
            val px = cx + (triRadius * cos(angle)).toFloat()
            val py = cy + (triRadius * sin(angle)).toFloat()
            if (i == 0) trianglePath.moveTo(px, py) else trianglePath.lineTo(px, py)
        }
        trianglePath.close()

        corePaint.color = primaryColor
        corePaint.alpha = 210
        canvas.drawPath(trianglePath, corePaint)
        canvas.restore()

        // 7. Radiant Center Focal Dot
        corePaint.color = Color.WHITE
        corePaint.alpha = 255
        canvas.drawCircle(cx, cy, 6f * pulseScale, corePaint)
    }
}
