package io.github.foadsf.microscope

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.foadsf.microscope.imaging.SteadyGate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Waits on the gyroscope for the phone to settle before a capture (see [SteadyGate]). */
object SteadyHold {
    private const val HOLD_NS = 120_000_000L

    fun available(context: Context): Boolean = gyroscope(context) != null

    private fun gyroscope(context: Context): Sensor? =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /**
     * Suspends until the phone is steady or [timeoutMs] passes. Returns true if it settled, false on
     * timeout, null when there is no gyroscope (the caller then simply goes ahead).
     */
    suspend fun await(context: Context, thresholdRadS: Float, timeoutMs: Long): Boolean? {
        val sensors = context.getSystemService(SensorManager::class.java) ?: return null
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
        val gate = SteadyGate(thresholdRadS, HOLD_NS)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        if (gate.offer(e.timestamp, e.values[0], e.values[1], e.values[2]) && cont.isActive) {
                            sensors.unregisterListener(this)
                            cont.resume(true)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensors.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
                cont.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        } ?: false
    }
}
