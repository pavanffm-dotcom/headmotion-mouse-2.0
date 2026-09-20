package com.assistive.headmouse.model

/**
 * Unified operating modes for HeadMotionMouse and J.A.R.V.I.S.
 */
enum class DualOperatingMode(
    val isHeadMouseActive: Boolean,
    val isJarvisActive: Boolean,
    val requiresCamera: Boolean = isHeadMouseActive,
    val requiresVoice: Boolean = isJarvisActive
) {
    /**
     * Both Head Mouse (60 FPS camera tracking) and J.A.R.V.I.S. (voice agent) active concurrently.
     */
    DUAL_MODE(isHeadMouseActive = true, isJarvisActive = true),

    /**
     * Camera-based 60 FPS head/eye mouse is active; J.A.R.V.I.S. voice engine is idle/off.
     */
    HEAD_MOUSE_ONLY(isHeadMouseActive = true, isJarvisActive = false),

    /**
     * Camera tracking is cleanly suspended (camera sensor powered off, privacy LED off);
     * J.A.R.V.I.S. background voice service, floating HUD, and screen actions active.
     */
    JARVIS_ONLY(isHeadMouseActive = false, isJarvisActive = true),

    /**
     * Both systems paused cleanly with zero background drain.
     */
    STANDBY(isHeadMouseActive = false, isJarvisActive = false);

    companion object {
        fun fromFlags(isHeadMouseActive: Boolean, isJarvisActive: Boolean): DualOperatingMode {
            return when {
                isHeadMouseActive && isJarvisActive -> DUAL_MODE
                isHeadMouseActive && !isJarvisActive -> HEAD_MOUSE_ONLY
                !isHeadMouseActive && isJarvisActive -> JARVIS_ONLY
                else -> STANDBY
            }
        }
    }
}

/**
 * Contract for controlling dual operating mode transitions.
 */
interface DualModeController {
    fun setOperatingMode(mode: DualOperatingMode)
    fun setHeadMouseEnabled(enabled: Boolean)
    fun setJarvisServiceEnabled(enabled: Boolean)
    fun getOperatingMode(): DualOperatingMode
}
