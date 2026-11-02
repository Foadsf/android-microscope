package io.github.foadsf.microscope.imaging

import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream

/** What a press of the shutter produces. */
enum class CaptureMode { PHOTO, SUPER_RES, FOCUS_STACK }

/** Registration plus fusion for each [CaptureMode]. */
object Pipeline {
    /** Output scale for a super-resolution merge of a [roiPixels]-pixel crop, bounded by memory. */
    fun superResScale(roiPixels: Int): Int = if (roiPixels <= 3_200_000) 2 else 1

    fun process(
        mode: CaptureMode,
        frames: List<YuvFrame>,
        sharpen: Float,
        progress: (Float) -> Unit = {},
    ): RgbImage = when {
        frames.size == 1 || mode == CaptureMode.PHOTO -> frames[0].toRgb().also { progress(1f) }
        mode == CaptureMode.SUPER_RES -> {
            val warps = alignToFirst(frames) { progress(0.3f * it) }
            val scale = superResScale(frames[0].width * frames[0].height)
            SuperResolution.merge(frames, warps, scale, sharpen) { progress(0.3f + 0.7f * it) }
        }
        else -> {
            val warps = alignChained(frames, frames.size / 2) { progress(0.3f * it) }
            FocusStack.fuse(frames, warps) { progress(0.3f + 0.7f * it) }
        }
    }

    /** Every frame registered directly to frame 0 (a handheld burst: all frames look alike). */
    fun alignToFirst(frames: List<YuvFrame>, progress: (Float) -> Unit = {}): List<Affine> {
        val ref = Aligner.pyramid(frames[0].lumaPlane())
        val done = AtomicInteger()
        val rest = concurrently(frames.size - 1, frames[0]) { i ->
            Aligner.align(ref, Aligner.pyramid(frames[i + 1].lumaPlane())).also {
                progress(done.incrementAndGet().toFloat() / (frames.size - 1))
            }
        }
        return listOf(Affine.IDENTITY) + rest
    }

    /**
     * A focus bracket registered outwards from [refIndex], each frame against its neighbour, since
     * neighbours share most of their blur while the ends of the bracket may share almost nothing.
     * Returned warps map reference coordinates to each frame's coordinates.
     */
    fun alignChained(frames: List<YuvFrame>, refIndex: Int, progress: (Float) -> Unit = {}): List<Affine> {
        // Pair k registers frame k (the one nearer the reference) to its outer neighbour.
        val pairs = frames.indices.filter { it != refIndex }.map { i -> (if (i > refIndex) i - 1 else i + 1) to i }
        val done = AtomicInteger()
        val steps = concurrently(pairs.size, frames[0]) { k ->
            val (inner, outer) = pairs[k]
            Aligner.align(Aligner.pyramid(frames[inner].lumaPlane()), Aligner.pyramid(frames[outer].lumaPlane())).also {
                progress(done.incrementAndGet().toFloat() / pairs.size)
            }
        }
        val warps = arrayOfNulls<Affine>(frames.size)
        warps[refIndex] = Affine.IDENTITY
        for (direction in intArrayOf(1, -1)) {
            var i = refIndex + direction
            while (i in frames.indices) {
                val step = steps[pairs.indexOfFirst { it.second == i }] // inner coords -> frame i coords
                warps[i] = step.compose(warps[i - direction]!!)
                i += direction
            }
        }
        return warps.map { it!! }
    }

    /**
     * Runs [n] independent registrations on as many cores as memory allows: each needs one or two
     * float pyramids (≈ 5.4 bytes per pixel each) of a frame the size of [like].
     */
    private fun <T> concurrently(n: Int, like: YuvFrame, task: (Int) -> T): List<T> {
        if (n <= 0) return emptyList()
        val perTask = 2L * like.width * like.height * 6
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val workers = (free / 2 / perTask).toInt().coerceIn(1, minOf(n, rt.availableProcessors()))
        val results = arrayOfNulls<Any>(n)
        val pool = ForkJoinPool(workers)
        try {
            pool.submit { IntStream.range(0, n).parallel().forEach { results[it] = task(it) } }.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdown()
        }
        @Suppress("UNCHECKED_CAST")
        return results.map { it as T }
    }
}
