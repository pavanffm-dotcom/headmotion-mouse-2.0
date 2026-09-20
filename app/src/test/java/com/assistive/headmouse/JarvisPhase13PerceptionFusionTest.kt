package com.assistive.headmouse

import android.graphics.RectF
import com.assistive.headmouse.agent.jarvis.action.*
import com.assistive.headmouse.agent.jarvis.autonomous.PerceptionFusion
import com.assistive.headmouse.agent.jarvis.autonomous.perception.PerceptionFusionEngine
import com.assistive.headmouse.agent.jarvis.autonomous.state.PerceptionSource
import com.assistive.headmouse.agent.jarvis.autonomous.state.SemanticNode
import com.assistive.headmouse.agent.jarvis.autonomous.state.VisualElement
import com.assistive.headmouse.agent.jarvis.autonomous.state.WorldState
import com.assistive.headmouse.agent.model.ScreenNode
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 13 Verification Test Suite: Accessibility + Vision Perception Fusion.
 * Validates:
 * 1. PerceptionFusionEngine matches overlapping A11y and Vision elements into ACCESSIBILITY_AND_VISION.
 * 2. Preservation of semantic Accessibility nodes when no vision match exists.
 * 3. Synthetic SemanticNode creation for unmatched visual elements (VISION fallback).
 * 4. TargetResolver precedence: FUSED (0.99f) > ACCESSIBILITY (0.95f) > VISION (0.85f).
 * 5. TargetResolver fallback to vision when accessibility cannot identify target.
 * 6. TargetResolver never automatically prefers vision over reliable accessibility.
 * 7. Source attribution and confidence reporting in TargetResolution.Success and ResolvedTarget.
 * 8. ScreenObserver.fusePerception integration.
 * 9. SemanticNode compact string formatting with [Fused] and [Vision] tags.
 */
class JarvisPhase13PerceptionFusionTest {

    // =========================================================================
    // 1. PerceptionFusionEngine Core Matching Tests
    // =========================================================================

    @Test
    fun test_PerceptionFusionEngine_matchesOverlappingA11yAndVision() {
        val a11yNode = SemanticNode(
            index = 1,
            text = "Search",
            left = 100f, top = 100f, right = 300f, bottom = 160f,
            isClickable = true
        )
        val visualElement = VisualElement(
            label = "Search",
            left = 105f, top = 102f, right = 295f, bottom = 158f,
            confidence = 0.94f,
            type = "ICON"
        )

        val fused = PerceptionFusionEngine.fuse(
            a11yNodes = listOf(a11yNode),
            visualElements = listOf(visualElement)
        )

        assertEquals(1, fused.size)
        val result = fused.first()
        assertEquals(PerceptionSource.ACCESSIBILITY_AND_VISION, result.perceptionSource)
        assertEquals(0.99f, result.fusedConfidence, 0.01f)
        assertEquals(0.94f, result.visualConfidence ?: 0f, 0.01f)
        assertEquals("Search", result.text)
        assertTrue(result.isClickable)
    }

    @Test
    fun test_PerceptionFusionEngine_preservesSemanticA11yWithoutVision() {
        val a11yNode = SemanticNode(
            index = 1,
            text = "Settings",
            left = 50f, top = 50f, right = 200f, bottom = 100f,
            isClickable = true
        )

        val fused = PerceptionFusionEngine.fuse(
            a11yNodes = listOf(a11yNode),
            visualElements = emptyList()
        )

        assertEquals(1, fused.size)
        val result = fused.first()
        assertEquals(PerceptionSource.ACCESSIBILITY, result.perceptionSource)
        assertEquals(0.95f, result.fusedConfidence, 0.01f)
        assertNull(result.visualConfidence)
        assertNull(result.visualBounds)
        assertEquals("Settings", result.text)
    }

    @Test
    fun test_PerceptionFusionEngine_createsSyntheticNodeForUnmatchedVision() {
        val a11yNode = SemanticNode(
            index = 1,
            text = "Home",
            left = 0f, top = 0f, right = 100f, bottom = 50f
        )
        val visualElement = VisualElement(
            label = "Floating Camera Button",
            left = 800f, top = 1500f, right = 950f, bottom = 1650f,
            confidence = 0.88f,
            type = "BUTTON"
        )

        val fused = PerceptionFusionEngine.fuse(
            a11yNodes = listOf(a11yNode),
            visualElements = listOf(visualElement)
        )

        assertEquals(2, fused.size)
        val a11yResult = fused.first { it.index == 1 }
        assertEquals(PerceptionSource.ACCESSIBILITY, a11yResult.perceptionSource)

        val visionResult = fused.first { it.index == 2 }
        assertEquals(PerceptionSource.VISION, visionResult.perceptionSource)
        assertEquals("Floating Camera Button", visionResult.text)
        assertEquals(0.88f, visionResult.fusedConfidence, 0.01f)
        assertEquals(0.88f, visionResult.visualConfidence ?: 0f, 0.01f)
        assertTrue(visionResult.isClickable)
        assertEquals(800f, visionResult.left, 0.1f)
        assertEquals(1500f, visionResult.top, 0.1f)
    }

