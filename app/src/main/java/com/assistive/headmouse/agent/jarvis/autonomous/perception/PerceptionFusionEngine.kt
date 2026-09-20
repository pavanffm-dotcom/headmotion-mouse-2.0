package com.assistive.headmouse.agent.jarvis.autonomous.perception

import android.graphics.RectF
import android.util.Log
import com.assistive.headmouse.agent.jarvis.autonomous.state.PerceptionSource
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.VisualElement
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Perception Fusion Engine (Phase 13).
 *
 * Unifies Accessibility Tree semantic nodes and Screenshot Vision elements into a coherent WorldState.
 * - Preserves semantic accessibility nodes and attributes.
 * - Preserves visual bounding boxes and detection confidence.
 * - Matches visual targets to accessibility nodes using spatial IoU, center-point proximity, and semantic labeling.
 * - Fallback: If Accessibility cannot identify a target, allows Vision fallback.
 * - Fallback: If Vision is unavailable or fails, preserves pure Accessibility.
 * - Precedence: Never prefers raw vision when semantic accessibility is more reliable; fused nodes receive highest confidence.
 * - Source & Confidence: Provides explicit PerceptionSource attribution (ACCESSIBILITY, VISION, ACCESSIBILITY_AND_VISION).
 */
object PerceptionFusionEngine {

    private const val TAG = "PerceptionFusionEngine"

    // Spatial matching thresholds
    private const val IOU_MATCH_THRESHOLD = 0.25f
    private const val PROXIMITY_DISTANCE_PX = 80f
    private const val OVERLAP_RATIO_THRESHOLD = 0.35f

    // Confidence thresholds
    const val CONFIDENCE_FUSED = 0.99f
    const val CONFIDENCE_ACCESSIBILITY = 0.95f
    const val CONFIDENCE_VISION_DEFAULT = 0.85f

    /**
     * Fuses accessibility nodes and visual elements into a unified list of SemanticNodes.
     */
    fun fuse(
        a11yNodes: List<SemanticNode>,
        visualElements: List<VisualElement>,
        screenDimensions: Pair<Int, Int> = 1080 to 2400
    ): List<SemanticNode> {
        if (visualElements.isEmpty()) {
            // No vision elements: return a11y nodes tagged with ACCESSIBILITY
            return a11yNodes.map { node ->
                node.copy(
                    perceptionSource = PerceptionSource.ACCESSIBILITY,
                    fusedConfidence = if (node.fusedConfidence == 1.0f) CONFIDENCE_ACCESSIBILITY else node.fusedConfidence
                )
            }
        }

        val matchedVisualIndices = mutableSetOf<Int>()
        val fusedNodes = mutableListOf<SemanticNode>()

        // 1. Match each a11y node against visual elements
        for (a11yNode in a11yNodes) {
            var bestVisualIndex = -1
            var bestScore = -1f

            for ((vIdx, visual) in visualElements.withIndex()) {
                if (vIdx in matchedVisualIndices) continue

                val score = computeMatchScore(a11yNode, visual)
                if (score > bestScore && score >= 0.5f) {
                    bestScore = score
                    bestVisualIndex = vIdx
                }
            }

            if (bestVisualIndex >= 0) {
                matchedVisualIndices.add(bestVisualIndex)
                val matchedVisual = visualElements[bestVisualIndex]
                Log.d(TAG, "Fused a11y node '${a11yNode.label}' with visual '${matchedVisual.label}' (score=$bestScore)")

                fusedNodes.add(
                    a11yNode.copy(
                        perceptionSource = PerceptionSource.ACCESSIBILITY_AND_VISION,
                        visualConfidence = matchedVisual.confidence,
                        fusedConfidence = max(CONFIDENCE_FUSED, matchedVisual.confidence),
                        visualBounds = matchedVisual.bounds
                    )
                )
            } else {
                // Preserved semantic a11y node without visual match
                fusedNodes.add(
                    a11yNode.copy(
                        perceptionSource = PerceptionSource.ACCESSIBILITY,
                        visualConfidence = null,
                        fusedConfidence = if (a11yNode.fusedConfidence == 1.0f) CONFIDENCE_ACCESSIBILITY else a11yNode.fusedConfidence,
                        visualBounds = null
                    )
                )
            }
        }

        // 2. Add unmatched visual elements as synthetic SemanticNodes
        val maxIndex = a11yNodes.maxOfOrNull { it.index } ?: 0
        var syntheticOffset = 1

        for ((vIdx, visual) in visualElements.withIndex()) {
            if (vIdx in matchedVisualIndices) continue

            val newIndex = maxIndex + syntheticOffset++
            val isInput = visual.type.equals("INPUT", ignoreCase = true) ||
                    visual.label.contains("input", ignoreCase = true) ||
                    visual.label.contains("search", ignoreCase = true)

            val syntheticNode = SemanticNode(
                index = newIndex,
                text = visual.label,
                contentDescription = "Visual element: ${visual.label}",
                className = if (isInput) "android.widget.EditText" else "android.widget.ImageView",
                left = visual.left,
                top = visual.top,
                right = visual.right,
                bottom = visual.bottom,
                bounds = RectF(visual.left, visual.top, visual.right, visual.bottom),
                isClickable = true,
                isEditable = isInput,
                isScrollable = false,
                isCheckable = false,
                isChecked = false,
                isFocused = false,
                isEnabled = true,
                perceptionSource = PerceptionSource.VISION,
                visualConfidence = visual.confidence,
                fusedConfidence = visual.confidence,
                visualBounds = RectF(visual.left, visual.top, visual.right, visual.bottom)
            )
            Log.d(TAG, "Created synthetic vision node: '${visual.label}' at index #$newIndex")
            fusedNodes.add(syntheticNode)
        }

        return fusedNodes
    }

