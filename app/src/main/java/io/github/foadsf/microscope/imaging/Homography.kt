package io.github.foadsf.microscope.imaging

import kotlin.math.abs

/**
 * 2-D projective map in pixel coordinates, the row-major 3 × 3 matrix [h]:
 * x' = (h0·x + h1·y + h2) / (h6·x + h7·y + h8), y' = (h3·x + h4·y + h5) / (h6·x + h7·y + h8).
 * A homography maps every point of a plane between two pinhole views; the overall scale of [h] is
 * irrelevant to the mapping but matters to [PlaneParallax], which reads metric pose out of it.
 */
class Homography(val h: DoubleArray) {
    init {
        require(h.size == 9) { "3 × 3 matrix" }
    }

    fun mapX(x: Double, y: Double) = (h[0] * x + h[1] * y + h[2]) / (h[6] * x + h[7] * y + h[8])
    fun mapY(x: Double, y: Double) = (h[3] * x + h[4] * y + h[5]) / (h[6] * x + h[7] * y + h[8])

    /** this ∘ other: apply [other] first, then this. */
    fun compose(other: Homography) = Homography(mat3Mul(h, other.h))

    fun inverse() = Homography(mat3Inverse(h))

    /** The same map scaled so that h8 = 1. */
    fun normalized(): Homography {
        require(abs(h[8]) > 1e-12) { "h8 is zero" }
        return Homography(DoubleArray(9) { h[it] / h[8] })
    }

    /** The same map expressed in the coordinates of pyramid level [level] (pixel i ↔ i * 2^level). */
    fun atLevel(level: Int): Homography {
        val s = (1 shl level).toDouble()
        return Homography(doubleArrayOf(h[0], h[1], h[2] / s, h[3], h[4], h[5] / s, h[6] * s, h[7] * s, h[8]))
    }

    /** Inverse of [atLevel]. */
    fun fromLevel(level: Int): Homography {
        val s = (1 shl level).toDouble()
        return Homography(doubleArrayOf(h[0], h[1], h[2] * s, h[3], h[4], h[5] * s, h[6] / s, h[7] / s, h[8]))
    }

    companion object {
        val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun of(a: Affine) = Homography(doubleArrayOf(a.a, a.b, a.c, a.d, a.e, a.f, 0.0, 0.0, 1.0))

        /**
         * The eight-parameter warp the Lucas-Kanade solver works in (Baker & Matthews 2004, §2.1),
         * in coordinates normalised about (cx, cy) by [s]: H = [[1 + p0, p2, p4], [p1, 1 + p3, p5],
         * [p6, p7, 1]], returned in pixel coordinates.
         */
        fun normalisedWarp(p: DoubleArray, cx: Double, cy: Double, s: Double): Homography {
            val n = doubleArrayOf(1 / s, 0.0, -cx / s, 0.0, 1 / s, -cy / s, 0.0, 0.0, 1.0)
            val nInv = doubleArrayOf(s, 0.0, cx, 0.0, s, cy, 0.0, 0.0, 1.0)
            val hn = doubleArrayOf(1 + p[0], p[2], p[4], p[1], 1 + p[3], p[5], p[6], p[7], 1.0)
            return Homography(mat3Mul(nInv, mat3Mul(hn, n)))
        }
    }
}

// Small dense linear algebra for 3 × 3 geometry. Matrices are row-major DoubleArrays of 9.

internal fun mat3Mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
    val r = i / 3
    val c = i % 3
    a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
}

internal fun mat3Transpose(a: DoubleArray) = DoubleArray(9) { i -> a[(i % 3) * 3 + i / 3] }

internal fun mat3Inverse(a: DoubleArray): DoubleArray {
    val c00 = a[4] * a[8] - a[5] * a[7]
    val c01 = a[5] * a[6] - a[3] * a[8]
    val c02 = a[3] * a[7] - a[4] * a[6]
    val det = a[0] * c00 + a[1] * c01 + a[2] * c02
    require(abs(det) > 1e-300) { "singular matrix" }
    val k = 1 / det
    return doubleArrayOf(
        c00 * k, (a[2] * a[7] - a[1] * a[8]) * k, (a[1] * a[5] - a[2] * a[4]) * k,
        c01 * k, (a[0] * a[8] - a[2] * a[6]) * k, (a[2] * a[3] - a[0] * a[5]) * k,
        c02 * k, (a[1] * a[6] - a[0] * a[7]) * k, (a[0] * a[4] - a[1] * a[3]) * k,
    )
}

/** Solves the n × n system m * x = b (row-major [m]) with partial pivoting; null if singular. */
internal fun solveLinear(m: DoubleArray, b: DoubleArray, n: Int): DoubleArray? {
    val a = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) m[r * n + c] else b[r] } }
    for (col in 0 until n) {
        var piv = col
        for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
        if (abs(a[piv][col]) < 1e-20) return null
        val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
        for (r in col + 1 until n) {
            val k = a[r][col] / a[col][col]
            for (c in col until n + 1) a[r][c] -= k * a[col][c]
        }
    }
    val x = DoubleArray(n)
    for (r in n - 1 downTo 0) {
        var s = a[r][n]
        for (c in r + 1 until n) s -= a[r][c] * x[c]
        x[r] = s / a[r][r]
    }
    return x
}
