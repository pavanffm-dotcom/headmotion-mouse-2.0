package com.assistive.headmouse.agent.jarvis.autonomous.state

import android.graphics.RectF
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ActionResult
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import java.security.MessageDigest
import java.util.UUID

/**
 * Source modality for a perceived screen element.
 */
enum class PerceptionSource {
    ACCESSIBILITY,           // Pure semantic node from AccessibilityNodeInfo
    VISION,                  // Pure visual detection from screenshot/model vision
    ACCESSIBILITY_AND_VISION // Fused target: matched across both modalities
}

/**
 * Visual element detected via screenshot vision or bounding box models.
 */
data class VisualElement(
    val label: String,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val confidence: Float = 0.85f,
    val type: String = "ICON"
) {
    constructor(
        label: String,
        bounds: RectF,
        confidence: Float = 0.85f,
        type: String = "ICON"
    ) : this(
        label = label,
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        confidence = confidence,
        type = type
    )

    val bounds: RectF get() = RectF(left, top, right, bottom)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Normalized representation of an interactive screen element.
 */
data class SemanticNode(
    val index: Int,                         // 1-based index (#1, #2, ...) for concise LLM referencing
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: RectF = RectF(),            // Absolute physical screen bounds (px)
    val left: Float = bounds.left,
    val top: Float = bounds.top,
    val right: Float = bounds.right,
    val bottom: Float = bounds.bottom,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isFocused: Boolean = false,
    val isEnabled: Boolean = true,
    val perceptionSource: PerceptionSource = PerceptionSource.ACCESSIBILITY,
    val visualConfidence: Float? = null,
    val fusedConfidence: Float = 1.0f,
    val visualBounds: RectF? = null
) {
    val centerX: Float get() = if (right != left) (left + right) / 2f else bounds.centerX()
    val centerY: Float get() = if (bottom != top) (top + bottom) / 2f else bounds.centerY()

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /**
     * Primary display label for semantic matching.
     */
    val label: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: resourceId?.substringAfterLast('/')?.replace('_', ' ')?.takeIf { it.isNotBlank() }
            ?: ""

    /**
     * Short concise representation for model reasoning.
     */
    fun toCompactString(): String {
        val type = when {
            isEditable -> "Input"
            isClickable -> "Button"
            isScrollable -> "List"
            else -> className?.substringAfterLast('.')?.removeSuffix("View") ?: "Element"
        }
        val srcTag = when (perceptionSource) {
            PerceptionSource.ACCESSIBILITY_AND_VISION -> " [Fused]"
            PerceptionSource.VISION -> " [Vision]"
            PerceptionSource.ACCESSIBILITY -> ""
        }
        val lbl = label.replace("\n", " ").trim()
        val displayLabel = if (lbl.length > 35) lbl.take(32) + "..." else lbl
        val coords = "(${centerX.toInt()}, ${centerY.toInt()})"
        return "#$index [$type]$srcTag \"$displayLabel\" $coords"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Phase 14 – Supplementary state types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Scroll position captured at the moment of observation.
 */
data class ScrollState(
    /** True when at least one scrollable container was detected. */
    val isScrollable: Boolean = false,
    /** Estimated vertical scroll offset in px (0 = top, negative = unknown). */
    val scrollOffsetY: Int = 0,
    /** Estimated horizontal scroll offset in px. */
    val scrollOffsetX: Int = 0,
    /** Index of the first fully-visible item (for RecyclerView/ListView). */
    val firstVisibleItem: Int = 0,
    /** Total estimated item count (-1 if unknown). */
    val totalItems: Int = -1
)

/**
 * Outcome of an agent verification pass after executing an action.
 */
data class VerificationResult(
    /** Whether the intended post-condition was confirmed. */
    val success: Boolean,
    /** Human-readable explanation of the outcome. */
    val message: String,
    /** The observationId of the WorldState used for verification. */
    val observationId: String,
    /** Timestamp (ms) when verification was completed. */
    val verifiedAt: Long = System.currentTimeMillis(),
    /** Optional confidence score [0.0, 1.0]. */
    val confidence: Float = 1.0f
)

/**
 * Authoritative perception snapshot of the Android device display at time T.
 * Provides normalized, noise-filtered state free of assistant overlays.
 */
data class WorldState(
    // ── Phase 14: Observation identity ──────────────────────────────────────────
    /** Unique identifier for this observation instance. */
    val observationId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),

    // 1. Foreground Window Identification
    val foregroundPackage: String,
    val foregroundActivity: String? = null,
    val windowTitle: String? = null,

    // 2. Extracted Semantic Nodes (Prioritized interactive elements)
    val nodes: List<SemanticNode> = emptyList(),

    // 3. Functional UI Groupings
    val clickableNodes: List<SemanticNode> = nodes.filter { it.isClickable },
    val editableNodes: List<SemanticNode> = nodes.filter { it.isEditable },
    val scrollableNodes: List<SemanticNode> = nodes.filter { it.isScrollable },
    val focusedNode: SemanticNode? = nodes.firstOrNull { it.isFocused },

    // 4. Global Indicators
    val isKeyboardVisible: Boolean = false,
    val isLoadingIndicatorPresent: Boolean = false,
    val isDialogBlocking: Boolean = false,

    // 5. Deterministic Hash Signatures
    val screenHash: String = computeScreenHash(foregroundPackage, foregroundActivity, nodes),
    val accessibilityHash: String = computeAccessibilityHash(nodes),

    // 6. Multimodal Visual Artifacts (Optional / On-Demand)
    val screenshotBase64: String? = null,
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0,
    val visualElements: List<VisualElement> = emptyList(),

    // ── Phase 14: Extended observation context ───────────────────────────────────
    /** Stable identifier for the current screen (packageName + activityName hash). */
    val screenId: String = computeScreenId(foregroundPackage, foregroundActivity),
    /** Scroll position captured during this observation. */
    val scrollState: ScrollState? = null,
    /** The action that was dispatched based on this WorldState (set post-execution). */
    val lastAction: ToolCall? = null,
    /** Result of [lastAction] (set after execution completes). */
    val lastActionResult: ActionResult? = null,
    /** Verification outcome after [lastAction] was executed (set post-verification). */
    val verificationResult: VerificationResult? = null
) {
    val screenDimensions: Pair<Int, Int>
        get() = (if (screenshotWidth > 0) screenshotWidth else 1080) to (if (screenshotHeight > 0) screenshotHeight else 2400)

    // ── Phase 14: Convenience accessors ─────────────────────────────────────────

    /** True when the last action has been confirmed as successful via verification. */
    val isLastActionVerified: Boolean
        get() = verificationResult?.success == true

    /** True when this snapshot is the result of a completed action cycle (observe → act → verify). */
    val hasActionContext: Boolean
        get() = lastAction != null

    /**
     * Returns a short summary of the last-action context for planner prompts.
     * Returns null when no action context is attached.
     */
    fun lastActionSummary(): String? {
        val action = lastAction ?: return null
        val result = lastActionResult
        val verification = verificationResult
        return buildString {
            append("Last action: ${action.name}")
            if (action.arguments.isNotEmpty()) {
                val args = action.arguments.entries
                    .take(3)
                    .joinToString(", ") { "${it.key}=${it.value}" }
                append(" ($args)")
            }
            if (result != null) {
                append(" → ${if (result.success) "OK" else "FAIL"}")
                if (!result.success && result.errorMessage != null) {
                    append(" [${result.errorMessage.take(60)}]")
                }
            }
            if (verification != null) {
                append(" | verified=${verification.success}")
                if (!verification.success) append(" (${verification.message.take(50)})")
            }
        }
    }

    /**
     * Compresses screen state into a concise tabular format strictly under ~300 tokens.
     */
    fun toCompressedSemanticIndex(maxNodes: Int = 40): String {
        val sb = StringBuilder()
        sb.append("=== ACTIVE DISPLAY ===\n")
        sb.append("OBS: $observationId | SCREEN: $screenId\n")
        sb.append("APP: $foregroundPackage\n")
        if (!foregroundActivity.isNullOrBlank()) {
            sb.append("ACTIVITY: ${foregroundActivity.substringAfterLast('.')}\n")
        }
        sb.append("KEYBOARD: ${if (isKeyboardVisible) "VISIBLE" else "HIDDEN"} | ")
        sb.append("DIALOG: ${if (isDialogBlocking) "YES" else "NO"} | ")
        sb.append("LOADING: ${if (isLoadingIndicatorPresent) "YES" else "NO"}\n")
        scrollState?.let {
            if (it.isScrollable) {
                sb.append("SCROLL: offsetY=${it.scrollOffsetY}px")
                if (it.totalItems > 0) sb.append(" items=${it.firstVisibleItem}/${it.totalItems}")
                sb.append("\n")
            }
        }
        lastActionSummary()?.let { sb.append("ACTION_CTX: $it\n") }

        sb.append("INTERACTIVE ELEMENTS (${nodes.size.coerceAtMost(maxNodes)} shown):\n")
        val displayNodes = nodes.take(maxNodes)
        if (displayNodes.isEmpty()) {
            sb.append("(No interactive accessibility nodes detected on screen)\n")
        } else {
            for (node in displayNodes) {
                sb.append(node.toCompactString()).append("\n")
            }
        }
        return sb.toString().trim()
    }

    /**
     * Determines whether the screen has mutated compared to another observation.
     */
    fun hasStateChanged(other: WorldState?): Boolean {
        if (other == null) return true
        return this.screenHash != other.screenHash || this.accessibilityHash != other.accessibilityHash
    }

    /**
     * Returns true if [other] belongs to the same logical screen (same app + activity).
     * Useful to detect navigation vs. in-screen content changes.
     */
    fun isSameScreen(other: WorldState?): Boolean =
        other != null && this.screenId == other.screenId

    /**
     * Creates a copy of this WorldState with action/verification context attached.
     * Called by AgentOrchestrator after executing an action and running verification.
     */
    fun withActionResult(
        action: ToolCall,
        result: ActionResult,
        verification: VerificationResult? = null
    ): WorldState = copy(
        lastAction = action,
        lastActionResult = result,
        verificationResult = verification
    )

    companion object {
        /**
         * Derives a stable screen identifier from package + activity.
         * Identical values = same screen across different observations.
         */
        fun computeScreenId(packageName: String, activity: String?): String {
            val raw = "$packageName|${activity ?: ""}"
            return hashString(raw).take(16)
        }

        fun computeScreenHash(packageName: String, activity: String?, nodes: List<SemanticNode>): String {
            val raw = buildString {
                append(packageName)
                append('|')
                append(activity ?: "")
                append('|')
                for (n in nodes) {
                    append(n.left.toInt())
                    append(',')
                    append(n.top.toInt())
                    append(',')
                    append(n.right.toInt())
                    append(',')
                    append(n.bottom.toInt())
                    append(';')
                }
            }
            return hashString(raw)
        }

        fun computeAccessibilityHash(nodes: List<SemanticNode>): String {
            val raw = buildString {
                for (n in nodes) {
                    append(n.label)
                    append('|')
                    append(n.isFocused)
                    append('|')
                    append(n.isChecked)
                    append(';')
                }
            }
            return hashString(raw)
        }

        private fun hashString(input: String): String {
            return try {
                val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
                bytes.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                input.hashCode().toString(16)
            }
        }
    }
}
