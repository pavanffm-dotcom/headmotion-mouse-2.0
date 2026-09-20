package com.assistive.headmouse.agent.jarvis.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.assistive.headmouse.ui.jarvis.ArcReactorView
import com.assistive.headmouse.ui.jarvis.JarvisState
import kotlin.math.abs

/**
 * Draggable, system-wide Floating Arc Reactor HUD Overlay.
 * Stays visible across all third-party apps and the home screen while JARVIS is active.
 * Provides real-time visual state feedback and 1-tap voice call control.
 */
class FloatingArcReactorOverlay(
    private val context: Context,
    private val onToggleVoice: () -> Unit,
    private val onEmergencyAbort: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayContainer: FrameLayout? = null
    private var arcReactorView: ArcReactorView? = null
    private var statusBadge: TextView? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L
    private var isDragging = false

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.START
        x = 40
        y = 280
    }

    init {
        createOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val density = context.resources.displayMetrics.density
        val arcSize = (56 * density).toInt()

        overlayContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. Sleek circular Arc Reactor
        arcReactorView = ArcReactorView(context).apply {
            layoutParams = LinearLayout.LayoutParams(arcSize, arcSize)
            setJarvisState(JarvisState.IDLE)
        }

        // 2. Mini status pill badge
        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14 * density
            setColor(Color.parseColor("#E60F172A")) // Dark translucent slate
            setStroke((1 * density).toInt(), Color.parseColor("#00E5FF"))
        }

        statusBadge = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
            background = badgeBg
            setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 10f
            text = "JARVIS"
        }

        contentLayout.addView(arcReactorView)
        contentLayout.addView(statusBadge)
        overlayContainer?.addView(contentLayout)

        // 3. Touch Handling (Draggable + Click to Toggle Voice + Long Press for Abort)
        overlayContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        updateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - touchStartTime
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)

                    if (dx < 15 && dy < 15) {
                        if (duration > 700L) {
                            // Long press -> Emergency Stop
                            onEmergencyAbort()
                            showTemporaryStatus("ABORTED", Color.parseColor("#FF1744"))
                        } else {
                            // Quick tap -> Toggle Voice Call
                            onToggleVoice()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateLayout() {
        try {
            if (overlayContainer?.isAttachedToWindow == true) {
                windowManager.updateViewLayout(overlayContainer, layoutParams)
            }
        } catch (_: Exception) {}
    }

    fun show() {
        try {
            if (overlayContainer?.isAttachedToWindow != true && overlayContainer != null) {
                windowManager.addView(overlayContainer, layoutParams)
            }
        } catch (_: Exception) {}
    }

    fun hide() {
        try {
            if (overlayContainer?.isAttachedToWindow == true) {
                windowManager.removeView(overlayContainer)
            }
        } catch (_: Exception) {}
    }

    fun updateState(state: JarvisState, statusText: String) {
        overlayContainer?.post {
            arcReactorView?.setJarvisState(state)
            statusBadge?.text = statusText.take(16)
            when (state) {
                JarvisState.LISTENING -> {
                    statusBadge?.setTextColor(Color.parseColor("#00E5FF")) // Bright cyan
                    statusBadge?.text = "LISTENING"
                }
                JarvisState.THINKING -> {
                    statusBadge?.setTextColor(Color.parseColor("#FFD600")) // Amber
                    statusBadge?.text = "THINKING"
                }
                JarvisState.SPEAKING -> {
                    statusBadge?.setTextColor(Color.parseColor("#00E676")) // Neon Green
                    statusBadge?.text = "SPEAKING"
                }
                JarvisState.IDLE -> {
                    statusBadge?.setTextColor(Color.parseColor("#94A3B8")) // Slate
                    statusBadge?.text = "JARVIS"
                }
            }
        }
    }

    fun showTemporaryStatus(message: String, color: Int = Color.parseColor("#00E5FF")) {
        overlayContainer?.post {
            statusBadge?.text = message
            statusBadge?.setTextColor(color)
            overlayContainer?.postDelayed({
                statusBadge?.text = "JARVIS"
                statusBadge?.setTextColor(Color.parseColor("#00E5FF"))
            }, 2000L)
        }
    }

    fun destroy() {
        hide()
        overlayContainer = null
        arcReactorView = null
        statusBadge = null
    }
}
