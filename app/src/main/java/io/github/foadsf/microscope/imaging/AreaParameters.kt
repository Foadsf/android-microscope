package io.github.foadsf.microscope.imaging

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ISO 25178-2 areal height parameters of a height map, after removing the least-squares plane (the
 * F-operation), over the cells that have a height. Heights in mm, so Sa, Sq, Sp, Sv and Sz are in mm;
 * Ssk and Sku are dimensionless.
 *
 * No S- or L-filter is applied: these describe the measured relief at the map's own lateral and
 * height resolution, not roughness in the standard's filtered sense.
 */
data class AreaParameters(
    /** Arithmetical mean height. */
    val sa: Double,
    /** Root-mean-square height. */
    val sq: Double,
    /** Maximum peak height and maximum pit depth, and their sum (maximum height). */
    val sp: Double,
    val sv: Double,
    val sz: Double,
    /** Skewness and kurtosis of the height distribution. */
    val ssk: Double,
    val sku: Double,
    /** Cells used. */
    val count: Int,
) {
    companion object {
        /** Null when fewer than [minCells] cells have a height. */
        fun of(map: HeightMap, minCells: Int = 100): AreaParameters? {
            val w = map.width
            // Least-squares plane z = a + b·x + c·y over the valid cells (normal equations).
            var n = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            var sz = 0.0; var sxz = 0.0; var syz = 0.0
            for (i in map.mm.indices) {
                val z = map.mm[i]
                if (z.isNaN()) continue
                val x = (i % w).toDouble()
                val y = (i / w).toDouble()
                n += 1; sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y
                sz += z; sxz += x * z; syz += y * z
            }
            if (n < minCells) return null
            val m = doubleArrayOf(n, sx, sy, sx, sxx, sxy, sy, sxy, syy)
            val (a, b, c) = solve3(m, doubleArrayOf(sz, sxz, syz)) ?: return null
            // Moments of the residual about its mean (zero for a least-squares plane, up to rounding).
            var s1 = 0.0
            for (i in map.mm.indices) {
                val z = map.mm[i]
                if (!z.isNaN()) s1 += z - (a + b * (i % w) + c * (i / w))
            }
            val mean = s1 / n
            var sAbs = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
            var peak = Double.NEGATIVE_INFINITY; var pit = Double.POSITIVE_INFINITY
            for (i in map.mm.indices) {
                val z = map.mm[i]
                if (z.isNaN()) continue
                val r = z - (a + b * (i % w) + c * (i / w)) - mean
                sAbs += abs(r); s2 += r * r; s3 += r * r * r; s4 += r * r * r * r
                if (r > peak) peak = r
                if (r < pit) pit = r
            }
            val sq = sqrt(s2 / n)
            return AreaParameters(
                sa = sAbs / n, sq = sq, sp = peak, sv = -pit, sz = peak - pit,
                ssk = if (sq > 0) s3 / n / (sq * sq * sq) else 0.0,
                sku = if (sq > 0) s4 / n / (sq * sq * sq * sq) else 0.0,
                count = n.toInt(),
            )
        }

        /** Solves the 3 × 3 system m · x = b by Cramer's rule; null if singular. */
        private fun solve3(m: DoubleArray, b: DoubleArray): DoubleArray? {
            fun det(a: DoubleArray) = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
            val d = det(m)
            if (abs(d) < 1e-12 * (abs(m[0]) * abs(m[4]) * abs(m[8]) + 1e-30)) return null
            return DoubleArray(3) { col ->
                val mc = m.copyOf()
                for (r in 0 until 3) mc[r * 3 + col] = b[r]
                det(mc) / d
            }
        }
    }
}
