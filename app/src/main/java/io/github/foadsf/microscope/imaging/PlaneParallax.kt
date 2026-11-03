package io.github.foadsf.microscope.imaging

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Surface topography from a few hand-tilted views of the specimen by **plane-plus-parallax**
 * (Kumar, Anandan & Hanna 1994; Irani & Anandan 1996).
 *
 * Every view k sees the specimen's mean plane through a homography, and every point off that plane
 * through the same homography plus a parallax along the view's epipolar direction:
 *
 *     x_k ≃ H_k·x + γ·K·t_k/d,   H_k = K·(R_k + (t_k/d)·nᵀ)·K⁻¹,   γ = height / depth,
 *
 * where K holds the intrinsics, R_k and t_k the view's rotation and translation, and n and d the
 * plane's normal and distance in the reference camera. The steps:
 *
 * 1. Register each view to the reference with an eight-parameter homography ([Aligner.alignHomography]).
 * 2. Take R_k from the gyroscope and the plane normal as the reference optical axis. Then
 *    K⁻¹·H_k·K = λ·(R_k + t'·nᵀ) is linear in the unknown scale λ and in t' = t_k/d: the first two
 *    columns give λ, the third gives t'. No decomposition, so none of its twofold ambiguity. How far
 *    the first two columns miss a rotation measures how well gyro and image agree.
 * 3. Find γ per grid cell by multi-baseline matching (Okutomi & Kanade 1993): one γ predicts the
 *    position in every view at once, and the cost over γ is the views' mean (1 − ZNCC) over a
 *    window. Coarse to fine over a pyramid: a full search at the top, a narrow one below, each
 *    refined to a fraction of a step by a parabola.
 * 4. The phone's LED moves with it, so glare moves between views. Saturated pixels (and a margin
 *    around them) are left out of every match, and at each γ the worst third of the views (at
 *    least one) is dropped (Kang, Szeliski & Chai 2001), so a highlight in one or two views does
 *    not pull the match.
 * 5. Heights: h = γ·d / (1 + γ) for the plane at the working distance d, reported above the lowest
 *    point (1st percentile), like [DepthFromFocus].
 *
 * Heights are relative to the plane the registration found, which is the specimen's dominant plane:
 * the specimen's tilt to the camera is removed. Cells without texture, without a clear match, or
 * disagreeing with their neighbours are left unknown.
 */
object PlaneParallax {
    /** Lateral baseline |t_xy| / d below which a view has too little parallax to use (≈ 1.1° of tilt). */
    const val MIN_BASELINE = 0.02

    /** Largest disagreement between gyro and image (radians, per column) for a view to be trusted. */
    const val MAX_GYRO_MISMATCH = 0.02

    /**
     * Grey-level standard deviation below which a window is too flat for ZNCC to mean anything.
     * It only screens out blank areas: whether texture stands above noise is decided by the match
     * itself, since noise does not correlate between views.
     */
    private const val MIN_TEXTURE = 1.0

    /** Mean ZNCC of the kept views below which a cell has no clear match. */
    const val MIN_ZNCC = 0.6f

    private const val SATURATED = 250
    private const val GLARE_MARGIN = 2
    private const val MATCH_PIXELS = 4_000_000
    private const val WINDOW = 3
    private const val TOP_PARALLAX_PX = 4.0
    private const val NARROW_STEPS = 3

    class ViewReport(
        val tiltDeg: Double,
        /** |t_xy| / d: the sine of the tilt about the specimen, roughly. */
        val baseline: Double,
        val gyroMismatchDeg: Double,
        val used: Boolean,
        val reason: String?,
    )

    class Result(val height: HeightMap, val views: List<ViewReport>)

    /** A view registered and reduced to what the matcher needs, at pyramid level `level`. */
    private class ViewGeometry(val h: DoubleArray, val e: DoubleArray)

