# Phase 1 Specification: Universal Model Client & Adapter Contract

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Document:** `PHASE_1_MODEL_CONTRACT.md`  
**Date:** 2026-09-17  

---

## 1. The Provider-Neutral `ModelClient` Interface

In Phase 0, all LLM communication was hardcoded directly inside `JarvisBrain.kt` across monolithic methods (`callCustomOpenRouterApi`, `callGeminiApi`, `callOpenAiApi`). Furthermore, `AppSettings.kt:261` contained a hardcoded getter (`get() = AiProvider.CUSTOM_OPENROUTER`) that completely ignored the user's UI provider selection.

Phase 1 completely decouples model communications into a clean, provider-neutral `ModelClient` interface:

```kotlin
package com.assistive.headmouse.agent.jarvis.autonomous.model

import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolCall
import com.assistive.headmouse.agent.jarvis.autonomous.tools.ToolDefinition

/**
 * Standardized model response containing either conversational output or an atomic tool call.
 */
data class ModelResponse(
    val thought: String? = null,
    val toolCall: ToolCall? = null,
    val conversationalReply: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val durationMs: Long = 0L
)

/**
 * Clean, provider-neutral interface for all LLM interactions.
 * The autonomous agent depends ONLY on this interface, never on HTTP engines or SDKs directly.
 */
interface ModelClient {
    suspend fun decideNextAction(
        systemInstruction: String,
        originalUserGoal: String,
        currentSubgoal: String?,
        compressedScreenIndex: String,
        actionHistory: List<String>,
        availableTools: List<ToolDefinition>,
        screenshotBase64: String? = null
    ): Result<ModelResponse>
}
```

---

## 2. Configuration Integrity: Fixing `AppSettings.kt:261`

### Defect in Current Code:
```kotlin
// AppSettings.kt (Line 260)
var aiProvider: AiProvider
    get() = AiProvider.CUSTOM_OPENROUTER  // BUG: Hardcoded, ignores UI selection!
    set(value) = prefs.edit().putString(KEY_AI_PROVIDER, value.name).apply()
```

### Repaired Target Implementation:
```kotlin
var aiProvider: AiProvider
    get() {
        val savedName = prefs.getString(KEY_AI_PROVIDER, null)
        return if (!savedName.isNullOrBlank()) {
            try { AiProvider.valueOf(savedName) } catch (_: Exception) { AiProvider.CUSTOM_OPENROUTER }
        } else {
            // Default based on whether custom models exist
            if (getCustomModels().isNotEmpty()) AiProvider.CUSTOM_OPENROUTER else AiProvider.GEMINI
        }
    }
    set(value) = prefs.edit().putString(KEY_AI_PROVIDER, value.name).apply()
```

---

## 3. Supported Model Adapters

The system supports 5 primary endpoints through a unified adapter architecture:

```
                          ┌───────────────────────────┐
                          │   ModelClientFactory      │
                          └─────────────┬─────────────┘
                                        │
           ┌────────────────────────────┼────────────────────────────┐
           ▼                            ▼                            ▼
┌─────────────────────┐      ┌─────────────────────┐      ┌─────────────────────┐
│ OpenAiCompatible    │      │ GoogleGeminiClient  │      │ DirectOpenAiClient  │
│ Client (OpenRouter, │      │ (Gemini 1.5 Flash / │      │ (GPT-4o / 4o-mini)  │
│ Nex N2.5, xKiro)    │      │ Pro via v1beta)     │      │                     │
└─────────────────────┘      └─────────────────────┘      └─────────────────────┘
```

### Universal Request Configuration:
1. **Endpoint Resolution**:
   - For OpenRouter: `https://openrouter.ai/api/v1/chat/completions`
   - For Nex N2.5 Pro / xKiro: `https://api.xkiro.com/v1/chat/completions` (or user's custom base URL)
   - For Google Gemini: `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}`
2. **Standardized Headers (OpenRouter & OpenAI compliant)**:
   - `Authorization: Bearer <API_KEY>`
   - `Content-Type: application/json`
   - `HTTP-Referer: https://github.com/assistive-headmouse`
   - `X-Title: Mobile-JARVIS-Autonomous-Agent`
3. **Timeouts & Reliability**:
   - `Connect Timeout`: 10,000 ms
   - `Read Timeout`: **30,000 ms** (Extended from Phase 0's fragile 15s to allow full TTFT on large reasoning models).
4. **Retry Policy**:
   - Automatic retry up to 2 times with exponential backoff + jitter on `SocketTimeoutException`, `502 Bad Gateway`, or `429 Rate Limit`.

---

## 4. Structured Output & Prompt Protocol

### Compact Decision Prompt Template:
```
You are J.A.R.V.I.S., an autonomous Android GUI agent.
Execute ONE atomic tool to achieve the user's goal.

GOAL: "{originalUserGoal}"
CURRENT FOCUS: "{currentSubgoal}"
HISTORY:
{recentStepHistory}

CURRENT FOREGROUND DISPLAY:
{compressedScreenIndex}

Respond ONLY with a valid tool call. If the goal is completely satisfied, call finish_task.
```

### Tool Payload Attachment:
Every request to OpenAI/OpenRouter compatible endpoints includes the canonical 12 tools under the `tools` array:
```json
{
  "model": "deepseek/deepseek-chat",
  "messages": [...],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "tap_element",
        "description": "Clicks an interactive UI element by index (#ID) or label.",
        "parameters": { ... }
      }
    }
  ],
  "tool_choice": "auto",
  "max_tokens": 300,
  "temperature": 0.1
}
```

### Graceful Degradation / Fallback Parsing:
If an older model emits raw JSON in the message body instead of `tool_calls`, `ModelClient` extracts the JSON object and parses it into `ToolCall`, guaranteeing zero dropped actions.
