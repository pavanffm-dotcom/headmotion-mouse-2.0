package com.assistive.headmouse

import com.assistive.headmouse.agent.jarvis.memory.JarvisMemoryManager
import com.assistive.headmouse.agent.jarvis.memory.MemoryTurn
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JarvisMemoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: android.content.Context
    private lateinit var testFilesDir: File

    @Before
    fun setUp() {
        testFilesDir = tempFolder.newFolder("files")
        // Minimal proxy for Context.filesDir to avoid mock framework issues
        mockContext = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File {
                return testFilesDir
            }
        }
    }

    @Test
    fun testMemorySerializationAndTurnAddition() {
        val memoryManager = JarvisMemoryManager(mockContext)

        memoryManager.addTurn(role = "user", content = "Hello JARVIS, I am Tony Stark.")
        memoryManager.addTurn(role = "assistant", content = "Welcome back, Sir. How may I assist you today?")

        val turns = memoryManager.getRecentTurns()
        assertEquals(2, turns.size)
        assertEquals("user", turns[0].role)
        assertEquals("Hello JARVIS, I am Tony Stark.", turns[0].content)
        assertEquals("assistant", turns[1].role)
        assertEquals("Welcome back, Sir. How may I assist you today?", turns[1].content)

        // Verify disk persistence: create fresh manager instance pointing to same storage
        val freshManager = JarvisMemoryManager(mockContext)
        val loadedTurns = freshManager.getRecentTurns()
        assertEquals("Should retain 2 turns after restart", 2, loadedTurns.size)
        assertEquals("Hello JARVIS, I am Tony Stark.", loadedTurns[0].content)
    }

    @Test
    fun testUserNameFactExtraction() {
        val memoryManager = JarvisMemoryManager(mockContext)

        memoryManager.addTurn(role = "user", content = "Mera naam Rahul Verma hai")
        assertEquals("Rahul Verma", memoryManager.getUserName())

        val summary = memoryManager.getMemoryContextSummary()
        assertTrue("Summary should contain user name", summary.contains("Rahul Verma"))

        // Create new manager instance from disk
        val freshManager = JarvisMemoryManager(mockContext)
        assertEquals("Reloaded manager should remember user name", "Rahul Verma", freshManager.getUserName())
    }

    @Test
    fun testSlidingWindowLimiting() {
        val memoryManager = JarvisMemoryManager(mockContext)

        for (i in 1..25) {
            memoryManager.addTurn(role = if (i % 2 == 1) "user" else "assistant", content = "Message turn $i")
        }

        val window10 = memoryManager.getRecentTurns(limit = 10)
        assertEquals(10, window10.size)
        assertEquals("Message turn 16", window10[0].content)
        assertEquals("Message turn 25", window10[9].content)
    }

    @Test
    fun testClearMemory() {
        val memoryManager = JarvisMemoryManager(mockContext)

        memoryManager.addTurn(role = "user", content = "Remember this secret code 9988")
        assertEquals(1, memoryManager.getRecentTurns().size)

        memoryManager.clearMemory()
        assertEquals(0, memoryManager.getRecentTurns().size)
        assertNull(memoryManager.getUserName())

        // Verify on disk as well
        val freshManager = JarvisMemoryManager(mockContext)
        assertEquals(0, freshManager.getRecentTurns().size)
    }
}
