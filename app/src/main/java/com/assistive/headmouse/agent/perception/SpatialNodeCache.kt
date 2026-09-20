package com.assistive.headmouse.agent.perception

import android.graphics.RectF
import com.assistive.headmouse.agent.model.ScreenNode
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlin.math.min

/**
 * Thread-safe in-memory 2D spatial cache of active, interactive ScreenNodes.
 * Provides microsecond-level spatial queries for magnetic cursor snapping
 * and fuzzy text search for named voice targeting.
 */
class SpatialNodeCache {

    private val nodes = CopyOnWriteArrayList<ScreenNode>()

    fun updateNodes(newNodes: List<ScreenNode>) {
        nodes.clear()
        nodes.addAll(newNodes)
    }

    fun getNodes(): List<ScreenNode> = nodes.toList()

    fun clear() {
        nodes.clear()
    }

    /**
     * Finds the nearest interactive node to (x, y) within maxDistancePx.
     * Prioritizes nodes that directly contain the point (selecting the smallest, most specific leaf node).
     * If no node contains the point, finds the node whose boundary/center is closest within maxDistancePx.
     */
    fun findNearestNode(x: Float, y: Float, maxDistancePx: Float): ScreenNode? {
        val currentNodes = nodes
        if (currentNodes.isEmpty()) return null

        // 1. Direct hit: check if (x, y) is directly inside any node bounds
        val containingNodes = currentNodes.filter { it.containsPoint(x, y) }
        if (containingNodes.isNotEmpty()) {
            // Return the smallest containing node (most specific child button/control)
            return containingNodes.minByOrNull { it.area }
        }

        // 2. Proximity check: compute distance from point to node bounding box
        var bestNode: ScreenNode? = null
        var bestDistance = maxDistancePx

        for (node in currentNodes) {
            val dist = distanceToNode(x, y, node)
            if (dist < bestDistance) {
                bestDistance = dist
                bestNode = node
            }
        }

        return bestNode
    }

    /**
     * Calculates Euclidean distance from point (px, py) to the nearest edge of node.
     */
    private fun distanceToNode(px: Float, py: Float, node: ScreenNode): Float {
        val nearestX = px.coerceIn(node.left, node.right)
        val nearestY = py.coerceIn(node.top, node.bottom)
        return hypot((px - nearestX).toDouble(), (py - nearestY).toDouble()).toFloat()
    }

    /**
     * Finds nodes matching the spoken query text using exact, substring, word-token,
     * and fuzzy Levenshtein matching.
     */
    fun findNodesByText(query: String): List<ScreenNode> {
        val cleanQuery = query.lowercase().trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val currentNodes = nodes.filter { !it.isPassword && it.label.isNotBlank() }

        // 1. Exact match
        val exactMatches = currentNodes.filter { it.label.lowercase().trim() == cleanQuery }
        if (exactMatches.isNotEmpty()) return exactMatches

        // 2. Starts-with or Substring containment
        val substringMatches = currentNodes.filter {
            val lbl = it.label.lowercase().trim()
            lbl.startsWith(cleanQuery) || cleanQuery.startsWith(lbl) || lbl.contains(cleanQuery) || cleanQuery.contains(lbl)
        }
        if (substringMatches.isNotEmpty()) return substringMatches

        // 3. Word-token intersection
        val queryTokens = cleanQuery.split("\\s+".toRegex()).filter { it.length > 1 }
        val tokenMatches = currentNodes.filter { node ->
            val labelTokens = node.label.lowercase().split("\\s+".toRegex())
            queryTokens.any { qToken -> labelTokens.any { lToken -> lToken.contains(qToken) || qToken.contains(lToken) } }
        }
        if (tokenMatches.isNotEmpty()) return tokenMatches

        // 4. Fuzzy Levenshtein distance (up to distance 2 for words >= 4 chars)
        return currentNodes.filter { node ->
            val lbl = node.label.lowercase().trim()
            levenshteinDistance(cleanQuery, lbl) <= 2 ||
                lbl.split("\\s+".toRegex()).any { word -> levenshteinDistance(cleanQuery, word) <= 2 }
        }
    }

    /**
     * Finds the primary scrollable container currently on screen.
     */
    fun findScrollableContainer(): ScreenNode? {
        return nodes.filter { it.isScrollable }
            .maxByOrNull { it.area }
    }

    companion object {
        fun levenshteinDistance(s1: String, s2: String): Int {
            val m = s1.length
            val n = s2.length
            val dp = Array(m + 1) { IntArray(n + 1) }

            for (i in 0..m) dp[i][0] = i
            for (j in 0..n) dp[0][j] = j

            for (i in 1..m) {
                for (j in 1..n) {
                    val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                    dp[i][j] = min(
                        dp[i - 1][j] + 1,
                        min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                    )
                }
            }
            return dp[m][n]
        }
    }
}
