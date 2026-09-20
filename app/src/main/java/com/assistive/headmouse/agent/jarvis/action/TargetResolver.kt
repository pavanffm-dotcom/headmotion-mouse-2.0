package com.assistive.headmouse.agent.jarvis.action

import android.graphics.RectF
import android.util.Log
import com.assistive.headmouse.agent.jarvis.autonomous.state.PerceptionSource
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.model.ScreenNode

data class ResolvedTarget(
    val x: Float,
    val y: Float,
    val bounds: RectF,
    val matchedNode: ScreenNode? = null,
    val matchType: String = "ACCESSIBILITY",
    val confidence: Float = 1.0f,
    val source: PerceptionSource = PerceptionSource.ACCESSIBILITY
)

/**
 * Query specification for target resolution in closed-loop agent execution.
 */
data class TargetQuery(
    val index: Int? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val coordinates: Pair<Float, Float>? = null
)

/**
 * Structured outcome of semantic target resolution.
 */
sealed class TargetResolution {
    data class Success(
        val node: SemanticNode,
        val x: Float,
        val y: Float,
        val matchType: String,
        val confidence: Float,
        val source: PerceptionSource = node.perceptionSource
    ) : TargetResolution()

    data class Ambiguous(
        val candidates: List<SemanticNode>,
        val reason: String
    ) : TargetResolution()

    data class Disabled(
        val node: SemanticNode,
        val reason: String
    ) : TargetResolution()

    data class ScrollRequired(
        val candidate: SemanticNode?,
        val direction: String, // "DOWN" or "UP"
        val reason: String
    ) : TargetResolution()

    data class Occluded(
        val node: SemanticNode,
        val occludedBy: String, // "KEYBOARD" or "OVERLAY"
        val reason: String
    ) : TargetResolution()

    data class NotFound(
        val query: String,
        val reason: String
    ) : TargetResolution()
}

/**
 * High-precision Semantic Target Resolver.
 * Resolves abstract action targets to physical screen coordinates using strict priority order:
 * 1. Accessibility text
 * 2. contentDescription
 * 3. resourceId
 * 4. className + semantic role
 * 5. visible text similarity (Levenshtein / fuzzy token overlap)
 * 6. accessibility bounds
 * 7. vision fallback
 */
object TargetResolver {

    private const val TAG = "TargetResolver"

