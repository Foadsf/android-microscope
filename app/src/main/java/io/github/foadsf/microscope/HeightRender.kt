package io.github.foadsf.microscope

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.scale
import io.github.foadsf.microscope.imaging.HeightMap
import io.github.foadsf.microscope.imaging.RgbImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Pictures and point clouds of a [HeightMap]. */
object HeightRender {
    /** Viridis at 0, 1/8, … 1: perceptually uniform, and readable in greyscale and by most colour-blind people. */
    private val VIRIDIS = intArrayOf(
        0x440154, 0x472C7A, 0x3B518B, 0x2C718E, 0x21908D, 0x27AD81, 0x5CC863, 0xAADC32, 0xFDE725,
    )
    private const val UNKNOWN = 0xFF303030.toInt()

    fun viridis(t: Float): Int {
        val x = t.coerceIn(0f, 1f) * (VIRIDIS.size - 1)
        val i = x.toInt().coerceAtMost(VIRIDIS.size - 2)
        val f = x - i
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(VIRIDIS[i], s) + (ch(VIRIDIS[i + 1], s) - ch(VIRIDIS[i], s)) * f).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * The map in viridis (unknown cells dark grey), scaled to [outW] × [outH] so it lines up with the
     * fused picture, with a legend in the corner. [approximate] marks the heights "≈".
     */
    fun picture(map: HeightMap, outW: Int, outH: Int, approximate: Boolean): Bitmap {
        val top = map.maxMm?.takeIf { it > 0f } ?: 1f
        val px = IntArray(map.width * map.height) { i ->
            val v = map.mm[i]
            if (v.isNaN()) UNKNOWN else viridis(v / top)
        }
        val small = Bitmap.createBitmap(px, map.width, map.height, Bitmap.Config.ARGB_8888)
        val out = small.scale(outW, outH, filter = false).copy(Bitmap.Config.ARGB_8888, true)
        small.recycle()

        val c = Canvas(out)
        val unit = outW / 40f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6EDF3.toInt(); textSize = unit }
        val label = "height " + (if (approximate) "≈ " else "") + "0 – " + "%.2f mm".format(Locale.ROOT, top)
        val barW = unit * 10
        val boxW = maxOf(barW, text.measureText(label)) + unit * 1.6f
        val boxH = unit * 3.6f
        val x0 = unit
        val y0 = outH - unit - boxH
        c.drawRoundRect(RectF(x0, y0, x0 + boxW, y0 + boxH), unit / 2, unit / 2, Paint().apply { color = 0x99000000.toInt() })
        val bx = x0 + unit * 0.8f
        val by = y0 + unit * 0.8f
        val bar = Paint().apply {
            shader = LinearGradient(bx, 0f, bx + barW, 0f, IntArray(VIRIDIS.size) { 0xFF shl 24 or VIRIDIS[it] }, null, Shader.TileMode.CLAMP)
        }
        c.drawRect(bx, by, bx + barW, by + unit * 0.9f, bar)
        c.drawText(label, bx, by + unit * 2.3f, text)
        return out
    }

    /**
     * Binary PLY point cloud, in mm: x right, y up, z the height; coloured from [colours] (the fused
     * picture, same orientation as [map]). Unknown cells are left out.
     */
    fun ply(map: HeightMap, colours: RgbImage, umPerCell: Float): ByteArray {
        val points = map.validCount
        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\n")
            append("comment Microscope: surface height by focus variation; units mm\n")
            append("element vertex $points\n")
            append("property float x\nproperty float y\nproperty float z\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            append("end_header\n")
        }.toByteArray(Charsets.US_ASCII)
        val body = ByteBuffer.allocate(points * 15).order(ByteOrder.LITTLE_ENDIAN)
        val mmPerCell = umPerCell / 1000f
        val sx = colours.width.toFloat() / map.width
        val sy = colours.height.toFloat() / map.height
        for (gy in 0 until map.height) for (gx in 0 until map.width) {
            val z = map.mm[gy * map.width + gx]
            if (z.isNaN()) continue
            body.putFloat(gx * mmPerCell)
            body.putFloat(-gy * mmPerCell)
            body.putFloat(z)
            val cx = ((gx + 0.5f) * sx).toInt().coerceIn(0, colours.width - 1)
            val cy = ((gy + 0.5f) * sy).toInt().coerceIn(0, colours.height - 1)
            val argb = colours.argb[cy * colours.width + cx]
            body.put((argb shr 16).toByte())
            body.put((argb shr 8).toByte())
            body.put(argb.toByte())
        }
        return ByteArrayOutputStream(header.size + points * 15).apply {
            write(header)
            write(body.array())
        }.toByteArray()
    }
}