    /**
     * Computes a composite match score between an a11y node and a visual element.
     * Combines IoU, center-point proximity, and semantic label similarity.
     */
    fun computeMatchScore(a11y: SemanticNode, visual: VisualElement): Float {
        val iou = computeIoU(
            a11y.left, a11y.top, a11y.right, a11y.bottom,
            visual.left, visual.top, visual.right, visual.bottom
        )
        val overlapRatio = computeIntersectionOverMinArea(
            a11y.left, a11y.top, a11y.right, a11y.bottom,
            visual.left, visual.top, visual.right, visual.bottom
        )
        val centerDist = computeCenterDistance(
            a11y.left, a11y.top, a11y.right, a11y.bottom,
            visual.left, visual.top, visual.right, visual.bottom
        )
        val labelSim = computeLabelSimilarity(a11y, visual)

        // Case 1: High spatial IoU (> 0.4)
        if (iou >= 0.4f) {
            return 0.7f + (0.3f * labelSim)
        }

        // Case 2: High overlap ratio (one inside or mostly covering the other)
        if (overlapRatio >= OVERLAP_RATIO_THRESHOLD) {
            return 0.6f + (0.4f * labelSim)
        }

        // Case 3: Close spatial proximity with semantic match
        if (centerDist <= PROXIMITY_DISTANCE_PX) {
            val distScore = (1f - (centerDist / PROXIMITY_DISTANCE_PX)).coerceIn(0f, 1f)
            return (0.4f * distScore) + (0.6f * labelSim)
        }

        // Case 4: Strong label match with moderate proximity (e.g. within 150px)
        if (labelSim >= 0.8f && centerDist <= 150f) {
            return 0.5f + (0.5f * labelSim)
        }

        return 0f
    }

