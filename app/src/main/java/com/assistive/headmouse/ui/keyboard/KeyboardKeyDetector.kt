package com.assistive.headmouse.ui.keyboard

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot

/**
 * Represents an individual key on the soft keyboard (e.g. 'A', 'Space', 'Delete').
 */
data class KeyboardKey(
    val id: String,
    val bounds: RectF,
    val centerX: Float = bounds.centerX(),
    val centerY: Float = bounds.centerY()
)

/**
 * Intelligent Keyboard Key Detector:
 * 1. Accurately detects when the Android Soft Keyboard (IME/Gboard) is open.
 * 2. Uses Accessibility Window info to get exact keyboard height and screen bounds.
 * 3. Inspects real Accessibility nodes when exposed by IME for 100% pixel-perfect key alignment.
 * 4. Fallback: Uses a navigation-bar aware dynamic QWERTY grid matching standard mobile keyboards.
 * 5. Maps head position (x, y) with magnetic snap and hysteresis to individual keys.
 */
class KeyboardKeyDetector(
    private val service: AccessibilityService,
    private val density: Float
) {

    private val tempRect = Rect()
    private var cachedKeyboardBounds: RectF? = null
    private var hasRealImeWindow: Boolean = false
    private var lastCheckTime: Long = 0L
    private var isImeVisible: Boolean = false

    private fun getNavBarHeight(): Float {
        val resourceId = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            service.resources.getDimensionPixelSize(resourceId).toFloat()
        } else {
            36f * density
        }
    }

    /**
     * Checks if the Soft Keyboard is active on the screen.
     */
    fun isKeyboardActive(screenHeight: Int, screenWidth: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < 200L && cachedKeyboardBounds != null) {
            return isImeVisible
        }
        lastCheckTime = now

        // 1. Check IME Window in Accessibility Windows
        try {
            val windows = service.windows
            if (windows != null) {
                for (window in windows) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        window.getBoundsInScreen(tempRect)
                        if (tempRect.height() > (120f * density) && tempRect.top < screenHeight) {
                            cachedKeyboardBounds = RectF(tempRect)
                            hasRealImeWindow = true
                            isImeVisible = true
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying accessibility windows: ", e)
        }

        // Only activate keyboard mode when a real Soft Keyboard (IME) window is physically present
        cachedKeyboardBounds = null
        isImeVisible = false
        hasRealImeWindow = false
        return false
    }

    /**
     * Gets the active screen bounds of the keyboard.
     */
    fun getKeyboardBounds(screenHeight: Int, screenWidth: Int): RectF {
        val cached = cachedKeyboardBounds
        if (cached != null && isImeVisible) {
            return cached
        }
        val navBarH = getNavBarHeight()
        val kbHeight = 295f * density
        val kbTop = screenHeight - navBarH - kbHeight
        val kbBottom = screenHeight - navBarH
        return RectF(0f, kbTop, screenWidth.toFloat(), kbBottom)
    }

    /**
     * Verifies if (x, y) falls inside the active keyboard area.
     */
    fun isPointInKeyboard(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!isKeyboardActive(screenHeight, screenWidth)) {
            return false
        }
        val bounds = getKeyboardBounds(screenHeight, screenWidth)
        return (x in 0f..screenWidth.toFloat()) && (y >= bounds.top) && (y <= bounds.bottom + (10f * density))
    }

    /**
     * Finds the targeted key with Magnetic Hysteresis:
     * If already locked on a key (e.g. 'A'), slight natural head tremors will stay locked
     * until head moves outside the key's expanded tolerance zone.
     */
    fun findKeyAt(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        currentKey: KeyboardKey?
    ): KeyboardKey? {
        val bounds = getKeyboardBounds(screenHeight, screenWidth)
        if (y < bounds.top || y > (bounds.bottom + 10f * density)) {
            return null
        }

        // 1. Hysteresis: Check if still within expanded boundary of current locked key
        if (currentKey != null) {
            val padding = 10f * density
            val expandedBounds = RectF(
                currentKey.bounds.left - padding,
                currentKey.bounds.top - padding,
                currentKey.bounds.right + padding,
                currentKey.bounds.bottom + padding
            )
            if (expandedBounds.contains(x, y)) {
                return currentKey
            }
        }

        // 2. Try querying real Accessibility Window nodes first (if Gboard exposes them)
        if (hasRealImeWindow) {
            val realKey = findKeyFromWindowNodes(x, y)
            if (realKey != null) {
                return realKey
            }
        }

        // 3. Fallback: Generate Precise QWERTY Grid
        val keys = generateQwertyKeys(bounds)

        // Find key whose bounds contain (x, y)
        for (key in keys) {
            if (key.bounds.contains(x, y)) {
                return key
            }
        }

        // Find closest key center if slightly near boundaries
        var closestKey: KeyboardKey? = null
        var minDistance = Float.MAX_VALUE
        for (key in keys) {
            val dist = hypot(x - key.centerX, y - key.centerY)
            if (dist < minDistance) {
                minDistance = dist
                closestKey = key
            }
        }

        return closestKey
    }

    private fun findKeyFromWindowNodes(x: Float, y: Float): KeyboardKey? {
        try {
            val windows = service.windows ?: return null
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val root = window.root ?: continue
                    val node = findDeepestKeyNode(root, x.toInt(), y.toInt())
                    if (node != null) {
                        node.getBoundsInScreen(tempRect)
                        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: "KEY"
                        return KeyboardKey(text, RectF(tempRect))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finding key from window nodes: ", e)
        }
        return null
    }

    private fun findDeepestKeyNode(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        node.getBoundsInScreen(tempRect)
        if (!tempRect.contains(x, y)) {
            return null
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDeepestKeyNode(child, x, y)
            if (found != null) {
                return found
            }
        }
        if (node.isClickable || !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) {
            return node
        }
        return null
    }

    /**
     * Dynamically partitions the keyboard area into standard 4-row mobile QWERTY layout.
     */
    private fun generateQwertyKeys(kbBounds: RectF): List<KeyboardKey> {
        val keys = ArrayList<KeyboardKey>(35)
        val kbWidth = kbBounds.width()
        val kbHeight = kbBounds.height()

        // Suggestion / Toolbar strip at top (~42dp)
        val headerHeight = (42f * density).coerceAtMost(kbHeight * 0.16f)
        val typingTop = kbBounds.top + headerHeight
        val typingBottom = kbBounds.bottom - (2f * density)
        val rowHeight = (typingBottom - typingTop) / 4f

        val keyW10 = kbWidth / 10f

        // --- ROW 1: Q W E R T Y U I O P (10 keys) ---
        val row1Letters = arrayOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
        val r1Top = typingTop
        val r1Bottom = r1Top + rowHeight
        for (i in 0..9) {
            val left = i * keyW10
            val right = left + keyW10
            keys.add(KeyboardKey(row1Letters[i], RectF(left, r1Top, right, r1Bottom)))
        }

        // --- ROW 2: A S D F G H J K L (9 keys, centered with 0.5 unit offset) ---
        val row2Letters = arrayOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
        val r2Top = r1Bottom
        val r2Bottom = r2Top + rowHeight
        val r2Offset = 0.5f * keyW10
        for (i in 0..8) {
            val left = r2Offset + (i * keyW10)
            val right = left + keyW10
            keys.add(KeyboardKey(row2Letters[i], RectF(left, r2Top, right, r2Bottom)))
        }

        // --- ROW 3: SHIFT, Z X C V B N M, BACKSPACE ---
        val row3Letters = arrayOf("Z", "X", "C", "V", "B", "N", "M")
        val r3Top = r2Bottom
        val r3Bottom = r3Top + rowHeight
        val sideKeyWidth = 1.45f * keyW10

        // Shift Key (Left)
        keys.add(KeyboardKey("SHIFT", RectF(0f, r3Top, sideKeyWidth, r3Bottom)))

        // 7 Letters (Z to M)
        val r3MidWidth = (kbWidth - (2f * sideKeyWidth)) / 7f
        for (i in 0..6) {
            val left = sideKeyWidth + (i * r3MidWidth)
            val right = left + r3MidWidth
            keys.add(KeyboardKey(row3Letters[i], RectF(left, r3Top, right, r3Bottom)))
        }

        // Backspace Key (Right)
        keys.add(KeyboardKey("BACKSPACE", RectF(kbWidth - sideKeyWidth, r3Top, kbWidth, r3Bottom)))

        // --- ROW 4: ?123, EMOJI, SPACE, PERIOD, ENTER ---
        val r4Top = r3Bottom
        val r4Bottom = typingBottom
        val numKeyW = 1.45f * keyW10
        val emojiKeyW = 1.15f * keyW10
        val enterKeyW = 1.55f * keyW10
        val periodKeyW = 1.15f * keyW10

        val leftCluster = numKeyW + emojiKeyW
        val rightCluster = periodKeyW + enterKeyW
        val spaceWidth = kbWidth - leftCluster - rightCluster

        // ?123
        keys.add(KeyboardKey("?123", RectF(0f, r4Top, numKeyW, r4Bottom)))
        // Emoji
        keys.add(KeyboardKey("EMOJI", RectF(numKeyW, r4Top, leftCluster, r4Bottom)))
        // Space Bar
        keys.add(KeyboardKey("SPACE", RectF(leftCluster, r4Top, leftCluster + spaceWidth, r4Bottom)))
        // Period .
        keys.add(KeyboardKey(".", RectF(leftCluster + spaceWidth, r4Top, kbWidth - enterKeyW, r4Bottom)))
        // Enter / Done
        keys.add(KeyboardKey("ENTER", RectF(kbWidth - enterKeyW, r4Top, kbWidth, r4Bottom)))

        return keys
    }

    companion object {
        private const val TAG = "KeyboardKeyDetector"
    }
}
