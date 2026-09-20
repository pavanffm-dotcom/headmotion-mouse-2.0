# Phase 1 Specification: Canonical Structured Tool Protocol

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Document:** `PHASE_1_TOOL_CONTRACT.md`  
**Date:** 2026-09-17  

---

## 1. Tool Protocol Overview

Phase 0 proved that free-form bracketed string parsing (`[ACTION:TAP:...]`, `[ACTION:OPEN_APP:...]`) is brittle because LLMs frequently format actions conversantly, drop parameters, or invent unsupported tags.

Phase 1 establishes **12 Canonical Structured Tools** complying with OpenAI/Anthropic/Gemini function calling schemas. Every tool execution returns an explicit, machine-validated `ActionResult`.

```kotlin
package com.assistive.headmouse.agent.jarvis.autonomous.tools

/**
 * Structured request issued by the model or autonomous planner.
 */
data class ToolCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, Any?>,
    val thought: String? = null
)

/**
 * Empirical result returned to the agent loop following execution.
 */
data class ActionResult(
    val callId: String,
    val toolName: String,
    val success: Boolean,
    val verified: Boolean,
    val stateChanged: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val isRecoverable: Boolean = true,
    val durationMs: Long = 0L,
    val postStateHash: String? = null
)
```

---

## 2. The 12 Canonical Tool Definitions

---

### Tool 1: `observe_screen`
- **Purpose:** Forces a synchronous capture of the current active `WorldState`.
- **JSON Schema:**
  ```json
  {
    "name": "observe_screen",
    "description": "Captures and returns the live interactive UI hierarchy and active app on screen.",
    "parameters": {
      "type": "object",
      "properties": {
        "force_refresh": { "type": "boolean", "description": "Whether to invalidate cache before reading." }
      }
    }
  }
  ```
- **Return:** `WorldState` summary with current package, activity, and interactive node list.
- **Safety:** Always Safe (Read-Only).

---

### Tool 2: `tap_element`
- **Purpose:** Clicks a visible UI element by its index number (`#index`) or primary label.
- **JSON Schema:**
  ```json
  {
    "name": "tap_element",
    "description": "Clicks an interactive UI element identified by its index (#ID) from the screen state or visible text label.",
    "parameters": {
      "type": "object",
      "properties": {
        "node_index": { "type": "integer", "description": "The numeric index of the node, e.g. 1 for #1" },
        "label": { "type": "string", "description": "The exact text or content description of the element" }
      },
      "required": ["label"]
    }
  }
  ```
- **Verification:** Verifies that targeted node triggered focus change, disappeared, or mutated `screenHash`.
- **Error Codes:** `TARGET_NOT_FOUND`, `CLICK_IGNORED_REFRACTORY`, `NODE_NOT_CLICKABLE`.
- **Recoverability:** If not found, prompts model to scroll down or re-observe.

---

### Tool 3: `tap_coordinates`
- **Purpose:** Dispatches a physical touch at exact `(x, y)` display coordinates (used for canvas views, games, or icon-only buttons without text).
- **JSON Schema:**
  ```json
  {
    "name": "tap_coordinates",
    "description": "Dispatches a physical touch gesture at exact pixel coordinates (x, y).",
    "parameters": {
      "type": "object",
      "properties": {
        "x": { "type": "number", "description": "X coordinate in screen pixels (0 to 1080)" },
        "y": { "type": "number", "description": "Y coordinate in screen pixels (0 to 2400)" }
      },
      "required": ["x", "y"]
    }
  }
  ```
- **Verification:** Verifies `screenHash` or `accessibilityHash` mutated post-tap.
- **Safety:** Requires coordinate validation within screen bounds `(0 <= x <= screenWidth, 0 <= y <= screenHeight)`.

---

### Tool 4: `type_text`
- **Purpose:** Injects text into the currently focused or specified editable field.
- **JSON Schema:**
  ```json
  {
    "name": "type_text",
    "description": "Enters text into the currently active or specified input field.",
    "parameters": {
      "type": "object",
      "properties": {
        "text": { "type": "string", "description": "The text string to type" },
        "node_index": { "type": "integer", "description": "Optional node index of the target EditText" },
        "press_enter": { "type": "boolean", "description": "Whether to press enter/search after typing" }
      },
      "required": ["text"]
    }
  }
  ```
- **Verification:** Verifies the target input node's text content contains the injected string.
- **Error Codes:** `NO_EDITABLE_FOCUSED`, `IME_INJECTION_FAILED`.

---

### Tool 5: `scroll`
- **Purpose:** Scrolls the foreground scrollable container to reveal hidden elements.
- **JSON Schema:**
  ```json
  {
    "name": "scroll",
    "description": "Scrolls the active scrollable view up or down.",
    "parameters": {
      "type": "object",
      "properties": {
        "direction": { "type": "string", "enum": ["DOWN", "UP"], "description": "DOWN reveals lower content; UP reveals upper content." },
        "distance": { "type": "string", "enum": ["SHORT", "MEDIUM", "LONG"], "default": "MEDIUM" }
      },
      "required": ["direction"]
    }
  }
  ```
