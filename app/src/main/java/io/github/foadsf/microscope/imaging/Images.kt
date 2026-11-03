package io.github.foadsf.microscope.imaging

import java.util.concurrent.ForkJoinWorkerThread
import java.util.stream.IntStream
import kotlin.math.min
import kotlin.math.roundToInt

// Pure Kotlin/JVM: nothing in this package touches the Android SDK, so the whole pipeline runs
// in plain JUnit tests.

/**
 * One planar YCbCr 4:2:0 frame, 8 bits per sample, full-range BT.601 (JFIF), which is how Camera2
 * delivers `YUV_420_888`. Width and height are even; the chroma planes are half size each way.
 * [timestampNs] is the sensor's start-of-exposure time (Camera2 SENSOR_TIMESTAMP), 0 if unknown.
 */
class YuvFrame(
    val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray, val timestampNs: Long = 0L,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "frame must be even-sized" }
        require(y.size == width * height) { "luma plane size" }
        require(u.size == width * height / 4 && v.size == u.size) { "chroma plane size" }
    }

    val chromaWidth get() = width / 2
    val chromaHeight get() = height / 2

    fun lumaPlane(): Plane = Plane(width, height, FloatArray(y.size) { (y[it].toInt() and 0xFF).toFloat() })
    fun uPlane(): Plane = Plane(chromaWidth, chromaHeight, FloatArray(u.size) { (u[it].toInt() and 0xFF).toFloat() })
    fun vPlane(): Plane = Plane(chromaWidth, chromaHeight, FloatArray(v.size) { (v[it].toInt() and 0xFF).toFloat() })

    /**
     * This frame with every [factor] × [factor] block averaged into one pixel (luma and chroma
     * alike), trimmed to even size: the same field at lower resolution, and less noise per pixel.
     */
    fun binned(factor: Int): YuvFrame {
        require(factor >= 1)
        if (factor == 1) return this
        val w2 = (width / factor) and -2
        val h2 = (height / factor) and -2
        require(w2 >= 2 && h2 >= 2) { "frame too small to bin by $factor" }
        fun bin(src: ByteArray, sw: Int, dw: Int, dh: Int): ByteArray {
            val out = ByteArray(dw * dh)
            val n = factor * factor
            for (y2 in 0 until dh) for (x2 in 0 until dw) {
                var sum = 0
                for (j in 0 until factor) {
                    val row = (y2 * factor + j) * sw + x2 * factor
                    for (i in 0 until factor) sum += src[row + i].toInt() and 0xFF
                }
                out[y2 * dw + x2] = ((sum + n / 2) / n).toByte()
            }
            return out
        }
        return YuvFrame(
            w2, h2, bin(y, width, w2, h2), bin(u, chromaWidth, w2 / 2, h2 / 2), bin(v, chromaWidth, w2 / 2, h2 / 2), timestampNs,
        )
    }
}

/** A single-channel float image, row-major. Values are on the 0..255 scale. */
class Plane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(data.size == width * height) { "plane size" }
    }

    operator fun get(x: Int, y: Int) = data[y * width + x]

    /** Bilinear sample with clamp-to-edge; (0,0) is the centre of the top-left pixel. */
    fun sample(x: Float, y: Float): Float = bilinear(data, width, height, x, y)
}

/** Packed 0xAARRGGBB pixels, row-major. */
class RgbImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "image size" }
    }

    /** Rotates clockwise by a multiple of 90 degrees. */
    fun rotated(degrees: Int): RgbImage {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return this
        val (w2, h2) = if (d == 180) width to height else height to width
        val out = IntArray(argb.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = when (d) {
                    90 -> x * w2 + (w2 - 1 - y)
                    180 -> (h2 - 1 - y) * w2 + (w2 - 1 - x)
                    else -> (h2 - 1 - x) * w2 + y
                }
                out[i] = argb[row + x]
            }
        }
        return RgbImage(w2, h2, out)
    }
}

