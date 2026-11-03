package io.github.foadsf.microscope

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import io.github.foadsf.microscope.imaging.GyroTrack
import kotlin.math.sqrt

/**
 * Integrates the gyroscope for a tilt series ([GyroTrack]) and reports the angular speed, so views
 * can be taken once the phone is steady. Sensor timestamps are on the elapsed-realtime clock, the
 * same clock as camera frames whose timestamp source is REALTIME.
 */
class TiltTracker(context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val track = GyroTrack()

    /** Latest angular speed, rad/s. */
    @Volatile var speed = Float.POSITIVE_INFINITY
        private set

    /** Time (elapsed-realtime ns) since which the speed has stayed below [calmBelow]. */
    @Volatile var calmSinceNs = -1L
        private set

    @Volatile var calmBelow = 0.03f

    val available: Boolean get() = gyro != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val w = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            speed = w
            calmSinceNs = if (w >= calmBelow) -1L else if (calmSinceNs < 0) e.timestamp else calmSinceNs
            track.offer(e.timestamp, e.values[0], e.values[1], e.values[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Starts listening at 200 Hz (the most Android allows without a special permission). */
    fun start(): Boolean {
        val g = gyro ?: return false
        return sensors?.registerListener(listener, g, SAMPLING_US) == true
    }

    fun stop() {
        sensors?.unregisterListener(listener)
    }

    /** True if the phone has been steady for [holdNs] up to now. */
    fun steadyFor(holdNs: Long): Boolean {
        val since = calmSinceNs
        return since >= 0 && SystemClock.elapsedRealtimeNanos() - since >= holdNs
    }

    private companion object {
        const val SAMPLING_US = 5_000
    }
}
