package io.github.foadsf.microscope.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Coarse-to-fine image registration with a similarity model (scale, rotation, translation).
 *
 * The coarsest pyramid level gets an exhaustive integer translation search, which gives a large
 * capture range. Every level from there down is refined with inverse-compositional Lucas-Kanade
 * (Baker & Matthews 2004, "Lucas-Kanade 20 Years On"), with a Huber weight against outliers and a
 * brightness offset so small exposure differences do not bias the fit. The scale term absorbs
 * focus breathing in focus brackets; the rotation term absorbs hand roll in handheld bursts.
 */
object Aligner {
    private const val MIN_SIDE = 48
    private const val SEARCH_RADIUS = 6
    private const val MAX_SAMPLES = 30_000 // ample for 4 parameters; error falls as 1/√N
    private const val HUBER_K = 12.0

    fun pyramid(luma: Plane): List<Plane> = gaussianPyramid(luma, maxLevels = 10, minSide = MIN_SIDE)

    /**
     * Returns W such that moving(W(x)) ≈ reference(x), with x in level-0 reference pixels.
     * [finestLevel] > 0 stops refining early (cheaper, accuracy ≈ 2^finestLevel / 10 px).
     */
    fun align(reference: List<Plane>, moving: List<Plane>, init: Affine = Affine.IDENTITY, finestLevel: Int = 0): Affine {
        val top = minOf(reference.size, moving.size) - 1
        val finest = finestLevel.coerceIn(0, top)
        var w = coarseSearch(reference[top], moving[top], init.atLevel(top))
        for (level in top downTo finest) {
            w = refine(reference[level], moving[level], w, if (level == top) 30 else 10)
            if (level > finest) w = w.fromLevel(1)
        }
        return w.fromLevel(finest)
    }

