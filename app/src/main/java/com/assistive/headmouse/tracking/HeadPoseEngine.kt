package com.assistive.headmouse.tracking

import com.assistive.headmouse.tracking.model.FacialGesture
import com.assistive.headmouse.tracking.model.HeadPoseData
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Parses ML Kit Face detection into standardized HeadPoseData and facial gestures.
 */
class HeadPoseEngine {

    private var smoothedYaw: Float? = null
    private var smoothedPitch: Float? = null
    private var smoothedNoseX: Float? = null
    private var smoothedNoseY: Float? = null
    private var smoothedEyeX: Float? = null
    private var smoothedEyeY: Float? = null

    private var lastProcessTimeMs: Long = 0L
    private val smileHistory = FloatArray(10)
    private var smileHistoryIdx = 0
    private var smileHistoryFilled = false

    fun resetFilters() {
        smoothedYaw = null
        smoothedPitch = null
        smoothedNoseX = null
        smoothedNoseY = null
        smoothedEyeX = null
        smoothedEyeY = null
        lastProcessTimeMs = 0L
        smileHistoryIdx = 0
        smileHistoryFilled = false
    }

    private fun scaleAlpha(baseAlpha: Float, dtRatio: Float): Float {
        val complement = (1.0 - baseAlpha.toDouble()).coerceIn(0.01, 0.99)
        return (1.0 - Math.pow(complement, dtRatio.toDouble())).toFloat().coerceIn(0.02f, 0.95f)
    }