    fun computeIoU(
        l1: Float, t1: Float, r1: Float, b1: Float,
        l2: Float, t2: Float, r2: Float, b2: Float
    ): Float {
        val intersectionLeft = max(l1, l2)
        val intersectionTop = max(t1, t2)
        val intersectionRight = min(r1, r2)
        val intersectionBottom = min(b1, b2)

        val intersectionArea = if (intersectionRight > intersectionLeft && intersectionBottom > intersectionTop) {
            (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        } else 0f

        val area1 = max(0f, (r1 - l1) * (b1 - t1))
        val area2 = max(0f, (r2 - l2) * (b2 - t2))
        val unionArea = area1 + area2 - intersectionArea

        return if (unionArea > 0f) (intersectionArea / unionArea).coerceIn(0f, 1f) else 0f
    }

    fun computeIoU(r1: RectF, r2: RectF): Float {
        return computeIoU(r1.left, r1.top, r1.right, r1.bottom, r2.left, r2.top, r2.right, r2.bottom)
    }

    fun computeIntersectionOverMinArea(
        l1: Float, t1: Float, r1: Float, b1: Float,
        l2: Float, t2: Float, r2: Float, b2: Float
    ): Float {
        val intersectionLeft = max(l1, l2)
        val intersectionTop = max(t1, t2)
        val intersectionRight = min(r1, r2)
        val intersectionBottom = min(b1, b2)

        val intersectionArea = if (intersectionRight > intersectionLeft && intersectionBottom > intersectionTop) {
            (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        } else 0f

        val area1 = max(0f, (r1 - l1) * (b1 - t1))
        val area2 = max(0f, (r2 - l2) * (b2 - t2))
        val minArea = min(area1, area2)

        return if (minArea > 0f) (intersectionArea / minArea).coerceIn(0f, 1f) else 0f
    }

    fun computeIntersectionOverMinArea(r1: RectF, r2: RectF): Float {
        return computeIntersectionOverMinArea(r1.left, r1.top, r1.right, r1.bottom, r2.left, r2.top, r2.right, r2.bottom)
    }

    fun computeCenterDistance(
        l1: Float, t1: Float, r1: Float, b1: Float,
        l2: Float, t2: Float, r2: Float, b2: Float
    ): Float {
        val c1x = (l1 + r1) / 2f
        val c1y = (t1 + b1) / 2f
        val c2x = (l2 + r2) / 2f
        val c2y = (t2 + b2) / 2f
        return hypot(c1x - c2x, c1y - c2y)
    }

    fun computeCenterDistance(r1: RectF, r2: RectF): Float {
        return computeCenterDistance(r1.left, r1.top, r1.right, r1.bottom, r2.left, r2.top, r2.right, r2.bottom)
    }

    fun computeLabelSimilarity(a11y: SemanticNode, visual: VisualElement): Float {
        val a11yLabel = a11y.label.lowercase().trim()
        val visLabel = visual.label.lowercase().trim()
        val visType = visual.type.lowercase().trim()

        if (a11yLabel.isBlank() || visLabel.isBlank()) {
            // Check if class/type matches (e.g. icon/image <-> button/view)
            return if (a11y.className?.contains("Image", ignoreCase = true) == true && visType.contains("icon")) 0.5f else 0f
        }

        // Exact match
        if (a11yLabel == visLabel) return 1.0f

        // Substring / contains match
        if (a11yLabel.contains(visLabel) || visLabel.contains(a11yLabel)) return 0.85f

        // Resource ID check
        val resId = a11y.resourceId?.lowercase() ?: ""
        if (resId.contains(visLabel)) return 0.80f

        // Semantic synonym checking
        val synonyms = getSynonyms(visLabel)
        if (synonyms.any { a11yLabel.contains(it) || resId.contains(it) }) return 0.85f

        return 0f
    }

    private fun getSynonyms(term: String): List<String> {
        val lower = term.lowercase()
        return when {
            lower.contains("search") || lower.contains("magnifying") || lower.contains("find") ->
                listOf("search", "find", "search_src_text", "query")
            lower.contains("settings") || lower.contains("gear") ->
                listOf("settings", "config", "preferences")
            lower.contains("back") || lower.contains("arrow_back") ->
                listOf("back", "up", "navigate up")
            lower.contains("close") || lower.contains("cross") || lower.contains("dismiss") ->
                listOf("close", "cancel", "dismiss", "exit")
            lower.contains("torch") || lower.contains("flashlight") ->
                listOf("torch", "flashlight")
            lower.contains("like") || lower.contains("heart") || lower.contains("thumb") ->
                listOf("like", "thumb up", "favorite")
            lower.contains("install") || lower.contains("download") ->
                listOf("install", "download", "get")
            else -> emptyList()
        }
    }
}