    /**
     * [frames] are the reference (index 0) and the tilted views; [rotations] are each view's
     * camera rotation from the reference (identity first), as [Rotations.relativeCamera] gives them.
     * [k] are the frames' intrinsics, [distanceMm] the working distance, [rangeMm] the largest
     * height difference searched for either side of the plane. The grid is the reference frame
     * reduced by powers of two until it has at most [maxCells] cells. [rejectGlare] = false turns
     * off the glare mask and the view trimming (only to show what they do).
     */
    fun estimate(
        frames: List<YuvFrame>,
        rotations: List<DoubleArray>,
        k: Intrinsics,
        distanceMm: Double,
        rangeMm: Double = 3.0,
        maxCells: Int = 250_000,
        rejectGlare: Boolean = true,
        progress: (Float) -> Unit = {},
    ): Result {
        require(frames.size >= 2 && rotations.size == frames.size) { "need a reference and tilted views with rotations" }
        val w = frames[0].width
        val h = frames[0].height
        // Match at full resolution up to MATCH_PIXELS, else one level down (memory and time).
        var finest = 0
        while ((w.toLong() * h shr (2 * finest)) > MATCH_PIXELS) finest++

        // 1. Pyramids from the matching level up, and each view's homography.
        val refPyr = Aligner.pyramid(frames[0].lumaPlane()).drop(finest)
        val done = AtomicInteger()
        val registered = Pipeline.concurrently(frames.size - 1, frames[0]) { i ->
            val pyr = Aligner.pyramid(frames[i + 1].lumaPlane()).drop(finest)
            val hm = Aligner.alignHomography(refPyr, pyr).fromLevel(finest)
            progress(0.4f * done.incrementAndGet() / (frames.size - 1))
            pyr to hm
        }

        // 2. Pose from homography and gyro.
        val kk = k.matrix
        // Angle from the optical axis to the farthest frame corner (as a tangent).
        val reach = sqrt(max(k.cx, w - 1 - k.cx).let { it * it } + max(k.cy, h - 1 - k.cy).let { it * it }) / k.f
        val ki = k.inverse
        val reports = ArrayList<ViewReport>()
        val usable = ArrayList<Int>() // indices into registered
        val geometry = ArrayList<ViewGeometry>()
        for ((i, reg) in registered.withIndex()) {
            val r = rotations[i + 1]
            val hm = reg.second.h
            val m = mat3Mul(ki, mat3Mul(hm, kk))
            // The first two rows of the first two columns are the frame's affine part, which the
            // registration pins down well; they set λ. The third row is the perspective part, which
            // a narrow field of view hardly constrains: it counts in the mismatch only as much as it
            // moves the frame's corners.
            var dot = 0.0
            var norm = 0.0
            for (row in 0 until 2) for (col in 0 until 2) {
                dot += m[3 * row + col] * r[3 * row + col]
                norm += m[3 * row + col] * m[3 * row + col]
            }
            val mu = dot / norm
            var miss = 0.0
            for (row in 0 until 3) for (col in 0 until 2) {
                val d = (mu * m[3 * row + col] - r[3 * row + col]) * (if (row == 2) reach else 1.0)
                miss += d * d
            }
            val mismatch = sqrt(miss / 2)
            val t = DoubleArray(3) { mu * m[3 * it + 2] - r[3 * it + 2] }
            val baseline = sqrt(t[0] * t[0] + t[1] * t[1])
            val reason = when {
                mismatch > MAX_GYRO_MISMATCH -> "gyro and image disagree"
                baseline < MIN_BASELINE -> "too little parallax"
                else -> null
            }
            reports.add(ViewReport(Math.toDegrees(Rotations.angle(r)), baseline, Math.toDegrees(mismatch), reason == null, reason))
            if (reason == null) {
                usable.add(i)
                val e = doubleArrayOf(k.f * t[0] + k.cx * t[2], k.f * t[1] + k.cy * t[2], t[2])
                geometry.add(ViewGeometry(DoubleArray(9) { mu * hm[it] }, e))
            }
        }

        // Output grid: cell (gx, gy) covers reference pixels [gx·s, (gx + 1)·s).
        var gridLevel = finest
        while (((w + (1 shl gridLevel) - 1) shr gridLevel).toLong() * ((h + (1 shl gridLevel) - 1) shr gridLevel) > maxCells) gridLevel++
        val s = 1 shl gridLevel
        val gw = (w + s - 1) / s
        val gh = (h + s - 1) / s
        if (usable.size < 2) {
            progress(1f)
            return Result(HeightMap(gw, gh, s, FloatArray(gw * gh) { Float.NaN }, FloatArray(gw * gh) { Float.NaN }), reports)
        }

        // Parallax in level-0 pixels per unit γ at the image centre, for the widest-baseline view.
        var perGamma = 0.0
        for (g in geometry) {
            val px = g.h[0] * k.cx + g.h[1] * k.cy + g.h[2]
            val py = g.h[3] * k.cx + g.h[4] * k.cy + g.h[5]
            val pz = g.h[6] * k.cx + g.h[7] * k.cy + g.h[8]
            val dx = (g.e[0] - px / pz * g.e[2]) / pz
            val dy = (g.e[1] - py / pz * g.e[2]) / pz
            perGamma = max(perGamma, sqrt(dx * dx + dy * dy))
        }
        val gammaMax = rangeMm / distanceMm
        val levels = refPyr.size // relative to `finest`
        var top = 0
        while (top < levels - 1 && perGamma * gammaMax / (1 shl (finest + top)) > TOP_PARALLAX_PX) top++

        val refMask = glareMask(frames[0], finest, top, rejectGlare)
        val viewMasks = usable.map { glareMask(frames[it + 1], finest, top, rejectGlare) }

        // 3–4. Coarse-to-fine multi-baseline search.
        val gamma = FloatArray(gw * gh)
        val score = FloatArray(gw * gh) { -1f }
        val sharp = BooleanArray(gw * gh)
        var edge = BooleanArray(gw * gh)
        var step = 0.0
        for (rel in top downTo 0) {
            val level = finest + rel
            val scale = (1 shl level).toDouble()
            step = 0.5 * scale / perGamma
            val span = if (rel == top) ceil(gammaMax / step).toInt() else NARROW_STEPS
            val ref = refPyr[rel]
            val views = usable.map { registered[it].first[rel] }
            val geo = geometry.map { g ->
                ViewGeometry(Homography(g.h).atLevel(level).h, doubleArrayOf(g.e[0] / scale, g.e[1] / scale, g.e[2]))
            }
            val masks = viewMasks.map { it[rel] }
            val radius = max(WINDOW, (s shr level) / 2)
            val newEdge = BooleanArray(gw * gh)
            // Above the finest level, cells finer than the level's pixels share one match.
            val stride = if (rel == 0) 1 else max(1, (1 shl level) / s)
            parallelFor((gh + stride - 1) / stride) { row ->
                val gy = row * stride
                val matcher = Matcher(ref, refMask[rel], views, geo, masks, radius, rejectGlare)
                for (gx in 0 until gw step stride) {
                    val i = gy * gw + gx
                    val x = (gx * s + 0.5 * (s - 1)) / scale
                    val y = (gy * s + 0.5 * (s - 1)) / scale
                    if (!matcher.template(x, y, MIN_TEXTURE)) {
                        score[i] = -1f
                        continue
                    }
                    val centre = if (rel == top) 0.0 else gamma[i].toDouble()
                    val best = matcher.search(centre, step, span)
                    if (best == null) {
                        score[i] = -1f
                        continue
                    }
                    gamma[i] = best.gamma.toFloat()
                    score[i] = best.score
                    sharp[i] = best.sharp
                    newEdge[i] = best.atEdge
                }
            }
            edge = newEdge
            if (stride > 1) {
                for (gy in 0 until gh) for (gx in 0 until gw) {
                    val from = (gy - gy % stride) * gw + (gx - gx % stride)
                    gamma[gy * gw + gx] = gamma[from]
                    score[gy * gw + gx] = score[from]
                }
            }
            if (rel > 0) medianFill(gamma, score, gw, gh)
            progress(0.4f + 0.55f * (top - rel + 1) / (top + 1))
        }

        // Final checks: a clear, sharp, in-range match that agrees with its neighbours.
        val valid = BooleanArray(gw * gh) { score[it] >= MIN_ZNCC && sharp[it] && !edge[it] }
        // A lone match among unmatched neighbours is more likely luck than surface.
        val med = median3(gamma, valid, gw, gh, minCount = 5)
        val heights = FloatArray(gw * gh) { Float.NaN }
        for (i in heights.indices) {
            if (!valid[i] || med[i].isNaN() || abs(gamma[i] - med[i]) > 3 * step) continue
            val g = gamma[i].toDouble()
            heights[i] = (g * distanceMm / (1 + g)).toFloat()
        }
        val sorted = heights.filter { !it.isNaN() }.sorted()
        val index = FloatArray(gw * gh) { Float.NaN }
        if (sorted.isNotEmpty()) {
            val base = sorted[((sorted.size - 1) * 0.01f).toInt()]
            for (i in heights.indices) {
                if (heights[i].isNaN()) continue
                index[i] = gamma[i]
                heights[i] = (heights[i] - base).coerceAtLeast(0f)
            }
        }
        progress(1f)
        return Result(HeightMap(gw, gh, s, heights, index), reports)
    }

