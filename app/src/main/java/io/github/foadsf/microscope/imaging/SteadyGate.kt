package io.github.foadsf.microscope.imaging

import kotlin.math.sqrt

/**
 * Decides from gyroscope samples when the phone has settled enough to expose: the angular speed
 * must stay below [thresholdRadS] for [holdNs]. Pressing the shutter jolts the phone; this waits
 * for that to die away instead of capturing it.
 *
 * Rotation moves the image by about ω·t·f / pixel pitch pixels during an exposure t, whatever the
 * working distance, so the threshold follows from a blur budget ([threshold]).
 */
class SteadyGate(private val thresholdRadS: Float, private val holdNs: Long) {
    private var calmSince = -1L

    /** Feeds one sample (timestamp in ns, rad/s about each axis); true once steady. */
    fun offer(timeNs: Long, wx: Float, wy: Float, wz: Float): Boolean {
        if (sqrt(wx * wx + wy * wy + wz * wz) >= thresholdRadS) {
            calmSince = -1L
            return false
        }
        if (calmSince < 0L) calmSince = timeNs
        return timeNs - calmSince >= holdNs
    }

    companion object {
        /** Image motion allowed during one exposure. */
        const val BLUR_PX = 1.5f

        /**
         * Never demand less motion than this (rad/s, ≈ 1.7°/s): a stricter gate would rarely open
         * for a handheld phone, and the burst merge wants some hand tremor anyway.
         */
        const val MIN_RAD_S = 0.03f
        const val MAX_RAD_S = 0.15f

        /** Angular speed that moves the image by [BLUR_PX] during [exposureNs]. */
        fun threshold(pixelPitchUm: Float, focalMm: Float, exposureNs: Long): Float {
            val t = exposureNs.coerceAtLeast(1_000_000L) / 1e9f
            return (BLUR_PX * pixelPitchUm * 1e-3f / (focalMm * t)).coerceIn(MIN_RAD_S, MAX_RAD_S)
        }
    }
}
