package com.assistive.headmouse.e2e.harness

import com.assistive.headmouse.agent.jarvis.ActionType
import com.assistive.headmouse.agent.model.ScreenNode
import com.assistive.headmouse.agent.perception.SpatialNodeCache
import com.assistive.headmouse.e2e.model.E2EDualOperatingMode
import com.assistive.headmouse.e2e.model.E2EHardwareState
import com.assistive.headmouse.e2e.model.E2EVoiceEngineState
import com.assistive.headmouse.e2e.model.E2EWakeWordParseResult
import com.assistive.headmouse.e2e.model.FilteredCursorPoint
import com.assistive.headmouse.e2e.model.VoiceProcessingResult
import com.assistive.headmouse.preferences.CursorStyle
import com.assistive.headmouse.tracking.filter.OneEuroFilter
import kotlin.math.hypot

/**
 * High-fidelity, deterministic E2E system harness modeling the unified
 * HeadMouseAccessibilityService, DualOperatingMode state machine,
 * CameraX 60 FPS lifecycle, and JarvisBackgroundVoiceService.
 */
class E2EUnifiedSystemHarness {

    // -------------------------------------------------------------
    // Core Dual Operating State
    // -------------------------------------------------------------
    var isHeadMouseActive: Boolean = true
        private set

    var isJarvisActive: Boolean = true
        private set

    var operatingMode: E2EDualOperatingMode = E2EDualOperatingMode.DUAL_MODE
        private set

    var voiceEngineState: E2EVoiceEngineState = E2EVoiceEngineState.WAKE_WORD_STANDBY
        private set

    // -------------------------------------------------------------
    // Hardware & Subsystem States
    // -------------------------------------------------------------
    var isCameraBound: Boolean = true
        private set

    var isCameraSensorPowered: Boolean = true
        private set

    var isPrivacyLedOn: Boolean = true
        private set

    var targetFpsMin: Int = 60
        private set

    var targetFpsMax: Int = 60
        private set

    var isMicrophoneActive: Boolean = true
        private set

    var isRecognizingSpeech: Boolean = true
        private set

    var isCursorOverlayVisible: Boolean = true
        private set

    var isFloatingHudVisible: Boolean = true
        private set

    var isHudExpanded: Boolean = false
        private set

    var hudStatusText: String = "JARVIS • STANDBY"
        private set

    var activeMissionLock: Boolean = false
        private set

    // Hardware and lifecycle telemetry
    var cameraUnbindCallCount: Int = 0
        private set
    var cameraBindCallCount: Int = 0
        private set
    var speechRecognizerContentionCount: Int = 0
        private set
    var activeSpeechSessionsCount: Int = 0
        private set
    var lastSpokenTts: String = ""
        private set
    var lastExecutedAction: String? = null
        private set
    var lastTargetPackage: String? = null
        private set
    var rapidSpeechAbortCount: Int = 0
        private set
    var lastHudPulseColor: String = "CYAN"
        private set

    // Concept 1 Filters
    private var oneEuroFilterX = OneEuroFilter(0.05, 0.004, 1.0)
    private var oneEuroFilterY = OneEuroFilter(0.05, 0.004, 1.0)
    private var lastFilteredX: Float = 540f
    private var lastFilteredY: Float = 960f

    // Spatial Node Cache for screen interaction
    val spatialCache = SpatialNodeCache()

