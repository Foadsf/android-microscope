package io.github.foadsf.microscope.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How a requested view (centre cx, cy and zoom, all in normalised sensor coordinates) is split
 * between the camera's ISP crop and GPU magnification.
 *
 * The ISP crop keeps real sensor detail, so it does as much of the zoom as it can (up to
 * [maxIspZoom]). Where the HAL allows any crop position (freeform) the crop simply follows the
 * view. Where it only crops about the sensor centre, the crop stays centred, is made just large
 * enough to contain the view, and the GPU pans within it.
 */
class ZoomSplit(zoom: Float, cx: Float, cy: Float, maxIspZoom: Float, centerOnly: Boolean) {
    val ispZoom: Float
    val cropCx: Float
    val cropCy: Float

    init {
        val limit = max(1f, maxIspZoom)
        if (centerOnly) {
            val reach = max(abs(cx - 0.5f), abs(cy - 0.5f)) + 0.5f / zoom
            ispZoom = min(zoom, min(limit, 0.5f / reach)).coerceAtLeast(1f)
            cropCx = 0.5f
            cropCy = 0.5f
        } else {
            ispZoom = zoom.coerceIn(1f, limit)
            cropCx = cx
            cropCy = cy
        }
    }

    val glZoom = zoom / ispZoom

    /** Where the view centre sits inside the crop, measured from the crop centre in crop units. */
    val glOffsetX = (cx - cropCx) * ispZoom
    val glOffsetY = (cy - cropCy) * ispZoom

    companion object {
        /** The nearest allowed view centre: the ISP crop (freeform) or the view (centre-only) must stay on the sensor. */
        fun clampCentre(zoom: Float, x: Float, y: Float, maxIspZoom: Float, centerOnly: Boolean): Pair<Float, Float> {
            val half = if (centerOnly) 0.5f / zoom.coerceAtLeast(1f) else 0.5f / zoom.coerceIn(1f, max(1f, maxIspZoom))
            return x.coerceIn(half, 1f - half) to y.coerceIn(half, 1f - half)
        }
    }
}
