package io.github.foadsf.microscope.imaging

import kotlin.math.ln

/**
 * Surface heights on a regular grid, in mm, NaN where unknown. One grid cell covers [step] × [step]
 * pixels of the reference frame. [index] is what each height was read from: the fractional bracket
 * slice for [DepthFromFocus], γ = height / depth for [PlaneParallax].
 */
class HeightMap(val width: Int, val height: Int, val step: Int, val mm: FloatArray, val index: FloatArray) {
    init {
        require(mm.size == width * height && index.size == mm.size) { "height map size" }
    }

    val validCount: Int get() = mm.count { !it.isNaN() }

    /** Highest valid height (the lowest is 0 by construction), or null if nothing is valid. */
    val maxMm: Float?
        get() {
            var top = Float.NaN
            for (v in mm) if (!v.isNaN() && (top.isNaN() || v > top)) top = v
            return if (top.isNaN()) null else top
        }

    /** Rotates clockwise by a multiple of 90 degrees, like [RgbImage.rotated]. */
    fun rotated(degrees: Int): HeightMap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return this
        val (w2, h2) = if (d == 180) width to height else height to width
        val outMm = FloatArray(mm.size)
        val outIdx = FloatArray(mm.size)
        for (y in 0 until height) for (x in 0 until width) {
            val i = when (d) {
                90 -> x * w2 + (w2 - 1 - y)
                180 -> (h2 - 1 - y) * w2 + (w2 - 1 - x)
                else -> (h2 - 1 - x) * w2 + y
            }
            outMm[i] = mm[y * width + x]
            outIdx[i] = index[y * width + x]
        }
        return HeightMap(w2, h2, step, outMm, outIdx)
    }
}

/**
 * Surface topography from a focus bracket by focus variation, also called shape from focus (Nayar &
 * Nakagawa 1994, "Shape from Focus"; standardised for areal surface measurement as ISO 25178-606).
 *
 * Every slice is resampled onto the reference frame with the bracket's registration, so focus
 * breathing does not shift features between slices. Its focus measure is the energy of the modified
 * Laplacian, pooled over a window. Each pixel's focus curve across the slices peaks where that part
 * of the surface was in focus. The peak is located to a fraction of a slice by fitting a Gaussian
 * through the best slice and its two neighbours. The fractional slice gives a focus distance, since
 * the bracket is evenly spaced in diopters, and that gives the height.
 *
 * A height is reported only where the curve really peaks: away from both ends of the bracket, well
 * above the curve's mean (by more than pooled sensor noise can fluctuate), and falling steadily for
 * two slices on either side, as defocus does. Flat, untextured or saturated areas have no focus curve to read, and
 * are left unknown rather than guessed.
 */
object DepthFromFocus {
    /** Peak focus measure over the curve's mean below which a pixel has no usable focus curve. */
    const val MIN_CONTRAST = 2f

    /**
     * Binomial blurs applied to the pooled focus measure (each adds variance 1 cell²). Pooled noise
     * energy fluctuates by about 1/√(cells in the window), so a wider window keeps noise-only curves
     * below [MIN_CONTRAST]; three passes (σ ≈ 1.7 cells) is the narrowest that does in the tests.
     */
    private const val POOL_PASSES = 3
    private const val EPS = 1e-6f

