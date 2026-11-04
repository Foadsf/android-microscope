package io.github.foadsf.microscope.imaging

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Gwyddion Simple Field (.gsf) export of a [HeightMap], for Gwyddion and other SPM software.
 *
 * Format (gwyddion.net/documentation/user-guide-en/gsf.html): the line "Gwyddion Simple Field 1.0",
 * "key = value" lines, one to four NUL bytes so the data starts at a multiple of 4, then XRes × YRes
 * little-endian float32 values, row by row from the top. Units are base SI (m). Unknown cells are
 * written as NaN, which Gwyddion 2.57 and later replace and mask on import.
 */
object Gsf {
    fun encode(map: HeightMap, umPerCell: Float, title: String): ByteArray {
        val cellM = umPerCell * 1e-6
        val header = buildString {
            append("Gwyddion Simple Field 1.0\n")
            append("XRes = ${map.width}\n")
            append("YRes = ${map.height}\n")
            append(String.format(Locale.ROOT, "XReal = %.9g\n", map.width * cellM))
            append(String.format(Locale.ROOT, "YReal = %.9g\n", map.height * cellM))
            append("XYUnits = m\n")
            append("ZUnits = m\n")
            append("Title = ${title.replace('\n', ' ').replace('\u0000', ' ')}\n")
        }.toByteArray(Charsets.UTF_8)
        val pad = 4 - header.size % 4 // 1 to 4 NULs
        val out = ByteBuffer.allocate(header.size + pad + 4 * map.mm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(header)
        repeat(pad) { out.put(0) }
        for (v in map.mm) out.putFloat(if (v.isNaN()) Float.NaN else v * 1e-3f)
        return out.array()
    }
}
