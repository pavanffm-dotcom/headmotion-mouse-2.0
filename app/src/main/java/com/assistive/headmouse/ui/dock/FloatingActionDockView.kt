package com.assistive.headmouse.ui.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageButton
import com.assistive.headmouse.R
import com.assistive.headmouse.tracking.model.DockAction

/**
 * Controller for the Floating Action Dock overlay sidebar.
 * Supports Dedicated Dock Focus Mode with live dwell countdown rings.
 */
class FloatingActionDockView(
    context: Context,
    private val onActionTriggered: (DockAction) -> Unit
) : FrameLayout(context) {

    val btnNavHome: ImageButton
    val btnNavBack: ImageButton
    val btnScrollUp: ImageButton
    val btnScrollDown: ImageButton
    val btnSwipeLeft: ImageButton
    val btnSwipeRight: ImageButton
    val btnRecenter: ImageButton
    val btnUtilPauseResume: ImageButton

    val buttonList: List<Pair<ImageButton, DockAction>>

    private val dwellRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00E5FF") // Neon Cyan
        strokeCap = Paint.Cap.ROUND
    }

    private val dwellBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#3300E5FF")
    }

    private var selectedIndex: Int = -1
    private var dwellProgress: Float = 0f
    private val buttonArcBounds = RectF()

    init {
        setWillNotDraw(false)
        LayoutInflater.from(context).inflate(R.layout.view_floating_dock, this, true)

        btnNavHome = findViewById(R.id.btn_nav_home)
        btnNavBack = findViewById(R.id.btn_nav_back)
        btnScrollUp = findViewById(R.id.btn_action_scroll_up)
        btnScrollDown = findViewById(R.id.btn_action_scroll_down)
        btnSwipeLeft = findViewById(R.id.btn_action_swipe_left)
        btnSwipeRight = findViewById(R.id.btn_action_swipe_right)
        btnRecenter = findViewById(R.id.btn_util_recenter)
        btnUtilPauseResume = findViewById(R.id.btn_util_pause_resume)

        buttonList = listOf(
            btnNavHome to DockAction.NAV_HOME,
            btnNavBack to DockAction.NAV_BACK,
            btnScrollUp to DockAction.ACTION_SCROLL_UP,
            btnScrollDown to DockAction.ACTION_SCROLL_DOWN,
            btnSwipeLeft to DockAction.ACTION_SWIPE_LEFT,
            btnSwipeRight to DockAction.ACTION_SWIPE_RIGHT,
            btnRecenter to DockAction.UTIL_RECENTER,
            btnUtilPauseResume to DockAction.UTIL_PAUSE_RESUME
        )

        setupClickListeners()
    }

    private fun setupClickListeners() {
        btnNavHome.setOnClickListener { onActionTriggered(DockAction.NAV_HOME) }
        btnNavBack.setOnClickListener { onActionTriggered(DockAction.NAV_BACK) }
        btnScrollUp.setOnClickListener { onActionTriggered(DockAction.ACTION_SCROLL_UP) }
        btnScrollDown.setOnClickListener { onActionTriggered(DockAction.ACTION_SCROLL_DOWN) }
        btnSwipeLeft.setOnClickListener { onActionTriggered(DockAction.ACTION_SWIPE_LEFT) }
        btnSwipeRight.setOnClickListener { onActionTriggered(DockAction.ACTION_SWIPE_RIGHT) }
        btnRecenter.setOnClickListener { onActionTriggered(DockAction.UTIL_RECENTER) }
        btnUtilPauseResume.setOnClickListener { onActionTriggered(DockAction.UTIL_PAUSE_RESUME) }
    }

    fun setPausedState(isPaused: Boolean) {
        if (isPaused) {
            btnUtilPauseResume.setImageResource(R.drawable.ic_play)
            btnUtilPauseResume.contentDescription = context.getString(R.string.dock_resume)
        } else {
            btnUtilPauseResume.setImageResource(R.drawable.ic_pause)
            btnUtilPauseResume.contentDescription = context.getString(R.string.dock_pause)
        }
        postInvalidate()
    }

    var isDockOnLeft: Boolean = false

    fun getDockScreenBounds(screenWidth: Int, screenHeight: Int): RectF {
        val location = IntArray(2)
        getLocationOnScreen(location)
        if ((location[0] != 0 || location[1] != 0) && width > 0 && height > 0) {
            val l = location[0].toFloat()
            val t = location[1].toFloat()
            return RectF(l, t, l + width.toFloat(), t + height.toFloat())
        }

        val density = resources.displayMetrics.density
        val dockWidth = 44f * density
        val dockHeight = 330f * density

        val top = (screenHeight / 2f) - (dockHeight / 2f)
        val bottom = (screenHeight / 2f) + (dockHeight / 2f)
        val left = if (isDockOnLeft) 0f else (screenWidth.toFloat() - dockWidth)
        val right = if (isDockOnLeft) dockWidth else screenWidth.toFloat()

        return RectF(left, top, right, bottom)
    }

    /**
     * Checks whether (x, y) is inside the dock region.
     * When [isCurrentlyInDock] is true, vertical boundaries are relaxed so the user can easily
     * reach Button 0 (Home at top) and Button 7 (Pause at bottom) without being prematurely kicked out.
     */
    fun isPointInsideDock(x: Float, y: Float, screenWidth: Int, screenHeight: Int, isCurrentlyInDock: Boolean = false): Boolean {
        val bounds = getDockScreenBounds(screenWidth, screenHeight)
        val horizontalMargin = 25f * resources.displayMetrics.density

        val isHorizontalMatch = if (isDockOnLeft) {
            x in 0f..(bounds.right + horizontalMargin)
        } else {
            x in (bounds.left - horizontalMargin)..screenWidth.toFloat()
        }

        return if (isCurrentlyInDock) {
            // Once inside dock mode, allow vertical head tilt across full height so pause button is 100% accessible!
            isHorizontalMatch
        } else {
            // Initial dock entry check: require hitting the dock rectangle
            isHorizontalMatch && (y >= bounds.top - 12f) && (y <= bounds.bottom + 12f)
        }
    }

    fun selectIndex(index: Int, progress: Float = 0f) {
        this.selectedIndex = index.coerceIn(0, buttonList.size - 1)
        this.dwellProgress = progress.coerceIn(0f, 1f)

        for (i in buttonList.indices) {
            buttonList[i].first.isSelected = (i == this.selectedIndex)
        }
        invalidate()
    }

    fun getActionForIndex(index: Int): DockAction {
        val safeIndex = index.coerceIn(0, buttonList.size - 1)
        return buttonList[safeIndex].second
    }

    fun clearSelection() {
        this.selectedIndex = -1
        this.dwellProgress = 0f
        for (pair in buttonList) {
            pair.first.isSelected = false
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        // Draw live circular dwell countdown ring around the focused button
        if (selectedIndex in buttonList.indices && dwellProgress > 0.01f) {
            val targetBtn = buttonList[selectedIndex].first
            val cx = targetBtn.left + targetBtn.width / 2f
            val cy = targetBtn.top + targetBtn.height / 2f
            val r = (targetBtn.width / 2f) + 4f

            buttonArcBounds.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawOval(buttonArcBounds, dwellBgPaint)

            val sweepAngle = dwellProgress * 360f
            canvas.drawArc(buttonArcBounds, -90f, sweepAngle, false, dwellRingPaint)
        }
    }
}
