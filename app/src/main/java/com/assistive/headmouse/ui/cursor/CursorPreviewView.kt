package com.assistive.headmouse.ui.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import com.assistive.headmouse.preferences.CursorStyle

/**
 * Compact preview widget for displaying a single cursor style inside Studio cards.
 */
class CursorPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cursorStyle: CursorStyle = CursorStyle.CLASSIC_TRIPLE_DOT
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = Color.parseColor("#00E5FF")
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val density = resources.displayMetrics.density
        // Render the cursor style centered with a fixed preview orbital angle of 45 deg
        CursorRenderer.drawCursor(
            canvas = canvas,
            cx = cx,
            cy = cy,
            style = cursorStyle,
            accentColor = accentColor,
            isFlash = false,
            density = density,
            orbitAngleRad = 0.785f
        )
    }
}