    // =========================================================================
    // 2. Spatial and Semantic Match Scoring Tests
    // =========================================================================

    @Test
    fun test_computeIoU_and_computeIntersectionOverMinArea() {
        // Primitive coordinates: r1 = (0,0)-(100,100), r2 = (50,0)-(150,100)
        // area1 = 10,000, area2 = 10,000, intersection = 5,000, union = 15,000
        val iou = PerceptionFusionEngine.computeIoU(
            0f, 0f, 100f, 100f,
            50f, 0f, 150f, 100f
        )
        assertEquals(5000f / 15000f, iou, 0.01f)

        val minOverlap = PerceptionFusionEngine.computeIntersectionOverMinArea(
            0f, 0f, 100f, 100f,
            50f, 0f, 150f, 100f
        )
        assertEquals(5000f / 10000f, minOverlap, 0.01f)
    }

    @Test
    fun test_computeLabelSimilarity_synonyms() {
        val node = SemanticNode(index = 1, text = "Torch", left = 0f, top = 0f, right = 50f, bottom = 50f)
        val visual = VisualElement(label = "Flashlight", left = 0f, top = 0f, right = 50f, bottom = 50f)

        val sim = PerceptionFusionEngine.computeLabelSimilarity(node, visual)
        assertTrue("Flashlight and Torch must be recognized as synonyms", sim >= 0.8f)
    }

    // =========================================================================
    // 3. TargetResolver Precedence & Fusion Integration Tests
    // =========================================================================

    @Test
    fun test_TargetResolver_prefersFusedOverSingleModality() {
        val a11yOnly = SemanticNode(
            index = 1,
            text = "Search",
            left = 10f, top = 10f, right = 100f, bottom = 50f,
            isClickable = true,
            perceptionSource = PerceptionSource.ACCESSIBILITY,
            fusedConfidence = 0.95f
        )
        val fusedNode = SemanticNode(
            index = 2,
            text = "Search",
            left = 10f, top = 100f, right = 100f, bottom = 150f,
            isClickable = true,
            perceptionSource = PerceptionSource.ACCESSIBILITY_AND_VISION,
            fusedConfidence = 0.99f
        )

        val worldState = WorldState(
            foregroundPackage = "com.test.app",
            nodes = listOf(a11yOnly, fusedNode)
        )

        val resolution = TargetResolver.resolveSemanticTarget(
            query = TargetQuery(text = "Search"),
            worldState = worldState
        )

        assertTrue(resolution is TargetResolution.Success)
        val success = resolution as TargetResolution.Success
        assertEquals("Must select the fused node #2", 2, success.node.index)
        assertEquals(PerceptionSource.ACCESSIBILITY_AND_VISION, success.source)
        assertEquals(0.99f, success.confidence, 0.01f)
    }

    @Test
    fun test_TargetResolver_doesNotPreferVisionOverA11y() {
        val a11yNode = SemanticNode(
            index = 1,
            text = "Submit",
            left = 10f, top = 10f, right = 100f, bottom = 50f,
            isClickable = true,
            perceptionSource = PerceptionSource.ACCESSIBILITY,
            fusedConfidence = 0.95f
        )
        val visionNode = SemanticNode(
            index = 2,
            text = "Submit",
            left = 10f, top = 100f, right = 100f, bottom = 150f,
            isClickable = true,
            perceptionSource = PerceptionSource.VISION,
            fusedConfidence = 0.85f
        )

        val worldState = WorldState(
            foregroundPackage = "com.test.app",
            nodes = listOf(a11yNode, visionNode)
        )

        val resolution = TargetResolver.resolveSemanticTarget(
            query = TargetQuery(text = "Submit"),
            worldState = worldState
        )

        assertTrue(resolution is TargetResolution.Success)
        val success = resolution as TargetResolution.Success
        assertEquals("Must prefer semantic accessibility #1 over vision #2", 1, success.node.index)
        assertEquals(PerceptionSource.ACCESSIBILITY, success.source)
    }

    @Test
    fun test_TargetResolver_fallsBackToVisionWhenA11yFails() {
        val a11yNode = SemanticNode(
            index = 1,
            text = "Settings",
            left = 10f, top = 10f, right = 100f, bottom = 50f,
            isClickable = true,
            perceptionSource = PerceptionSource.ACCESSIBILITY
        )
        val visionNode = SemanticNode(
            index = 2,
            text = "Special Filter Icon",
            left = 10f, top = 100f, right = 100f, bottom = 150f,
            isClickable = true,
            perceptionSource = PerceptionSource.VISION,
            fusedConfidence = 0.85f
        )

        val worldState = WorldState(
            foregroundPackage = "com.test.app",
            nodes = listOf(a11yNode, visionNode)
        )

        val resolution = TargetResolver.resolveSemanticTarget(
            query = TargetQuery(text = "Filter"),
            worldState = worldState
        )

        assertTrue(resolution is TargetResolution.Success)
        val success = resolution as TargetResolution.Success
        assertEquals(2, success.node.index)
        assertEquals(PerceptionSource.VISION, success.source)
        assertEquals("VISION_FALLBACK", success.matchType)
    }