    /**
     * [warps] map reference pixel coordinates to each frame's coordinates (as from
     * [Pipeline.alignChained]). [diopters] is each frame's focus setting. The grid is the reference
     * frame reduced by powers of two until it has at most [maxCells] cells.
     */
    fun estimate(
        frames: List<YuvFrame>,
        warps: List<Affine>,
        diopters: FloatArray,
        maxCells: Int = 600_000,
        progress: (Float) -> Unit = {},
    ): HeightMap {
        val n = frames.size
        require(n >= 3 && warps.size == n && diopters.size == n) { "need at least 3 registered slices with focus settings" }
        val w = frames[0].width
        val h = frames[0].height
        var level = 0
        while (((w + (1 shl level) - 1) shr level).toLong() * ((h + (1 shl level) - 1) shr level) > maxCells) level++

        val warped = Plane(w, h)
        var gw = 0
        var gh = 0
        lateinit var best: FloatArray
        lateinit var before: FloatArray
        lateinit var after: FloatArray
        // The curve two slices either side of the peak; −1 where that slice does not exist.
        lateinit var before2: FloatArray
        lateinit var after2: FloatArray
        lateinit var sum: FloatArray
        lateinit var bestIdx: IntArray
        var prev: FloatArray? = null
        var prev2: FloatArray? = null

        for (k in 0 until n) {
            val frame = frames[k]
            val m = warps[k]
            parallelFor(h) { y ->
                val row = y * w
                for (x in 0 until w) {
                    warped.data[row + x] = bilinearU8(
                        frame.y, frame.width, frame.height,
                        m.mapX(x.toDouble(), y.toDouble()).toFloat(), m.mapY(x.toDouble(), y.toDouble()).toFloat(),
                    )
                }
            }
            // Pool the focus measure down to the grid, then over a window of a few cells.
            var fm = modifiedLaplacianEnergy(warped)
            repeat(level) { fm = reduce(fm) }
            repeat(POOL_PASSES) { fm = blur(fm) }
            val f = fm.data
            if (k == 0) {
                gw = fm.width
                gh = fm.height
                best = FloatArray(f.size) { -1f }
                before = FloatArray(f.size)
                after = FloatArray(f.size)
                before2 = FloatArray(f.size)
                after2 = FloatArray(f.size) { -1f }
                sum = FloatArray(f.size)
                bestIdx = IntArray(f.size)
            }
            val p = prev
            val p2 = prev2
            for (i in f.indices) {
                val v = f[i]
                if (k > 0 && bestIdx[i] == k - 1) after[i] = v
                if (k > 1 && bestIdx[i] == k - 2) after2[i] = v
                if (v > best[i]) {
                    before[i] = if (p != null) p[i] else 0f
                    before2[i] = if (p2 != null) p2[i] else -1f
                    after2[i] = -1f
                    best[i] = v
                    bestIdx[i] = k
                }
                sum[i] += v
            }
            prev2 = p
            prev = f
            progress(0.9f * (k + 1) / n)
        }

        val mmDist = FloatArray(gw * gh) { Float.NaN }
        val index = FloatArray(gw * gh) { Float.NaN }
        for (i in mmDist.indices) {
            val k = bestIdx[i]
            if (k == 0 || k == n - 1) continue // the peak may lie beyond the bracket
            if (best[i] < MIN_CONTRAST * sum[i] / n) continue
            // Defocus only ever lowers the focus measure further from the peak. A curve that rises
            // again within two slices comes from something else (motion, misregistration, noise).
            if (before2[i] >= 0f && before2[i] > before[i]) continue
            if (after2[i] >= 0f && after2[i] > after[i]) continue
            val lb = ln(before[i] + EPS)
            val l0 = ln(best[i] + EPS)
            val la = ln(after[i] + EPS)
            val curvature = lb - 2f * l0 + la
            if (curvature >= 0f) continue
            val delta = (0.5f * (lb - la) / curvature).coerceIn(-0.5f, 0.5f)
            val pos = k + delta
            index[i] = pos
            val d = interpolate(diopters, pos)
            if (d > 0f) mmDist[i] = Optics.distanceMm(d)
        }

        // Heights above the farthest point that is not an outlier (the 99th percentile of distance).
        val valid = mmDist.finiteSorted()
        val heights = FloatArray(mmDist.size) { Float.NaN }
        if (valid.isNotEmpty()) {
            val base = valid[((valid.size - 1) * 0.99f).toInt()]
            for (i in heights.indices) {
                val v = mmDist[i]
                if (!v.isNaN()) heights[i] = (base - v).coerceAtLeast(0f)
            }
        }
        progress(1f)
        return HeightMap(gw, gh, 1 shl level, heights, index)
    }

    private fun interpolate(values: FloatArray, pos: Float): Float {
        val i0 = pos.toInt().coerceIn(0, values.size - 2)
        val t = pos - i0
        return values[i0] + (values[i0 + 1] - values[i0]) * t
    }

    /**
     * Squared modified Laplacian, |2I − I(x−1) − I(x+1)| + |2I − I(y−1) − I(y+1)| (Nayar), which,
     * unlike the plain Laplacian, cannot cancel where the two second derivatives have opposite signs.
     */
    internal fun modifiedLaplacianEnergy(p: Plane): Plane {
        val w = p.width
        val h = p.height
        val out = Plane(w, h)
        val d = p.data
        parallelFor(h) { y ->
            val ym = if (y > 0) y - 1 else 0
            val yp = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else 0
                val xp = if (x < w - 1) x + 1 else w - 1
                val c = 2f * d[y * w + x]
                val ml = kotlin.math.abs(c - d[y * w + xm] - d[y * w + xp]) + kotlin.math.abs(c - d[ym * w + x] - d[yp * w + x])
                out.data[y * w + x] = ml * ml
            }
        }
        return out
    }
}
