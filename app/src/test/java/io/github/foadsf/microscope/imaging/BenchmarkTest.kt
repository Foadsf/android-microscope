package io.github.foadsf.microscope.imaging

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/** Wall-clock timings of each pipeline stage at phone-like sizes. Off unless -Pbench=1. */
class BenchmarkTest {
    private fun frames(w: Int, h: Int, n: Int): List<YuvFrame> {
        val r = Random(1)
        return List(n) {
            val y = ByteArray(w * h) { i -> (((i % w) * 7 + (i / w) * 3 + r.nextInt(16)) and 0xFF).toByte() }
            val c = ByteArray(w * h / 4) { 128.toByte() }
            YuvFrame(w, h, y, c, c.copyOf())
        }
    }

    private inline fun <T> time(label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val v = block()
        println("%-34s %7d ms".format(label, (System.nanoTime() - t0) / 1_000_000))
        return v
    }

    @Test
    fun stages() {
        assumeTrue(System.getProperty("microscope.bench").orEmpty().isNotEmpty())
        println("cores: ${Runtime.getRuntime().availableProcessors()}")
        val sizes = if (System.getProperty("microscope.bench") == "small") listOf(1856 to 1392) else listOf(1856 to 1392, 4000 to 3000)
        for ((w, h) in sizes) {
            val f = frames(w, h, 9)
            val stackWarps = time("align chained 9 @ ${w}x$h") { Pipeline.alignChained(f, 4) }
            time("focus stack 9 @ ${w}x$h") { FocusStack.fuse(f, stackWarps) }
            val burst = f.take(8)
            val warps = time("align burst 8 @ ${w}x$h") { Pipeline.alignToFirst(burst) }
            val scale = Pipeline.superResScale(w * h)
            time("merge 8 @ ${w}x$h scale $scale") { SuperResolution.merge(burst, warps, scale, 0.5f) }
            if (scale == 2) time("merge 8 @ ${w}x$h unsteered") { SuperResolution.merge(burst, warps, scale, 0.5f, steer = 0f) }
            if (scale == 2) time("merge 8 @ ${w}x$h steered, warm") { SuperResolution.merge(burst, warps, scale, 0.5f) }
        }
    }
}
