package io.github.foadsf.microscope.imaging

import kotlin.math.PI
import kotlin.math.floor

/**
 * Which tilted views a tilt series still needs: [bins] equal sectors of tilt direction around a
 * ring of tilt angles from [minDeg] to [maxDeg]. Views spread around the ring give parallax in
 * every direction, so texture running any way can be matched; the ring keeps every view tilted
 * enough for parallax ([PlaneParallax.MIN_BASELINE] ≈ 1.1°) yet little enough that the specimen
 * stays in focus and looks alike from every view.
 */
class TiltGuide(val bins: Int, val minDeg: Double = 3.0, val maxDeg: Double = 8.0) {
    private val filled = BooleanArray(bins)

    val captured: Int get() = filled.count { it }
    val complete: Boolean get() = filled.all { it }

    fun isFilled(bin: Int) = filled[bin]

    /** The sector a tilt of [tiltRad] towards [directionRad] falls in, or null outside the ring. */
    fun bin(tiltRad: Double, directionRad: Double): Int? {
        val deg = Math.toDegrees(tiltRad)
        if (deg < minDeg || deg > maxDeg) return null
        val turn = ((directionRad % (2 * PI)) + 2 * PI) % (2 * PI)
        return floor(turn / (2 * PI) * bins).toInt().coerceIn(0, bins - 1)
    }

    /** The sector to capture at this tilt, or null if it is outside the ring or already captured. */
    fun wanted(tiltRad: Double, directionRad: Double): Int? = bin(tiltRad, directionRad)?.takeUnless { filled[it] }

    fun fill(bin: Int) {
        filled[bin] = true
    }
}
