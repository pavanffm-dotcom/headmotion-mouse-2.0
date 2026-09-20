package com.assistive.headmouse.tracking.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Monitors hardware IMU sensors (Linear Acceleration & Accelerometer)
 * to detect vehicle motion, road bumps, and cancel out device shake from head tracking.
 */
class MotionSensorManager(
    private val context: Context,
    private val onVehicleStateChanged: (Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var isVehicleActive: Boolean = false
        private set

    var accelX: Float = 0f
        private set
    var accelY: Float = 0f
        private set

    // Circular buffer for calculating vibration variance in the 2-8 Hz vehicle band
    private val sampleHistory = FloatArray(24)
    private var historyIdx = 0
    private var historyFilled = false
    private var lastVehicleDetectedTime = 0L

    fun start() {
        val targetSensor = linearAccelSensor ?: accelSensor ?: return
        sensorManager?.registerListener(
            this,
            targetSensor,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        isVehicleActive = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        val ax: Float
        val ay: Float
        val az: Float

        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            ax = event.values[0]
            ay = event.values[1]
            az = event.values[2]
        } else {
            // High-pass filter approximation for raw accelerometer
            ax = event.values[0] * 0.4f
            ay = event.values[1] * 0.4f
            az = (event.values[2] - 9.8f) * 0.4f
        }

        // Low pass filter on instantaneous acceleration vector
        accelX = (accelX * 0.75f) + (ax * 0.25f)
        accelY = (accelY * 0.75f) + (ay * 0.25f)

        // Calculate instantaneous 3D acceleration magnitude
        val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

        sampleHistory[historyIdx] = magnitude
        historyIdx = (historyIdx + 1) % sampleHistory.size
        if (historyIdx == 0) historyFilled = true

        val count = if (historyFilled) sampleHistory.size else historyIdx
        if (count >= 12) {
            var sum = 0f
            for (i in 0 until count) sum += sampleHistory[i]
            val mean = sum / count
            var varSum = 0f
            for (i in 0 until count) {
                val d = sampleHistory[i] - mean
                varSum += d * d
            }
            val variance = varSum / count

            val now = System.currentTimeMillis()
            // High variance (> 0.45 m/s²) with sustained vibration indicates moving vehicle / road bumps
            if (variance > 0.45f) {
                lastVehicleDetectedTime = now
                if (!isVehicleActive) {
                    isVehicleActive = true
                    onVehicleStateChanged(true)
                }
            } else if (isVehicleActive && (now - lastVehicleDetectedTime > 2500L)) {
                // Road has smoothed out or vehicle is stopped at a traffic light
                isVehicleActive = false
                onVehicleStateChanged(false)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
