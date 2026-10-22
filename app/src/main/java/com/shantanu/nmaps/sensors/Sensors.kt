package com.shantanu.nmaps.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.sqrt

/** Compass heading in degrees from true north, with an estimated accuracy (± degrees). */
data class Heading(val degrees: Float, val accuracy: Float)

/**
 * Thin wrappers that turn Android sensors into flows. Each flow registers its listener on
 * collection and unregisters when the collector goes away, so sensors only run while used.
 */
class Sensors(private val context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)

    val hasCompass get() = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
        manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null
    val hasBarometer get() = manager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
    val hasStepCounter get() = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /**
     * Device heading from the fused rotation-vector sensor (gyroscope + accelerometer + magnetometer).
     * [declination] converts magnetic north to true north for the current position.
     */
    fun heading(declination: () -> Float): Flow<Heading> = callbackFlow {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var statusAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        var smoothed = Float.NaN
        var lastSent = Float.NaN
        var lastAccuracy = 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, e.values.copyOf(4))
                // Keep "up" on screen aligned with heading in any display rotation.
                val (ax, ay) = when (display?.rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, ax, ay, remapped)
                SensorManager.getOrientation(remapped, orientation)
                // Held upright (pitch past ~63°) the flat azimuth is unstable; use where the back camera points instead.
                if (abs(orientation[1]) > 1.1f) {
                    SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                    SensorManager.getOrientation(remapped, orientation)
                }
                val raw = normalize(Math.toDegrees(orientation[0].toDouble()).toFloat() + declination())
                // Circular low-pass filter: the gyro keeps it responsive, this takes off magnetometer jitter.
                smoothed = if (smoothed.isNaN()) raw else normalize(smoothed + shortestDelta(smoothed, raw) * 0.25f)

                val accuracy = if (e.values.size > 4 && e.values[4] >= 0f) {
                    Math.toDegrees(e.values[4].toDouble()).toFloat()
                } else {
                    when (statusAccuracy) {
                        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 10f
                        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 20f
                        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 35f
                        else -> 60f
                    }
                }
                if (lastSent.isNaN() || abs(shortestDelta(lastSent, smoothed)) > 0.8f || abs(accuracy - lastAccuracy) > 5f) {
                    lastSent = smoothed
                    lastAccuracy = accuracy
                    trySend(Heading(smoothed, accuracy))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                statusAccuracy = accuracy
            }
        }
        manager.registerListener(listener, sensor, 33_000)
        awaitClose { manager.unregisterListener(listener) }
    }

    /** Ambient light in lux. */
    fun lux(): Flow<Float> = single(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL) { it.values[0] }

    /**
     * True while the phone is at rest, from the RMS of linear acceleration over about a second.
     * Used to hold the location dot steady and to auto-pause recordings quickly.
     */
    fun stillness(): Flow<Boolean> = callbackFlow {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val window = FloatArray(50)
        var count = 0
        var stillSince = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = e.values
                window[count % window.size] = x * x + y * y + z * z
                count++
                if (count < window.size) return
                val rms = sqrt(window.average().toFloat())
                val now = System.currentTimeMillis()
                if (rms < 0.15f) {
                    if (stillSince == 0L) stillSince = now
                    trySend(now - stillSince > 2_000)
                } else {
                    stillSince = 0L
                    trySend(false)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, 20_000)
        awaitClose { manager.unregisterListener(listener) }
    }.distinctUntilChanged()

    /** Steps since boot from the hardware step counter. Needs ACTIVITY_RECOGNITION on Android 10+. */
    fun steps(): Flow<Float> = single(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL) { it.values[0] }

    /** Barometric altitude in metres (relative changes are precise; the absolute value drifts with weather). */
    fun pressureAltitude(): Flow<Float> = single(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL) {
        SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it.values[0])
    }

    private fun <T> single(type: Int, rate: Int, read: (SensorEvent) -> T): Flow<T> = callbackFlow {
        val sensor = manager.getDefaultSensor(type)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                trySend(read(e))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, rate)
        awaitClose { manager.unregisterListener(listener) }
    }

    companion object {
        fun normalize(deg: Float) = ((deg % 360f) + 360f) % 360f

        /** Signed smallest rotation from [from] to [to], in -180..180. */
        fun shortestDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f
    }
}
