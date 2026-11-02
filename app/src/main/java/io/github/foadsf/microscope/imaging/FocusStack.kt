package io.github.foadsf.microscope.imaging

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Extended depth of field from a focus bracket by Laplacian-pyramid fusion
 * (Burt & Adelson 1983; Burt & Kolczynski 1993, "Enhanced image capture through fusion").
 *
 * Frames are registered to the reference with a similarity warp, which also removes focus
 * breathing (the slight magnification change as the lens moves). Each band-pass coefficient of the
 * result is a weighted mean of the frames' coefficients, weighted by the cube of the local band
 * energy. Where one frame is clearly sharper this is a hard selection; where all frames are alike
 * (flat or equally blurred areas) it averages them, which avoids picking up noise. The low-pass
 * residual is averaged. Chroma takes the weights of the matching (half-resolution) band.
 *
 * Work is done in overlapping tiles so memory stays bounded and all cores are used.
 */
object FocusStack {
    private const val LEVELS = 5
    private const val CORE = 512
    private const val MARGIN = 64 // multiple of 2^(LEVELS-1); covers the kernel support of 4 levels
    private const val EXT = CORE + 2 * MARGIN
    private const val EPS = 1e-2f

    /** [warps] map reference pixel coordinates to each frame's coordinates. */
    fun fuse(frames: List<YuvFrame>, warps: List<Affine>, progress: (Float) -> Unit = {}): RgbImage {
        require(frames.isNotEmpty() && frames.size == warps.size)
        val w = frames[0].width
        val h = frames[0].height
        val outY = Plane(w, h)
        val outU = Plane(w / 2, h / 2)
        val outV = Plane(w / 2, h / 2)
        val tilesX = (w + CORE - 1) / CORE
        val tilesY = (h + CORE - 1) / CORE
        val total = tilesX * tilesY
        val done = AtomicInteger()
        parallelFor(total) { t ->
            fuseTile(frames, warps, (t % tilesX) * CORE - MARGIN, (t / tilesX) * CORE - MARGIN, outY, outU, outV)
            progress(0.95f * done.incrementAndGet() / total)
        }
        val rgb = toRgb(outY, outU, outV)
        progress(1f)
        return rgb
    }

    private fun fuseTile(
        frames: List<YuvFrame>, warps: List<Affine>, x0: Int, y0: Int,
        outY: Plane, outU: Plane, outV: Plane,
    ) {
        var num: List<FloatArray>? = null
        var den: List<FloatArray>? = null
        var shapes: List<Plane>? = null
        var top: FloatArray? = null
        val ce = EXT / 2
        val cu = FloatArray(ce * ce)
        val cv = FloatArray(ce * ce)
        val cwt = FloatArray(ce * ce)

        for (f in frames.indices) {
            val frame = frames[f]
            val m = warps[f]
            val warped = Plane(EXT, EXT)
            for (j in 0 until EXT) {
                val qy = (y0 + j).toDouble()
                for (i in 0 until EXT) {
                    val qx = (x0 + i).toDouble()
                    warped.data[j * EXT + i] = bilinearU8(
                        frame.y, frame.width, frame.height,
                        m.mapX(qx, qy).toFloat(), m.mapY(qx, qy).toFloat(),
                    )
                }
            }
            val lp = laplacianPyramid(warped, LEVELS)
            if (num == null) {
                shapes = lp
                num = lp.dropLast(1).map { FloatArray(it.data.size) }
                den = lp.dropLast(1).map { FloatArray(it.data.size) }
                top = FloatArray(lp.last().data.size)
            }
            for (k in 0 until lp.size - 1) {
                val band = lp[k]
                val sq = Plane(band.width, band.height, FloatArray(band.data.size) { band.data[it] * band.data[it] })
                val energy = blur(sq).data
                val n = num[k]
                val d = den!![k]
                val weights = if (k == 1) FloatArray(energy.size) else null
                for (i in energy.indices) {
                    val e = energy[i] + EPS
                    val wt = e * e * e
                    n[i] += wt * band.data[i]
                    d[i] += wt
                    if (weights != null) weights[i] = wt
                }
                if (weights != null) {
                    // Level 1 is exactly the chroma resolution of this tile.
                    for (cj in 0 until ce) {
                        // Chroma pixel (x0/2 + ci) sits at luma coordinate 2*c + 0.5.
                        val qy = (y0 + 2 * cj + 0.5)
                        for (ci in 0 until ce) {
                            val qx = (x0 + 2 * ci + 0.5)
                            val px = m.mapX(qx, qy).toFloat()
                            val py = m.mapY(qx, qy).toFloat()
                            val ux = (px + 0.5f) / 2f - 0.5f
                            val uy = (py + 0.5f) / 2f - 0.5f
                            val idx = cj * ce + ci
                            val wt = weights[idx]
                            cu[idx] += wt * bilinearU8(frame.u, frame.chromaWidth, frame.chromaHeight, ux, uy)
                            cv[idx] += wt * bilinearU8(frame.v, frame.chromaWidth, frame.chromaHeight, ux, uy)
                            cwt[idx] += wt
                        }
                    }
                }
            }
            val last = lp.last().data
            val tp = top!!
            for (i in last.indices) tp[i] += last[i]
        }

        val bands = ArrayList<Plane>(LEVELS)
        for (k in 0 until num!!.size) {
            val shape = shapes!![k]
            val n = num[k]
            val d = den!![k]
            bands.add(Plane(shape.width, shape.height, FloatArray(n.size) { if (d[it] > 0f) n[it] / d[it] else 0f }))
        }
        val residual = shapes!!.last()
        val count = frames.size.toFloat()
        bands.add(Plane(residual.width, residual.height, FloatArray(residual.data.size) { top!![it] / count }))
        val fused = collapse(bands)

        val w = outY.width
        val h = outY.height
        val ox0 = x0 + MARGIN
        val oy0 = y0 + MARGIN
        for (j in 0 until min(CORE, h - oy0)) {
            for (i in 0 until min(CORE, w - ox0)) {
                val v = fused.data[(j + MARGIN) * EXT + i + MARGIN]
                outY.data[(oy0 + j) * w + ox0 + i] = if (v < 0f) 0f else if (v > 255f) 255f else v
            }
        }
        val cwOut = outU.width
        val chOut = outU.height
        val cm = MARGIN / 2
        for (j in 0 until min(CORE / 2, chOut - oy0 / 2)) {
            for (i in 0 until min(CORE / 2, cwOut - ox0 / 2)) {
                val idx = (j + cm) * ce + i + cm
                val o = (oy0 / 2 + j) * cwOut + ox0 / 2 + i
                outU.data[o] = cu[idx] / cwt[idx]
                outV.data[o] = cv[idx] / cwt[idx]
            }
        }
    }
}
