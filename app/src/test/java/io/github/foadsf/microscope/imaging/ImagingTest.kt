package io.github.foadsf.microscope.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** A band-limited analytic scene, so warped and blurred versions can be generated exactly. */
private class Scene(seed: Long, maxFreq: Double, count: Int = 24) {
    private val r = Random(seed)
    private val kx = DoubleArray(count) { (r.nextDouble() * 2 - 1) * maxFreq }
    private val ky = DoubleArray(count) { (r.nextDouble() * 2 - 1) * maxFreq }
    private val ph = DoubleArray(count) { r.nextDouble() * 6.283 }
    private val amp = DoubleArray(count) { 6.0 + r.nextDouble() * 6.0 }

    fun at(x: Double, y: Double): Double {
        var s = 128.0
        for (i in kx.indices) s += amp[i] * sin(kx[i] * x + ky[i] * y + ph[i])
        return s
    }
}

private fun frameOf(luma: Plane): YuvFrame {
    val y = ByteArray(luma.data.size) { luma.data[it].toInt().coerceIn(0, 255).toByte() }
    val c = ByteArray(luma.data.size / 4) { 128.toByte() }
    return YuvFrame(luma.width, luma.height, y, c, c.copyOf())
}

private fun lumaOf(img: RgbImage) = Plane(img.width, img.height, FloatArray(img.argb.size) { ((img.argb[it] shr 8) and 0xFF).toFloat() })

private fun psnr(a: Plane, b: Plane, border: Int): Double {
    var se = 0.0
    var n = 0
    for (y in border until a.height - border) for (x in border until a.width - border) {
        val d = a[x, y] - b[x, y]
        se += d * d
        n++
    }
    return 10 * log10(255.0 * 255.0 / (se / n))
}

class ImagingTest {
    @Test
    fun laplacianPyramidIsExactlyInvertible() {
        val s = Scene(1, 0.8)
        val p = Plane(203, 151).also { for (y in 0 until it.height) for (x in 0 until it.width) it.data[y * it.width + x] = s.at(x.toDouble(), y.toDouble()).toFloat() }
        val back = collapse(laplacianPyramid(p, 5))
        for (i in p.data.indices) assertEquals(p.data[i], back.data[i], 1e-3f)
    }

