package com.assistive.headmouse.agent.jarvis.autonomous.model

import com.assistive.headmouse.preferences.AiProvider

/**
 * Validates whether a selected AI provider and model identifier supports multimodal vision / image inputs.
 * Prevents HTTP 400 (Bad Request) errors caused by sending image payloads to text-only LLMs.
 */
object ModelVisionCapabilities {

    /**
     * Determines whether the given provider and model ID can accept images in request payloads.
     */
    fun supportsVision(provider: AiProvider, modelName: String): Boolean {
        val lower = modelName.trim().lowercase()

        return when (provider) {
            AiProvider.GEMINI -> {
                // All modern Google Gemini 1.5, 2.0, 2.5 models support multimodal image inputs natively
                lower.startsWith("gemini") || lower.contains("flash") || lower.contains("pro") || lower.isEmpty()
            }

            AiProvider.OPENAI -> {
                // OpenAI models with vision capabilities
                if (lower.contains("3.5") || lower.contains("davinci") || lower.contains("babbage")) {
                    false
                } else {
                    lower.contains("4o") ||
                    lower.contains("vision") ||
                    lower.contains("gpt-4-turbo") ||
                    lower.contains("o1") ||
                    lower.contains("o3")
                }
            }

            AiProvider.CUSTOM_OPENROUTER -> {
                // Known multimodal vision models on OpenRouter / xKiro / custom endpoints
                when {
                    // Explicit vision or VL indicators
                    lower.contains("vision") || lower.contains("-vl") || lower.contains("vl-") -> true

                    // Vision-capable model families
                    lower.contains("4o") -> true
                    lower.contains("gemini") -> true
                    lower.contains("claude-3") || lower.contains("claude-3.5") || lower.contains("claude-3-5") -> true
                    lower.contains("pixtral") -> true
                    lower.contains("llama-3.2") && lower.contains("vision") -> true
                    lower.contains("qwen") && (lower.contains("vl") || lower.contains("vision")) -> true

                    // Explicit text-only models (like deepseek-chat, deepseek-r1, mistral-7b)
                    lower.contains("deepseek") || lower.contains("mistral") || lower.contains("llama") -> false

                    // Conservative fallback: assume false to prevent HTTP 400 unless explicitly marked
                    else -> false
                }
            }
        }
    }
}