- **Verification:** `accessibilityHash` must differ post-scroll. If identical, detects `END_OF_PAGE`.

---

### Tool 6: `swipe`
- **Purpose:** Performs a directional horizontal or vertical swipe gesture (e.g. carousels, tabs, dismissing notifications).
- **JSON Schema:**
  ```json
  {
    "name": "swipe",
    "description": "Executes a swipe gesture across the screen.",
    "parameters": {
      "type": "object",
      "properties": {
        "direction": { "type": "string", "enum": ["LEFT", "RIGHT", "UP", "DOWN"] },
        "startX": { "type": "number" },
        "startY": { "type": "number" }
      },
      "required": ["direction"]
    }
  }
  ```

---

### Tool 7: `long_press`
- **Purpose:** Long-presses an element (800ms hold) to trigger context menus or drag handles.
- **JSON Schema:**
  ```json
  {
    "name": "long_press",
    "description": "Performs an extended hold gesture on an element.",
    "parameters": {
      "type": "object",
      "properties": {
        "node_index": { "type": "integer" },
        "label": { "type": "string" }
      },
      "required": ["label"]
    }
  }
  ```

---

### Tool 8: `press_navigation`
- **Purpose:** Dispatches system-level Android navigation actions.
- **JSON Schema:**
  ```json
  {
    "name": "press_navigation",
    "description": "Performs system navigation: BACK, HOME, or RECENTS.",
    "parameters": {
      "type": "object",
      "properties": {
        "action": { "type": "string", "enum": ["BACK", "HOME", "RECENTS"] }
      },
      "required": ["action"]
    }
  }
  ```
- **Verification:** Verifies that active package or screen hash transitioned.

---

### Tool 9: `launch_app`
- **Purpose:** Launches an installed Android application by package name or common identifier.
- **JSON Schema:**
  ```json
  {
    "name": "launch_app",
    "description": "Launches an installed Android app by package name or common app name (e.g. YouTube, Settings, WhatsApp).",
    "parameters": {
      "type": "object",
      "properties": {
        "package_or_name": { "type": "string", "description": "Package name like 'com.google.android.youtube' or app name like 'YouTube'" }
      },
      "required": ["package_or_name"]
    }
  }
  ```
- **Verification:** `WorldState.foregroundPackage` must match the target package within 2500ms.

---

### Tool 10: `wait`
- **Purpose:** Explicitly pauses to allow async page loading or animations to finish.
- **JSON Schema:**
  ```json
  {
    "name": "wait",
    "description": "Waits for a specified duration to allow screen content or network requests to settle.",
    "parameters": {
      "type": "object",
      "properties": {
        "duration_ms": { "type": "integer", "description": "Milliseconds to wait (100 to 3000)" }
      },
      "required": ["duration_ms"]
    }
  }
  ```

---

### Tool 11: `take_screenshot`
- **Purpose:** Requests a high-resolution visual screenshot when accessibility hierarchy is incomplete.
- **JSON Schema:**
  ```json
  {
    "name": "take_screenshot",
    "description": "Captures a 720p visual frame of the active display for multimodal vision reasoning.",
    "parameters": { "type": "object", "properties": {} }
  }
  ```

---

### Tool 12: `finish_task`
- **Purpose:** Signals that the user's high-level goal has been completely achieved.
- **JSON Schema:**
  ```json
  {
    "name": "finish_task",
    "description": "Marks the autonomous mission as complete and provides the final spoken response to the user.",
    "parameters": {
      "type": "object",
      "properties": {
        "success": { "type": "boolean", "description": "True if the user goal was satisfied" },
        "spoken_summary": { "type": "string", "description": "Brief, natural confirmation spoken to the user" }
      },
      "required": ["success", "spoken_summary"]
    }
  }
  ```

---

## 3. Tool Dispatcher Architecture

```kotlin
class ToolDispatcher(
    private val context: Context,
    private val accessibilityService: HeadMouseAccessibilityService
) {
    suspend fun execute(toolCall: ToolCall): ActionResult {
        val startTime = System.currentTimeMillis()
        return when (toolCall.name) {
            "tap_element" -> executeTapElement(toolCall)
            "tap_coordinates" -> executeTapCoordinates(toolCall)
            "type_text" -> executeTypeText(toolCall)
            "scroll" -> executeScroll(toolCall)
            "launch_app" -> executeLaunchApp(toolCall)
            "press_navigation" -> executeNavigation(toolCall)
            "wait" -> executeWait(toolCall)
            "finish_task" -> executeFinish(toolCall)
            else -> ActionResult(
                callId = toolCall.callId,
                toolName = toolCall.name,
                success = false,
                verified = false,
                stateChanged = false,
                errorCode = "UNKNOWN_TOOL",
                errorMessage = "Tool '${toolCall.name}' is not registered."
            )
        }
    }
}
```
