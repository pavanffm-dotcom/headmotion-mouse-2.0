# Phase 4 — Model Adapter and API Reliability Report

## 1. Executive Summary
Phase 4 separated agent logic from provider-specific API logic by implementing a normalized ModelClient abstraction. All HTTP communication, prompt construction, context compression, retries, and token budgeting are encapsulated cleanly.

## 2. Model Client Architecture
- Normalized Contract (ModelClient.kt): Defines decideNextActionStructured(ModelDecisionRequest): StructuredModelResult.
- Implementations:
  - OpenAiCompatibleClient: Handles OpenAI, OpenRouter, DeepSeek, Ollama, Groq. Normalizes URLs to ensure /chat/completions.
  - GoogleGeminiClient: Native REST client for Google Generative Language API.
  - DisabledModelClient: Safe fallback when cloud AI is disabled.
- Pluggable Transport (HttpTransport): Injected interface for JVM unit testing without network calls.

## 3. HTTP Reliability & Backoff
- 12 distinct ErrorType categories including CONNECTION_FAILURE, HTTP_RATE_LIMIT, HTTP_UNAUTHORIZED, PARSER_FAILURE.
- 401 Unauthorized fails fast without retry.
- 429 and 5xx retry with exponential backoff.
- Pure Kotlin recursive-descent SimpleJson parses model output without Android stub dependency.

## 4. Security & Credential Hygiene
- Zero API keys logged. Keys are masked via OpenAiCompatibleClient.maskKey().
