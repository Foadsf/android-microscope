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
internal class Scene(seed: Long, maxFreq: Double, count: Int = 24) {
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

internal fun frameOf(luma: Plane): YuvFrame {
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

/** A focus bracket of a surface whose depth rises left to right through slices 0..n-1. */
private fun depthRampBracket(n: Int, w: Int, h: Int, noise: Double, seed: Long): List<YuvFrame> {
    val s = Scene(13, 0.9)
    val sharp = Array(h) { y -> DoubleArray(w) { x -> s.at(x.toDouble(), y.toDouble()) } }
    val r = Random(seed)
    return (0 until n).map { k ->
        fun sigma(x: Int) = 0.2 + 1.2 * abs(x.toDouble() / w * (n - 1) - k)
        // Separable Gaussian whose width follows the defocus at each column.
        val tmp = Array(h) { DoubleArray(w) }
        for (x in 0 until w) {
            val sg = sigma(x)
            val rad = (3 * sg).toInt().coerceAtLeast(1)
            val kern = DoubleArray(2 * rad + 1) { exp(-((it - rad) * (it - rad)) / (2 * sg * sg)) }
            val ks = kern.sum()
            for (y in 0 until h) {
                var acc = 0.0
                for (j in -rad..rad) acc += kern[j + rad] * sharp[(y + j).coerceIn(0, h - 1)][x]
                tmp[y][x] = acc / ks
            }
        }
        val p = Plane(w, h)
        for (x in 0 until w) {
            val sg = sigma(x)
            val rad = (3 * sg).toInt().coerceAtLeast(1)
            val kern = DoubleArray(2 * rad + 1) { exp(-((it - rad) * (it - rad)) / (2 * sg * sg)) }
            val ks = kern.sum()
            for (y in 0 until h) {
                var acc = 0.0
                for (i in -rad..rad) acc += kern[i + rad] * tmp[y][(x + i).coerceIn(0, w - 1)]
                p.data[y * w + x] = (acc / ks + r.nextGaussian() * noise).toFloat()
            }
        }
        frameOf(p)
    }
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

    @Test
    fun zoomSplitKeepsTheViewInsideTheIspCrop() {
        val r = Random(7)
        repeat(2000) {
            val zoom = 1f + r.nextFloat() * 15f
            val maxIsp = 1f + r.nextFloat() * 9f
            val centerOnly = r.nextBoolean()
            val (cx, cy) = ZoomSplit.clampCentre(zoom, r.nextFloat(), r.nextFloat(), maxIsp, centerOnly)
            val z = ZoomSplit(zoom, cx, cy, maxIsp, centerOnly)
            val view = 0.5f / zoom
            val crop = 0.5f / z.ispZoom
            assertTrue(z.ispZoom >= 1f && z.ispZoom <= maxOf(1f, maxIsp) + 1e-4f && z.ispZoom <= zoom + 1e-4f)
            assertEquals(zoom, z.ispZoom * z.glZoom, 1e-3f)
            // The visible region lies inside the crop, and the crop inside the sensor.
            assertTrue(abs(cx - z.cropCx) + view <= crop + 1e-4f)
            assertTrue(abs(cy - z.cropCy) + view <= crop + 1e-4f)
            assertTrue(z.cropCx - crop >= -1e-4f && z.cropCx + crop <= 1f + 1e-4f)
            // The GPU offset puts the view centre where it belongs inside the crop.
            assertEquals(cx, z.cropCx + z.glOffsetX / z.ispZoom, 1e-5f)
            if (centerOnly) assertEquals(0.5f, z.cropCx, 0f) else assertEquals(0f, z.glOffsetX, 0f)
        }
    }

    @Test
    fun steadyGateWaitsForTheJoltToDecay() {
        // A tap impulse: 0.3 rad/s ringing at 12 Hz, decaying with a 60 ms time constant, plus tremor.
        val threshold = 0.04f
        val gate = SteadyGate(threshold, holdNs = 120_000_000L)
        val r = Random(9)
        var firedAt = -1L
        var t = 0L
        while (t < 2_000_000_000L && firedAt < 0) {
            val s = t / 1e9
            val ring = 0.3 * exp(-s / 0.06) * kotlin.math.cos(2 * Math.PI * 12 * s)
            val tremor = r.nextGaussian() * 0.004
            if (gate.offer(t, (ring + tremor).toFloat(), (tremor * 0.5).toFloat(), 0f)) firedAt = t
            t += 5_000_000L // 200 Hz
        }
        // The envelope drops below the threshold at 60 ms * ln(0.3 / 0.04) ≈ 121 ms; then 120 ms of calm.
        assertTrue("fired at ${firedAt / 1e6} ms", firedAt in 200_000_000L..330_000_000L)
        // Constant shaking above the threshold never opens the gate.
        val shaky = SteadyGate(threshold, 120_000_000L)
        assertTrue((0 until 400).none { shaky.offer(it * 5_000_000L, 0.05f, 0f, 0f) })
        // Note10-like main camera at 1/30 s: a 1.5 px budget is stricter than hand tremor, so the floor applies.
        assertEquals(SteadyGate.MIN_RAD_S, SteadyGate.threshold(1.4f, 4.3f, 33_333_333L), 1e-6f)
        // At 1/200 s the budget itself (1.5 * 1.4 µm / (4.3 mm * 5 ms) ≈ 0.098 rad/s) applies.
        assertEquals(0.0977f, SteadyGate.threshold(1.4f, 4.3f, 5_000_000L), 1e-3f)
    }

    @Test
    fun calibrationCorrectsTheLensModel() {
        val model = { d: Float -> Optics.objectPixelUm(1.4f, 4.3f, Optics.distanceMm(d)) }
        // No calibration: the model, marked approximate.
        val bare = Scale.estimate(model, null, 12f)!!
        assertEquals(model(12f), bare.umPerSensorPixel, 1e-4f)
        assertTrue(!bare.calibrated)
        // A ruler says the model is 8 % high at 12 D: exact there and calibrated.
        val cal = Calibration(12f, model(12f) / 1.08f)
        val at = Scale.estimate(model, cal, 12f)!!
        assertEquals(cal.umPerSensorPixel, at.umPerSensorPixel, 1e-5f)
        assertTrue(at.calibrated)
        // Within 2 %: still calibrated, scaled by the model's ratio.
        val near = Scale.estimate(model, cal, 12.2f)!!
        assertTrue(near.calibrated)
        assertEquals(model(12.2f) / 1.08f, near.umPerSensorPixel, 1e-4f)
        // Far away: corrected, but approximate.
        val far = Scale.estimate(model, cal, 8f)!!
        assertTrue(!far.calibrated)
        assertEquals(model(8f) / 1.08f, far.umPerSensorPixel, 1e-4f)
        // A lens without metric focus: the calibration holds only at its own setting.
        val c2 = Calibration(0.6f, 10f)
        assertEquals(10f, Scale.estimate(null, c2, 0.6f)!!.umPerSensorPixel, 0f)
        assertTrue(Scale.estimate(null, c2, 0.4f) == null)
        assertTrue(Scale.estimate(null, null, 0.6f) == null)
        // Stored form round-trips; junk is rejected.
        assertEquals(cal, Calibration.decode(Calibration.encode(cal)))
        assertTrue(Calibration.decode("x;1") == null && Calibration.decode("1;-2") == null && Calibration.decode(null) == null)
    }

    @Test
    fun measuredLengthLabels() {
        assertEquals("84.2 µm", Optics.formatMeasure(84.2f))
        assertEquals("512 µm", Optics.formatMeasure(512.3f))
        assertEquals("1.27 mm", Optics.formatMeasure(1270f))
        assertEquals("12.4 mm", Optics.formatMeasure(12_400f))
    }

    @Test
    fun kernelSteeringHelpsEdgesWithoutHurtingTexture() {
        // Sharp-edged scene: discs and bars at random angles, values 50..210, 2x ground truth.
        val r = Random(11)
        val discs = Array(14) { doubleArrayOf(r.nextDouble() * 320, r.nextDouble() * 240, 8 + r.nextDouble() * 30, 50 + r.nextDouble() * 160) }
        val bars = Array(10) { doubleArrayOf(r.nextDouble() * 320, r.nextDouble() * 240, r.nextDouble() * Math.PI, 2 + r.nextDouble() * 8, 50 + r.nextDouble() * 160) }
        fun at(x: Double, y: Double): Double {
            var v = 128.0
            for (d in discs) if ((x - d[0]) * (x - d[0]) + (y - d[1]) * (y - d[1]) < d[2] * d[2]) v = d[3]
            for (b in bars) if (abs(-(x - b[0]) * kotlin.math.sin(b[2]) + (y - b[1]) * kotlin.math.cos(b[2])) < b[3]) v = b[4]
            return v
        }
        // Mean over a w x w square centred on (x, y), by 6x6 supersampling.
        fun box(x: Double, y: Double, w: Double): Double {
            var acc = 0.0
            for (j in 0 until 6) for (i in 0 until 6) acc += at(x + ((i + 0.5) / 6 - 0.5) * w, y + ((j + 0.5) / 6 - 0.5) * w)
            return acc / 36
        }
        val w = 160
        val h = 120
        val frames = (0 until 8).map { k ->
            val dx = if (k == 0) 0.0 else r.nextDouble() * 2 - 1
            val dy = if (k == 0) 0.0 else r.nextDouble() * 2 - 1
            val p = Plane(w, h)
            for (y in 0 until h) for (x in 0 until w) p.data[y * w + x] = (box(2 * (x + dx) + 0.5, 2 * (y + dy) + 0.5, 2.0) + r.nextGaussian() * 3).toFloat()
            frameOf(p)
        }
        val truth = Plane(2 * w, 2 * h)
        for (y in 0 until 2 * h) for (x in 0 until 2 * w) truth.data[y * 2 * w + x] = box(x.toDouble(), y.toDouble(), 1.0).toFloat()
        val warps = Pipeline.alignToFirst(frames)
        val steered = psnr(lumaOf(SuperResolution.merge(frames, warps, 2, 0f)), truth, 8)
        val plain = psnr(lumaOf(SuperResolution.merge(frames, warps, 2, 0f, steer = 0f)), truth, 8)

        // The band-limited texture of burstMergeBeatsSingleFrameUpscale: steering must not hurt it.
        val sc = Scene(4, 1.1)
        val rt = Random(5)
        val boxed = { u: Double, v: Double -> 0.25 * (sc.at(u - 0.5, v - 0.5) + sc.at(u + 0.5, v - 0.5) + sc.at(u - 0.5, v + 0.5) + sc.at(u + 0.5, v + 0.5)) }
        val tex = (0 until 8).map { k ->
            val dx = if (k == 0) 0.0 else rt.nextDouble() * 2 - 1
            val dy = if (k == 0) 0.0 else rt.nextDouble() * 2 - 1
            val p = Plane(w, h)
            for (y in 0 until h) for (x in 0 until w) p.data[y * w + x] = (boxed(2 * (x + dx) + 0.5, 2 * (y + dy) + 0.5) + rt.nextGaussian() * 3).toFloat()
            frameOf(p)
        }
        val texTruth = Plane(2 * w, 2 * h)
        for (y in 0 until 2 * h) for (x in 0 until 2 * w) texTruth.data[y * 2 * w + x] = boxed(x.toDouble(), y.toDouble()).toFloat()
        val tw = Pipeline.alignToFirst(tex)
        val texSteered = psnr(lumaOf(SuperResolution.merge(tex, tw, 2, 0f)), texTruth, 8)
        val texPlain = psnr(lumaOf(SuperResolution.merge(tex, tw, 2, 0f, steer = 0f)), texTruth, 8)
        println("edges: steered %.2f dB, isotropic %.2f dB; texture: steered %.2f dB, isotropic %.2f dB".format(steered, plain, texSteered, texPlain))
        assertTrue("steering should sharpen edges: $steered vs $plain dB", steered > plain + 0.5)
        assertTrue("steering must not cost texture: $texSteered vs $texPlain dB", texSteered > texPlain - 0.1)
    }

    @Test
    fun focusVariationRecoversTheDepthRamp() {
        val n = 7
        val w = 336
        val h = 120
        val frames = depthRampBracket(n, w, h, noise = 1.5, seed = 21)
        // Evenly spaced in diopters, near to far, as Optics.bracket plans them.
        val diopters = FloatArray(n) { 12f - 0.5f * it }
        val warps = List(n) { Affine.IDENTITY }
        for (maxCells in intArrayOf(w * h, w * h / 4)) {
            val map = DepthFromFocus.estimate(frames, warps, diopters, maxCells)
            val st = map.step
            var se = 0.0
            var count = 0
            var inside = 0
            for (gy in 0 until map.height) for (gx in 0 until map.width) {
                val cx = gx * st + 0.5 * (st - 1)
                val cy = gy * st + 0.5 * (st - 1)
                val truth = cx / w * (n - 1)
                if (truth < 0.75 || truth > n - 1.75 || cy < 8 || cy > h - 9) continue
                inside++
                val est = map.index[gy * map.width + gx]
                if (est.isNaN()) continue
                se += (est - truth) * (est - truth)
                count++
            }
            val rms = sqrt(se / count)
            val coverage = count.toDouble() / inside
            println("focus variation, grid step $st: slice RMS error %.3f, coverage %.1f %%".format(rms, 100 * coverage))
            assertTrue("slice error $rms", rms < 0.2)
            assertTrue("coverage $coverage", coverage > 0.9)
            // Heights follow the distances the slices were focused at: the mid-height column sits
            // at the difference between the far end and slice 3, within the slice error.
            val col = (3.0 / (n - 1) * w / st).toInt()
            val row = map.height / 2
            val expected = Optics.distanceMm(diopters[5]) - Optics.distanceMm(diopters[3])
            val far = (0 until map.width).maxOf { gx -> map.index[row * map.width + gx].takeIf { !it.isNaN() } ?: 0f }
            val got = map.mm[row * map.width + col] - (map.mm.filterIndexed { i, v -> !v.isNaN() && i / map.width == row && map.index[i] >= far - 0.1f }.average().toFloat())
            assertTrue("height of slice 3 above the far end: $got vs ≈ $expected mm", abs(got - expected) < 0.25f * expected)
        }
        // A surface with no texture has no focus curve: nothing may be reported.
        val flat = List(n) { frameOf(Plane(w, h, FloatArray(w * h) { 128f + (Random(it.toLong()).nextGaussian() * 0.0).toFloat() })) }
        assertEquals(0, DepthFromFocus.estimate(flat, warps, diopters).validCount)
        // Pure noise has focus curves with no consistent peak: almost nothing is reported.
        val noise = Random(5)
        val noisy = List(n) { frameOf(Plane(w, h, FloatArray(w * h) { (128 + noise.nextGaussian() * 4).toFloat() })) }
        val nm = DepthFromFocus.estimate(noisy, warps, diopters)
        println("focus variation on pure noise: %.2f %% of cells reported".format(100.0 * nm.validCount / (nm.width * nm.height)))
        assertTrue("noise gave ${nm.validCount} of ${nm.width * nm.height} heights", nm.validCount < 0.02 * nm.width * nm.height)
    }
}
