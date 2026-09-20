package com.assistive.headmouse.model

import org.json.JSONObject
import java.util.UUID

/**
 * Represents a user-configurable custom AI model endpoint (e.g. xKiro, OpenRouter, DeepSeek, Mistral, Ollama).
 * Users can add, save, and configure unlimited custom models to dynamically swap J.A.R.V.I.S. reasoning brains.
 */
data class CustomAiModel(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var modelId: String,
    var baseUrl: String = "https://api.xkiro.com/v1",
    var apiKey: String = "",
    var isActive: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("modelId", modelId)
            put("baseUrl", baseUrl)
            put("apiKey", apiKey)
            put("isActive", isActive)
        }
    }

    val maskedApiKey: String
        get() {
            val key = apiKey.trim()
            if (key.isBlank()) return "No Key Set"
            return if (key.length <= 8) "••••••••"
            else "${key.take(4)}••••${key.takeLast(4)}"
        }

    companion object {
        fun fromJson(json: JSONObject): CustomAiModel {
            return CustomAiModel(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Custom Model"),
                modelId = json.optString("modelId", "deepseek/deepseek-chat"),
                baseUrl = json.optString("baseUrl", "https://api.xkiro.com/v1"),
                apiKey = json.optString("apiKey", ""),
                isActive = json.optBoolean("isActive", false)
            )
        }
    }
}
