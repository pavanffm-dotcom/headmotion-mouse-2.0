package com.assistive.headmouse.e2e.model

/**
 * Operating mode enum matching PROJECT.md § Interface Contracts:
 * DualOperatingMode (DUAL_MODE, HEAD_MOUSE_ONLY, JARVIS_ONLY, STANDBY)
 */
enum class E2EDualOperatingMode {
    DUAL_MODE,        // Both Head Mouse & J.A.R.V.I.S. active
    HEAD_MOUSE_ONLY,  // Head Mouse active (60 FPS camera); J.A.R.V.I.S. idle
    JARVIS_ONLY,      // Camera completely unbound/powered off; J.A.R.V.I.S. active
    STANDBY           // Both engines paused with zero background drain
}

/**
 * Voice engine state enum matching PROJECT.md § Interface Contracts:
 * VoiceEngineState (OFF, WAKE_WORD_STANDBY, ACTIVE_LISTENING, THINKING, SPEAKING)
 */
enum class E2EVoiceEngineState {
    OFF,
    WAKE_WORD_STANDBY,
    ACTIVE_LISTENING,
    THINKING,
    SPEAKING
}

/**
 * Wake word parse result matching PROJECT.md § Interface Contracts
 */
data class E2EWakeWordParseResult(
    val isWakeWordDetected: Boolean,
    val matchedTrigger: String?,
    val compoundCommand: String?,
    val isImmediateSleepTrigger: Boolean
)

/**
 * Simulated hardware and system state observed by E2E tests
 */
data class E2EHardwareState(
    val isCameraBound: Boolean,
    val isCameraSensorPowered: Boolean,
    val isPrivacyLedOn: Boolean,
    val targetFpsMin: Int,
    val targetFpsMax: Int,
    val isMicrophoneActive: Boolean,
    val isRecognizingSpeech: Boolean,
    val isCursorOverlayVisible: Boolean,
    val isFloatingHudVisible: Boolean,
    val isHudExpanded: Boolean,
    val hudStatusText: String,
    val activeMissionLock: Boolean
)

/**
 * Filtered cursor coordinate point
 */
data class FilteredCursorPoint(
    val x: Float,
    val y: Float,
    val isStationary: Boolean,
    val driftDistanceDp: Float
)

/**
 * Spoken command execution outcome
 */
data class VoiceProcessingResult(
    val spokenText: String,
    val displayText: String,
    val targetPackage: String? = null,
    val actionType: String? = null,
    val stateTransition: E2EVoiceEngineState,
    val responseTimeMs: Long
)