internal fun bilinear(d: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
    val xc = if (x <= 0f) 0f else if (x >= w - 1) (w - 1).toFloat() else x
    val yc = if (y <= 0f) 0f else if (y >= h - 1) (h - 1).toFloat() else y
    val x0 = xc.toInt()
    val y0 = yc.toInt()
    val x1 = min(x0 + 1, w - 1)
    val y1 = min(y0 + 1, h - 1)
    val fx = xc - x0
    val fy = yc - y0
    val r0 = y0 * w
    val r1 = y1 * w
    val top = d[r0 + x0] + (d[r0 + x1] - d[r0 + x0]) * fx
    val bottom = d[r1 + x0] + (d[r1 + x1] - d[r1 + x0]) * fx
    return top + (bottom - top) * fy
}

internal fun bilinearU8(d: ByteArray, w: Int, h: Int, x: Float, y: Float): Float {
    val xc = if (x <= 0f) 0f else if (x >= w - 1) (w - 1).toFloat() else x
    val yc = if (y <= 0f) 0f else if (y >= h - 1) (h - 1).toFloat() else y
    val x0 = xc.toInt()
    val y0 = yc.toInt()
    val x1 = min(x0 + 1, w - 1)
    val y1 = min(y0 + 1, h - 1)
    val fx = xc - x0
    val fy = yc - y0
    val r0 = y0 * w
    val r1 = y1 * w
    val a = (d[r0 + x0].toInt() and 0xFF).toFloat()
    val b = (d[r0 + x1].toInt() and 0xFF).toFloat()
    val c = (d[r1 + x0].toInt() and 0xFF).toFloat()
    val e = (d[r1 + x1].toInt() and 0xFF).toFloat()
    val top = a + (b - a) * fx
    val bottom = c + (e - c) * fx
    return top + (bottom - top) * fy
}

/**
 * Runs [body] for 0 until [n] on all cores (the common ForkJoin pool). Called from inside another
 * parallel loop it runs sequentially: the outer loop already occupies every core, and nested
 * fine-grained tasks only add scheduling overhead.
 */
internal inline fun parallelFor(n: Int, crossinline body: (Int) -> Unit) {
    if (Thread.currentThread() is ForkJoinWorkerThread) {
        for (i in 0 until n) body(i)
    } else {
        IntStream.range(0, n).parallel().forEach { body(it) }
    }
}

private fun clamp8(v: Float): Int {
    val r = v.roundToInt()
    return if (r < 0) 0 else if (r > 255) 255 else r
}

/** Full-range BT.601 (JFIF) YCbCr to ARGB. */
internal fun ycbcrToArgb(y: Float, cb: Float, cr: Float): Int {
    val u = cb - 128f
    val v = cr - 128f
    val r = clamp8(y + 1.402f * v)
    val g = clamp8(y - 0.344136f * u - 0.714136f * v)
    val b = clamp8(y + 1.772f * u)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** Converts a luma plane plus chroma planes of any size (resampled to match) to RGB. */
fun toRgb(luma: Plane, cb: Plane, cr: Plane): RgbImage {
    val w = luma.width
    val h = luma.height
    val out = IntArray(w * h)
    val sx = cb.width.toFloat() / w
    val sy = cb.height.toFloat() / h
    parallelFor(h) { y ->
        val cy = (y + 0.5f) * sy - 0.5f
        val row = y * w
        for (x in 0 until w) {
            val cx = (x + 0.5f) * sx - 0.5f
            out[row + x] = ycbcrToArgb(luma.data[row + x], cb.sample(cx, cy), cr.sample(cx, cy))
        }
    }
    return RgbImage(w, h, out)
}

/** A single frame straight to RGB (chroma upsampled bilinearly). */
fun YuvFrame.toRgb(): RgbImage = toRgb(lumaPlane(), uPlane(), vPlane())

/**
 * Estimates the luma noise standard deviation from the median absolute horizontal difference
 * (robust to texture because most adjacent-pixel differences in a natural image are noise).
 */
fun estimateNoiseSigma(luma: Plane): Float {
    val step = maxOf(1, (luma.width * luma.height / 200_000))
    val diffs = ArrayList<Float>()
    var i = 0
    while (i < luma.data.size - 1) {
        if ((i + 1) % luma.width != 0) diffs.add(kotlin.math.abs(luma.data[i + 1] - luma.data[i]))
        i += step
    }
    if (diffs.isEmpty()) return 1f
    diffs.sort()
    val median = diffs[diffs.size / 2]
    // For Gaussian noise, MAD of the difference of two samples = 0.6745 * sqrt(2) * sigma.
    return (median / (0.6745f * 1.41421356f)).coerceAtLeast(0.5f)
}
