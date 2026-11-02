package io.github.foadsf.microscope

import android.graphics.Rect
import android.util.Size
import io.github.foadsf.microscope.camera.Lens
import io.github.foadsf.microscope.gl.PreviewRenderer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps between the three coordinate systems in play:
 *  - view: normalised (0..1) position in the on-screen preview, origin top-left;
 *  - upright: normalised position in the whole sensor image as the user sees it (rotated upright);
 *  - buffer: normalised position in the sensor's own orientation (active array, YUV buffers).
 * The view shows the upright region centred on (cx, cy) with half-size 0.5 / zoom.
 */
class Geometry(val lens: Lens, val zoom: Float, val cx: Float, val cy: Float) {
    private val toBuffer = PreviewRenderer.uprightToBuffer(lens.sensorOrientation)

    /** Part of the zoom done by the ISP (real detail), the rest is GPU magnification. */
    val ispZoom = zoom.coerceIn(1f, max(1f, lens.maxDigitalZoom))
    val glZoom = zoom / ispZoom

    /** Upright image width / height. */
    val uprightAspect: Float
        get() {
            val w = lens.activeArray.width().toFloat()
            val h = lens.activeArray.height().toFloat()
            return if (lens.sensorOrientation % 180 == 0) w / h else h / w
        }

    /** Active-array pixels across the upright image's width. */
    val uprightSensorWidth: Int
        get() = if (lens.sensorOrientation % 180 == 0) lens.activeArray.width() else lens.activeArray.height()

    fun viewToUpright(vx: Float, vy: Float) = (cx + (vx - 0.5f) / zoom) to (cy + (vy - 0.5f) / zoom)

    /** The ISP crop (SCALER_CROP_REGION) for the preview. */
    fun cropRegion(): Rect = toActive(cx, cy, 0.5f / ispZoom)

    /** A metering rectangle around a tap at view position (vx, vy). */
    fun meteringRegion(vx: Float, vy: Float): Rect {
        val (ux, uy) = viewToUpright(vx, vy)
        return toActive(ux.coerceIn(0f, 1f), uy.coerceIn(0f, 1f), 0.06f / zoom)
    }

    /** What the user sees, in pixels of a [capture]-sized YUV frame, even-aligned. */
    fun captureRoi(capture: Size): Rect {
        val (u0, v0, u1, v1) = bufferRect(cx, cy, 0.5f / zoom)
        fun even(v: Float, limit: Int) = ((v * limit).roundToInt() / 2 * 2).coerceIn(0, limit)
        var l = even(u0, capture.width)
        var t = even(v0, capture.height)
        var r = even(u1, capture.width)
        var b = even(v1, capture.height)
        if (r - l < MIN_ROI) { l = max(0, min(capture.width - MIN_ROI, l)); r = l + MIN_ROI }
        if (b - t < MIN_ROI) { t = max(0, min(capture.height - MIN_ROI, t)); b = t + MIN_ROI }
        return Rect(l, t, r, b)
    }

    private fun toActive(ux: Float, uy: Float, half: Float): Rect {
        val (u0, v0, u1, v1) = bufferRect(ux, uy, half)
        val w = lens.activeArray.width()
        val h = lens.activeArray.height()
        return Rect(
            (u0 * w).roundToInt().coerceIn(0, w - 1), (v0 * h).roundToInt().coerceIn(0, h - 1),
            (u1 * w).roundToInt().coerceIn(1, w), (v1 * h).roundToInt().coerceIn(1, h),
        )
    }

    /** Buffer-normalised bounds of the upright square-in-aspect region centre ± half. */
    private fun bufferRect(ux: Float, uy: Float, half: Float): FloatArray {
        val xs = floatArrayOf(ux - half, ux + half)
        val ys = floatArrayOf(uy - half, uy + half)
        var u0 = 1f; var v0 = 1f; var u1 = 0f; var v1 = 0f
        for (x in xs) for (y in ys) {
            val u = toBuffer.mapX(x.toDouble(), y.toDouble()).toFloat()
            val v = toBuffer.mapY(x.toDouble(), y.toDouble()).toFloat()
            u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
        }
        return floatArrayOf(u0.coerceIn(0f, 1f), v0.coerceIn(0f, 1f), u1.coerceIn(0f, 1f), v1.coerceIn(0f, 1f))
    }

    companion object {
        private const val MIN_ROI = 64

        /** Keeps the centre where the ISP crop still fits inside the sensor. */
        fun clampCentre(lens: Lens, zoom: Float, x: Float, y: Float): Pair<Float, Float> {
            val half = 0.5f / zoom.coerceIn(1f, max(1f, lens.maxDigitalZoom))
            return x.coerceIn(half, 1f - half) to y.coerceIn(half, 1f - half)
        }
    }
}
