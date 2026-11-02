package io.github.foadsf.microscope.imaging

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Multi-frame merge of a handheld burst, optionally onto a 2x finer grid.
 *
 * Each frame is registered to frame 0 ([Aligner]). Every input luma sample is forward-mapped to
 * its sub-pixel position on the output grid, and each output pixel is estimated by first-order
 * (locally linear) kernel regression over the samples around it (Takeda, Farsiu & Milanfar 2007,
 * "Kernel Regression for Image Processing and Reconstruction"), at two kernel sizes. The narrow
 * fit resolves detail but needs samples spread around the pixel, which only sub-pixel motion
 * provides; the wide fit reaches the neighbouring input pixels, which interpolates like bilinear
 * when the frames coincide (a still burst). Each output pixel blends the two by how well the
 * narrow kernel's samples surround it.
 * A per-sample robustness weight suppresses samples that disagree with the reference by more than
 * noise plus expected interpolation error (motion, misregistration), and a weak prior from the
 * upsampled reference fills any output pixel no sample landed near.
 *
 * Honest limits: the input is ISP-processed YUV, not raw Bayer, so the gain beyond Nyquist is
 * modest; the reliable gains are lower noise (≈ √N) and fewer interpolation artefacts. Sub-pixel
 * diversity comes from hand tremor; on a rigid stand the frames coincide and this is a denoiser.
 */
object SuperResolution {
    private const val PRIOR_WEIGHT = 0.08f
    private const val BAND = 32
    // Per kernel: Σw, Σw·dx, Σw·dy, Σw·dx², Σw·dx·dy, Σw·dy², Σw·v, Σw·v·dx, Σw·v·dy.
    private const val ACC = 9
    private const val NARROW = 1.2f // output pixels
    // Kernel steering along edges (Wronski et al. 2019, "Handheld Multi-Frame Super-Resolution").
    internal const val STEER = 1f
    private const val STRETCH = 1.0f
    private const val SHRINK = 0.35f
    private const val NOISE_TRACE = 4f

    /** Wide kernel radius in output pixels: must reach the neighbouring input samples (1.5 px away at 2x). */
    private fun wideRadius(scale: Int) = scale.toFloat()

    private fun kernel(d: Float, radius: Float): Float {
        val t = d / radius
        val s = 1f - t * t
        return if (s <= 0f) 0f else s * s
    }

    /**
     * [warps] map frame-0 pixel coordinates to each frame's coordinates (warps[0] = identity).
     * [scale] is 1 or 2. [sharpen] is the unsharp-mask amount applied to the merged luma.
     */
    fun merge(
        frames: List<YuvFrame>,
        warps: List<Affine>,
        scale: Int,
        sharpen: Float,
        steer: Float = STEER,
        progress: (Float) -> Unit = {},
    ): RgbImage {
        require(frames.isNotEmpty() && frames.size == warps.size)
        require(scale == 1 || scale == 2)
        val ref = frames[0]
        val w = ref.width
        val h = ref.height
        val refY = ref.lumaPlane()
        val sigma = estimateNoiseSigma(refY)
        val refGrad = gradientMagnitude(refY)
        val inverses = warps.map { it.inverse() }
        val outW = w * scale
        val outH = h * scale
        val outY = Plane(outW, outH)
        val s = scale.toFloat()
        val tol0 = (3f * sigma + 2f).let { it * it }

        if (scale == 1) {
            temporalMerge(frames, warps, refY, refGrad, tol0, outY) { progress(0.8f * it) }
        } else {
            val shape = if (steer > 0f) steering(refY, sigma, steer, wideRadius(scale)) else null
            splatMerge(frames, warps, inverses, refY, refGrad, tol0, outY, scale, progress, shape)
        }

        // Chroma: robust temporal average on a half-resolution output grid.
        val cw = outW / 2
        val ch = outH / 2
        val outU = Plane(cw, ch)
        val outV = Plane(cw, ch)
        parallelFor(ch) { cy ->
            for (cx in 0 until cw) {
                val qx = ((2 * cx + 1) + 0.5f) / s - 0.5f
                val qy = ((2 * cy + 1) + 0.5f) / s - 0.5f
                val yRef = refY.sample(qx, qy)
                val g = refGrad.sample(qx, qy)
                val tol = tol0 + 0.25f * g * g
                var su = 0f
                var sv = 0f
                var sw = 0f
                for (f in frames.indices) {
                    val frame = frames[f]
                    val m = warps[f]
                    val px = m.mapX(qx.toDouble(), qy.toDouble()).toFloat()
                    val py = m.mapY(qx.toDouble(), qy.toDouble()).toFloat()
                    val wt = if (f == 0) {
                        1f
                    } else {
                        val d = bilinearU8(frame.y, frame.width, frame.height, px, py) - yRef
                        1f / (1f + d * d / tol)
                    }
                    val ux = (px + 0.5f) / 2f - 0.5f
                    val uy = (py + 0.5f) / 2f - 0.5f
                    su += wt * bilinearU8(frame.u, frame.chromaWidth, frame.chromaHeight, ux, uy)
                    sv += wt * bilinearU8(frame.v, frame.chromaWidth, frame.chromaHeight, ux, uy)
                    sw += wt
                }
                outU.data[cy * cw + cx] = su / sw
                outV.data[cy * cw + cx] = sv / sw
            }
        }
        progress(0.9f)
        unsharp(outY, sharpen)
        val rgb = toRgb(outY, outU, outV)
        progress(1f)
        return rgb
    }

