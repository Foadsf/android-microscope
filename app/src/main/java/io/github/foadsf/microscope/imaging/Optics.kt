package io.github.foadsf.microscope.imaging

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Thin-lens bookkeeping for a phone camera used close up. All of it is first-order: Camera2 reports
 * focus distance from the front of the lens, not from the principal plane, and many HALs only
 * report APPROXIMATE distances. Everything derived here is shown to the user with a "≈".
 */
object Optics {
    /** Object distance in mm for a Camera2 focus distance in diopters (0 means infinity). */
    fun distanceMm(diopters: Float): Float = if (diopters <= 1e-3f) Float.POSITIVE_INFINITY else 1000f / diopters

    /** Lateral magnification of a thin lens of focal length [focalMm] focused at [distanceMm]. */
    fun magnification(focalMm: Float, distanceMm: Float): Float =
        if (distanceMm.isInfinite() || distanceMm <= focalMm) 0f else focalMm / (distanceMm - focalMm)

    /** Size on the specimen, in µm, of one sensor pixel of pitch [pixelPitchUm]. */
    fun objectPixelUm(pixelPitchUm: Float, focalMm: Float, distanceMm: Float): Float {
        val m = magnification(focalMm, distanceMm)
        return if (m <= 0f) Float.POSITIVE_INFINITY else pixelPitchUm / m
    }

    /**
     * Total depth of field expressed in diopters, valid well inside the hyperfocal distance:
     * ΔD ≈ 2·N·c / f². Conveniently it does not depend on the focus distance, so a focus bracket
     * can step uniformly in diopters. [cocUm] is the acceptable circle of confusion.
     */
    fun depthOfFieldDiopters(fNumber: Float, cocUm: Float, focalMm: Float): Float {
        val c = cocUm * 1e-6f
        val f = focalMm * 1e-3f
        return 2f * fNumber * c / (f * f)
    }

    /**
     * Focus distances (diopters) for a bracket of [count] frames centred on [centre], spaced by
     * [step], clamped to the lens range [0, maxDiopters]. Ordered near to far.
     */
    fun bracket(centre: Float, count: Int, step: Float, maxDiopters: Float): FloatArray {
        val half = (count - 1) / 2f
        var lo = centre - half * step
        var hi = centre + half * step
        // Slide the window back inside the lens range instead of piling frames up at a limit.
        if (hi > maxDiopters) { lo -= hi - maxDiopters; hi = maxDiopters }
        if (lo < 0f) { hi = (hi - lo).coerceAtMost(maxDiopters); lo = 0f }
        val span = hi - lo
        return FloatArray(count) { i -> if (count == 1) centre.coerceIn(0f, maxDiopters) else hi - span * i / (count - 1) }
    }

    /** Largest 1-2-5 value not above [x] (x > 0). */
    fun niceFloor(x: Float): Float {
        val e = floor(log10(x.toDouble()))
        val base = 10.0.pow(e)
        val m = x / base
        val n = when {
            m >= 5 -> 5.0
            m >= 2 -> 2.0
            else -> 1.0
        }
        return (n * base).toFloat()
    }

    /** [x] rounded to two significant digits, for readouts that should not look over-precise. */
    fun niceRound(x: Float): Float {
        if (x <= 0f || !x.isFinite()) return x
        val base = 10.0.pow(floor(log10(x.toDouble())) - 1)
        return (kotlin.math.round(x / base) * base).toFloat()
    }

    /** "500 µm", "1 mm", "2.5 cm"-style label for a length in µm. */
    fun formatLength(um: Float): String = when {
        um >= 10_000f -> "${trim(um / 10_000f)} cm"
        um >= 1000f -> "${trim(um / 1000f)} mm"
        else -> "${trim(um)} µm"
    }

    /** A measured length with three significant digits: "84.2 µm", "512 µm", "1.27 mm", "12.4 mm". */
    fun formatMeasure(um: Float): String = when {
        um >= 10_000f -> "%.1f mm".format(Locale.ROOT, um / 1000f)
        um >= 1000f -> "%.2f mm".format(Locale.ROOT, um / 1000f)
        um >= 100f -> "%.0f µm".format(Locale.ROOT, um)
        else -> "%.1f µm".format(Locale.ROOT, um)
    }

    private fun trim(v: Float): String {
        val r = (v * 10f).let { kotlin.math.round(it) / 10f }
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }
}
