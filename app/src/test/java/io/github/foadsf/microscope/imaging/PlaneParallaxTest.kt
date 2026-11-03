package io.github.foadsf.microscope.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plane-plus-parallax against ground truth: a textured relief rendered by ray casting from known
 * hand-tilted poses, as a Note10 main camera (4.3 mm, 1.4 µm pixels) sees it at 80 mm, where one
 * pixel covers 24.6 µm of specimen.
 */
class PlaneParallaxTest {
    private val d = 80.0
    private val w = 320
    private val h = 240
    private val k = Intrinsics.focused(4.3, 1.4, d, (w - 1) / 2.0, (h - 1) / 2.0)
    private val texture = Scene(31, 0.8)

    /** Two bumps (+0.5 mm, −0.35 mm) and a ±0.1 mm ripple, in mm above the plane z = d. */
    private fun relief(x: Double, y: Double): Double {
        val b1 = 0.5 * exp(-((x - 1.2) * (x - 1.2) + (y - 0.6) * (y - 0.6)) / (2 * 1.0 * 1.0))
        val b2 = -0.35 * exp(-((x + 1.5) * (x + 1.5) + (y + 0.8) * (y + 0.8)) / (2 * 0.8 * 0.8))
        return b1 + b2 + 0.1 * sin(2 * PI * x / 2.5) * cos(2 * PI * y / 3.0)
    }

    /** A camera with centre [c] whose axes, in reference camera coordinates, are the columns of [g]. */
    private class Pose(val g: DoubleArray, val c: DoubleArray) {
        /** Camera rotation from the reference: X_view = R·X_ref + t with R = gᵀ. */
        val r get() = mat3Transpose(g)
    }

    /**
     * Tilted by [tiltDeg] towards azimuth [azDeg] about a pivot on the specimen ([pivotErrMm] off
     * it, as a hand would), and rolled by [rollDeg] about its own axis.
     */
    private fun tilted(tiltDeg: Double, azDeg: Double, rollDeg: Double, pivotErr: DoubleArray): Pose {
        val t = Math.toRadians(tiltDeg)
        val a = Math.toRadians(azDeg)
        val g = mat3Mul(Rotations.exp(-sin(a) * t, cos(a) * t, 0.0), Rotations.exp(0.0, 0.0, Math.toRadians(rollDeg)))
        // Rotating about P = (0, 0, d) moves the centre from 0 to P − g·P.
        val c = DoubleArray(3) { i -> (if (i == 2) d else 0.0) - g[3 * i + 2] * d + pivotErr[i] }
        return Pose(g, c)
    }

    /** Where the ray from [c] along [dir] (reference coordinates) meets the surface: (x, y, height). */
    private fun hit(c: DoubleArray, dir: DoubleArray, surface: (Double, Double) -> Double): DoubleArray {
        var z = 0.0
        var x = 0.0
        var y = 0.0
        repeat(12) {
            val s = (d - z - c[2]) / dir[2]
            x = c[0] + s * dir[0]
            y = c[1] + s * dir[1]
            z = surface(x, y)
        }
        return doubleArrayOf(x, y, z)
    }

    /** Ray-cast view; [glare] adds a view-fixed highlight (x, y, peak, sigma) that may saturate. */
    private fun render(
        pose: Pose, surface: (Double, Double) -> Double, rnd: Random, textured: Boolean = true,
        gain: Double = 1.0, glare: DoubleArray? = null,
    ): YuvFrame {
        val p = Plane(w, h)
        for (v in 0 until h) for (u in 0 until w) {
            val dv = doubleArrayOf((u - k.cx) / k.f, (v - k.cy) / k.f, 1.0)
            val dir = DoubleArray(3) { i -> pose.g[3 * i] * dv[0] + pose.g[3 * i + 1] * dv[1] + pose.g[3 * i + 2] * dv[2] }
            val q = hit(pose.c, dir, surface)
            // The texture is painted on the specimen: reference pixel coordinates of a flat plane.
            val tex = if (textured) 128 + 0.7 * (texture.at(q[0] * k.f / d + k.cx, q[1] * k.f / d + k.cy) - 128) else 128.0
            var value = gain * tex + rnd.nextGaussian() * 1.5
            if (glare != null) {
                val r2 = (u - glare[0]) * (u - glare[0]) + (v - glare[1]) * (v - glare[1])
                value += glare[2] * exp(-r2 / (2 * glare[3] * glare[3]))
            }
            p.data[v * w + u] = value.toFloat()
        }
        return frameOf(p)
    }