    private fun accumulate(acc: FloatArray, a: Int, wt: Float, dx: Float, dy: Float, value: Float) {
        val wdx = wt * dx
        val wdy = wt * dy
        acc[a] += wt
        acc[a + 1] += wdx
        acc[a + 2] += wdy
        acc[a + 3] += wdx * dx
        acc[a + 4] += wdx * dy
        acc[a + 5] += wdy * dy
        acc[a + 6] += wt * value
        acc[a + 7] += wdx * value
        acc[a + 8] += wdy * value
    }

    /**
     * 0..1: how well the samples in a kernel surround the pixel, from the determinant of their
     * weighted position covariance (0 when they all sit at one spot, as in a still burst).
     */
    private fun spread(acc: FloatArray, a: Int): Float {
        val s0 = acc[a]
        if (s0 < 1e-3f) return 0f
        val mx = acc[a + 1] / s0
        val my = acc[a + 2] / s0
        val cxx = acc[a + 3] / s0 - mx * mx
        val cxy = acc[a + 4] / s0 - mx * my
        val cyy = acc[a + 5] / s0 - my * my
        val det = cxx * cyy - cxy * cxy
        // Samples scattered uniformly through the narrow kernel give det ≈ 0.01.
        return (sqrt(det.coerceAtLeast(0f)) / 0.06f).coerceIn(0f, 1f)
    }