    fun processFace(
        face: Face,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<HeadPoseData, FacialGesture> {
        val now = System.currentTimeMillis()
        val dt = if (lastProcessTimeMs > 0L) {
            ((now - lastProcessTimeMs) / 1000f).coerceIn(0.008f, 0.1f)
        } else {
            0.0166f
        }
        lastProcessTimeMs = now
        val dtRatio = dt / 0.0166f // Normalized to 60 FPS standard

        // Pure 3D Head Orientation directly from Face Geometry
        val rawPitch = face.headEulerAngleX // Head up/down tilt
        val rawYaw = face.headEulerAngleY   // Head left/right turn
        val roll = face.headEulerAngleZ    // Head sideways tilt

        // Dynamic Physiological Tremor Killer:
        val prevP = smoothedPitch ?: rawPitch
        val prevY = smoothedYaw ?: rawYaw

        val diffP = kotlin.math.abs(rawPitch - prevP)
        val diffY = kotlin.math.abs(rawYaw - prevY)

        val baseAlphaPitch = if (diffP < 2.0f) 0.06f else if (diffP > 4.5f) 0.35f else (0.06f + (diffP - 2.0f) / 2.5f * 0.29f)
        val baseAlphaYaw = if (diffY < 2.0f) 0.07f else if (diffY > 4.5f) 0.35f else (0.07f + (diffY - 2.0f) / 2.5f * 0.28f)

        val alphaPitch = scaleAlpha(baseAlphaPitch, dtRatio)
        val alphaYaw = scaleAlpha(baseAlphaYaw, dtRatio)

        val yaw = prevY + alphaYaw * (rawYaw - prevY)
        val pitch = prevP + alphaPitch * (rawPitch - prevP)
        smoothedYaw = yaw
        smoothedPitch = pitch

        // Exact Sub-Pixel Nose-Tip Landmark
        val noseLandmark = face.getLandmark(FaceLandmark.NOSE_BASE)
        val rawNoseX = if (noseLandmark != null && imageWidth > 0) {
            noseLandmark.position.x / imageWidth.toFloat()
        } else if (imageWidth > 0) {
            face.boundingBox.exactCenterX() / imageWidth.toFloat()
        } else {
            0.5f
        }

        val rawNoseY = if (noseLandmark != null && imageHeight > 0) {
            noseLandmark.position.y / imageHeight.toFloat()
        } else if (imageHeight > 0) {
            face.boundingBox.exactCenterY() / imageHeight.toFloat()
        } else {
            0.5f
        }

        val prevNx = smoothedNoseX ?: rawNoseX
        val prevNy = smoothedNoseY ?: rawNoseY
        val diffNx = kotlin.math.abs(rawNoseX - prevNx)
        val diffNy = kotlin.math.abs(rawNoseY - prevNy)
        val baseAlphaNx = if (diffNx < 0.01f) 0.08f else if (diffNx > 0.03f) 0.40f else 0.15f
        val baseAlphaNy = if (diffNy < 0.01f) 0.08f else if (diffNy > 0.03f) 0.40f else 0.15f

        val alphaNoseX = scaleAlpha(baseAlphaNx, dtRatio)
        val alphaNoseY = scaleAlpha(baseAlphaNy, dtRatio)

        val noseX = prevNx + alphaNoseX * (rawNoseX - prevNx)
        val noseY = prevNy + alphaNoseY * (rawNoseY - prevNy)
        smoothedNoseX = noseX
        smoothedNoseY = noseY

        val leftEyeOpen = face.leftEyeOpenProbability
        val rightEyeOpen = face.rightEyeOpenProbability
        val smilingProb = face.smilingProbability

        // Conversational Speech Detection via Mouth Variance over 10 frames
        if (smilingProb != null) {
            smileHistory[smileHistoryIdx] = smilingProb
            smileHistoryIdx = (smileHistoryIdx + 1) % smileHistory.size
            if (smileHistoryIdx == 0) smileHistoryFilled = true
        }

        val count = if (smileHistoryFilled) smileHistory.size else smileHistoryIdx
        var isConversationalSpeech = false
        if (count >= 5) {
            var sum = 0f
            for (i in 0 until count) sum += smileHistory[i]
            val mean = sum / count
            var varianceSum = 0f
            for (i in 0 until count) {
                val diff = smileHistory[i] - mean
                varianceSum += diff * diff
            }
            val stdDev = kotlin.math.sqrt((varianceSum / count).toDouble()).toFloat()
            // High variance in mouth/smile within ~300-500ms indicates active conversation/talking syllables
            if (stdDev > 0.12f && mean < 0.70f) {
                isConversationalSpeech = true
            }
        }

        // Eye Gaze Landmark Tracking with Adaptive Physiological Stabilizer
        val leftEyeLandmark = face.getLandmark(FaceLandmark.LEFT_EYE)
        val rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE)
        val finalEyeX: Float?
        val finalEyeY: Float?

        if (leftEyeLandmark != null && rightEyeLandmark != null && imageWidth > 0 && imageHeight > 0) {
            val midX = (leftEyeLandmark.position.x + rightEyeLandmark.position.x) / 2f
            val midY = (leftEyeLandmark.position.y + rightEyeLandmark.position.y) / 2f
            val rawEyeX = midX / imageWidth.toFloat()
            val rawEyeY = midY / imageHeight.toFloat()

            val prevEx = smoothedEyeX ?: rawEyeX
            val prevEy = smoothedEyeY ?: rawEyeY
            val diffEx = kotlin.math.abs(rawEyeX - prevEx)
            val diffEy = kotlin.math.abs(rawEyeY - prevEy)

            val baseAlphaEx = if (diffEx < 0.005f) 0.06f else if (diffEx > 0.018f) 0.35f else (0.06f + (diffEx - 0.005f) / 0.013f * 0.29f)
            val baseAlphaEy = if (diffEy < 0.005f) 0.06f else if (diffEy > 0.018f) 0.35f else (0.06f + (diffEy - 0.005f) / 0.013f * 0.29f)

            val alphaEx = scaleAlpha(baseAlphaEx, dtRatio)
            val alphaEy = scaleAlpha(baseAlphaEy, dtRatio)

            val seX = prevEx + alphaEx * (rawEyeX - prevEx)
            val seY = prevEy + alphaEy * (rawEyeY - prevEy)
            smoothedEyeX = seX
            smoothedEyeY = seY

            finalEyeX = seX
            finalEyeY = seY
        } else {
            finalEyeX = null
            finalEyeY = null
        }

        // Natural Freedom: Look-Away Intent Gating
        // If head is turned past 22° yaw, tilted > 20° pitch, or eyes glance far off-axis,
        // mark isLookingAway = true so cursor and dwell freeze to give the user complete freedom
        val isLookingAway = kotlin.math.abs(yaw) > 22.0f ||
                kotlin.math.abs(pitch) > 20.0f ||
                kotlin.math.abs(roll) > 24.0f ||
                (finalEyeX != null && (finalEyeX < 0.12f || finalEyeX > 0.88f))

        val gesture = if (isLookingAway || isConversationalSpeech) {
            FacialGesture.NONE
        } else {
            detectGesture(leftEyeOpen, rightEyeOpen, smilingProb)
        }

        val poseData = HeadPoseData(
            pitch = pitch,
            yaw = yaw,
            roll = roll,
            noseX = noseX,
            noseY = noseY,
            eyeGazeX = finalEyeX,
            eyeGazeY = finalEyeY,
            leftEyeOpenProb = leftEyeOpen,
            rightEyeOpenProb = rightEyeOpen,
            smilingProb = smilingProb,
            isLookingAway = isLookingAway,
            isConversationalSpeech = isConversationalSpeech,
            timestampMs = now
        )

        return Pair(poseData, gesture)
    }

    private fun detectGesture(leftEyeOpen: Float?, rightEyeOpen: Float?, smilingProb: Float?): FacialGesture {
        // Teeth-reveal / wide smile gesture for Pause/Resume toggle
        if (smilingProb != null && smilingProb > 0.74f) {
            return FacialGesture.TEETH_SHOW
        }

        if (leftEyeOpen == null || rightEyeOpen == null) return FacialGesture.NONE

        val winkThresholdClose = 0.25f
        val winkThresholdOpen = 0.70f

        // Left eye closed while right eye is open (Instant Back)
        if (leftEyeOpen < winkThresholdClose && rightEyeOpen > winkThresholdOpen) {
            return FacialGesture.LEFT_WINK
        }

        // Right eye closed while left eye is open (Instant Tap)
        if (rightEyeOpen < winkThresholdClose && leftEyeOpen > winkThresholdOpen) {
            return FacialGesture.RIGHT_WINK
        }

        return FacialGesture.NONE
    }
}