    fun resolveTarget(
        target: ActionTarget?,
        nodes: List<ScreenNode>,
        visionCoordinates: Pair<Float, Float>? = null
    ): ResolvedTarget? {
        if (target == null) {
            // Default to vision fallback coordinates if provided
            if (visionCoordinates != null) {
                val (vx, vy) = visionCoordinates
                return ResolvedTarget(
                    x = vx,
                    y = vy,
                    bounds = RectF(vx - 20f, vy - 20f, vx + 20f, vy + 20f),
                    matchType = "VISION_FALLBACK",
                    confidence = 0.85f,
                    source = PerceptionSource.VISION
                )
            }
            return null
        }

        val targetVal = target.value.trim()
        val lowerTarget = targetVal.lowercase()

        // 0. Priority 0: Numeric Node ID / Index Match (e.g. "#1", "1", "node_1", or exact node.id)
        val cleanTarget = targetVal.removePrefix("#").trim()
        val indexMatch = cleanTarget.toIntOrNull()?.let { idx ->
            if (idx in 1..nodes.size) nodes[idx - 1] else null
        } ?: nodes.firstOrNull { it.id.equals(targetVal, ignoreCase = true) || it.id.equals(cleanTarget, ignoreCase = true) }

        if (indexMatch != null) {
            Log.i(TAG, "Resolved target via Priority 0 (Node Index/ID): ${indexMatch.label} at (${indexMatch.centerX}, ${indexMatch.centerY})")
            return ResolvedTarget(
                x = indexMatch.centerX,
                y = indexMatch.centerY,
                bounds = indexMatch.bounds,
                matchedNode = indexMatch,
                matchType = "NODE_INDEX_ID",
                confidence = 0.99f
            )
        }

        // 1. Accessibility text (exact match, then contains)
        val textMatch = nodes.firstOrNull { node ->
            node.text?.trim().equals(targetVal, ignoreCase = true)
        } ?: nodes.firstOrNull { node ->
            node.text?.lowercase()?.contains(lowerTarget) == true
        }

        if (textMatch != null) {
            Log.i(TAG, "Resolved target via Priority 1 (Accessibility Text): ${textMatch.label}")
            return ResolvedTarget(
                x = textMatch.centerX,
                y = textMatch.centerY,
                bounds = textMatch.bounds,
                matchedNode = textMatch,
                matchType = "ACCESSIBILITY_TEXT",
                confidence = 0.98f
            )
        }

        // 2. contentDescription (exact match, then contains)
        val descMatch = nodes.firstOrNull { node ->
            node.contentDescription?.trim().equals(targetVal, ignoreCase = true)
        } ?: nodes.firstOrNull { node ->
            node.contentDescription?.lowercase()?.contains(lowerTarget) == true
        }

        if (descMatch != null) {
            Log.i(TAG, "Resolved target via Priority 2 (contentDescription): ${descMatch.label}")
            return ResolvedTarget(
                x = descMatch.centerX,
                y = descMatch.centerY,
                bounds = descMatch.bounds,
                matchedNode = descMatch,
                matchType = "CONTENT_DESCRIPTION",
                confidence = 0.95f
            )
        }

        // 3. resourceId / viewIdResourceName
        val idMatch = nodes.firstOrNull { node ->
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            resId.contains(lowerTarget) || resId.endsWith("/$lowerTarget")
        }

        if (idMatch != null) {
            Log.i(TAG, "Resolved target via Priority 3 (resourceId): ${idMatch.viewIdResourceName}")
            return ResolvedTarget(
                x = idMatch.centerX,
                y = idMatch.centerY,
                bounds = idMatch.bounds,
                matchedNode = idMatch,
                matchType = "RESOURCE_ID",
                confidence = 0.90f
            )
        }

        // 4. className + semantic role (e.g. "search_bar" -> EditText, "button" -> Button)
        if (target.type == TargetType.SEMANTIC_ROLE || target.type == TargetType.CLASS_NAME ||
            lowerTarget == "search" || lowerTarget == "search_bar" || lowerTarget == "input" ||
            lowerTarget == "edittext" || lowerTarget == "search field" || lowerTarget == "input field") {
            val editMatch = nodes.firstOrNull { node ->
                node.className?.contains("EditText", ignoreCase = true) == true ||
                        (node.isFocusable && !node.isClickable)
            }
            if (editMatch != null) {
                Log.i(TAG, "Resolved target via Priority 4 (Semantic Role: EditText)")
                return ResolvedTarget(
                    x = editMatch.centerX,
                    y = editMatch.centerY,
                    bounds = editMatch.bounds,
                    matchedNode = editMatch,
                    matchType = "SEMANTIC_ROLE_EDIT_TEXT",
                    confidence = 0.88f
                )
            }
        }

        // 4b. Semantic Synonyms (e.g. flashlight <-> torch, shorts, like, install)
        val synonyms = when {
            lowerTarget.contains("flashlight") || lowerTarget.contains("torch") -> listOf("flashlight", "torch")
            lowerTarget.contains("shorts") -> listOf("shorts")
            lowerTarget.contains("install") || lowerTarget.contains("download") -> listOf("install", "download", "get")
            lowerTarget.contains("like") -> listOf("like", "thumb up")
            lowerTarget.contains("search") -> listOf("search", "find", "search settings", "search apps")
            else -> emptyList()
        }
        if (synonyms.isNotEmpty()) {
            val synMatch = nodes.firstOrNull { node ->
                synonyms.any { syn -> node.label.lowercase().contains(syn) }
            }
            if (synMatch != null) {
                Log.i(TAG, "Resolved target via Priority 4b (Semantic Synonym): ${synMatch.label}")
                return ResolvedTarget(
                    x = synMatch.centerX,
                    y = synMatch.centerY,
                    bounds = synMatch.bounds,
                    matchedNode = synMatch,
                    matchType = "SEMANTIC_SYNONYM",
                    confidence = 0.92f
                )
            }
        }

        // 5. Visible text similarity (Levenshtein distance <= 2)
        var bestNode: ScreenNode? = null
        var bestDistance = 999

        for (node in nodes) {
            val lbl = node.label.lowercase()
            if (lbl.isBlank()) continue
            val dist = computeLevenshtein(lowerTarget, lbl)
            if (dist <= 2 && dist < bestDistance) {
                bestDistance = dist
                bestNode = node
            }
        }

        if (bestNode != null) {
            Log.i(TAG, "Resolved target via Priority 5 (Text Similarity distance=$bestDistance): ${bestNode.label}")
            return ResolvedTarget(
                x = bestNode.centerX,
                y = bestNode.centerY,
                bounds = bestNode.bounds,
                matchedNode = bestNode,
                matchType = "TEXT_SIMILARITY",
                confidence = 0.85f
            )
        }

        // 6. Coordinates if target explicitly specified COORDINATES
        if (target.type == TargetType.COORDINATES) {
            val parts = targetVal.split(",", "x", " ")
            if (parts.size >= 2) {
                val x = parts[0].trim().toFloatOrNull()
                val y = parts[1].trim().toFloatOrNull()
                if (x != null && y != null) {
                    Log.i(TAG, "Resolved target via Priority 6 (Explicit Coordinates: $x, $y)")
                    return ResolvedTarget(
                        x = x,
                        y = y,
                        bounds = RectF(x - 20f, y - 20f, x + 20f, y + 20f),
                        matchType = "EXPLICIT_COORDINATES",
                        confidence = 0.80f
                    )
                }
            }
        }

        // 7. Vision fallback
        if (visionCoordinates != null) {
            val (vx, vy) = visionCoordinates
            Log.i(TAG, "Resolved target via Priority 7 (Vision Fallback: $vx, $vy)")
            return ResolvedTarget(
                x = vx,
                y = vy,
                bounds = RectF(vx - 20f, vy - 20f, vx + 20f, vy + 20f),
                matchType = "VISION_FALLBACK",
                confidence = 0.75f,
                source = PerceptionSource.VISION
            )
        }

        Log.w(TAG, "TargetResolver could not resolve target: $targetVal")
        return null
    }