    /** 2x: first-order kernel regression of forward-mapped samples (see the class comment). */
    private fun splatMerge(
        frames: List<YuvFrame>, warps: List<Affine>, inverses: List<Affine>, refY: Plane, refGrad: Plane,
        tol0: Float, outY: Plane, scale: Int, progress: (Float) -> Unit,
        shape: FloatArray?,
    ) {
        val w = refY.width
        val outW = outY.width
        val outH = outY.height
        val s = scale.toFloat()
        val radius = wideRadius(scale)
        val bands = (outH + BAND - 1) / BAND
        val done = java.util.concurrent.atomic.AtomicInteger()
        parallelFor(bands) { band ->
            val oy0 = band * BAND
            val oy1 = min(outH, oy0 + BAND)
            val rows = oy1 - oy0
            val acc = FloatArray(rows * outW * ACC)
            val nar = FloatArray(rows * outW * ACC)
            val kx = FloatArray(8)
            val kxn = FloatArray(8)
            // Reference rows whose samples can reach this band.
            val qy0 = ((oy0 - radius + 0.5f) / s - 0.5f - 1f).toDouble()
            val qy1 = ((oy1 - 1 + radius + 0.5f) / s - 0.5f + 1f).toDouble()
            for (f in frames.indices) {
                val frame = frames[f]
                val m = warps[f]
                val inv = inverses[f]
                val xsC = doubleArrayOf(m.mapX(-1.0, qy0), m.mapX(w.toDouble(), qy0), m.mapX(-1.0, qy1), m.mapX(w.toDouble(), qy1))
                val ysC = doubleArrayOf(m.mapY(-1.0, qy0), m.mapY(w.toDouble(), qy0), m.mapY(-1.0, qy1), m.mapY(w.toDouble(), qy1))
                val px0 = max(0, floor(xsC.min()).toInt())
                val px1 = min(frame.width - 1, ceil(xsC.max()).toInt())
                val py0 = max(0, floor(ysC.min()).toInt())
                val py1 = min(frame.height - 1, ceil(ysC.max()).toInt())
                for (py in py0..py1) {
                    val row = py * frame.width
                    for (px in px0..px1) {
                        val qx = inv.mapX(px.toDouble(), py.toDouble()).toFloat()
                        val qy = inv.mapY(px.toDouble(), py.toDouble()).toFloat()
                        val ox = (qx + 0.5f) * s - 0.5f
                        val oy = (qy + 0.5f) * s - 0.5f
                        if (oy < oy0 - radius || oy > oy1 - 1 + radius || ox < -radius || ox > outW - 1 + radius) continue
                        val value = (frame.y[row + px].toInt() and 0xFF).toFloat()
                        var robust = 1f
                        if (f != 0) {
                            val d = value - refY.sample(qx, qy)
                            val g = refGrad.sample(qx, qy)
                            val tol = tol0 + 0.25f * g * g
                            robust = 1f / (1f + d * d / tol)
                        }
                        // Narrow kernel shape at the sample's reference position (see [steering]).
                        var e1x = 1f
                        var e1y = 0f
                        var rAcross = NARROW
                        var rAlong = NARROW
                        if (shape != null) {
                            val sx = (qx + 0.5f).toInt().coerceIn(0, w - 1)
                            val sy = (qy + 0.5f).toInt().coerceIn(0, refY.height - 1)
                            val k = 4 * (sy * w + sx)
                            e1x = shape[k]
                            e1y = shape[k + 1]
                            rAcross = shape[k + 2]
                            rAlong = shape[k + 3]
                        }
                        val steered = rAlong != NARROW || rAcross != NARROW
                        val ix0 = max(0, ceil(ox - radius).toInt())
                        val ix1 = min(outW - 1, floor(ox + radius).toInt())
                        val iy0 = max(oy0, ceil(oy - radius).toInt())
                        val iy1 = min(oy1 - 1, floor(oy + radius).toInt())
                        // Separable kernels: evaluate each 1-D weight once per sample.
                        for (ix in ix0..ix1) {
                            val dx = ox - ix
                            kx[ix - ix0] = kernel(dx, radius)
                            kxn[ix - ix0] = if (dx < NARROW && dx > -NARROW) kernel(dx, NARROW) else 0f
                        }
                        for (iy in iy0..iy1) {
                            val dy = oy - iy
                            val ky = kernel(dy, radius) * robust
                            if (ky == 0f) continue
                            val kyn = if (dy < NARROW && dy > -NARROW) kernel(dy, NARROW) * robust else 0f
                            val o = (iy - oy0) * outW
                            for (ix in ix0..ix1) {
                                val j = ix - ix0
                                val wt = ky * kx[j]
                                if (wt == 0f) continue
                                val dx = ox - ix
                                val a = (o + ix) * ACC
                                accumulate(acc, a, wt, dx, dy, value)
                                val wn = if (steered) {
                                    val u = (dx * e1x + dy * e1y) / rAcross
                                    val v = (dy * e1x - dx * e1y) / rAlong
                                    val r2 = u * u + v * v
                                    if (r2 < 1f) (1f - r2) * (1f - r2) * robust else 0f
                                } else {
                                    kyn * kxn[j]
                                }
                                if (wn > 0f) accumulate(nar, a, wn, dx, dy, value)
                            }
                        }
                    }
                }
            }
            for (r in 0 until rows) {
                val oy = oy0 + r
                val qy = (oy + 0.5f) / s - 0.5f
                for (ox in 0 until outW) {
                    val qx = (ox + 0.5f) / s - 0.5f
                    val a = (r * outW + ox) * ACC
                    val prior = refY.sample(qx, qy)
                    val wide = solveLocalLinear(acc, a, prior)
                    val alpha = spread(nar, a)
                    val v = if (alpha > 0f) alpha * solveLocalLinear(nar, a, prior) + (1f - alpha) * wide else wide
                    outY.data[oy * outW + ox] = v.coerceIn(0f, 255f)
                }
            }
            progress(0.8f * done.incrementAndGet() / bands)
        }
    }

    /**
     * 1x: every frame resampled onto the reference grid and averaged with the same robustness
     * weights. With no finer grid to fill, regression would only add cost; this is the classic
     * align-and-merge denoiser.
     */
    private fun temporalMerge(
        frames: List<YuvFrame>, warps: List<Affine>, refY: Plane, refGrad: Plane, tol0: Float, outY: Plane,
        progress: (Float) -> Unit,
    ) {
        val w = refY.width
        val h = refY.height
        val done = java.util.concurrent.atomic.AtomicInteger()
        parallelFor(h) { y ->
            val row = y * w
            for (x in 0 until w) {
                val r = refY.data[row + x]
                val g = refGrad.data[row + x]
                val tol = tol0 + 0.25f * g * g
                var sum = r
                var ws = 1f
                for (f in 1 until frames.size) {
                    val fr = frames[f]
                    val m = warps[f]
                    val px = m.mapX(x.toDouble(), y.toDouble()).toFloat()
                    val py = m.mapY(x.toDouble(), y.toDouble()).toFloat()
                    val v = bilinearU8(fr.y, fr.width, fr.height, px, py)
                    val d = v - r
                    val wt = 1f / (1f + d * d / tol)
                    sum += wt * v
                    ws += wt
                }
                outY.data[row + x] = sum / ws
            }
            if (y % 64 == 0) progress(done.addAndGet(64).toFloat() / h)
        }
    }

