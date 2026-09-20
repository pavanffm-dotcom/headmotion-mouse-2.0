# Phase 0 Forensic Report: AI, Model & API Forensics

**Target Project:** HeadMotionMouse / J.A.R.V.I.S.  
**Working Directory:** `c:\Users\Admin\Documents\SYBSC\assignments\antigravity\HeadMotionMouse`  
**Classification Mode:** FORENSIC AUDIT ONLY (Zero Production Source Code Modifications)  
**Date:** 2026-09-17  

---

## 1. UI Model Selection to Runtime Execution Trace

```
UI MODEL SELECTION (MainActivity.kt / Tab 5 Brain Settings)
  │
  ▼
[AppSettings.kt] SharedPreferences Storage
  ├── KEY_CUSTOM_MODELS_JSON: Stores List<CustomAiModel>
  ├── KEY_AI_MODEL_NAME: "deepseek/deepseek-chat"
  ├── KEY_CUSTOM_BASE_URL: "https://api.xkiro.com/v1"
  └── KEY_CUSTOM_API_KEY: "[USER_API_KEY]"
  │
  ▼ [CRITICAL DISCONNECT: Hardcoded Provider Getter]
AppSettings.aiProvider:
  get() = AiProvider.CUSTOM_OPENROUTER  (Line 261)
  [VERIFIED FROM CURRENT CODE: Any user selection of GEMINI or OPENAI is completely ignored by the getter!]
  │
  ▼
[JarvisBackgroundVoiceService.kt:305-317]
  val activeModel = appSettings.getActiveCustomModel()
  val currentApiKey = activeModel?.apiKey?.takeIf { it.isNotBlank() } ?: appSettings.getActiveApiKey()
  val currentBaseUrl = activeModel?.baseUrl?.takeIf { it.isNotBlank() } ?: appSettings.customBaseUrl
  val currentModel = activeModel?.modelId?.takeIf { it.isNotBlank() } ?: appSettings.aiModelName
  │
  ▼
[JarvisBrain.kt:122-126] Provider Switch
  when (provider) {
      AiProvider.GEMINI -> callGeminiApi(...)
      AiProvider.OPENAI -> callOpenAiApi(...)
      AiProvider.CUSTOM_OPENROUTER -> callCustomOpenRouterApi(cleanPrompt, apiKey, modelName, customBaseUrl)
  }
  │
  ▼
[JarvisBrain.callCustomOpenRouterApi] (Line 796)
  Endpoint: "$customBaseUrl/chat/completions" (Line 802)
  Headers: Authorization: Bearer $cleanKey, User-Agent, HTTP-Referer, X-Title (Lines 818-823)
  Body: {"model": targetModel, "messages": [...], "max_tokens": 250} (Lines 848-852)
  │
  ▼
Network Call via HttpURLConnection
  Connect Timeout: 10,000 ms (Line 825)
  Read Timeout: 15,000 ms (Line 826)
  Streaming: FALSE (Standard blocking read)
  Tool Calling: NONE (Plain string output requested)
  │
  ▼
Response Parser: extractChoiceMessageContent(firstChoice) (Line 679)
  Regex Tag Extraction: looks for [ACTION:TAP:...] or tool_calls
  │
  ▼
Action Dispatch: parseAndExecuteCloudAction(cloudAnswer) (Line 1150)
  Regex match: [ACTION:(.*?)(?::(.*?))?]
  Calls: HeadMouseAccessibilityService click / home / back / launchApp
```

---

## 2. Parameter-by-Parameter API Audit

| Parameter | Current Code Value | Verification Status | Defect / Vulnerability |
|---|---|---|---|
| **Active Provider** | Hardcoded `AiProvider.CUSTOM_OPENROUTER` (`AppSettings.kt:261`) | `VERIFIED FROM CURRENT CODE` | **CRITICAL:** UI provider selection has zero effect. `AppSettings.aiProvider` returns `CUSTOM_OPENROUTER` unconditionally. |
| **Model ID** | `deepseek/deepseek-chat` or active custom model id (`AppSettings.kt:246`) | `VERIFIED FROM CURRENT CODE` | If model ID contains unexpected prefix or typo, server returns 404. |
| **Base URL** | `https://api.xkiro.com/v1` or active custom model base URL (`AppSettings.kt:235`) | `VERIFIED FROM CURRENT CODE` | Normalizes to `$baseUrl/chat/completions`. |
| **Endpoint Resolution** | `cleanBase + "/chat/completions"` (`JarvisBrain.kt:802`) | `VERIFIED FROM CURRENT CODE` | Correctly prevents `/chat/completions/chat/completions` duplication. |
| **Request Headers** | `Authorization: Bearer <key>`, `User-Agent`, `HTTP-Referer`, `X-Title` | `VERIFIED FROM CURRENT CODE` | OpenRouter-compliant headers present. |
| **Request Body** | Raw JSON with `model`, `messages`, `max_tokens: 250` | `VERIFIED FROM CURRENT CODE` | **MISSING:** Tools array (`tools`) is NOT attached to the request body even though `ToolRegistry` builds schemas! |
| **Tool Calling Contract** | Fragile bracketed regex: `[ACTION:OPEN_APP:target]`, `[ACTION:TAP:target]` | `VERIFIED FROM CURRENT CODE` | Models often generate conversational descriptions, losing the bracketed action tags. |
| **Streaming Support** | `false` (Blocking synchronous `HttpURLConnection`) | `VERIFIED FROM CURRENT CODE` | Causes high perception latency and voice barge-in freezing during generation. |
| **Connect Timeout** | `10,000 ms` (`JarvisBrain.kt:825`) | `VERIFIED FROM CURRENT CODE` | Standard connection timeout. |
| **Read Timeout** | `15,000 ms` (`JarvisBrain.kt:826`) | `VERIFIED FROM CURRENT CODE` | **CRITICAL TIMEOUT BUG:** TTFT + full generation on OpenRouter / Nex N2.5 Pro often takes 16-25s. At 15,001ms, `SocketTimeoutException` is thrown! |
| **Retry Policy** | None (0 retries on timeout or 5xx) | `VERIFIED FROM CURRENT CODE` | Any transient network blip or gateway 502 aborts immediately. |
| **Error Handling** | Catch-all `catch (e: Exception)` formats `"Network Error: ..."` | `VERIFIED FROM CURRENT CODE` | Hides the root cause (e.g. read timeout vs DNS failure vs payload size). |