    private fun computeLevenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * Resolves a TargetQuery to a single, verified interactive SemanticNode on the active WorldState.
     * Prevents blind selection of ambiguous elements, flags disabled nodes, and detects off-screen/scroll-required nodes.
     */
    fun resolveSemanticTarget(
        query: TargetQuery,
        worldState: WorldState
    ): TargetResolution {
        val (screenWidth, screenHeight) = worldState.screenDimensions.let {
            val w = if (it.first > 0) it.first else 1080
            val h = if (it.second > 0) it.second else 2400
            w to h
        }

        // 0. Explicit Coordinates Match
        if (query.coordinates != null) {
            val (cx, cy) = query.coordinates
            val node = worldState.nodes.firstOrNull { it.contains(cx, cy) }
                ?: SemanticNode(
                    index = 0,
                    text = "Coordinate Target ($cx, $cy)",
                    left = cx - 20f, top = cy - 20f, right = cx + 20f, bottom = cy + 20f,
                    isClickable = true,
                    perceptionSource = PerceptionSource.ACCESSIBILITY
                )
            return TargetResolution.Success(node, cx, cy, "COORDINATES", 0.95f, node.perceptionSource)
        }

        // 1. Priority 0: Numeric Node Index Match
        if (query.index != null && query.index > 0) {
            val indexMatch = worldState.nodes.firstOrNull { it.index == query.index }
            if (indexMatch != null) {
                return validateAndCreateResolution(indexMatch, screenWidth, screenHeight, worldState, "NODE_INDEX", 1.0f)
            }
        }

        val rawTarget = (query.text ?: query.contentDescription ?: query.resourceId ?: "").trim()
        if (rawTarget.isBlank()) {
            return TargetResolution.NotFound("", "Empty target query.")
        }
        val lowerTarget = rawTarget.lowercase()

        val a11yNodes = worldState.nodes.filter { it.perceptionSource != PerceptionSource.VISION }

        // 2. Exact text or contentDescription matches on Accessibility/Fused nodes
        val exactMatches = a11yNodes.filter { node ->
            node.text?.trim().equals(rawTarget, ignoreCase = true) ||
            node.contentDescription?.trim().equals(rawTarget, ignoreCase = true)
        }

        if (exactMatches.size > 1) {
            // Priority 2a: Check if there is an ACCESSIBILITY_AND_VISION match among them
            val fusedExact = exactMatches.filter { it.perceptionSource == PerceptionSource.ACCESSIBILITY_AND_VISION }
            if (fusedExact.size == 1) {
                return validateAndCreateResolution(fusedExact.first(), screenWidth, screenHeight, worldState, "EXACT_TEXT_FUSED", 0.99f)
            }
            val clickableExact = exactMatches.filter { it.isClickable }
            if (clickableExact.size == 1) {
                return validateAndCreateResolution(clickableExact.first(), screenWidth, screenHeight, worldState, "EXACT_TEXT_CLICKABLE", 0.98f)
            }
            // Ambiguity: Multiple identical matches! Do not blindly click first one!
            Log.w(TAG, "Ambiguous target: Found ${exactMatches.size} identical matches for '$rawTarget'")
            return TargetResolution.Ambiguous(
                candidates = exactMatches,
                reason = "Multiple candidate elements (${exactMatches.size}) identically match '$rawTarget' at indices ${exactMatches.map { it.index }}. Disambiguate by index or coordinates."
            )
        } else if (exactMatches.size == 1) {
            return validateAndCreateResolution(exactMatches.first(), screenWidth, screenHeight, worldState, "EXACT_TEXT", exactMatches.first().fusedConfidence)
        }

        // 3. Resource ID match
        if (!query.resourceId.isNullOrBlank() || rawTarget.contains(":id/") || rawTarget.contains("/")) {
            val idQuery = (query.resourceId ?: rawTarget).lowercase()
            val idMatches = a11yNodes.filter { node ->
                val res = node.resourceId?.lowercase() ?: ""
                res.contains(idQuery) || res.endsWith("/$idQuery")
            }
            if (idMatches.size > 1) {
                return TargetResolution.Ambiguous(
                    candidates = idMatches,
                    reason = "Multiple elements match resource ID '$idQuery' at indices ${idMatches.map { it.index }}."
                )
            } else if (idMatches.size == 1) {
                return validateAndCreateResolution(idMatches.first(), screenWidth, screenHeight, worldState, "RESOURCE_ID", 0.92f)
            }
        }

        // 4. Substring text or contentDescription match on Accessibility/Fused nodes
        val substringMatches = a11yNodes.filter { node ->
            val lbl = node.label.lowercase().trim()
            lbl.contains(lowerTarget) || lowerTarget.contains(lbl)
        }

        if (substringMatches.size > 1) {
            val fusedSub = substringMatches.filter { it.perceptionSource == PerceptionSource.ACCESSIBILITY_AND_VISION }
            if (fusedSub.size == 1) {
                return validateAndCreateResolution(fusedSub.first(), screenWidth, screenHeight, worldState, "SUBSTRING_FUSED", 0.95f)
            }
            val clickableSub = substringMatches.filter { it.isClickable }
            if (clickableSub.size == 1) {
                return validateAndCreateResolution(clickableSub.first(), screenWidth, screenHeight, worldState, "SUBSTRING_CLICKABLE", 0.90f)
            }
            val sorted = substringMatches.sortedBy { kotlin.math.abs(it.label.length - rawTarget.length) }
            if (sorted[0].label.length < sorted[1].label.length) {
                return validateAndCreateResolution(sorted.first(), screenWidth, screenHeight, worldState, "BEST_SUBSTRING", 0.88f)
            }
            return TargetResolution.Ambiguous(
                candidates = substringMatches,
                reason = "Multiple elements partially match '$rawTarget' (${substringMatches.map { it.label }}). Disambiguate by index."
            )
        } else if (substringMatches.size == 1) {
            return validateAndCreateResolution(substringMatches.first(), screenWidth, screenHeight, worldState, "SUBSTRING", substringMatches.first().fusedConfidence)
        }

        // 5. Semantic Synonyms on Accessibility/Fused nodes
        val synonyms = when {
            lowerTarget.contains("flashlight") || lowerTarget.contains("torch") -> listOf("flashlight", "torch")
            lowerTarget.contains("shorts") -> listOf("shorts")
            lowerTarget.contains("install") || lowerTarget.contains("download") || lowerTarget.contains("get") -> listOf("install", "download", "get")
            lowerTarget.contains("search") -> listOf("search", "find", "search settings", "search apps")
            lowerTarget.contains("close") || lowerTarget.contains("cancel") || lowerTarget.contains("dismiss") -> listOf("close", "cancel", "dismiss")
            else -> emptyList()
        }
        if (synonyms.isNotEmpty()) {
            val synMatches = a11yNodes.filter { node ->
                synonyms.any { syn -> node.label.lowercase().contains(syn) }
            }
            if (synMatches.size == 1) {
                return validateAndCreateResolution(synMatches.first(), screenWidth, screenHeight, worldState, "SEMANTIC_SYNONYM", 0.86f)
            } else if (synMatches.size > 1) {
                return TargetResolution.Ambiguous(
                    candidates = synMatches,
                    reason = "Multiple synonym matches found for '$rawTarget'."
                )
            }
        }

        // 5b. Vision Fallback: If accessibility didn't identify a target, check vision elements
        val visionCandidates = worldState.nodes.filter { node ->
            node.perceptionSource == PerceptionSource.VISION &&
            (node.text?.trim().equals(rawTarget, ignoreCase = true) ||
             node.contentDescription?.trim().equals(rawTarget, ignoreCase = true) ||
             node.label.lowercase().contains(lowerTarget) ||
             lowerTarget.contains(node.label.lowercase()))
        }
        if (visionCandidates.size == 1) {
            return validateAndCreateResolution(visionCandidates.first(), screenWidth, screenHeight, worldState, "VISION_FALLBACK", visionCandidates.first().fusedConfidence)
        } else if (visionCandidates.size > 1) {
            return TargetResolution.Ambiguous(
                candidates = visionCandidates,
                reason = "Multiple vision fallback elements match '$rawTarget' at indices ${visionCandidates.map { it.index }}."
            )
        }

        // 6. Check if target is off-screen and requires scrolling
        if (worldState.scrollableNodes.isNotEmpty()) {
            return TargetResolution.ScrollRequired(
                candidate = null,
                direction = "DOWN",
                reason = "Target '$rawTarget' is not visible in current viewport, but scrollable container is available. Scroll DOWN to reveal."
            )
        }

        // 7. Not found
        return TargetResolution.NotFound(
            query = rawTarget,
            reason = "Target '$rawTarget' could not be located on the active display."
        )
    }

