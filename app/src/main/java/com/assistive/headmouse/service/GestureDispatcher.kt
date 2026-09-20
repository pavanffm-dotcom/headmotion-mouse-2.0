package com.assistive.headmouse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Handles simulated touch gestures (Tap, Double Tap, Long Press, Swipes, Scrolls)
 * and Global Navigation actions using Android AccessibilityService.
 */
class GestureDispatcher(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun dispatchSingleClick(x: Float, y: Float, onComplete: (() -> Unit)? = null) {
        val clickPath = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Single click dispatched at ($x, $y)")
                onComplete?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Single click cancelled at ($x, $y)")
            }
        }, null)
    }

    fun dispatchDoubleClick(x: Float, y: Float, onComplete: (() -> Unit)? = null) {
        dispatchSingleClick(x, y) {
            mainHandler.postDelayed({
                dispatchSingleClick(x, y, onComplete)
            }, 80)
        }
    }

    fun dispatchLongPress(x: Float, y: Float, onComplete: (() -> Unit)? = null) {
        val holdPath = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(holdPath, 0, 800)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Long press dispatched at ($x, $y)")
                onComplete?.invoke()
            }
        }, null)
    }

    fun dispatchScroll(x: Float, y: Float, scrollUp: Boolean, onComplete: (() -> Unit)? = null) {
        val displayMetrics = service.resources.displayMetrics
        val screenW = displayMetrics.widthPixels.toFloat()
        val screenH = displayMetrics.heightPixels.toFloat()

        val clampedX = x.coerceIn(120f, (screenW - 120f).coerceAtLeast(200f))
        val scrollDistance = 580f

        // In Android touch interaction:
        // scrollUp = false (Scroll Down, user wants lower content): swipe finger UPWARDS
        // scrollUp = true  (Scroll Up, user wants upper content): swipe finger DOWNWARDS
        val (startY, endY) = if (!scrollUp) {
            val start = y.coerceIn(screenH * 0.42f, screenH * 0.85f)
            Pair(start, (start - scrollDistance).coerceAtLeast(screenH * 0.08f))
        } else {
            val start = y.coerceIn(screenH * 0.15f, screenH * 0.58f)
            Pair(start, (start + scrollDistance).coerceAtMost(screenH * 0.92f))
        }

        val scrollPath = Path().apply {
            moveTo(clampedX, startY)
            lineTo(clampedX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(scrollPath, 0, 260)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Scroll dispatched (scrollUp=$scrollUp) from $startY to $endY at x=$clampedX")
                onComplete?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Scroll cancelled at x=$clampedX")
            }
        }, null)
    }

    fun dispatchSwipeHorizontal(x: Float, y: Float, swipeLeft: Boolean, onComplete: (() -> Unit)? = null) {
        val displayMetrics = service.resources.displayMetrics
        val screenW = displayMetrics.widthPixels.toFloat()
        val screenH = displayMetrics.heightPixels.toFloat()

        val clampedY = y.coerceIn(screenH * 0.2f, screenH * 0.8f)
        val swipeDistance = 560f

        // swipeLeft = true (swipe towards left, next page): x moves from right to left
        // swipeLeft = false (swipe towards right, prev page): x moves from left to right
        val (startX, endX) = if (swipeLeft) {
            val start = x.coerceIn(screenW * 0.48f, screenW * 0.88f)
            Pair(start, (start - swipeDistance).coerceAtLeast(screenW * 0.08f))
        } else {
            val start = x.coerceIn(screenW * 0.12f, screenW * 0.52f)
            Pair(start, (start + swipeDistance).coerceAtMost(screenW * 0.92f))
        }

        val swipePath = Path().apply {
            moveTo(startX, clampedY)
            lineTo(endX, clampedY)
        }

        val stroke = GestureDescription.StrokeDescription(swipePath, 0, 260)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe dispatched (swipeLeft=$swipeLeft) from $startX to $endX at y=$clampedY")
                onComplete?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled")
            }
        }, null)
    }

    fun dispatchDrag(startX: Float, startY: Float, endX: Float, endY: Float, onComplete: (() -> Unit)? = null) {
        val dragPath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(dragPath, 0, 500)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Drag dispatched from ($startX, $startY) to ($endX, $endY)")
                onComplete?.invoke()
            }
        }, null)
    }

    // Global Navigation Actions
    fun performBack(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    fun performRecents(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    fun performNotifications(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    fun performQuickSettings(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    fun performLockScreen(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        private const val TAG = "GestureDispatcher"
    }
}