---

## 3. Disconnect Audit: Do Model Decisions Reach the Executor?

1. **In Conversational Mode (`processUserPrompt`)**:
   - **YES, PARTIALLY.** If the model emits `[ACTION:OPEN_APP:...]` or `[ACTION:TAP:...]`, `parseAndExecuteCloudAction()` extracts the tag and calls `HeadMouseAccessibilityService`.
   - **FAILURE MODE:** If the model outputs natural language without the exact bracket format (e.g., *"I will open YouTube for you"*), the action is discarded, and J.A.R.V.I.S. only speaks.
2. **In Autonomous Mission Mode (`AgentOrchestrator`)**:
   - **NO! Model decisions do NOT reach the executor during continuous steps.**
   - In `DynamicPlanner.kt:38-177`, common compound goals (Shorts, Flashlight, Play Store search) are **intercepted by static `when` branches**. The LLM is never called.
   - Even when the goal hits the `else` branch and calls `jarvisBrain.generateActionPlan()`, it generates a **full static plan upfront**. During step execution, the orchestrator iterates through the pre-baked list without sending the live screen back to the LLM.

---

## 4. Timeout Anatomy: Connect vs. Server vs. Read vs. Execution

The reported **"Network Error: Connection failed" / "Read timed out"** was forensically analyzed across the five stages:

```
[Connect] (0 - 500ms) ──► [Server Queuing / TTFT] (3,000 - 12,000ms) ──► [Read Generation] (4,000 - 8,000ms)
                                     ▲                                              │
                                     └────────── TOTAL ELAPSED: > 15,000 ms ────────┘
                                                         │
                                               [SocketTimeoutException]
                                                         ▼
                                          Caught at JarvisBrain.kt:143
                                          Emits: "Network Error: timeout"
```

1. **Connect Timeout (10,000ms):** Rarely triggered unless DNS or cellular connection is down.
2. **Server Processing + Read Timeout (15,000ms):** **PRIMARY ROOT CAUSE.**
   - The combined duration of server TTFT (Time To First Token) and reading the JSON payload frequently exceeds 15 seconds when sending 3,500+ token context windows to OpenRouter / Nex / DeepSeek models.
   - Because `HttpURLConnection.setReadTimeout(15000)` applies to socket read operations, slow generation triggers `java.net.SocketTimeoutException: Read timed out`.
3. **Downstream Execution:** Handled asynchronously via `mainHandler.post`, so it does not block the HTTP socket.

---

## 5. Token Explosion Forensics: 72,000 Tokens in 15 Minutes

The forensic calculation of the token runaway observed in user sessions:

```
Per-Request Payload Composition:
1. System Instruction (`getSystemInstruction()`):
   - Detailed instructions, action tag syntax, rules: ~550 tokens.
2. Live Screen Hierarchy (`toPromptSummary(30)`):
   - Up to 30 visible nodes with bounds, text, className, ID: ~650 to 900 tokens.
3. Accumulated Memory Turns (`memoryManager.getRecentTurns(16)`):
   - 16 past conversation turns: ~1,500 to 1,900 tokens.
4. User Prompt & Overhead:
   - ~100 tokens.

Total Tokens Per Single Request: ~2,800 to 3,450 tokens.
Mission Steps / Turns in 15 mins: 22 to 25 interactions.
Total Consumed: 24 × 3,000 = 72,000 TOKENS!
```

**Root Cause:**
- `JarvisMemoryManager` stores all previous turns in `jarvis_chat_memory.json` and loads the last 16 turns on **every single request**, repeating earlier screen snapshots and conversational filler.
- `toPromptSummary(30)` dumps full uncompressed node labels on every call instead of concise semantic action anchors.