    private fun validateAndCreateResolution(
        node: SemanticNode,
        screenWidth: Int,
        screenHeight: Int,
        worldState: WorldState,
        matchType: String,
        confidence: Float
    ): TargetResolution {
        // A. Check if disabled
        if (!node.isEnabled) {
            return TargetResolution.Disabled(
                node = node,
                reason = "Target element '${node.label}' (index #${node.index}) is disabled and cannot receive interactions."
            )
        }

        // B. Check if off-screen (below or above viewport)
        if (node.top >= screenHeight || (node.bottom > screenHeight && node.top > screenHeight * 0.85f)) {
            return TargetResolution.ScrollRequired(
                candidate = node,
                direction = "DOWN",
                reason = "Target element '${node.label}' is located off-screen below the current viewport (top=${node.top}, screenHeight=$screenHeight). Scroll DOWN to reveal."
            )
        }
        if (node.bottom <= 0 || (node.top < 0 && node.bottom < screenHeight * 0.15f)) {
            return TargetResolution.ScrollRequired(
                candidate = node,
                direction = "UP",
                reason = "Target element '${node.label}' is located off-screen above the current viewport (bottom=${node.bottom}). Scroll UP to reveal."
            )
        }

        // C. Check if occluded by software keyboard
        if (worldState.isKeyboardVisible && !node.isFocused) {
            val keyboardThreshold = screenHeight * 0.55f
            if (node.top >= keyboardThreshold) {
                return TargetResolution.Occluded(
                    node = node,
                    occludedBy = "KEYBOARD",
                    reason = "Target element '${node.label}' is occluded by the active software keyboard."
                )
            }
        }

        // D. Valid on-screen interactive target
        return TargetResolution.Success(
            node = node,
            x = node.centerX,
            y = node.centerY,
            matchType = matchType,
            confidence = confidence,
            source = node.perceptionSource
        )
    }
}
