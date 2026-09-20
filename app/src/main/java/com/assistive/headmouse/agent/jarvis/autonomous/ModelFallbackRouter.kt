package com.assistive.headmouse.agent.jarvis.autonomous

import android.util.Log
import com.assistive.headmouse.model.CustomAiModel
import com.assistive.headmouse.preferences.AppSettings

/**
 * Model Fallback Router (R28, R29).
 * Manages graceful failover between primary AI models and fallback endpoints
 * when network errors, rate limits (HTTP 429), or provider outages occur.
 */
class ModelFallbackRouter(
    private val appSettings: AppSettings,
    private val timeline: MissionTimeline? = null
) {
    companion object {
        private const val TAG = "ModelFallbackRouter"
    }

    /**
     * Resolves the active model to use, with fallback strategy.
     */
    fun resolveActiveModel(role: ModelRole = ModelRole.MAIN_AGENT): CustomAiModel? {
        val configured = appSettings.getActiveCustomModel()
        if (configured != null) {
            return configured
        }

        // Default fallback if no custom model configured
        val primaryApiKey = appSettings.getActiveApiKey()
        if (primaryApiKey.isNotBlank()) {
            return CustomAiModel(
                id = "default_gemini",
                name = "Gemini Flash",
                modelId = appSettings.aiModelName.ifBlank { "gemini-1.5-flash" },
                baseUrl = appSettings.customBaseUrl,
                apiKey = primaryApiKey
            )
        }

        return null
    }

    /**
     * Resolves an alternate/fallback model if the primary request failed with a provider error.
     */
    fun resolveFallbackModel(failedModelId: String): CustomAiModel? {
        val allModels = appSettings.getCustomModels()
        val alternate = allModels.firstOrNull { it.modelId != failedModelId && it.apiKey.isNotBlank() }

        if (alternate != null) {
            Log.w(TAG, "Provider failure on $failedModelId. Routing to fallback: ${alternate.name} (${alternate.modelId})")
            timeline?.record("MODEL_FALLBACK", "Switched from $failedModelId to ${alternate.name}")
            return alternate
        }

        Log.w(TAG, "No alternate model configured for fallback.")
        return null
    }
}