    /** Small random rotation, as the error of an integrated gyroscope. */
    private fun gyroError(rnd: Random, sigmaRad: Double) =
        Rotations.exp(rnd.nextGaussian() * sigmaRad, rnd.nextGaussian() * sigmaRad, rnd.nextGaussian() * sigmaRad)

    private val reference = Pose(Rotations.IDENTITY, doubleArrayOf(0.0, 0.0, 0.0))

    /** Six views around the ring at 4–7°, with hand errors: pivot off by up to ±0.5 mm, roll ±3°. */
    private fun ring(rnd: Random) = (0 until 6).map { i ->
        tilted(
            4.0 + 3.0 * rnd.nextDouble(), 60.0 * i + 20 * (rnd.nextDouble() - 0.5), 6 * (rnd.nextDouble() - 0.5),
            doubleArrayOf(rnd.nextGaussian() * 0.25, rnd.nextGaussian() * 0.25, rnd.nextGaussian() * 0.5),
        )
    }

    /** Height error after removing the best-fitting plane (the reference plane is a gauge), and coverage. */
    private class Score(val rms: Double, val reliefRms: Double, val coverage: Double, val cells: List<Int>)

    private fun score(map: HeightMap, surface: (Double, Double) -> Double, border: Int, only: ((Double, Double) -> Boolean)? = null): Score {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val err = ArrayList<Double>()
        val truthList = ArrayList<Double>()
        val cells = ArrayList<Int>()
        var inside = 0
        for (gy in 0 until map.height) for (gx in 0 until map.width) {
            val u = gx * map.step + 0.5 * (map.step - 1)
            val v = gy * map.step + 0.5 * (map.step - 1)
            if (u < border || v < border || u > w - 1 - border || v > h - 1 - border) continue
            if (only != null && !only(u, v)) continue
            inside++
            val est = map.mm[gy * map.width + gx]
            if (est.isNaN()) continue
            val truth = hit(reference.c, doubleArrayOf((u - k.cx) / k.f, (v - k.cy) / k.f, 1.0), surface)[2]
            xs.add(u); ys.add(v); err.add(est - truth); truthList.add(truth)
            cells.add(gy * map.width + gx)
        }
        if (err.isEmpty()) return Score(Double.NaN, Double.NaN, 0.0, cells)
        return Score(sqrt(planeResidual(xs, ys, err)), sqrt(planeResidual(xs, ys, truthList)), err.size.toDouble() / inside, cells)
    }

    /** Mean squared residual of [z] after a least-squares plane in (x, y). */
    private fun planeResidual(x: List<Double>, y: List<Double>, z: List<Double>): Double {
        val m = DoubleArray(9)
        val b = DoubleArray(3)
        for (i in z.indices) {
            val row = doubleArrayOf(1.0, x[i], y[i])
            for (r in 0 until 3) {
                b[r] += row[r] * z[i]
                for (c in 0 until 3) m[3 * r + c] += row[r] * row[c]
            }
        }
        val p = solveLinear(m, b, 3)!!
        var se = 0.0
        for (i in z.indices) {
            val e = z[i] - p[0] - p[1] * x[i] - p[2] * y[i]
            se += e * e
        }
        return se / z.size
    }

    @Test
    fun homographyAlignmentRecoversATiltedPlane() {
        // A flat specimen seen from a 6° tilt: exactly a homography of the reference.
        val rnd = Random(40)
        val flat = { _: Double, _: Double -> 0.0 }
        val view = tilted(6.0, 35.0, 2.0, doubleArrayOf(0.3, -0.2, 0.4))
        val ref = render(reference, flat, rnd)
        val mov = render(view, flat, rnd)
        // Truth: H = K (R + t nᵀ / d) K⁻¹ with t = −R·c.
        val r = view.r
        val t = DoubleArray(3) { i -> -(r[3 * i] * view.c[0] + r[3 * i + 1] * view.c[1] + r[3 * i + 2] * view.c[2]) }
        val m = DoubleArray(9) { r[it] + if (it % 3 == 2) t[it / 3] / d else 0.0 }
        val truth = Homography(mat3Mul(k.matrix, mat3Mul(m, k.inverse)))
        val est = Aligner.alignHomography(Aligner.pyramid(ref.lumaPlane()), Aligner.pyramid(mov.lumaPlane()))
        var worst = 0.0
        for ((x, y) in listOf(0.0 to 0.0, w - 1.0 to 0.0, 0.0 to h - 1.0, w - 1.0 to h - 1.0, w / 2.0 to h / 2.0)) {
            val dx = est.mapX(x, y) - truth.mapX(x, y)
            val dy = est.mapY(x, y) - truth.mapY(x, y)
            worst = maxOf(worst, sqrt(dx * dx + dy * dy))
        }
        println("homography on a 6° tilt: worst corner error %.3f px".format(worst))
        assertTrue("corner error $worst px", worst < 0.1)
        // The similarity model cannot follow the tilt's foreshortening as well.
        val sim = Aligner.align(Aligner.pyramid(ref.lumaPlane()), Aligner.pyramid(mov.lumaPlane()))
        var simWorst = 0.0
        for ((x, y) in listOf(0.0 to 0.0, w - 1.0 to 0.0, 0.0 to h - 1.0, w - 1.0 to h - 1.0)) {
            val dx = sim.mapX(x, y) - truth.mapX(x, y)
            val dy = sim.mapY(x, y) - truth.mapY(x, y)
            simWorst = maxOf(simWorst, sqrt(dx * dx + dy * dy))
        }
        println("similarity on the same tilt: worst corner error %.3f px".format(simWorst))
        assertTrue("homography should beat similarity: $worst vs $simWorst px", worst < simWorst)
    }

