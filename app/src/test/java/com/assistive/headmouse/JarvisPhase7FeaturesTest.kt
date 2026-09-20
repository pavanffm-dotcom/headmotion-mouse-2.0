package com.assistive.headmouse

import android.graphics.RectF
import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.agent.perception.SpatialNodeCache
import com.assistive.headmouse.voice.VoiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisPhase7FeaturesTest {

    @Test
    fun testVoiceActionNewTargetingActionsDefined() {
        assertNotNull("CLICK_THIS must exist in VoiceAction", VoiceAction.valueOf("CLICK_THIS"))
        assertNotNull("CLICK_NAMED must exist in VoiceAction", VoiceAction.valueOf("CLICK_NAMED"))
    }

    @Test
    fun testScreenNodeLabelResolution() {
        val nodeWithText = ScreenNode(
            id = "node1",
            left = 10f, top = 10f, right = 100f, bottom = 50f,
            text = "Submit",
            contentDescription = "Submit button",
            viewIdResourceName = "com.app:id/btn_submit"
        )
        assertEquals("Submit", nodeWithText.label)

        val nodeWithDescOnly = ScreenNode(
            id = "node2",
            left = 10f, top = 10f, right = 100f, bottom = 50f,
            text = null,
            contentDescription = "Search",
            viewIdResourceName = "com.app:id/search_icon"
        )
        assertEquals("Search", nodeWithDescOnly.label)

        val nodeWithViewIdOnly = ScreenNode(
            id = "node3",
            left = 10f, top = 10f, right = 100f, bottom = 50f,
            text = null,
            contentDescription = null,
            viewIdResourceName = "com.app:id/action_settings"
        )
        assertEquals("action settings", nodeWithViewIdOnly.label)
    }

    @Test
    fun testLevenshteinDistanceCalculation() {
        assertEquals(0, SpatialNodeCache.levenshteinDistance("chrome", "chrome"))
        assertEquals(1, SpatialNodeCache.levenshteinDistance("settings", "setings"))
        assertEquals(1, SpatialNodeCache.levenshteinDistance("youtube", "youtub"))
        assertEquals(1, SpatialNodeCache.levenshteinDistance("search", "serch"))
    }

    @Test
    fun testSpatialNodeCacheFuzzySearch() {
        val cache = SpatialNodeCache()
        val nodeSettings = ScreenNode(
            id = "s1",
            left = 50f, top = 100f, right = 300f, bottom = 160f,
            text = "Settings",
            isClickable = true
        )
        val nodeWifi = ScreenNode(
            id = "w1",
            left = 50f, top = 200f, right = 300f, bottom = 260f,
            text = "Network & Internet",
            contentDescription = "Wi-Fi and mobile network",
            isClickable = true
        )
        val nodePassword = ScreenNode(
            id = "p1",
            left = 50f, top = 300f, right = 300f, bottom = 360f,
            text = "SecretPassword123",
            isPassword = true,
            isClickable = true
        )

        cache.updateNodes(listOf(nodeSettings, nodeWifi, nodePassword))

        // 1. Exact match
        val exact = cache.findNodesByText("settings")
        assertEquals(1, exact.size)
        assertEquals("s1", exact[0].id)

        // 2. Substring match
        val sub = cache.findNodesByText("network")
        assertEquals(1, sub.size)
        assertEquals("w1", sub[0].id)

        // 3. Fuzzy match (typo "setings" -> Settings)
        val fuzzy = cache.findNodesByText("setings")
        assertEquals(1, fuzzy.size)
        assertEquals("s1", fuzzy[0].id)

        // 4. Password fields must never match or leak
        val pwSearch = cache.findNodesByText("SecretPassword123")
        assertTrue("Password field must never be indexed or returned in search", pwSearch.isEmpty())
    }

    @Test
    fun testScrollableContainerLookup() {
        val cache = SpatialNodeCache()
        val btn = ScreenNode(
            id = "b1",
            left = 10f, top = 10f, right = 100f, bottom = 50f,
            text = "Click me",
            isClickable = true,
            isScrollable = false
        )
        val smallScroll = ScreenNode(
            id = "s1",
            left = 0f, top = 0f, right = 200f, bottom = 400f,
            isScrollable = true
        )
        val mainScroll = ScreenNode(
            id = "s2",
            left = 0f, top = 0f, right = 1080f, bottom = 2000f,
            isScrollable = true
        )

        cache.updateNodes(listOf(btn, smallScroll, mainScroll))

        val primaryScroll = cache.findScrollableContainer()
        assertNotNull(primaryScroll)
        assertEquals("s2", primaryScroll?.id)
    }

    @Test
    fun testJarvisBrainAppLaunchIntent() = kotlinx.coroutines.runBlocking {
        val brain = com.assistive.headmouse.agent.jarvis.JarvisBrain()
        val resWhatsapp = brain.processUserPrompt("Open WhatsApp", null)
        assertEquals(com.assistive.headmouse.agent.jarvis.ActionType.LAUNCH_APP, resWhatsapp.actionType)
        assertEquals("com.whatsapp", resWhatsapp.actionData)

        val resYouTube = brain.processUserPrompt("Open YouTube", null)
        assertEquals(com.assistive.headmouse.agent.jarvis.ActionType.LAUNCH_APP, resYouTube.actionType)
        assertEquals("com.google.android.youtube", resYouTube.actionData)
    }

    @Test
    fun testJarvisBrainMouseControls() = kotlinx.coroutines.runBlocking {
        val brain = com.assistive.headmouse.agent.jarvis.JarvisBrain()
        val resRecenter = brain.processUserPrompt("recenter mouse", null)
        assertEquals(com.assistive.headmouse.agent.jarvis.ActionType.RECENTER_MOUSE, resRecenter.actionType)

        val resPause = brain.processUserPrompt("pause mouse", null)
        assertEquals(com.assistive.headmouse.agent.jarvis.ActionType.PAUSE_MOUSE, resPause.actionType)

        val resResume = brain.processUserPrompt("resume mouse", null)
        assertEquals(com.assistive.headmouse.agent.jarvis.ActionType.RESUME_MOUSE, resResume.actionType)

        val resScrollDown = brain.processUserPrompt("scroll down", null)
        assertEquals(com.assistive.headmouse.agent.jarvis.ActionType.SCROLL_DOWN, resScrollDown.actionType)

        val resScrollUp = brain.processUserPrompt("scroll up", null)
        assertEquals(com.assistive.headmouse.agent.jarvis.ActionType.SCROLL_UP, resScrollUp.actionType)
    }

    @Test
    fun testJarvisBrainScreenInspection() = kotlinx.coroutines.runBlocking {
        val cache = SpatialNodeCache()
        val node1 = ScreenNode(id = "n1", left = 0f, top = 0f, right = 100f, bottom = 50f, text = "Profile Button")
        val node2 = ScreenNode(id = "n2", left = 0f, top = 60f, right = 100f, bottom = 100f, text = "Settings Menu")
        cache.updateNodes(listOf(node1, node2))

        val brain = com.assistive.headmouse.agent.jarvis.JarvisBrain(spatialCache = cache)
        val response = brain.processUserPrompt("What is on my screen?", null)
        assertTrue(response.spokenText.contains("Profile Button") || response.displayText.contains("Profile Button"))
        assertTrue(response.displayText.contains("Settings Menu"))
    }

    @Test
    fun testJarvisBrainConversationalIdentity() = kotlinx.coroutines.runBlocking {
        val brain = com.assistive.headmouse.agent.jarvis.JarvisBrain()
        val response = brain.processUserPrompt("Who are you?", null)
        assertTrue(response.spokenText.contains("JARVIS") || response.spokenText.contains("J.A.R.V.I.S."))
    }

    @Test
    fun testAiProviderEnumValues() {
        val providers = com.assistive.headmouse.preferences.AiProvider.values()
        assertEquals(3, providers.size)
        assertNotNull(com.assistive.headmouse.preferences.AiProvider.valueOf("GEMINI"))
        assertNotNull(com.assistive.headmouse.preferences.AiProvider.valueOf("OPENAI"))
        assertNotNull(com.assistive.headmouse.preferences.AiProvider.valueOf("CUSTOM_OPENROUTER"))
    }

    @Test
    fun testJarvisBrainOfflineModeToggle() = kotlinx.coroutines.runBlocking {
        val brain = com.assistive.headmouse.agent.jarvis.JarvisBrain()
        // With isCloudEnabled = false, it must never call external networks and safely return offline response
        val res = brain.processUserPrompt(
            prompt = "Who created you?",
            apiKey = "dummy_key",
            isCloudEnabled = false,
            provider = com.assistive.headmouse.preferences.AiProvider.OPENAI,
            modelName = "gpt-4o"
        )
        assertNotNull(res)
        assertTrue(res.spokenText.isNotBlank())
    }
}
