package com.assistive.headmouse

import android.content.Context
import com.assistive.headmouse.agent.jarvis.action.ActionStep
import com.assistive.headmouse.agent.jarvis.action.ActionTarget
import com.assistive.headmouse.agent.jarvis.action.ActionResult
import com.assistive.headmouse.agent.jarvis.action.AutonomousActionType
import com.assistive.headmouse.agent.jarvis.action.TargetType
import com.assistive.headmouse.agent.jarvis.autonomous.ContextCompressor
import com.assistive.headmouse.agent.jarvis.autonomous.MissionMemory
import com.assistive.headmouse.agent.jarvis.memory.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JarvisMemory2Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var testFilesDir: File

    @Before
    fun setUp() {
        testFilesDir = tempFolder.newFolder("files")
        mockContext = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File {
                return testFilesDir
            }
            override fun getApplicationContext(): Context {
                return this
            }
        }
    }

    @Test
    fun testSeparateMemoryConceptsInHub() {
        val hub = JarvisMemoryHub(mockContext)

        // 1. Conversation Memory
        hub.conversationMemory.addTurn(role = "user", content = "Hello JARVIS, I am Tony Stark.")
        assertEquals(1, hub.conversationMemory.getRecentTurns().size)

        // 2. User Preferences
        hub.userPreferences.setPreference("music_app", "Spotify")
        assertEquals("Spotify", hub.userPreferences.getPreference("music_app"))

        // 3. Learned App Patterns
        hub.appPatterns.recordPatternOutcome(
            packageName = "com.google.android.youtube",
            intentType = "search",
            description = "Click on search icon",
            action = "tap",
            targetSelector = "Search YouTube",
            succeeded = true
        )
        val patterns = hub.appPatterns.getRelevantPatterns("com.google.android.youtube")
        assertEquals(1, patterns.size)

        // 4. Mission Memory (isolated per mission)
        val missionMemory = hub.createMissionMemory()
        missionMemory.recordExecution(
            ActionStep(id = 1, action = AutonomousActionType.TAP, target = ActionTarget(type = TargetType.TEXT, value = "Search")),
            ActionResult(success = true, action = "tap", verified = true)
        )
        assertEquals(1, missionMemory.actionHistory.size)

        // 5. Task History
        hub.archiveMission(
            missionId = "m-101",
            goal = "Search for lofi beats on YouTube",
            outcome = MissionOutcome.SUCCESS,
            stepsCount = 3,
            durationMs = 4500L,
            summary = "Opened YouTube, searched for lofi beats, and played first track",
            involvedPackages = listOf("com.google.android.youtube")
        )
        assertEquals(1, hub.taskHistory.totalRecordsCount)

        // Verify that clearing task history does not clear conversation memory
        hub.taskHistory.clearHistory()
        assertEquals(0, hub.taskHistory.totalRecordsCount)
        assertEquals(1, hub.conversationMemory.getRecentTurns().size)
    }

    @Test
    fun testTaskHistoryPersistenceAndRelevantRetrieval() {
        val taskHistoryManager = TaskHistoryManager(mockContext)

        taskHistoryManager.recordMission(
            MissionRecord(
                missionId = "m-001",
                goal = "Open WhatsApp and send message to Alice",
                outcome = MissionOutcome.SUCCESS,
                summary = "Opened WhatsApp, selected Alice, sent text message.",
                stepsCount = 4,
                durationMs = 5000L,
                involvedPackages = listOf("com.whatsapp")
            )
        )

        taskHistoryManager.recordMission(
            MissionRecord(
                missionId = "m-002",
                goal = "Order pizza on Domino's",
                outcome = MissionOutcome.FAILED,
                summary = "Domino's app got stuck on location permission screen.",
                stepsCount = 2,
                durationMs = 3000L,
                failureReason = "Location permission dialog blocked UI",
                involvedPackages = listOf("com.dominospizza")
            )
        )

        // Test fresh instance loading from disk
        val freshManager = TaskHistoryManager(mockContext)
        assertEquals(2, freshManager.totalRecordsCount)

        // Test relevance search
        val matches = freshManager.findRelevantMissions(
            queryGoal = "Send a WhatsApp message to Bob",
            activePackage = "com.whatsapp"
        )
        assertTrue("Should find WhatsApp mission", matches.isNotEmpty())
        assertEquals("m-001", matches[0].missionId)

        // Test prompt summary token bounding
        val summary = freshManager.formatContextSummary(matches)
        assertTrue(summary.contains("RELEVANT PAST TASK HISTORY:"))
        assertTrue(summary.contains("WhatsApp"))
    }

    @Test
    fun testTaskHistoryBoundedGrowth() {
        val taskHistoryManager = TaskHistoryManager(mockContext)

        // Add 65 records, exceeding the 50 limit
        for (i in 1..65) {
            taskHistoryManager.recordMission(
                MissionRecord(
                    missionId = "mission-$i",
                    goal = "Task goal number $i",
                    outcome = if (i % 2 == 0) MissionOutcome.SUCCESS else MissionOutcome.FAILED,
                    summary = "Summary for mission $i",
                    stepsCount = i,
                    durationMs = 1000L * i
                )
            )
        }

        assertEquals(TaskHistoryManager.MAX_RECORDS, taskHistoryManager.totalRecordsCount)
        val recent = taskHistoryManager.getRecentMissions(limit = 100)
        assertEquals(TaskHistoryManager.MAX_RECORDS, recent.size)
        // Check FIFO/LRU eviction: earliest remaining should be mission-16
        assertEquals("mission-16", recent.first().missionId)
        assertEquals("mission-65", recent.last().missionId)
    }

    @Test
    fun testAppPatternLearningConfidenceAndPruning() {
        val patternManager = AppPatternManager(mockContext)

        // 1. Only learns on SUCCESS
        patternManager.recordPatternOutcome(
            packageName = "com.google.android.youtube",
            intentType = "search",
            description = "Tap search icon",
            action = "tap",
            targetSelector = "Search YouTube",
            succeeded = true
        )
        assertEquals(1, patternManager.totalPatternsCount)

        // Reinforce pattern
        patternManager.recordPatternOutcome(
            packageName = "com.google.android.youtube",
            intentType = "search",
            description = "Tap search icon",
            action = "tap",
            targetSelector = "Search YouTube",
            succeeded = true
        )
        val pattern = patternManager.getRelevantPatterns("com.google.android.youtube").first()
        assertEquals(2, pattern.successCount)
        assertTrue("Confidence should increase on success", pattern.confidence >= 0.89f)

        // Degrade pattern on repeated failure
        patternManager.recordPatternOutcome(
            packageName = "com.google.android.youtube",
            intentType = "search",
            description = "Tap search icon",
            action = "tap",
            targetSelector = "Search YouTube",
            succeeded = false
        )
        patternManager.recordPatternOutcome(
            packageName = "com.google.android.youtube",
            intentType = "search",
            description = "Tap search icon",
            action = "tap",
            targetSelector = "Search YouTube",
            succeeded = false
        )
        patternManager.recordPatternOutcome(
            packageName = "com.google.android.youtube",
            intentType = "search",
            description = "Tap search icon",
            action = "tap",
            targetSelector = "Search YouTube",
            succeeded = false
        )

        // Should be evicted or below threshold once confidence drops
        val remaining = patternManager.getRelevantPatterns("com.google.android.youtube", minConfidence = 0.5f)
        assertTrue("Pattern should be pruned when confidence degrades", remaining.isEmpty())
    }

    @Test
    fun testUserPreferencesPersistence() {
        val prefManager = UserPreferenceMemory(mockContext)

        prefManager.setPreference("theme", "Dark")
        prefManager.setPreference("voice_feedback", "concise")

        val freshPrefManager = UserPreferenceMemory(mockContext)
        assertEquals("Dark", freshPrefManager.getPreference("theme"))
        assertEquals("concise", freshPrefManager.getPreference("voice_feedback"))

        val summary = freshPrefManager.formatPromptSummary()
        assertTrue(summary.contains("theme=Dark"))
        assertTrue(summary.contains("voice_feedback=concise"))
    }

    @Test
    fun testMemorySecuritySanitization() {
        val rawInputWithApiKey = "Please use apiKey AIzaSyD98765432101234567890123456789012 to connect"
        val sanitizedKey = MemorySecuritySanitizer.sanitize(rawInputWithApiKey)
        assertFalse("Raw API key must not be present", sanitizedKey.contains("AIzaSyD98765432101234567890123456789012"))
        assertTrue("Placeholder must be used", sanitizedKey.contains("[REDACTED_API_KEY]"))

        val rawInputWithOtp = "Your verification code is 482910 for authentication."
        val sanitizedOtp = MemorySecuritySanitizer.sanitize(rawInputWithOtp)
        assertFalse("OTP must not be present", sanitizedOtp.contains("482910"))
        assertTrue("Placeholder must be used", sanitizedOtp.contains("[REDACTED_OTP]"))

        val rawInputWithCard = "Pay with card 4111 2222 3333 4444 thanks"
        val sanitizedCard = MemorySecuritySanitizer.sanitize(rawInputWithCard)
        assertFalse("Card digits must not be present", sanitizedCard.contains("4111"))
        assertTrue("Card placeholder must be used", sanitizedCard.contains("[REDACTED_CARD]"))

        val rawInputWithPin = "My security PIN is: 8899"
        val sanitizedPin = MemorySecuritySanitizer.sanitize(rawInputWithPin)
        assertFalse("PIN must not be present", sanitizedPin.contains("8899"))
        assertTrue("PIN placeholder must be used", sanitizedPin.contains("[REDACTED_PIN]"))
    }

    @Test
    fun testContextCompressionWithMemoryContext() {
        val memoryContext = "RELEVANT PAST TASK HISTORY:\n- [SUCCESS] \"Open YouTube\": Opened YouTube and searched lofi."
        val prompt = ContextCompressor.buildCompressedDecisionPrompt(
            originalGoal = "Search YouTube for jazz",
            currentSubgoal = null,
            compressedScreenIndex = "Screen: Home [Node 1: YouTube]",
            actionHistory = listOf("Tapped Home"),
            maxHistorySteps = 4,
            maxTokenBudget = 800,
            historicalMemoryContext = memoryContext
        )

        assertTrue(prompt.contains("RELEVANT MEMORY & LEARNED PATTERNS:"))
        assertTrue(prompt.contains("Open YouTube"))
        assertTrue(prompt.contains("ORIGINAL GOAL: \"Search YouTube for jazz\""))
        assertTrue(ContextCompressor.estimateTokenCount(prompt) <= 800)
    }

    @Test
    fun testMissionMemorySummaryGeneration() {
        val memory = MissionMemory()

        memory.recordExecution(
            ActionStep(id = 1, action = AutonomousActionType.OPEN_APP, target = ActionTarget(type = TargetType.RESOURCE_ID, value = "com.google.android.youtube")),
            ActionResult(success = true, action = "open_app", verified = true)
        )
        memory.recordExecution(
            ActionStep(id = 2, action = AutonomousActionType.TAP, target = ActionTarget(type = TargetType.TEXT, value = "Search")),
            ActionResult(success = true, action = "tap", verified = true)
        )
        memory.recordExecution(
            ActionStep(id = 3, action = AutonomousActionType.TYPE_TEXT, target = ActionTarget(type = TargetType.TEXT, value = "lofi hip hop")),
            ActionResult(success = true, action = "type_text", verified = true)
        )

        val summary = memory.generateMissionSummary("Play lofi music on YouTube")
        assertTrue(summary.contains("Play lofi music on YouTube"))
        assertTrue(summary.contains("OPEN_APP 'com.google.android.youtube' (OK)"))
        assertTrue(summary.contains("TAP 'Search' (OK)"))
        assertTrue(summary.contains("TYPE_TEXT 'lofi hip hop' (OK)"))

        val packages = memory.getInvolvedPackages()
        assertEquals(1, packages.size)
        assertEquals("com.google.android.youtube", packages[0])
    }
}