    @Test
    fun gyroTrackIntegratesAConstantRate() {
        val track = GyroTrack()
        // 0.5 rad/s about device y for 0.2 s at 200 Hz, then still.
        for (i in 0..40) track.offer(i * 5_000_000L, 0f, 0.5f, 0f)
        for (i in 41..60) track.offer(i * 5_000_000L, 0f, 0f, 0f)
        assertEquals(0.1, Rotations.angle(track.at(200_000_000L)!!), 1e-6)
        assertEquals(0.05, Rotations.angle(track.at(100_000_000L)!!), 1e-6)
        assertEquals(0.0525, Rotations.angle(track.at(105_000_000L)!!), 1e-6)
        assertEquals(0.1, Rotations.angle(track.latest()!!), 1e-6)
        // A rear camera with the usual 90° sensor turns device y into camera −x.
        val c = Rotations.cameraFromDevice(90)
        val r = Rotations.relativeCamera(c, Rotations.IDENTITY, track.latest()!!)
        val expected = Rotations.exp(0.1, 0.0, 0.0)
        for (i in 0 until 9) assertEquals(expected[i], r[i], 1e-9)
    }

    @Test
    fun tiltGuideBinsTheRing() {
        val guide = TiltGuide(6)
        val deg = Math.toRadians(1.0)
        assertEquals(null, guide.bin(2.0 * deg, 0.0)) // not tilted enough
        assertEquals(null, guide.bin(9.0 * deg, 0.0)) // too far
        assertEquals(0, guide.bin(5.0 * deg, 10 * deg))
        assertEquals(1, guide.bin(5.0 * deg, 70 * deg))
        assertEquals(5, guide.bin(5.0 * deg, -10 * deg))
        guide.fill(1)
        assertEquals(null, guide.wanted(5.0 * deg, 70 * deg))
        assertEquals(2, guide.wanted(5.0 * deg, 130 * deg))
        for (b in 0 until 6) guide.fill(b)
        assertTrue(guide.complete)
        // Rotations.tilt reads the direction the optical axis leans to, in the reference image.
        val (tilt, direction) = Rotations.tilt(mat3Transpose(Rotations.exp(0.0, 0.08, 0.0)))
        assertEquals(0.08, tilt, 1e-12)
        assertEquals(0.0, direction, 1e-12)
    }

