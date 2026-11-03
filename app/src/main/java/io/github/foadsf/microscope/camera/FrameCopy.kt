package io.github.foadsf.microscope.camera

import android.graphics.Rect
import android.media.Image
import io.github.foadsf.microscope.imaging.YuvFrame

/**
 * Copies [roi] (capture pixels, even-aligned) out of a `YUV_420_888` image into a compact planar
 * [YuvFrame], honouring each plane's row and pixel stride (chroma may be planar or interleaved).
 * Copying lets the image go back to the camera immediately.
 */
internal fun copyRoi(img: Image, roi: Rect): YuvFrame {
    val w = roi.width()
    val h = roi.height()
    val cw = w / 2
    val ch = h / 2
    val y = ByteArray(w * h)
    val u = ByteArray(cw * ch)
    val v = ByteArray(cw * ch)

    val yPlane = img.planes[0]
    val yBuf = yPlane.buffer.duplicate()
    for (r in 0 until h) {
        yBuf.position((roi.top + r) * yPlane.rowStride + roi.left)
        yBuf.get(y, r * w, w)
    }
    copyChroma(img.planes[1], roi, cw, ch, u)
    copyChroma(img.planes[2], roi, cw, ch, v)
    return YuvFrame(w, h, y, u, v, img.timestamp)
}

private fun copyChroma(plane: Image.Plane, roi: Rect, cw: Int, ch: Int, out: ByteArray) {
    val ps = plane.pixelStride
    val rs = plane.rowStride
    val buf = plane.buffer.duplicate()
    val row = if (ps == 1) null else ByteArray((cw - 1) * ps + 1)
    for (r in 0 until ch) {
        buf.position((roi.top / 2 + r) * rs + (roi.left / 2) * ps)
        if (row == null) {
            buf.get(out, r * cw, cw)
        } else {
            buf.get(row)
            val o = r * cw
            for (i in 0 until cw) out[o + i] = row[i * ps]
        }
    }
}
