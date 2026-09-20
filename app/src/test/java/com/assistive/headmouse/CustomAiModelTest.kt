package com.assistive.headmouse

import com.assistive.headmouse.model.CustomAiModel
import org.junit.Assert.*
import org.junit.Test

class CustomAiModelTest {

    @Test
    fun testCustomAiModelProperties() {
        val model = CustomAiModel(
            id = "model-123",
            name = "DeepSeek V3",
            modelId = "deepseek/deepseek-chat",
            baseUrl = "https://api.xkiro.com/v1",
            apiKey = "sk-xkiro-secret-token",
            isActive = true
        )

        assertEquals("model-123", model.id)
        assertEquals("DeepSeek V3", model.name)
        assertEquals("deepseek/deepseek-chat", model.modelId)
        assertEquals("https://api.xkiro.com/v1", model.baseUrl)
        assertEquals("sk-xkiro-secret-token", model.apiKey)
        assertTrue(model.isActive)
    }

    @Test
    fun testMaskedApiKey() {
        val emptyKeyModel = CustomAiModel(name = "Test", modelId = "test", apiKey = "")
        assertEquals("No Key Set", emptyKeyModel.maskedApiKey)

        val shortKeyModel = CustomAiModel(name = "Test", modelId = "test", apiKey = "1234567")
        assertEquals("••••••••", shortKeyModel.maskedApiKey)

        val fullKeyModel = CustomAiModel(name = "Test", modelId = "test", apiKey = "sk-xkiro-1234567890abcdef")
        assertTrue(fullKeyModel.maskedApiKey.startsWith("sk-x"))
        assertTrue(fullKeyModel.maskedApiKey.endsWith("cdef"))
        assertTrue(fullKeyModel.maskedApiKey.contains("••••"))
    }

    @Test
    fun testDynamicBrainSwitching() {
        val model1 = CustomAiModel(id = "1", name = "DeepSeek", modelId = "deepseek/deepseek-chat", isActive = true)
        val model2 = CustomAiModel(id = "2", name = "Claude 3.5", modelId = "anthropic/claude-3.5-sonnet", isActive = false)
        val model3 = CustomAiModel(id = "3", name = "Mistral Large", modelId = "mistralai/mistral-large-2411", isActive = false)

        val models = mutableListOf(model1, model2, model3)

        // Switch active brain to model2
        val targetId = "2"
        for (m in models) {
            m.isActive = (m.id == targetId)
        }

        assertFalse(models[0].isActive)
        assertTrue(models[1].isActive)
        assertFalse(models[2].isActive)

        val activeBrain = models.firstOrNull { it.isActive }
        assertNotNull(activeBrain)
        assertEquals("Claude 3.5", activeBrain?.name)
        assertEquals("anthropic/claude-3.5-sonnet", activeBrain?.modelId)

        // Switch active brain to model3
        val targetId3 = "3"
        for (m in models) {
            m.isActive = (m.id == targetId3)
        }
        val activeBrain3 = models.firstOrNull { it.isActive }
        assertEquals("Mistral Large", activeBrain3?.name)
    }

    @Test
    fun testDeleteActiveModelFallback() {
        val model1 = CustomAiModel(id = "1", name = "DeepSeek", modelId = "deepseek/deepseek-chat", isActive = true)
        val model2 = CustomAiModel(id = "2", name = "Claude 3.5", modelId = "anthropic/claude-3.5-sonnet", isActive = false)

        val models = mutableListOf(model1, model2)

        // Delete active model
        val toRemove = models.first { it.id == "1" }
        val wasActive = toRemove.isActive
        models.remove(toRemove)
        if (wasActive && models.isNotEmpty()) {
            models[0].isActive = true
        }

        assertEquals(1, models.size)
        assertTrue(models[0].isActive)
        assertEquals("Claude 3.5", models[0].name)
    }
}