    @Test
    fun parallaxRecoversTheReliefFromTiltedViews() {
        val rnd = Random(41)
        val surface = ::relief
        val poses = ring(rnd)
        val frames = ArrayList<YuvFrame>()
        frames.add(render(reference, surface, rnd))
        // Exposure drifts a little between views; two views carry the LED's glare, one saturated.
        val glare = mapOf(1 to doubleArrayOf(110.0, 90.0, 320.0, 7.0), 3 to doubleArrayOf(210.0, 150.0, 90.0, 12.0))
        for ((i, pose) in poses.withIndex()) frames.add(render(pose, surface, rnd, gain = 1 + 0.04 * (rnd.nextDouble() - 0.5), glare = glare[i]))
        // The gyroscope's rotations are off by about 0.06° per axis.
        val rotations = listOf(Rotations.IDENTITY) + poses.map { mat3Mul(gyroError(rnd, 1e-3), it.r) }
        val t0 = System.nanoTime()
        val result = PlaneParallax.estimate(frames, rotations, k, d)
        val ms = (System.nanoTime() - t0) / 1e6
        for ((i, v) in result.views.withIndex()) {
            println("view %d: tilt %.1f°, baseline %.3f, gyro mismatch %.3f°, %s".format(i + 1, v.tiltDeg, v.baseline, v.gyroMismatchDeg, v.reason ?: "used"))
        }
        assertTrue(result.views.all { it.used })
        val s = score(result.height, surface, border = 16)
        println(
            "plane+parallax, %d views, %d × %d: height RMS error %.1f µm (relief RMS %.0f µm), coverage %.1f %%, %.0f ms"
                .format(poses.size, w, h, 1000 * s.rms, 1000 * s.reliefRms, 100 * s.coverage, ms),
        )
        assertTrue("height error ${1000 * s.rms} µm", s.rms < 0.012)
        assertTrue("coverage ${s.coverage}", s.coverage > 0.9)
        // Where the glare fell, heights are still right (or left unknown), not pulled by the highlight.
        val glared = { u: Double, v: Double ->
            glare.values.any { g -> (u - g[0]) * (u - g[0]) + (v - g[1]) * (v - g[1]) < (2.5 * g[3]) * (2.5 * g[3]) }
        }
        val inGlare = score(result.height, surface, border = 16, only = glared)
        // Control: the same views without the glare mask and view trimming.
        val naive = score(PlaneParallax.estimate(frames, rotations, k, d, rejectGlare = false).height, surface, border = 16, only = glared)
        println(
            "  inside the glare: height RMS error %.1f µm, coverage %.1f %%; without glare rejection %.1f µm, coverage %.1f %%"
                .format(1000 * inGlare.rms, 100 * inGlare.coverage, 1000 * naive.rms, 100 * naive.coverage),
        )
        assertTrue("glare error ${1000 * inGlare.rms} µm", inGlare.rms < 0.012)
        assertTrue("glare rejection should help: ${inGlare.rms} vs ${naive.rms} mm", inGlare.rms < 0.7 * naive.rms)
    }

    @Test
    fun noReliefWithoutParallaxOrTexture() {
        val rnd = Random(42)
        // A flat specimen: plane-plus-parallax must not invent relief.
        val flat = { _: Double, _: Double -> 0.0 }
        val poses = ring(rnd)
        val frames = listOf(render(reference, flat, rnd)) + poses.map { render(it, flat, rnd) }
        val rotations = listOf(Rotations.IDENTITY) + poses.map { mat3Mul(gyroError(rnd, 1e-3), it.r) }
        val flatScore = score(PlaneParallax.estimate(frames, rotations, k, d).height, flat, border = 16)
        println("flat specimen: reported relief RMS %.1f µm, coverage %.1f %%".format(1000 * flatScore.rms, 100 * flatScore.coverage))
        assertTrue("flat relief ${1000 * flatScore.rms} µm", flatScore.rms < 0.006)

        // Turning the phone about its own lens instead of about the specimen gives no baseline:
        // every view is a pure homography whatever the relief, so no height may be reported.
        val surface = ::relief
        val turned = (0 until 4).map { i ->
            Pose(mat3Mul(Rotations.exp(0.005 * cos(i * PI / 2), 0.005 * sin(i * PI / 2), 0.0), Rotations.exp(0.0, 0.0, 0.03)), doubleArrayOf(0.0, 0.0, 0.0))
        }
        val turnedFrames = listOf(render(reference, surface, rnd)) + turned.map { render(it, surface, rnd) }
        val noBase = PlaneParallax.estimate(turnedFrames, listOf(Rotations.IDENTITY) + turned.map { it.r }, k, d)
        noBase.views.forEach { println("pure rotation view: baseline %.4f, %s".format(it.baseline, it.reason ?: "used")) }
        assertTrue(noBase.views.none { it.used })
        assertEquals(0, noBase.height.validCount)

        // A gyro that disagrees with the images (here: rotations inverted, as a wrong axis
        // convention would give) must not be turned into heights. In a field this narrow the
        // images cannot tell a tilt from the opposite tilt, so only each view's roll exposes it:
        // a view held with almost no roll can slip through, but never enough of them to measure.
        val reliefFrames = listOf(render(reference, surface, rnd)) + poses.map { render(it, surface, rnd) }
        val wrong = PlaneParallax.estimate(reliefFrames, listOf(Rotations.IDENTITY) + poses.map { it.g }, k, d)
        wrong.views.forEach { println("inverted gyro view: mismatch %.2f°, %s".format(it.gyroMismatchDeg, it.reason ?: "used")) }
        assertTrue(wrong.views.count { it.used } < 2)
        assertEquals(0, wrong.height.validCount)

        // No texture: nothing to match, nothing reported.
        val blank = listOf(render(reference, surface, rnd, textured = false)) + poses.map { render(it, surface, rnd, textured = false) }
        val none = PlaneParallax.estimate(blank, rotations, k, d)
        println("untextured: %d of %d cells reported".format(none.height.validCount, none.height.width * none.height.height))
        assertEquals(0, none.height.validCount)
    }
}