    // Canonical app packages dictionary per requirements
    val knownApps = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera",
        "play store" to "com.android.vending",
        "maps" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos"
    )

    // Supported wake triggers & sleep triggers per PROJECT.md
    val wakeTriggers = listOf(
        "hey jarvis",
        "hello jarvis",
        "ok jarvis",
        "sun jarvis",
        "jarvis",
        "सुन जार्विस"
    )

    val sleepTriggers = listOf(
        "stop",
        "ruk ja",
        "ruko",
        "band ho jao",
        "go to sleep",
        "bye jarvis",
        "goodbye",
        "good bye",
        "bye",
        "so jao",
        "standby",
        "alvida",
        "call band karo",
        "stop mission",
        "band karo"
    )

    init {
        // Initial setup in DUAL_MODE
        recalculateModeAndHardware()
    }

    // -------------------------------------------------------------
    // Mode Switching & Lifecycle Management (F1, F2, F3)
    // -------------------------------------------------------------

    fun setOperatingMode(mode: E2EDualOperatingMode) {
        when (mode) {
            E2EDualOperatingMode.DUAL_MODE -> {
                isHeadMouseActive = true
                isJarvisActive = true
            }
            E2EDualOperatingMode.HEAD_MOUSE_ONLY -> {
                isHeadMouseActive = true
                isJarvisActive = false
            }
            E2EDualOperatingMode.JARVIS_ONLY -> {
                isHeadMouseActive = false
                isJarvisActive = true
            }
            E2EDualOperatingMode.STANDBY -> {
                isHeadMouseActive = false
                isJarvisActive = false
            }
        }
        recalculateModeAndHardware()
    }

    fun setHeadMouseEnabled(enabled: Boolean) {
        isHeadMouseActive = enabled
        recalculateModeAndHardware()
    }

    fun setJarvisServiceEnabled(enabled: Boolean) {
        isJarvisActive = enabled
        recalculateModeAndHardware()
    }

    fun toggleHeadMouse() {
        setHeadMouseEnabled(!isHeadMouseActive)
    }

    fun toggleJarvis() {
        setJarvisServiceEnabled(!isJarvisActive)
    }

    private fun recalculateModeAndHardware() {
        operatingMode = when {
            isHeadMouseActive && isJarvisActive -> E2EDualOperatingMode.DUAL_MODE
            isHeadMouseActive && !isJarvisActive -> E2EDualOperatingMode.HEAD_MOUSE_ONLY
            !isHeadMouseActive && isJarvisActive -> E2EDualOperatingMode.JARVIS_ONLY
            else -> E2EDualOperatingMode.STANDBY
        }

        // Camera hardware lifecycle
        if (isHeadMouseActive) {
            bindCamera(60, 60)
            isCursorOverlayVisible = true
        } else {
            unbindCamera()
            isCursorOverlayVisible = false
        }

        // Voice hardware lifecycle
        if (isJarvisActive) {
            isFloatingHudVisible = true
            startVoiceCoordinator()
        } else {
            isFloatingHudVisible = false
            stopVoiceCoordinator()
        }
    }

    fun bindCamera(fpsMin: Int = 60, fpsMax: Int = 60) {
        cameraBindCallCount++
        isCameraBound = true
        isCameraSensorPowered = true
        isPrivacyLedOn = true
        targetFpsMin = fpsMin
        targetFpsMax = fpsMax
    }

    fun unbindCamera() {
        cameraUnbindCallCount++
        isCameraBound = false
        isCameraSensorPowered = false
        isPrivacyLedOn = false
    }

    private fun startVoiceCoordinator() {
        if (activeSpeechSessionsCount > 0 && isRecognizingSpeech) {
            // Already safely active; do not create duplicate
        } else {
            activeSpeechSessionsCount = 1
            isMicrophoneActive = true
            isRecognizingSpeech = true
            voiceEngineState = E2EVoiceEngineState.WAKE_WORD_STANDBY
            hudStatusText = "JARVIS • STANDBY"
            lastHudPulseColor = "AMBER"
        }
    }

    private fun stopVoiceCoordinator() {
        activeSpeechSessionsCount = 0
        isMicrophoneActive = false
        isRecognizingSpeech = false
        voiceEngineState = E2EVoiceEngineState.OFF
        hudStatusText = "STANDBY"
        lastHudPulseColor = "SLATE"
        activeMissionLock = false
    }

    // -------------------------------------------------------------
    // Wake-Word & Utterance Processing (F5, F6, F7, F8, F9, F10)
    // -------------------------------------------------------------

    fun resolvePackage(query: String): String? {
        val clean = query.trim().lowercase()
        for ((name, pkg) in knownApps) {
            if (clean.contains(name)) {
                return pkg
            }
        }
        return null
    }

    /**
     * Opaque-box requirement parser matching PROJECT.md § parseWakeWordUtterance
     */
    fun parseWakeWordUtterance(rawText: String): E2EWakeWordParseResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return E2EWakeWordParseResult(
                isWakeWordDetected = false,
                matchedTrigger = null,
                compoundCommand = null,
                isImmediateSleepTrigger = false
            )
        }

        val lower = trimmed.lowercase()

        // 1. Immediate sleep trigger check (before or independent of wake word)
        val isSleep = sleepTriggers.any { lower == it || lower.contains(it) }
        if (isSleep) {
            val matchedW = wakeTriggers.firstOrNull { lower.startsWith(it) || lower.contains(it) }
            val remainder = if (matchedW != null) lower.substringAfter(matchedW).trim(' ', ',', '.', '!', '?').trim().takeIf { it.isNotBlank() } else null
            return E2EWakeWordParseResult(
                isWakeWordDetected = matchedW != null,
                matchedTrigger = matchedW,
                compoundCommand = remainder,
                isImmediateSleepTrigger = true
            )
        }

        // 2. Check if utterance starts with or contains any wake trigger
        var matchedTrigger: String? = null
        for (trigger in wakeTriggers) {
            if (lower.startsWith(trigger)) {
                matchedTrigger = trigger
                break
            }
        }

        // Substring fallback if not at the start
        if (matchedTrigger == null) {
            for (trigger in wakeTriggers) {
                if (lower.contains(trigger)) {
                    matchedTrigger = trigger
                    break
                }
            }
        }

        if (matchedTrigger == null) {
            return E2EWakeWordParseResult(
                isWakeWordDetected = false,
                matchedTrigger = null,
                compoundCommand = null,
                isImmediateSleepTrigger = false
            )
        }

        // Extract remainder after matched wake word
        val remainder = lower.substringAfter(matchedTrigger).trim(' ', ',', '.', '!', '?').trim()
        val compoundCommand = if (remainder.isNotBlank()) remainder else null

        // Check if compound command is itself an immediate sleep trigger (e.g. "Hey Jarvis stop")
        val isImmediateSleep = compoundCommand != null && sleepTriggers.any {
            compoundCommand == it || compoundCommand.contains(it)
        }

        return E2EWakeWordParseResult(
            isWakeWordDetected = true,
            matchedTrigger = matchedTrigger,
            compoundCommand = compoundCommand,
            isImmediateSleepTrigger = isImmediateSleep
        )
    }

    /**
     * Complete voice turn execution engine
     */
    fun processSpokenUtterance(utterance: String): VoiceProcessingResult {
        val startTime = System.currentTimeMillis()

        if (!isJarvisActive || voiceEngineState == E2EVoiceEngineState.OFF) {
            return VoiceProcessingResult(
                spokenText = "",
                displayText = "Voice engine is OFF",
                stateTransition = E2EVoiceEngineState.OFF,
                responseTimeMs = System.currentTimeMillis() - startTime
            )
        }

        val parseResult = parseWakeWordUtterance(utterance)

        // Case A: Standby mode requiring wake-word trigger
        if (voiceEngineState == E2EVoiceEngineState.WAKE_WORD_STANDBY) {
            if (!parseResult.isWakeWordDetected) {
                // Ignore background chatter without wake word
                return VoiceProcessingResult(
                    spokenText = "",
                    displayText = "Listening for 'Hey Jarvis'...",
                    stateTransition = E2EVoiceEngineState.WAKE_WORD_STANDBY,
                    responseTimeMs = System.currentTimeMillis() - startTime
                )
            }

            // Wake word detected!
            if (parseResult.isImmediateSleepTrigger) {
                // e.g. "Hey Jarvis stop"
                voiceEngineState = E2EVoiceEngineState.WAKE_WORD_STANDBY
                hudStatusText = "JARVIS • STANDBY"
                lastHudPulseColor = "AMBER"
                lastSpokenTts = "Standby confirmed, Sir."
                return VoiceProcessingResult(
                    spokenText = lastSpokenTts,
                    displayText = "Standby mode active",
                    stateTransition = E2EVoiceEngineState.WAKE_WORD_STANDBY,
                    responseTimeMs = System.currentTimeMillis() - startTime
                )
            }

            if (parseResult.compoundCommand != null) {
                // Compound execution in one breath: "Hey Jarvis open YouTube"
                voiceEngineState = E2EVoiceEngineState.THINKING
                lastHudPulseColor = "CYAN"
                hudStatusText = "JARVIS • ACTING"
                return executeCommand(parseResult.compoundCommand, startTime)
            } else {
                // Single wake word: "Hey Jarvis"
                voiceEngineState = E2EVoiceEngineState.ACTIVE_LISTENING
                lastHudPulseColor = "CYAN"
                hudStatusText = "CALL CONNECTED"
                lastSpokenTts = "At your service, Sir."
                return VoiceProcessingResult(
                    spokenText = lastSpokenTts,
                    displayText = "Online & listening",
                    stateTransition = E2EVoiceEngineState.ACTIVE_LISTENING,
                    responseTimeMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // Case B: Active Continuous Conversation (ACTIVE_LISTENING or SPEAKING)
        val lower = utterance.lowercase().trim()

        // 1. Re-wake or maintain active listening when only wake word is spoken
        if (parseResult.isWakeWordDetected && parseResult.compoundCommand == null && !parseResult.isImmediateSleepTrigger) {
            voiceEngineState = E2EVoiceEngineState.ACTIVE_LISTENING
            lastHudPulseColor = "CYAN"
            hudStatusText = "CALL CONNECTED"
            lastSpokenTts = "At your service, Sir."
            return VoiceProcessingResult(
                spokenText = lastSpokenTts,
                displayText = "Online & listening",
                stateTransition = E2EVoiceEngineState.ACTIVE_LISTENING,
                responseTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // 2. Emergency Abort (<200ms)
        if (lower.contains("stop mission") || lower.contains("abort") || lower == "cancel") {
            triggerEmergencyAbort()
            return VoiceProcessingResult(
                spokenText = lastSpokenTts,
                displayText = "Mission aborted",
                stateTransition = E2EVoiceEngineState.WAKE_WORD_STANDBY,
                responseTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // 3. Sleep command check (<200ms)
        if (parseResult.isImmediateSleepTrigger || sleepTriggers.any { lower == it || lower.contains(it) }) {
            voiceEngineState = E2EVoiceEngineState.WAKE_WORD_STANDBY
            lastHudPulseColor = "AMBER"
            hudStatusText = "JARVIS • STANDBY"
            lastSpokenTts = "Entering standby mode, Sir. I remain ready on your screen."
            activeMissionLock = false
            return VoiceProcessingResult(
                spokenText = lastSpokenTts,
                displayText = "Standby mode active",
                stateTransition = E2EVoiceEngineState.WAKE_WORD_STANDBY,
                responseTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // 4. Normal command processing
        val effectiveCommand = if (parseResult.isWakeWordDetected && parseResult.compoundCommand != null) {
            parseResult.compoundCommand
        } else {
            utterance
        }

        return executeCommand(effectiveCommand, startTime)
    }

    private fun executeCommand(command: String, startTime: Long): VoiceProcessingResult {
        val lower = command.lowercase().trim()
        voiceEngineState = E2EVoiceEngineState.THINKING
        lastHudPulseColor = "CYAN"

        // Contextual search detection — must come BEFORE app launch to correctly
        // handle phrases like "Search artificial intelligence on YouTube" which
        // otherwise match the YouTube package name and return OPEN_APP.
        if (lower.startsWith("search ") || lower.contains("dhoondo") || lower.contains("khojo")) {
            val query = lower.removePrefix("search ").trim()
            lastExecutedAction = "SEARCH"
            voiceEngineState = E2EVoiceEngineState.SPEAKING
            lastHudPulseColor = "GREEN"
            lastSpokenTts = "Searching for $query."
            return VoiceProcessingResult(
                spokenText = lastSpokenTts,
                displayText = "Searching: $query",
                actionType = "SEARCH",
                stateTransition = E2EVoiceEngineState.SPEAKING,
                responseTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // App Launch detection
        val pkg = resolvePackage(command)
        if (pkg != null) {
            lastTargetPackage = pkg
            lastExecutedAction = "OPEN_APP"
            voiceEngineState = E2EVoiceEngineState.SPEAKING
            lastHudPulseColor = "GREEN"
            lastSpokenTts = "Opening application, Sir."
            return VoiceProcessingResult(
                spokenText = lastSpokenTts,
                displayText = "Launching $pkg",
                targetPackage = pkg,
                actionType = "OPEN_APP",
                stateTransition = E2EVoiceEngineState.SPEAKING,
                responseTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Directional / Mouse actions
        if (lower.contains("scroll down") || lower.contains("niche scroll")) {
            lastExecutedAction = "SCROLL_DOWN"
            voiceEngineState = E2EVoiceEngineState.SPEAKING
            lastHudPulseColor = "GREEN"
            lastSpokenTts = "Scrolling down."
            return VoiceProcessingResult(
                spokenText = lastSpokenTts,
                displayText = "Scrolled Down",
                actionType = "SCROLL_DOWN",
                stateTransition = E2EVoiceEngineState.SPEAKING,
                responseTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Conversational Fallback / Identity
        if (lower.contains("who are you") || lower.contains("tum kaun ho") || lower.contains("identity")) {
            voiceEngineState = E2EVoiceEngineState.SPEAKING
            lastHudPulseColor = "CYAN"
            lastSpokenTts = "I am J.A.R.V.I.S., your autonomous hands-free assistant, functioning at peak efficiency."
            return VoiceProcessingResult(
                spokenText = lastSpokenTts,
                displayText = "I am J.A.R.V.I.S.",
                actionType = "CHAT",
                stateTransition = E2EVoiceEngineState.SPEAKING,
                responseTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Default conversational response
        voiceEngineState = E2EVoiceEngineState.SPEAKING
        lastHudPulseColor = "CYAN"
        lastSpokenTts = "Understood, Sir. Processing your request."
        return VoiceProcessingResult(
            spokenText = lastSpokenTts,
            displayText = "Processing request",
            actionType = "GENERAL",
            stateTransition = E2EVoiceEngineState.SPEAKING,
            responseTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * TTS completion callback to auto-resume listening in continuous mode (F8)
     */
    fun onTtsSpeakingFinished() {
        if (isJarvisActive && voiceEngineState == E2EVoiceEngineState.SPEAKING) {
            // Auto-transition back to ACTIVE_LISTENING after 400ms TTS buffer
            voiceEngineState = E2EVoiceEngineState.ACTIVE_LISTENING
            lastHudPulseColor = "CYAN"
            hudStatusText = "Listening..."
        }
    }

    /**
     * Sub-200ms Emergency Abort mechanism (F9, F12)
     */
    fun triggerEmergencyAbort() {
        rapidSpeechAbortCount++
        activeMissionLock = false
        voiceEngineState = E2EVoiceEngineState.WAKE_WORD_STANDBY
        lastHudPulseColor = "RED"
        hudStatusText = "MISSION ABORTED"
        lastSpokenTts = "Mission aborted, Sir."
    }

    // -------------------------------------------------------------
    // Floating HUD Quick-Toggles (F12)
    // -------------------------------------------------------------

    fun expandHud() {
        isHudExpanded = true
    }

    fun collapseHud() {
        isHudExpanded = false
    }

    fun toggleHeadMouseFromHud() {
        toggleHeadMouse()
    }

    fun toggleVoiceFromHud() {
        if (!isJarvisActive) {
            setJarvisServiceEnabled(true)
            voiceEngineState = E2EVoiceEngineState.ACTIVE_LISTENING
            hudStatusText = "CALL CONNECTED"
            lastHudPulseColor = "CYAN"
            lastSpokenTts = "Listening, Sir."
            return
        }
        if (voiceEngineState == E2EVoiceEngineState.ACTIVE_LISTENING) {
            voiceEngineState = E2EVoiceEngineState.WAKE_WORD_STANDBY
            hudStatusText = "JARVIS • STANDBY"
            lastHudPulseColor = "AMBER"
        } else if (voiceEngineState == E2EVoiceEngineState.WAKE_WORD_STANDBY) {
            voiceEngineState = E2EVoiceEngineState.ACTIVE_LISTENING
            hudStatusText = "CALL CONNECTED"
            lastHudPulseColor = "CYAN"
            lastSpokenTts = "Listening, Sir."
        }
    }

    // -------------------------------------------------------------
    // Concept 1 Cursor & Gesture Simulation (F4)
    // -------------------------------------------------------------

    fun simulateCameraFrame(dt: Float, rawX: Float, rawY: Float): FilteredCursorPoint {
        if (!isHeadMouseActive || !isCameraSensorPowered) {
            return FilteredCursorPoint(lastFilteredX, lastFilteredY, true, 0f)
        }

        val fx = oneEuroFilterX.filter(rawX.toDouble(), 1.0 / dt.toDouble()).toFloat()
        val fy = oneEuroFilterY.filter(rawY.toDouble(), 1.0 / dt.toDouble()).toFloat()

        val drift = hypot(fx - lastFilteredX, fy - lastFilteredY)
        lastFilteredX = fx
        lastFilteredY = fy

        return FilteredCursorPoint(
            x = fx,
            y = fy,
            isStationary = drift < 1.5f,
            driftDistanceDp = drift
        )
    }

    fun getHardwareSnapshot(): E2EHardwareState {
        return E2EHardwareState(
            isCameraBound = isCameraBound,
            isCameraSensorPowered = isCameraSensorPowered,
            isPrivacyLedOn = isPrivacyLedOn,
            targetFpsMin = targetFpsMin,
            targetFpsMax = targetFpsMax,
            isMicrophoneActive = isMicrophoneActive,
            isRecognizingSpeech = isRecognizingSpeech,
            isCursorOverlayVisible = isCursorOverlayVisible,
            isFloatingHudVisible = isFloatingHudVisible,
            isHudExpanded = isHudExpanded,
            hudStatusText = hudStatusText,
            activeMissionLock = activeMissionLock
        )
    }
}
