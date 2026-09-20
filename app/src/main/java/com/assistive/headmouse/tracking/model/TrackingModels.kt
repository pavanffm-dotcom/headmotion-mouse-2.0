package com.assistive.headmouse.tracking.model

/**
 * 4 Dedicated Separate Operating Modes (No mixing/interference)
 */
enum class TrackingMode {
    HEAD_ONLY,       // Mode 1: Only Head Motion Tracking (Eye OFF, Voice OFF)
    EYE_ONLY,        // Mode 2: Only Eye Gaze Tracking (Head OFF, Voice OFF)
    HEAD_AND_VOICE,  // Mode 3: Head Tracking + Voice Commands Combo (Eye OFF, Voice ON)
    EYE_AND_VOICE    // Mode 4: Eye Tracking + Voice Commands Combo (Head OFF, Voice ON)
}

/**
 * Raw 3D Head Pose & Facial / Eye Landmark Data
 */
data class HeadPoseData(
    val pitch: Float, // Up / Down tilt (degrees)
    val yaw: Float,   // Left / Right turn (degrees)
    val roll: Float,  // Sideways tilt (degrees)
    val noseX: Float, // Normalized X position [0..1]
    val noseY: Float, // Normalized Y position [0..1]
    val eyeGazeX: Float? = null, // Normalized Eye Gaze X [0..1]
    val eyeGazeY: Float? = null, // Normalized Eye Gaze Y [0..1]
    val leftEyeOpenProb: Float? = null,
    val rightEyeOpenProb: Float? = null,
    val smilingProb: Float? = null,
    val isLookingAway: Boolean = false,
    val isConversationalSpeech: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Screen Coordinate in Pixels
 */
data class ScreenCoordinate(
    val x: Float,
    val y: Float,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Facial Gestures detected from landmarks
 */
enum class FacialGesture {
    NONE,
    LEFT_WINK,
    RIGHT_WINK,
    SMILE,
    MOUTH_OPEN,
    EYEBROW_RAISE,
    TEETH_SHOW
}

/**
 * Active Click & Interaction Mode
 */
enum class ClickMode {
    SINGLE_CLICK,
    DOUBLE_CLICK,
    LONG_PRESS,
    DRAG_HOLD,
    SCROLL_UP,
    SCROLL_DOWN
}

/**
 * Global Dock Actions
 */
enum class DockAction {
    MODE_SINGLE,
    MODE_DOUBLE,
    MODE_LONG,
    ACTION_SCROLL_UP,
    ACTION_SCROLL_DOWN,
    ACTION_SWIPE_LEFT,
    ACTION_SWIPE_RIGHT,
    NAV_BACK,
    NAV_HOME,
    NAV_RECENTS,
    NAV_NOTIFICATIONS,
    UTIL_RECENTER,
    UTIL_PAUSE_RESUME
}