    @Test
    fun alignmentRecoversSubPixelSimilarity() {
        val s = Scene(2, 0.35)
        val w = 640
        val h = 480
        val truth = Affine.similarity(doubleArrayOf(0.012, 0.006, 7.3, -4.6), (w - 1) / 2.0, (h - 1) / 2.0)
        val inv = truth.inverse()
        val noise = Random(3)
        val ref = Plane(w, h)
        val mov = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            ref.data[y * w + x] = (s.at(x.toDouble(), y.toDouble()) + noise.nextGaussian() * 2).toFloat()
            // moving(truth(q)) = ref(q)  =>  moving(p) = scene(truth⁻¹(p))
            mov.data[y * w + x] = (s.at(inv.mapX(x.toDouble(), y.toDouble()), inv.mapY(x.toDouble(), y.toDouble())) + noise.nextGaussian() * 2).toFloat()
        }
        val est = Aligner.align(Aligner.pyramid(ref), Aligner.pyramid(mov))
        for ((x, y) in listOf(0.0 to 0.0, w - 1.0 to 0.0, 0.0 to h - 1.0, w - 1.0 to h - 1.0, w / 2.0 to h / 2.0)) {
            val err = sqrt(
                (est.mapX(x, y) - truth.mapX(x, y)).let { it * it } + (est.mapY(x, y) - truth.mapY(x, y)).let { it * it },
            )
            assertTrue("corner ($x,$y) off by $err px", err < 0.1)
        }
    }

    @Test
    fun burstMergeBeatsSingleFrameUpscale() {
        // Ground truth at 2x; each low-res frame integrates 2x2 high-res pixels at a random
        // sub-pixel offset, plus sensor noise.
        val s = Scene(4, 1.1)
        val w = 160
        val h = 120
        val rnd = Random(5)
        val boxed = { u: Double, v: Double -> 0.25 * (s.at(u - 0.5, v - 0.5) + s.at(u + 0.5, v - 0.5) + s.at(u - 0.5, v + 0.5) + s.at(u + 0.5, v + 0.5)) }
        // Control: the same burst with no sub-pixel motion (a rigid stand). It gets the same noise
        // averaging, so any extra gain of the shifted burst is resolution, not denoising.
        val frames = ArrayList<YuvFrame>()
        val still = ArrayList<YuvFrame>()
        for (i in 0 until 8) {
            val dx = if (i == 0) 0.0 else rnd.nextDouble() * 2 - 1
            val dy = if (i == 0) 0.0 else rnd.nextDouble() * 2 - 1
            val p = Plane(w, h)
            val q = Plane(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                p.data[y * w + x] = (boxed(2 * (x + dx) + 0.5, 2 * (y + dy) + 0.5) + rnd.nextGaussian() * 3).toFloat()
                q.data[y * w + x] = (boxed(2.0 * x + 0.5, 2.0 * y + 0.5) + rnd.nextGaussian() * 3).toFloat()
            }
            frames.add(frameOf(p))
            still.add(frameOf(q))
        }
        val truth = Plane(2 * w, 2 * h)
        for (y in 0 until 2 * h) for (x in 0 until 2 * w) truth.data[y * 2 * w + x] = boxed(x.toDouble(), y.toDouble()).toFloat()

        val warps = Pipeline.alignToFirst(frames)
        val merged = lumaOf(SuperResolution.merge(frames, warps, scale = 2, sharpen = 0f))
        val single = Plane(2 * w, 2 * h)
        val f0 = frames[0].lumaPlane()
        for (y in 0 until 2 * h) for (x in 0 until 2 * w) single.data[y * 2 * w + x] = f0.sample((x + 0.5f) / 2 - 0.5f, (y + 0.5f) / 2 - 0.5f)

        val stillMerged = lumaOf(SuperResolution.merge(still, Pipeline.alignToFirst(still), scale = 2, sharpen = 0f))

        val pMerged = psnr(merged, truth, 8)
        val pSingle = psnr(single, truth, 8)
        val pStill = psnr(stillMerged, truth, 8)
        println("burst merge PSNR %.2f dB; same burst without motion %.2f dB; single-frame bilinear %.2f dB".format(pMerged, pStill, pSingle))
        assertTrue("merge $pMerged dB should beat single frame $pSingle dB by 2 dB", pMerged > pSingle + 2.0)
        assertTrue("sub-pixel motion should add resolution: $pMerged vs $pStill dB", pMerged > pStill + 1.0)
        assertTrue("a still burst must not be worse than one frame: $pStill vs $pSingle dB", pStill > pSingle)
    }

    @Test
    fun focusStackIsSharperThanEveryInputFrame() {
        // Depth increases left to right; frame k is in focus around depth k.
        val s = Scene(6, 0.9)
        val w = 320
        val h = 160
        val n = 5
        val truth = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) truth.data[y * w + x] = s.at(x.toDouble(), y.toDouble()).toFloat()
        val frames = (0 until n).map { k ->
            val p = Plane(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val depth = x.toDouble() / w * (n - 1)
                val sigma = 0.2 + 1.6 * abs(depth - k)
                val r = (3 * sigma).toInt().coerceAtLeast(1)
                var acc = 0.0
                var ws = 0.0
                for (j in -r..r) for (i in -r..r) {
                    val wt = exp(-(i * i + j * j) / (2 * sigma * sigma))
                    acc += wt * s.at((x + i).toDouble(), (y + j).toDouble())
                    ws += wt
                }
                p.data[y * w + x] = (acc / ws).toFloat()
            }
            frameOf(p)
        }
        val fused = lumaOf(Pipeline.process(CaptureMode.FOCUS_STACK, frames, sharpen = 0f))
        val pFused = psnr(fused, truth, 6)
        val pBest = frames.maxOf { psnr(it.lumaPlane(), truth, 6) }
        println("focus stack PSNR %.2f dB vs best single frame %.2f dB".format(pFused, pBest))
        assertTrue("stack $pFused dB should beat best frame $pBest dB by 3 dB", pFused > pBest + 3.0)
    }

    @Test
    fun opticsMatchesHandCalculation() {
        // Galaxy Note10 main camera figures: f = 4.3 mm, 1.4 µm pixels, focused at 80 mm.
        assertEquals(0.0568f, Optics.magnification(4.3f, 80f), 1e-4f)
        assertEquals(24.6f, Optics.objectPixelUm(1.4f, 4.3f, 80f), 0.1f)
        // f/2.4 with a 2-pixel circle of confusion: about 0.73 D of depth of field.
        assertEquals(0.727f, Optics.depthOfFieldDiopters(2.4f, 2.8f, 4.3f), 0.005f)
        assertEquals(12.5f, 1000f / Optics.distanceMm(12.5f), 1e-4f)
        assertTrue(Optics.distanceMm(0f).isInfinite())
    }

    @Test
    fun bracketStaysInsideLensRange() {
        val b = Optics.bracket(centre = 9.8f, count = 5, step = 0.5f, maxDiopters = 10f)
        assertEquals(5, b.size)
        assertEquals(10f, b.first(), 1e-5f)
        assertEquals(8f, b.last(), 1e-5f)
        for (i in 1 until b.size) assertTrue(b[i] < b[i - 1])
    }

    @Test
    fun scaleBarLabels() {
        assertEquals(500f, Optics.niceFloor(730f), 1e-3f)
        assertEquals(2f, Optics.niceFloor(2.7f), 1e-5f)
        assertEquals("1 mm", Optics.formatLength(1000f))
        assertEquals("200 µm", Optics.formatLength(200f))
        assertEquals("2.5 cm", Optics.formatLength(25_000f))
    }

    @Test
    fun rotationAndColourConversion() {
        val img = RgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val r = img.rotated(90)
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        // Clockwise: the bottom-left pixel (4) becomes the top-left.
        assertEquals(listOf(4, 1, 5, 2, 6, 3), r.argb.toList())
        assertEquals(img.argb.toList(), img.rotated(90).rotated(270).argb.toList())
        assertEquals(0xFF808080.toInt(), ycbcrToArgb(128f, 128f, 128f))
    }
}