    /**
     * Intercept of the weighted least-squares plane v ≈ a + b·dx + c·dy, with the reference prior
     * as a pseudo-sample at the pixel centre and a small ridge on the slopes. Falls back to the
     * weighted mean when the samples cannot support a slope.
     */
    private fun solveLocalLinear(acc: FloatArray, a: Int, prior: Float): Float {
        val s0 = acc[a] + PRIOR_WEIGHT
        val v0 = acc[a + 6] + PRIOR_WEIGHT * prior
        val ridge = 1e-3f * s0 + 1e-4f
        val sx = acc[a + 1].toDouble()
        val sy = acc[a + 2].toDouble()
        val sxx = acc[a + 3] + ridge.toDouble()
        val sxy = acc[a + 4].toDouble()
        val syy = acc[a + 5] + ridge.toDouble()
        val vx = acc[a + 7].toDouble()
        val vy = acc[a + 8].toDouble()
        // Eliminate the slopes: a = (v0 - [sx sy]·M⁻¹·[vx vy]) / (s0 - [sx sy]·M⁻¹·[sx sy]).
        val det = sxx * syy - sxy * sxy
        val mean = v0 / s0
        if (det <= 1e-9 * s0 * s0) return mean
        val ix = (syy * vx - sxy * vy) / det
        val iy = (sxx * vy - sxy * vx) / det
        val jx = (syy * sx - sxy * sy) / det
        val jy = (sxx * sy - sxy * sx) / det
        val den = s0 - (sx * jx + sy * jy)
        if (den <= 1e-6 * s0) return mean
        return ((v0 - (sx * ix + sy * iy)) / den).toFloat()
    }

    /**
     * Narrow-kernel shape for every reference pixel, as (e1x, e1y, r across, r along): from the
     * smoothed structure tensor, the kernel is narrowed across an edge (along the gradient e1) and
     * stretched along it, in proportion to the edge's coherence (λ1 − λ2)/(λ1 + λ2), discounted
     * where the gradient is mostly noise. Flat or noisy areas keep the round kernel.
     */
    private fun steering(p: Plane, sigma: Float, steer: Float, maxRadius: Float): FloatArray {
        val w = p.width
        val h = p.height
        val xx = Plane(w, h)
        val xy = Plane(w, h)
        val yy = Plane(w, h)
        parallelFor(h) { y ->
            val ym = max(0, y - 1)
            val yp = min(h - 1, y + 1)
            for (x in 0 until w) {
                val gx = 0.5f * (p[min(w - 1, x + 1), y] - p[max(0, x - 1), y])
                val gy = 0.5f * (p[x, yp] - p[x, ym])
                val i = y * w + x
                xx.data[i] = gx * gx
                xy.data[i] = gx * gy
                yy.data[i] = gy * gy
            }
        }
        val jxx = blur(blur(xx)).data
        val jxy = blur(blur(xy)).data
        val jyy = blur(blur(yy)).data
        val noise = NOISE_TRACE * sigma * sigma
        val out = FloatArray(4 * w * h)
        parallelFor(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val a = jxx[i]
                val b = jxy[i]
                val c = jyy[i]
                val tr = a + c
                val root = sqrt((a - c) * (a - c) + 4f * b * b)
                val o = 4 * i
                // Eigenvector of the larger eigenvalue: (a − c + root, 2b), or the x axis if isotropic.
                var ex = a - c + root
                var ey = 2f * b
                val n = sqrt(ex * ex + ey * ey)
                if (tr <= 1e-6f || n <= 1e-12f) {
                    out[o] = if (a >= c) 1f else 0f
                    out[o + 1] = if (a >= c) 0f else 1f
                    out[o + 2] = NARROW
                    out[o + 3] = NARROW
                    continue
                }
                ex /= n
                ey /= n
                val coherence = root / tr * (tr / (tr + noise)) * steer
                out[o] = ex
                out[o + 1] = ey
                out[o + 2] = NARROW * (1f - SHRINK * coherence)
                out[o + 3] = min(maxRadius, NARROW * (1f + STRETCH * coherence))
            }
        }
        return out
    }

    private fun gradientMagnitude(p: Plane): Plane {
        val out = Plane(p.width, p.height)
        parallelFor(p.height) { y ->
            val ym = max(0, y - 1)
            val yp = min(p.height - 1, y + 1)
            for (x in 0 until p.width) {
                val xm = max(0, x - 1)
                val xp = min(p.width - 1, x + 1)
                val gx = 0.5f * (p[xp, y] - p[xm, y])
                val gy = 0.5f * (p[x, yp] - p[x, ym])
                out.data[y * p.width + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }
}
