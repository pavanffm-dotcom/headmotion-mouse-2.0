# Phase 1 Specification: Authoritative World State & Screen Perception

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Document:** `PHASE_1_WORLD_STATE.md`  
**Date:** 2026-09-17  

---

## 1. Normalized `WorldState` Model

In Phase 0, the agent failed to identify active screen elements because it inspected `rootInActiveWindow` (which was hijacked by floating overlay windows) and fell back to stale node caches.

The `WorldState` model provides a **normalized, canonical representation of the Android device display at time $T$**, filtered of all internal assistive overlays.

```kotlin
package com.assistive.headmouse.agent.jarvis.autonomous.state

import android.graphics.RectF

/**
 * Normalized representation of an interactive screen element.
 */
data class SemanticNode(
    val index: Int,                         // 1-based index (#1, #2, ...) for concise LLM referencing
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: RectF,                      // Absolute physical screen bounds (px)
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isFocused: Boolean = false
) {
    val centerX: Float get() = bounds.centerX()
    val centerY: Float get() = bounds.centerY()

    /**
     * Primary display label for semantic matching.
     */
    val label: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: resourceId?.substringAfterLast('/')?.replace('_', ' ')?.takeIf { it.isNotBlank() }
            ?: ""
}

/**
 * Authoritative perception snapshot of the Android display.
 */
data class WorldState(
    val timestamp: Long = System.currentTimeMillis(),
    
    // 1. Foreground Window Identification
    val foregroundPackage: String,
    val foregroundActivity: String? = null,
    val windowTitle: String? = null,

    // 2. Extracted Semantic Nodes (Max 60 prioritized interactive elements)
    val nodes: List<SemanticNode>,
    
    // 3. Functional UI Groupings
    val clickableNodes: List<SemanticNode>,
    val editableNodes: List<SemanticNode>,
    val scrollableNodes: List<SemanticNode>,
    val focusedNode: SemanticNode?,

    // 4. Global Indicators
    val isKeyboardVisible: Boolean,
    val isLoadingIndicatorPresent: Boolean,
    val isDialogBlocking: Boolean,

    // 5. Deterministic Hash Signatures
    val screenHash: String,                // Structural hash of element bounds & hierarchy
    val accessibilityHash: String,         // Content hash of visible text and labels

    // 6. Multimodal Visual Artifacts (Optional / On-Demand)
    val screenshotBase64: String? = null,
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0
)
```

---

## 2. Multi-Window Perception & Active Window Isolation

### The Overlay Hijacking Problem (Phase 0 Root Cause)
When `CursorOverlayView` or `FloatingArcReactorOverlay` exists, Android's `AccessibilityService.rootInActiveWindow` frequently points to package `com.assistive.headmouse` instead of the foreground target application.

### The Target Window Resolution Rule:

```
                      ┌────────────────────────────────────────┐
                      │    AccessibilityService.windows        │
                      └───────────────────┬────────────────────┘
                                          │
                  ┌───────────────────────┴───────────────────────┐
                  ▼                                               ▼
     [FILTER OUT OVERLAYS]                            [FIND ACTIVE APP WINDOW]
  Ignore TYPE_ACCESSIBILITY_OVERLAY            Filter: it.type == TYPE_APPLICATION
  Ignore TYPE_APPLICATION_OVERLAY                      AND it.root != null
  (Completely strip our cursor/HUD)           Sort by: it.layer DESCENDING (Topmost)
                                                                  │
                                                                  ▼
                                                      [TRUE FOREGROUND ROOT]
                                                      Inspect: root.packageName
                                                      Verify: packageName != ourPackage
```

```kotlin
fun resolveActiveApplicationWindow(windows: List<AccessibilityWindowInfo>, ownPackageName: String): AccessibilityNodeInfo? {
    // 1. Filter out all system overlays and self overlays
    val appWindows = windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
        .sortedByDescending { it.layer } // Topmost window on screen

    // 2. Select the top non-assistant application window
    val targetWindow = appWindows.firstOrNull { win ->
        val pkg = win.root?.packageName?.toString() ?: ""
        pkg.isNotBlank() && pkg != ownPackageName
    } ?: appWindows.firstOrNull()

    return targetWindow?.root
}
```

---

## 3. Dual Hash System for Deterministic UI Change Detection

To eliminate unreliable `Thread.sleep(600)` pauses and arbitrary state guesses, `WorldState` computes two independent MD5/SHA-256 hashes on every capture:

1. **`screenHash` (Structural Hash)**:
   - Formed by hashing: `packageName + activityName + nodes.map { "${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}" }`.
   - **Purpose:** Detects navigation changes, layout redraws, dialog popups, and keyboard appearances.
2. **`accessibilityHash` (Content Hash)**:
   - Formed by hashing: `nodes.map { "${it.label}|${it.isFocused}|${it.isChecked}" }`.
   - **Purpose:** Detects text changes, toggled switches, search results loading, and typing updates even when layout geometry is identical.

### State Mutation Test:
```kotlin
fun hasStateChanged(prev: WorldState, curr: WorldState): Boolean {
    return prev.screenHash != curr.screenHash || prev.accessibilityHash != curr.accessibilityHash
}
```

---

## 4. Prompt Compression: < 800 Tokens per Request

In Phase 0, sending 30 full verbose JSON node objects consumed 900+ tokens for screen state alone, leading to 72,000 tokens in 15 minutes.

### The Semantic Index Format:
Instead of raw accessibility JSON dumps, `WorldState` compresses screen elements into a **compact tabular index**:

```
=== SCREEN STATE [com.google.android.youtube] ===
KEYBOARD: OFF | DIALOG: NONE | LOADING: NO
INTERACTIVE ELEMENTS:
#1 [Button] "Search" (950, 120)
#2 [Tab] "Home" (108, 2280)
#3 [Tab] "Shorts" (324, 2280)
#4 [Tab] "Subscriptions" (756, 2280)
#5 [Button] "Account" (980, 2280)
#6 [Card] "Minecraft Speedrun Live" (540, 680)
```

### Token Savings:
- **Legacy Dump:** 30 nodes $\times$ 35 tokens = **1,050 tokens**.
- **Compressed Semantic Index:** 30 nodes $\times$ 8 tokens = **240 tokens** (77% token reduction).
- This guarantees the entire prompt (system instructions + goal + memory + screen index) stays strictly under **800 tokens**, eliminating read timeouts and context exhaustion.
