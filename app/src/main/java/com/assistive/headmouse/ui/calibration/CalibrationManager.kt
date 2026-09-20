package com.assistive.headmouse.ui.calibration

import com.assistive.headmouse.preferences.AppSettings
import com.assistive.headmouse.tracking.filter.OneEuroFilter
import com.assistive.headmouse.tracking.model.HeadPoseData
import com.assistive.headmouse.tracking.model.ScreenCoordinate
import com.assistive.headmouse.tracking.model.TrackingMode

/**
 * Transforms raw 3D head pose and eye gaze into ultra-smooth, glitch-free screen coordinates.
 */
class CalibrationManager(
    private val appSettings: AppSettings,
    private var screenWidth: Int = 1080,
    private var screenHeight: Int = 2400
) {
    // High-precision adaptive 1€ Filter for silky 60fps tracking
    private val filterX = OneEuroFilter(0.035, 0.004)
    private val filterY = OneEuroFilter(0.035, 0.004)

    private var smoothedGear: Float = 1.0f
    private var lastInputX: Float = 0.5f
    private var lastInputY: Float = 0.5f
    private var lastPoseTimeMs: Long = 0L

    private var vehicleCompensationX: Float = 0f
    private var vehicleCompensationY: Float = 0f
    private var isVehicleActive: Boolean = false

    // Anti-Jitter Stationary Stabilizer for rock-solid targeting
    private var steadyAnchorX: Float? = null
    private var steadyAnchorY: Float? = null

    fun updateScreenDimensions(width: Int, height: Int) {
        this.screenWidth = width
        this.screenHeight = height
    }

    fun updateFilterSettings() {
        filterX.adaptToFps(appSettings.targetFps)
        filterY.adaptToFps(appSettings.targetFps)
    }

    fun setVehicleCompensation(compX: Float, compY: Float, vehicleActive: Boolean) {
        this.vehicleCompensationX = compX
        this.vehicleCompensationY = compY
        this.isVehicleActive = vehicleActive
    }

    fun calibrateCenter(pose: HeadPoseData) {
        appSettings.centerNoseX = pose.noseX
        appSettings.centerNoseY = pose.noseY
        appSettings.centerYawOffset = pose.yaw
        appSettings.centerPitchOffset = pose.pitch
        if (pose.eyeGazeX != null && pose.eyeGazeY != null) {
            appSettings.centerEyeX = pose.eyeGazeX
            appSettings.centerEyeY = pose.eyeGazeY
        }
        filterX.reset()
        filterY.reset()
        steadyAnchorX = null
        steadyAnchorY = null
        smoothedGear = 1.0f
    }

    fun mapHeadPoseToScreen(pose: HeadPoseData): ScreenCoordinate {
        val timestampSec = pose.timestampMs / 1000.0

        val dt = if (lastPoseTimeMs > 0L) {
            ((pose.timestampMs - lastPoseTimeMs) / 1000.0).coerceIn(0.008, 0.1)
        } else {
            0.033
        }

        // Determine tracking input coordinates (Head Motion vs Eye Gaze)
        // In vehicle mode, auto-switch to Eye Gaze if available for bump immunity!
        val preferEyeInVehicle = isVehicleActive && appSettings.isVehicleModeEnabled && (pose.eyeGazeX != null) && (pose.eyeGazeY != null)
        val isEyeMode = preferEyeInVehicle ||
                ((appSettings.trackingMode == TrackingMode.EYE_ONLY || appSettings.trackingMode == TrackingMode.EYE_AND_VOICE) &&
                (pose.eyeGazeX != null) && (pose.eyeGazeY != null))

        val currentInputX = if (isEyeMode) pose.eyeGazeX!! else pose.noseX
        val currentInputY = if (isEyeMode) pose.eyeGazeY!! else pose.noseY
        val centerOriginX = if (isEyeMode) appSettings.centerEyeX else appSettings.centerNoseX
        val centerOriginY = if (isEyeMode) appSettings.centerEyeY else appSettings.centerNoseY

        // Real-time velocity of input to detect micro-movements
        val dX = (currentInputX - lastInputX) / dt
        val dY = (currentInputY - lastInputY) / dt
        val inputVelocity = kotlin.math.hypot(dX, dY)

        lastInputX = currentInputX
        lastInputY = currentInputY
        lastPoseTimeMs = pose.timestampMs

        // 1. Continuous Sub-Pixel Delta relative to calibrated center
        var deltaX = (centerOriginX - currentInputX)
        var deltaY = (currentInputY - centerOriginY)

        // Vehicle bump cancellation: subtract phone IMU acceleration from camera displacement
        if (isVehicleActive && !isEyeMode) {
            deltaX -= (vehicleCompensationX * 0.005f)
            deltaY -= (vehicleCompensationY * 0.005f)
        }

        // 2. Smooth Deadzone & Resting Neutral Orbit (absorbs relaxed everyday head drift)
        var baseDeadzone = (appSettings.deadzoneDegrees * 0.0022f).coerceIn(0.0018f, 0.015f)
        if (isVehicleActive) baseDeadzone *= 1.85f // Expand deadzone during road potholes/bumps
        val deadzone = if (isEyeMode) baseDeadzone * 1.35f else baseDeadzone
        val distFromCenter = kotlin.math.hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()

        if (distFromCenter < deadzone) {
            deltaX = 0f
            deltaY = 0f
        } else {
            val scale = (distFromCenter - deadzone) / distFromCenter
            deltaX *= scale
            deltaY *= scale
        }

        // 3. Linearized Screen-Pixel Gear Acceleration:
        // When holding still on a target (inputVelocity < 0.035), gear stays 1.0 (sub-pixel precision hold).
        // Only increases progressively during intentional sweeps.
        val speedFactor = ((inputVelocity - 0.035) / 0.30).coerceIn(0.0, 1.0)
        val targetGear = (1.0 + (0.65 * speedFactor)).toFloat()
        smoothedGear = (smoothedGear * 0.90f) + (targetGear * 0.10f)

        val modeMultiplier = if (isEyeMode) 1.65f else 1.0f
        val baseSpeed = appSettings.cursorSpeed * modeMultiplier
        val factorX = baseSpeed * appSettings.sensitivityX * smoothedGear
        val factorY = baseSpeed * appSettings.sensitivityY * smoothedGear

        val rawPixelX = (screenWidth / 2f) + (deltaX * screenWidth * factorX).toDouble()
        val rawPixelY = (screenHeight / 2f) + (deltaY * screenHeight * factorY).toDouble()

        // 4. Silky-Smooth Anti-Jitter Smoothing via 1€ Filter (Continuous floating point, no grid snapping)
        val smoothX = filterX.filter(rawPixelX, timestampSec).toFloat()
        val smoothY = filterY.filter(rawPixelY, timestampSec).toFloat()

        // 5. Stationary Precision Stabilizer:
        // When holding steady on a target (inputVelocity < 0.055), suppress physiological micro-tremors (< 14px).
        // Progressive hysteresis damping eliminates jitter/fluctuation completely with zero threshold snap.
        val anchorX = steadyAnchorX
        val anchorY = steadyAnchorY
        val finalX: Float
        val finalY: Float

        if (anchorX != null && anchorY != null) {
            val distFromAnchor = kotlin.math.hypot((smoothX - anchorX).toDouble(), (smoothY - anchorY).toDouble()).toFloat()
            if (distFromAnchor < 14.0f && inputVelocity < 0.055) {
                // Progressive damping: smooth hysteresis so cursor never jumps or vibrates
                val blendFactor = ((distFromAnchor / 14.0f) * 0.12f).coerceIn(0.04f, 0.15f)
                finalX = anchorX + (smoothX - anchorX) * blendFactor
                finalY = anchorY + (smoothY - anchorY) * blendFactor
                steadyAnchorX = finalX
                steadyAnchorY = finalY
            } else {
                // Intentional movement: follow user immediately
                finalX = smoothX
                finalY = smoothY
                steadyAnchorX = smoothX
                steadyAnchorY = smoothY
            }
        } else {
            finalX = smoothX
            finalY = smoothY
            steadyAnchorX = smoothX
            steadyAnchorY = smoothY
        }

        // 6. Screen bounds clamping (Fluid sub-pixel continuous coordinates)
        val clampedX = finalX.coerceIn(0f, screenWidth.toFloat())
        val clampedY = finalY.coerceIn(0f, screenHeight.toFloat())

        return ScreenCoordinate(clampedX, clampedY, pose.timestampMs)
    }

    fun resetFilters() {
        filterX.reset()
        filterY.reset()
        steadyAnchorX = null
        steadyAnchorY = null
        smoothedGear = 1.0f
    }
}