    private class Match(val gamma: Double, val score: Float, val sharp: Boolean, val atEdge: Boolean)

    /** Window matching for one row of cells (it keeps per-thread scratch buffers). */
    private class Matcher(
        val ref: Plane,
        val refMask: ByteArray,
        val views: List<Plane>,
        val geo: List<ViewGeometry>,
        val masks: List<ByteArray>,
        val radius: Int,
        val trim: Boolean,
    ) {
        private val n = (2 * radius + 1) * (2 * radius + 1)
        private val tc = FloatArray(n)
        private var tNorm = 0.0
        private var x0 = 0.0
        private var y0 = 0.0
        private val perView = DoubleArray(views.size)
        private val costs = DoubleArray(2 * 64 + 1)

        /** Loads the reference window at level coordinates (x, y); false if it cannot be matched. */
        fun template(x: Double, y: Double, minStd: Double): Boolean {
            if (x - radius < 0 || y - radius < 0 || x + radius > ref.width - 1 || y + radius > ref.height - 1) return false
            x0 = x
            y0 = y
            var sum = 0.0
            var j = 0
            for (v in -radius..radius) for (u in -radius..radius) {
                val xs = (x + u).toFloat()
                val ys = (y + v).toFloat()
                if (refMask[ys.roundToInt() * ref.width + xs.roundToInt()].toInt() != 0) return false
                val t = ref.sample(xs, ys)
                tc[j++] = t
                sum += t
            }
            val mean = (sum / n).toFloat()
            var ss = 0.0
            for (i in 0 until n) {
                tc[i] -= mean
                ss += tc[i].toDouble() * tc[i]
            }
            tNorm = sqrt(ss)
            return sqrt(ss / n) >= minStd
        }

        /** Best γ among centre + j·step, |j| ≤ span, refined by a parabola; null if no γ matched. */
        fun search(centre: Double, step: Double, span: Int): Match? {
            val count = min(2 * span + 1, costs.size)
            val half = count / 2
            var best = -1
            for (j in 0 until count) {
                costs[j] = cost(centre + (j - half) * step)
                if (!costs[j].isNaN() && (best < 0 || costs[j] < costs[best])) best = j
            }
            if (best < 0) return null
            var delta = 0.0
            var sharp = false
            if (best in 1 until count - 1 && !costs[best - 1].isNaN() && !costs[best + 1].isNaN()) {
                val curvature = costs[best - 1] - 2 * costs[best] + costs[best + 1]
                if (curvature > 0) {
                    sharp = true
                    delta = (0.5 * (costs[best - 1] - costs[best + 1]) / curvature).coerceIn(-0.5, 0.5)
                }
            }
            return Match(centre + (best - half + delta) * step, (1 - costs[best]).toFloat(), sharp, best == 0 || best == count - 1)
        }

        /** At least half of the views (and two) must see a window, so a frame edge cannot leave a lucky pair to decide. */
        private val minViews = max(2, (views.size + 1) / 2)

        /** Mean (1 − ZNCC) at γ without the worst third of views; NaN if fewer than [minViews] see the window. */
        private fun cost(gamma: Double): Double {
            var m = 0
            for (k in views.indices) {
                val c = viewCost(k, gamma)
                if (!c.isNaN()) perView[m++] = c
            }
            if (m < minViews) return Double.NaN
            java.util.Arrays.sort(perView, 0, m)
            val keep = if (trim && m >= 3) m - max(1, m / 3) else m
            var sum = 0.0
            for (i in 0 until keep) sum += perView[i]
            return sum / keep
        }

        /** 1 − ZNCC of view [k] at γ, clipped to [0, 1]; NaN if the window leaves the frame or meets glare. */
        private fun viewCost(k: Int, gamma: Double): Double {
            val img = views[k]
            val mask = masks[k]
            val hh = geo[k].h
            val e = geo[k].e
            val ex = gamma * e[0]
            val ey = gamma * e[1]
            val ez = gamma * e[2]
            var sum = 0.0
            var sum2 = 0.0
            var cross = 0.0
            var j = 0
            val wMax = img.width - 1.0
            val hMax = img.height - 1.0
            for (v in -radius..radius) {
                val y = y0 + v
                for (u in -radius..radius) {
                    val x = x0 + u
                    val pz = hh[6] * x + hh[7] * y + hh[8] + ez
                    val xs = (hh[0] * x + hh[1] * y + hh[2] + ex) / pz
                    val ys = (hh[3] * x + hh[4] * y + hh[5] + ey) / pz
                    if (xs < 0 || ys < 0 || xs > wMax || ys > hMax) return Double.NaN
                    if (mask[(ys + 0.5).toInt() * img.width + (xs + 0.5).toInt()].toInt() != 0) return Double.NaN
                    val value = img.sample(xs.toFloat(), ys.toFloat()).toDouble()
                    sum += value
                    sum2 += value * value
                    cross += tc[j++] * value
                }
            }
            val varI = sum2 - sum * sum / n
            if (varI <= 1e-6 || tNorm <= 1e-6) return Double.NaN
            val zncc = cross / (tNorm * sqrt(varI))
            return (1 - zncc).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Glare masks for pyramid levels [finest] .. [finest] + [top]: non-zero where the frame is
     * saturated, or within [GLARE_MARGIN] pixels of it, or (on coarser levels) covers any such pixel.
     */
    private fun glareMask(frame: YuvFrame, finest: Int, top: Int, enabled: Boolean): List<ByteArray> {
        val w = frame.width
        val h = frame.height
        var m = ByteArray(w * h)
        var any = false
        if (enabled) for (i in m.indices) if ((frame.y[i].toInt() and 0xFF) >= SATURATED) { m[i] = 1; any = true }
        var mw = w
        var mh = h
        val out = ArrayList<ByteArray>()
        if (any) m = dilate(m, w, h, GLARE_MARGIN)
        for (level in 0..finest + top) {
            if (level >= finest) out.add(m)
            if (level == finest + top) break
            // Same grid as [reduce]: level pixel i sits at i·2 below, and covers its neighbours.
            val w2 = (mw + 1) / 2
            val h2 = (mh + 1) / 2
            val next = ByteArray(w2 * h2)
            if (any) {
                for (y2 in 0 until h2) for (x2 in 0 until w2) {
                    var v = 0
                    for (yy in max(0, 2 * y2 - 1)..min(mh - 1, 2 * y2 + 1)) for (xx in max(0, 2 * x2 - 1)..min(mw - 1, 2 * x2 + 1)) {
                        v = v or m[yy * mw + xx].toInt()
                    }
                    next[y2 * w2 + x2] = v.toByte()
                }
            }
            m = next
            mw = w2
            mh = h2
        }
        return out
    }

    private fun dilate(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (m[y * w + x].toInt() == 0) continue
            for (xx in max(0, x - r)..min(w - 1, x + r)) tmp[y * w + xx] = 1
        }
        val out = ByteArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (tmp[y * w + x].toInt() == 0) continue
            for (yy in max(0, y - r)..min(h - 1, y + r)) out[yy * w + x] = 1
        }
        return out
    }

    /** The 3 × 3 median of the cells flagged [valid] around each cell; NaN where fewer than [minCount] are. */
    private fun median3(v: FloatArray, valid: BooleanArray, w: Int, h: Int, minCount: Int = 1): FloatArray {
        val out = FloatArray(v.size) { Float.NaN }
        val buf = FloatArray(9)
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                val i = yy * w + xx
                if (valid[i]) buf[n++] = v[i]
            }
            if (n >= minCount) {
                buf.sort(0, n)
                out[y * w + x] = if (n % 2 == 1) buf[n / 2] else 0.5f * (buf[n / 2 - 1] + buf[n / 2])
            }
        }
        return out
    }

    /**
     * Between levels: every cell's γ becomes the median of the matched cells around it, so one bad
     * match does not steer the narrower search below, and unmatched cells inherit their neighbours'.
     */
    private fun medianFill(gamma: FloatArray, score: FloatArray, w: Int, h: Int) {
        val valid = BooleanArray(gamma.size) { score[it] >= 0f }
        val med = median3(gamma, valid, w, h)
        for (i in gamma.indices) if (!med[i].isNaN()) gamma[i] = med[i]
    }
}
