package io.github.foadsf.microscope.imaging

import kotlin.math.abs

/** 2-D affine map: x' = a*x + b*y + c, y' = d*x + e*y + f, in pixel coordinates. */
data class Affine(
    val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0,
    val d: Double = 0.0, val e: Double = 1.0, val f: Double = 0.0,
) {
    fun mapX(x: Double, y: Double) = a * x + b * y + c
    fun mapY(x: Double, y: Double) = d * x + e * y + f

    /** this ∘ other: apply [other] first, then this. */
    fun compose(other: Affine) = Affine(
        a * other.a + b * other.d, a * other.b + b * other.e, a * other.c + b * other.f + c,
        d * other.a + e * other.d, d * other.b + e * other.e, d * other.c + e * other.f + f,
    )

    fun inverse(): Affine {
        val det = a * e - b * d
        require(abs(det) > 1e-12) { "singular warp" }
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }

    /** The same map expressed in the coordinates of pyramid level [level] (pixel i ↔ i * 2^level). */
    fun atLevel(level: Int): Affine {
        val s = (1 shl level).toDouble()
        return copy(c = c / s, f = f / s)
    }

    /** Inverse of [atLevel]. */
    fun fromLevel(level: Int): Affine {
        val s = (1 shl level).toDouble()
        return copy(c = c * s, f = f * s)
    }

    companion object {
        val IDENTITY = Affine()

        fun translation(tx: Double, ty: Double) = Affine(c = tx, f = ty)

        /**
         * Similarity about centre (cx, cy): scale (1 + p0), rotation from p1, then translation (p2, p3).
         * This is the parameterisation the Lucas-Kanade solver works in.
         */
        fun similarity(p: DoubleArray, cx: Double, cy: Double): Affine {
            val s = 1.0 + p[0]
            val r = p[1]
            // x' = s*(x-cx) - r*(y-cy) + cx + tx
            return Affine(s, -r, -s * cx + r * cy + cx + p[2], r, s, -r * cx - s * cy + cy + p[3])
        }
    }
}
