package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.ActionType
import com.assistive.headmouse.agent.jarvis.FileItem
import com.assistive.headmouse.agent.jarvis.JarvisBrain
import com.assistive.headmouse.agent.jarvis.MissionStatus
import com.assistive.headmouse.agent.jarvis.MissionStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisAutonomousTest {

    @Test
    fun testActionTypeNewValuesDefined() {
        assertNotNull("LIST_FILES must exist", ActionType.valueOf("LIST_FILES"))
        assertNotNull("DELETE_FILE must exist", ActionType.valueOf("DELETE_FILE"))
        assertNotNull("SEARCH_FILES must exist", ActionType.valueOf("SEARCH_FILES"))
        assertNotNull("OPEN_FILE must exist", ActionType.valueOf("OPEN_FILE"))
        assertNotNull("START_MISSION must exist", ActionType.valueOf("START_MISSION"))
    }

    @Test
    fun testMissionStatusEnumValues() {
        assertEquals(5, MissionStatus.values().size)
        assertNotNull(MissionStatus.valueOf("IDLE"))
        assertNotNull(MissionStatus.valueOf("RUNNING"))
        assertNotNull(MissionStatus.valueOf("COMPLETED"))
        assertNotNull(MissionStatus.valueOf("FAILED"))
        assertNotNull(MissionStatus.valueOf("ABORTED"))
    }

    @Test
    fun testMissionStepDataClass() {
        val step = MissionStep(
            stepNumber = 2,
            thought = "Click on search bar",
            action = "TAP",
            x = 270f,
            y = 2280f,
            text = "John Doe",
            spokenUpdate = "Searching for profile, Sir.",
            isFinished = false
        )
        assertEquals(2, step.stepNumber)
        assertEquals("Click on search bar", step.thought)
        assertEquals("TAP", step.action)
        assertEquals(270f, step.x)
        assertEquals(2280f, step.y)
        assertEquals("John Doe", step.text)
        assertEquals("Searching for profile, Sir.", step.spokenUpdate)
        assertFalse(step.isFinished)
    }

    @Test
    fun testFileItemFormatting() {
        val fileItem = FileItem(
            name = "report.pdf",
            path = "/storage/emulated/0/Download/report.pdf",
            isDirectory = false,
            sizeBytes = 1048576L,
            formattedSize = "1.0 MB",
            lastModified = "14 Sep 2026 17:00"
        )
        assertEquals("report.pdf", fileItem.name)
        assertFalse(fileItem.isDirectory)
        assertEquals("1.0 MB", fileItem.formattedSize)
    }

    @Test
    fun testOfflineMissionStepPlanning() = runBlocking {
        val brain = JarvisBrain(null, null)

        // Step 1 of YouTube Shorts mission
        val step1 = brain.planNextMissionStep(
            missionGoal = "Open YouTube and scroll shorts",
            stepNumber = 1,
            stepHistory = emptyList(),
            screenImageBase64 = null,
            apiKey = "",
            isCloudEnabled = false,
            provider = com.assistive.headmouse.preferences.AiProvider.GEMINI,
            modelName = "gemini-1.5-flash",
            customBaseUrl = ""
        )
        assertNotNull(step1)
        assertEquals("LAUNCH_APP", step1?.action)
        assertEquals("com.google.android.youtube", step1?.text)

        // Step 2 of YouTube Shorts mission
        val step2 = brain.planNextMissionStep(
            missionGoal = "Open YouTube and scroll shorts",
            stepNumber = 2,
            stepHistory = listOf("Step 1: LAUNCH_APP -> com.google.android.youtube"),
            screenImageBase64 = null,
            apiKey = "",
            isCloudEnabled = false,
            provider = com.assistive.headmouse.preferences.AiProvider.GEMINI,
            modelName = "gemini-1.5-flash",
            customBaseUrl = ""
        )
        assertNotNull(step2)
        assertEquals("TAP", step2?.action)

        // Step 3 of YouTube Shorts mission
        val step3 = brain.planNextMissionStep(
            missionGoal = "Open YouTube and scroll shorts",
            stepNumber = 3,
            stepHistory = listOf("Step 1: LAUNCH_APP", "Step 2: TAP"),
            screenImageBase64 = null,
            apiKey = "",
            isCloudEnabled = false,
            provider = com.assistive.headmouse.preferences.AiProvider.GEMINI,
            modelName = "gemini-1.5-flash",
            customBaseUrl = ""
        )
        assertNotNull(step3)
        assertEquals("SWIPE_UP", step3?.action)
        assertTrue(step3?.isFinished == true)
    }

    @Test
    fun testInstagramMissionSequence() = runBlocking {
        val brain = JarvisBrain(null, null)

        val step1 = brain.planNextMissionStep(
            missionGoal = "Instagram open karo and profile search karo",
            stepNumber = 1,
            stepHistory = emptyList(),
            screenImageBase64 = null,
            apiKey = "",
            isCloudEnabled = false,
            provider = com.assistive.headmouse.preferences.AiProvider.GEMINI,
            modelName = "gemini-1.5-flash",
            customBaseUrl = ""
        )
        assertNotNull(step1)
        assertEquals("LAUNCH_APP", step1?.action)
        assertEquals("com.instagram.android", step1?.text)

        val step2 = brain.planNextMissionStep(
            missionGoal = "Instagram open karo and profile search karo",
            stepNumber = 2,
            stepHistory = listOf("Step 1: Open Instagram"),
            screenImageBase64 = null,
            apiKey = "",
            isCloudEnabled = false,
            provider = com.assistive.headmouse.preferences.AiProvider.GEMINI,
            modelName = "gemini-1.5-flash",
            customBaseUrl = ""
        )
        assertNotNull(step2)
        assertEquals("TAP", step2?.action)
    }
}
