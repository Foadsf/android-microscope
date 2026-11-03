package io.github.foadsf.microscope.imaging

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Camera geometry for multi-view work. Camera coordinates follow the computer-vision convention:
// x right and y down in the sensor image, z forward along the optical axis. Rotations are row-major
// 3 × 3 DoubleArrays.

/** Pinhole intrinsics in frame pixels: focal length [f] (square pixels, no skew), principal point ([cx], [cy]). */
data class Intrinsics(val f: Double, val cx: Double, val cy: Double) {
    val matrix get() = doubleArrayOf(f, 0.0, cx, 0.0, f, cy, 0.0, 0.0, 1.0)
    val inverse get() = doubleArrayOf(1 / f, 0.0, -cx / f, 0.0, 1 / f, -cy / f, 0.0, 0.0, 1.0)

    companion object {
        /**
         * A thin lens of focal length [focalMm] focused at [distanceMm], seen through pixels of
         * [pixelUm]. Close up the image plane sits at the image distance v = u·f / (u − f), not at
         * f, so the pinhole focal length is v in pixels: 5 % longer than f for a 4.3 mm lens at 80 mm.
         */
        fun focused(focalMm: Double, pixelUm: Double, distanceMm: Double, cx: Double, cy: Double): Intrinsics {
            require(distanceMm > focalMm) { "focused closer than the focal length" }
            val v = distanceMm * focalMm / (distanceMm - focalMm)
            return Intrinsics(v * 1000 / pixelUm, cx, cy)
        }
    }
}

object Rotations {
    val IDENTITY get() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** Rotation by the vector (x, y, z): angle |v| about axis v/|v| (Rodrigues' formula). */
    fun exp(x: Double, y: Double, z: Double): DoubleArray {
        val a = sqrt(x * x + y * y + z * z)
        if (a < 1e-12) return doubleArrayOf(1.0, -z, y, z, 1.0, -x, -y, x, 1.0)
        val kx = x / a
        val ky = y / a
        val kz = z / a
        val c = cos(a)
        val s = sin(a)
        val v = 1 - c
        return doubleArrayOf(
            c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v,
        )
    }

    /** Rotation angle of [r], in radians. */
    fun angle(r: DoubleArray): Double = acos(((r[0] + r[4] + r[8] - 1) / 2).coerceIn(-1.0, 1.0))

    /** Angle of the rotation between orientations [a] and [b], in radians. */
    fun angleBetween(a: DoubleArray, b: DoubleArray): Double = angle(mat3Mul(mat3Transpose(a), b))

    /**
     * Device axes to camera axes for a rear camera whose image must be turned [sensorOrientation]
     * degrees clockwise to stand upright (Camera2 SENSOR_ORIENTATION). Android's sensor axes are x
     * right, y up, z out of the screen in the natural (portrait) orientation; the rear camera looks
     * along −z. Returns C with v_camera = C · v_device.
     */
    fun cameraFromDevice(sensorOrientation: Int): DoubleArray {
        val t = sensorOrientation * PI / 180
        val c = cos(t)
        val s = sin(t)
        return doubleArrayOf(c, -s, 0.0, -s, -c, 0.0, 0.0, 0.0, -1.0)
    }

    /**
     * Relative pose of a camera between two device orientations (as from [GyroTrack]): R with
     * X_view = R · X_reference for a point fixed in the world, in camera coordinates.
     */
    fun relativeCamera(cameraFromDevice: DoubleArray, reference: DoubleArray, view: DoubleArray): DoubleArray =
        mat3Mul(cameraFromDevice, mat3Mul(mat3Transpose(view), mat3Mul(reference, mat3Transpose(cameraFromDevice))))

    /**
     * Tilt of the optical axis of [r] (as from [relativeCamera]) away from the reference axis:
     * (tilt angle, direction) in radians, the direction measured in the reference camera's image
     * from +x towards +y.
     */
    fun tilt(r: DoubleArray): Pair<Double, Double> {
        // The view's optical axis in reference camera coordinates is the third row of R.
        return acos(r[8].coerceIn(-1.0, 1.0)) to atan2(r[7], r[6])
    }
}

/**
 * Integrates gyroscope rates (rad/s about the device axes, Android `TYPE_GYROSCOPE`) into device
 * orientation, and keeps a history so the orientation at a frame's exposure can be looked up after
 * the frame arrives. Each orientation Q holds the device axes, in the coordinates of the device at
 * the first sample, as its columns: dQ/dt = Q·[ω]×.
 */
class GyroTrack(private val capacity: Int = 4096) {
    private val times = LongArray(capacity)
    private val rates = Array(capacity) { DoubleArray(3) }
    private val poses = Array(capacity) { DoubleArray(9) }
    private var count = 0
    private var head = 0 // index of the newest sample
    private var q = Rotations.IDENTITY
    private var last = Long.MIN_VALUE

    @Synchronized
    fun offer(timestampNs: Long, wx: Float, wy: Float, wz: Float) {
        if (count > 0 && timestampNs <= last) return
        if (count > 0) {
            // Rates hold between samples; a long gap (sensor paused) is not integrated across.
            val dt = ((timestampNs - last) / 1e9).coerceAtMost(MAX_STEP_S)
            q = mat3Mul(q, Rotations.exp(wx * dt, wy * dt, wz * dt))
        }
        head = (head + 1) % capacity
        times[head] = timestampNs
        rates[head][0] = wx.toDouble(); rates[head][1] = wy.toDouble(); rates[head][2] = wz.toDouble()
        q.copyInto(poses[head])
        if (count < capacity) count++
        last = timestampNs
    }

    /** The newest orientation, or null before the first sample. */
    @Synchronized
    fun latest(): DoubleArray? = if (count == 0) null else poses[head].copyOf()

    @Synchronized
    fun latestTimeNs(): Long? = if (count == 0) null else times[head]

    /**
     * Orientation at [timestampNs], integrating forward from the sample before it with the rate of
     * the sample after it; clamped to the oldest and newest samples. Null before the first sample.
     */
    @Synchronized
    fun at(timestampNs: Long): DoubleArray? {
        if (count == 0) return null
        var i = head
        var back = 0
        while (back < count - 1 && times[i] > timestampNs) {
            i = (i - 1 + capacity) % capacity
            back++
        }
        if (times[i] > timestampNs || i == head) return poses[i].copyOf()
        val next = (i + 1) % capacity
        val dt = (timestampNs - times[i]) / 1e9
        val r = rates[next]
        return mat3Mul(poses[i], Rotations.exp(r[0] * dt, r[1] * dt, r[2] * dt))
    }

    private companion object {
        const val MAX_STEP_S = 0.05
    }
}