    @Test
    fun test_TargetResolver_resolveTarget_sourceAttribution() {
        // Vision fallback coordinates
        val visionResolved = TargetResolver.resolveTarget(
            target = null,
            nodes = emptyList(),
            visionCoordinates = 500f to 600f
        )
        assertNotNull(visionResolved)
        assertEquals(PerceptionSource.VISION, visionResolved?.source)
        assertEquals("VISION_FALLBACK", visionResolved?.matchType)

        // Accessibility node match
        val a11yScreenNode = ScreenNode(
            id = "node_1",
            text = "Profile",
            left = 100f, top = 200f, right = 300f, bottom = 250f,
            isClickable = true
        )
        val a11yResolved = TargetResolver.resolveTarget(
            target = ActionTarget(type = TargetType.TEXT, value = "Profile"),
            nodes = listOf(a11yScreenNode)
        )
        assertNotNull(a11yResolved)
        assertEquals(PerceptionSource.ACCESSIBILITY, a11yResolved?.source)
    }

    // =========================================================================
    // 4. PerceptionFusion Helper Object Tests
    // =========================================================================

    @Test
    fun test_PerceptionFusion_resolve_bothMatch() {
        val a11yNode = ScreenNode(id = "1", text = "Search", left = 10f, top = 10f, right = 100f, bottom = 50f)
        val visionNode = ScreenNode(id = "1", text = "Search", left = 12f, top = 11f, right = 98f, bottom = 49f)

        val result = PerceptionFusion.resolve(
            target = ActionTarget(type = TargetType.TEXT, value = "Search"),
            accessibilityNodes = listOf(a11yNode),
            visionCandidates = listOf(visionNode)
        )

        assertNotNull(result)
        assertEquals(PerceptionSource.ACCESSIBILITY_AND_VISION, result?.source)
        assertEquals(0.99f, result?.confidence ?: 0f, 0.01f)
    }

    @Test
    fun test_PerceptionFusion_resolve_visionOnlyMatch() {
        val visionNode = ScreenNode(id = "v1", text = "Custom Icon", left = 12f, top = 11f, right = 98f, bottom = 49f)

        val result = PerceptionFusion.resolve(
            target = ActionTarget(type = TargetType.TEXT, value = "Custom Icon"),
            accessibilityNodes = emptyList(),
            visionCandidates = listOf(visionNode)
        )

        assertNotNull(result)
        assertEquals(PerceptionSource.VISION, result?.source)
    }

    // =========================================================================
    // 5. ScreenObserver and SemanticNode Formatting Tests
    // =========================================================================

    @Test
    fun test_ScreenObserver_fusePerception() {
        val observer = ScreenObserver()
        val initialWorld = WorldState(
            foregroundPackage = "com.test.browser",
            nodes = listOf(
                SemanticNode(index = 1, text = "Refresh", left = 10f, top = 10f, right = 80f, bottom = 80f)
            )
        )
        val visualElements = listOf(
            VisualElement(label = "Refresh", left = 12f, top = 12f, right = 78f, bottom = 78f, confidence = 0.93f)
        )

        val fusedWorld = observer.fusePerception(initialWorld, visualElements)
        assertEquals(1, fusedWorld.nodes.size)
        assertEquals(PerceptionSource.ACCESSIBILITY_AND_VISION, fusedWorld.nodes[0].perceptionSource)
        assertEquals(1, fusedWorld.visualElements.size)
    }

    @Test
    fun test_SemanticNode_compactString_includesFusionTags() {
        val fusedNode = SemanticNode(
            index = 1,
            text = "Search",
            left = 0f, top = 0f, right = 100f, bottom = 50f,
            perceptionSource = PerceptionSource.ACCESSIBILITY_AND_VISION
        )
        assertTrue("Compact string must contain [Fused] tag", fusedNode.toCompactString().contains("[Fused]"))

        val visionNode = SemanticNode(
            index = 2,
            text = "Camera",
            left = 0f, top = 0f, right = 100f, bottom = 50f,
            perceptionSource = PerceptionSource.VISION
        )
        assertTrue("Compact string must contain [Vision] tag", visionNode.toCompactString().contains("[Vision]"))

        val a11yNode = SemanticNode(
            index = 3,
            text = "Settings",
            left = 0f, top = 0f, right = 100f, bottom = 50f,
            perceptionSource = PerceptionSource.ACCESSIBILITY
        )
        assertFalse("A11y node should not contain [Fused]", a11yNode.toCompactString().contains("[Fused]"))
        assertFalse("A11y node should not contain [Vision]", a11yNode.toCompactString().contains("[Vision]"))
    }
}