    private fun coarseSearch(t: Plane, img: Plane, init: Affine): Affine {
        val stride = max(1, sqrt(t.width * t.height / 4000.0).toInt())
        var best = init
        var bestCost = Double.MAX_VALUE
        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                val cand = Affine.translation(dx.toDouble(), dy.toDouble()).compose(init)
                var sum = 0.0
                var n = 0
                var y = 0
                while (y < t.height) {
                    var x = 0
                    while (x < t.width) {
                        val u = cand.mapX(x.toDouble(), y.toDouble())
                        val v = cand.mapY(x.toDouble(), y.toDouble())
                        if (u >= 0 && v >= 0 && u <= img.width - 1 && v <= img.height - 1) {
                            sum += abs(img.sample(u.toFloat(), v.toFloat()) - t[x, y])
                            n++
                        }
                        x += stride
                    }
                    y += stride
                }
                // Require a reasonable overlap so a tiny corner match cannot win.
                if (n > 0 && n * stride * stride > t.width * t.height / 3) {
                    val cost = sum / n
                    if (cost < bestCost) {
                        bestCost = cost
                        best = cand
                    }
                }
            }
        }
        return best
    }

    /** One pyramid level of inverse-compositional Lucas-Kanade. */
    private fun refine(t: Plane, img: Plane, start: Affine, iterations: Int): Affine {
        val w = t.width
        val h = t.height
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val stride = max(1, sqrt(w.toDouble() * h / MAX_SAMPLES).toInt())

        // Steepest-descent images (∇T · ∂W/∂p at p = 0) on a sample grid of textured pixels.
        var xs = IntArray(0)
        var ys = IntArray(0)
        var tv = FloatArray(0)
        var sd = FloatArray(0)
        for (threshold in floatArrayOf(1.0f, 0f)) {
            val lx = ArrayList<Int>()
            val ly = ArrayList<Int>()
            var y = 2
            while (y < h - 2) {
                var x = 2
                while (x < w - 2) {
                    val gx = 0.5f * (t[x + 1, y] - t[x - 1, y])
                    val gy = 0.5f * (t[x, y + 1] - t[x, y - 1])
                    if (gx * gx + gy * gy > threshold * threshold) {
                        lx.add(x)
                        ly.add(y)
                    }
                    x += stride
                }
                y += stride
            }
            if (lx.size >= 500 || threshold == 0f) {
                xs = lx.toIntArray()
                ys = ly.toIntArray()
                break
            }
        }
        val n = xs.size
        if (n < 32) return start
        tv = FloatArray(n)
        sd = FloatArray(4 * n)
        for (i in 0 until n) {
            val x = xs[i]
            val y = ys[i]
            val gx = 0.5f * (t[x + 1, y] - t[x - 1, y])
            val gy = 0.5f * (t[x, y + 1] - t[x, y - 1])
            val xc = (x - cx).toFloat()
            val yc = (y - cy).toFloat()
            tv[i] = t[x, y]
            sd[4 * i] = gx * xc + gy * yc
            sd[4 * i + 1] = -gx * yc + gy * xc
            sd[4 * i + 2] = gx
            sd[4 * i + 3] = gy
        }

        var warp = start
        var bias = 0.0
        val hess = DoubleArray(16)
        val grad = DoubleArray(4)
        repeat(iterations) {
            hess.fill(0.0)
            grad.fill(0.0)
            var diffSum = 0.0
            var used = 0
            for (i in 0 until n) {
                val x = xs[i].toDouble()
                val y = ys[i].toDouble()
                val u = warp.mapX(x, y)
                val v = warp.mapY(x, y)
                if (u < 0 || v < 0 || u > img.width - 1 || v > img.height - 1) continue
                val iv = img.sample(u.toFloat(), v.toFloat())
                val diff = (iv - tv[i]).toDouble()
                val e = diff - bias
                val rw = if (abs(e) <= HUBER_K) 1.0 else HUBER_K / abs(e)
                val o = 4 * i
                val s0 = sd[o].toDouble()
                val s1 = sd[o + 1].toDouble()
                val s2 = sd[o + 2].toDouble()
                val s3 = sd[o + 3].toDouble()
                val w0 = s0 * rw
                val w1 = s1 * rw
                val w2 = s2 * rw
                val w3 = s3 * rw
                grad[0] += w0 * e; grad[1] += w1 * e; grad[2] += w2 * e; grad[3] += w3 * e
                hess[0] += w0 * s0; hess[1] += w0 * s1; hess[2] += w0 * s2; hess[3] += w0 * s3
                hess[5] += w1 * s1; hess[6] += w1 * s2; hess[7] += w1 * s3
                hess[10] += w2 * s2; hess[11] += w2 * s3
                hess[15] += w3 * s3
                diffSum += diff
                used++
            }
            if (used < 32) return warp
            bias = diffSum / used
            for (r in 0 until 4) for (c in 0 until r) hess[r * 4 + c] = hess[c * 4 + r]
            val trace = hess[0] + hess[5] + hess[10] + hess[15]
            for (r in 0 until 4) hess[r * 5] += 1e-9 * trace + 1e-12
            val dp = solve4(hess, grad) ?: return warp
            warp = warp.compose(Affine.similarity(dp, cx, cy).inverse())
            if (abs(dp[2]) < 5e-3 && abs(dp[3]) < 5e-3 && abs(dp[0]) < 1e-5 && abs(dp[1]) < 1e-5) return warp
        }
        return warp
    }

    /** Solves the 4x4 system m * x = b with partial pivoting; null if singular. */
    internal fun solve4(m: DoubleArray, b: DoubleArray): DoubleArray? {
        val a = Array(4) { r -> DoubleArray(5) { c -> if (c < 4) m[r * 4 + c] else b[r] } }
        for (col in 0 until 4) {
            var piv = col
            for (r in col + 1 until 4) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-20) return null
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            for (r in col + 1 until 4) {
                val k = a[r][col] / a[col][col]
                for (c in col until 5) a[r][c] -= k * a[col][c]
            }
        }
        val x = DoubleArray(4)
        for (r in 3 downTo 0) {
            var s = a[r][4]
            for (c in r + 1 until 4) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }
}
