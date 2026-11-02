package io.github.foadsf.microscope.imaging

// Gaussian and Laplacian pyramids with the 5-tap binomial kernel [1 4 6 4 1]/16
// (Burt & Adelson 1983, "The Laplacian Pyramid as a Compact Image Code", a = 0.375).
// Level-k pixel i sits at level-0 coordinate i * 2^k, because REDUCE keeps the even samples.

private const val K0 = 0.375f
private const val K1 = 0.25f
private const val K2 = 0.0625f

private fun clampIndex(i: Int, n: Int) = if (i < 0) 0 else if (i >= n) n - 1 else i

/** Blur with the binomial kernel, then keep every second sample in each direction. */
fun reduce(p: Plane): Plane {
    val w = p.width
    val h = p.height
    val w2 = (w + 1) / 2
    val h2 = (h + 1) / 2
    val tmp = FloatArray(w2 * h)
    parallelFor(h) { y ->
        val row = y * w
        val out = y * w2
        for (x2 in 0 until w2) {
            val x = 2 * x2
            val d = p.data
            tmp[out + x2] = K0 * d[row + x] +
                K1 * (d[row + clampIndex(x - 1, w)] + d[row + clampIndex(x + 1, w)]) +
                K2 * (d[row + clampIndex(x - 2, w)] + d[row + clampIndex(x + 2, w)])
        }
    }
    val res = Plane(w2, h2)
    parallelFor(h2) { y2 ->
        val y = 2 * y2
        val r0 = y * w2
        val rm1 = clampIndex(y - 1, h) * w2
        val rp1 = clampIndex(y + 1, h) * w2
        val rm2 = clampIndex(y - 2, h) * w2
        val rp2 = clampIndex(y + 2, h) * w2
        val out = y2 * w2
        for (x in 0 until w2) {
            res.data[out + x] = K0 * tmp[r0 + x] + K1 * (tmp[rm1 + x] + tmp[rp1 + x]) + K2 * (tmp[rm2 + x] + tmp[rp2 + x])
        }
    }
    return res
}

/**
 * Burt-Adelson EXPAND to [w] x [h]: zero-insertion followed by the same kernel times 4, which gives
 * 0.75*c + 0.125*(l + r) at even positions and 0.5*(l + r) at odd ones.
 */
fun expand(p: Plane, w: Int, h: Int): Plane {
    val sw = p.width
    val sh = p.height
    val tmp = FloatArray(w * sh)
    parallelFor(sh) { y ->
        val row = y * sw
        val out = y * w
        val d = p.data
        for (x in 0 until w) {
            val i = x shr 1
            tmp[out + x] = if (x and 1 == 0) {
                0.75f * d[row + clampIndex(i, sw)] + 0.125f * (d[row + clampIndex(i - 1, sw)] + d[row + clampIndex(i + 1, sw)])
            } else {
                0.5f * (d[row + clampIndex(i, sw)] + d[row + clampIndex(i + 1, sw)])
            }
        }
    }
    val res = Plane(w, h)
    parallelFor(h) { y ->
        val i = y shr 1
        val out = y * w
        if (y and 1 == 0) {
            val r0 = clampIndex(i, sh) * w
            val rm = clampIndex(i - 1, sh) * w
            val rp = clampIndex(i + 1, sh) * w
            for (x in 0 until w) res.data[out + x] = 0.75f * tmp[r0 + x] + 0.125f * (tmp[rm + x] + tmp[rp + x])
        } else {
            val r0 = clampIndex(i, sh) * w
            val r1 = clampIndex(i + 1, sh) * w
            for (x in 0 until w) res.data[out + x] = 0.5f * (tmp[r0 + x] + tmp[r1 + x])
        }
    }
    return res
}

/** Blur with the binomial kernel without decimation (a Gaussian of sigma = 1 px). */
fun blur(p: Plane): Plane {
    val w = p.width
    val h = p.height
    val tmp = FloatArray(w * h)
    parallelFor(h) { y ->
        val row = y * w
        val d = p.data
        for (x in 0 until w) {
            tmp[row + x] = K0 * d[row + x] +
                K1 * (d[row + clampIndex(x - 1, w)] + d[row + clampIndex(x + 1, w)]) +
                K2 * (d[row + clampIndex(x - 2, w)] + d[row + clampIndex(x + 2, w)])
        }
    }
    val res = Plane(w, h)
    parallelFor(h) { y ->
        val r0 = y * w
        val rm1 = clampIndex(y - 1, h) * w
        val rp1 = clampIndex(y + 1, h) * w
        val rm2 = clampIndex(y - 2, h) * w
        val rp2 = clampIndex(y + 2, h) * w
        for (x in 0 until w) {
            res.data[r0 + x] = K0 * tmp[r0 + x] + K1 * (tmp[rm1 + x] + tmp[rp1 + x]) + K2 * (tmp[rm2 + x] + tmp[rp2 + x])
        }
    }
    return res
}

/** Levels 0..n-1, level 0 being [p] itself. Stops early once a side would drop below [minSide]. */
fun gaussianPyramid(p: Plane, maxLevels: Int, minSide: Int = 16): List<Plane> {
    val levels = ArrayList<Plane>()
    levels.add(p)
    while (levels.size < maxLevels) {
        val last = levels.last()
        if (minOf(last.width, last.height) / 2 < minSide) break
        levels.add(reduce(last))
    }
    return levels
}

/** Band-pass levels L0..L(n-2) followed by the low-pass residual G(n-1). */
fun laplacianPyramid(p: Plane, levels: Int): List<Plane> {
    val g = gaussianPyramid(p, levels, minSide = 1)
    val out = ArrayList<Plane>(g.size)
    for (k in 0 until g.size - 1) {
        val up = expand(g[k + 1], g[k].width, g[k].height)
        val l = Plane(g[k].width, g[k].height)
        val a = g[k].data
        for (i in l.data.indices) l.data[i] = a[i] - up.data[i]
        out.add(l)
    }
    out.add(g.last())
    return out
}

/** Inverse of [laplacianPyramid]. */
fun collapse(pyr: List<Plane>): Plane {
    var acc = pyr.last()
    for (k in pyr.size - 2 downTo 0) {
        val band = pyr[k]
        val up = expand(acc, band.width, band.height)
        for (i in up.data.indices) up.data[i] += band.data[i]
        acc = up
    }
    return acc
}

/** In-place unsharp mask: p + amount * (p - blur(p)). */
fun unsharp(p: Plane, amount: Float) {
    if (amount <= 0f) return
    val b = blur(p)
    for (i in p.data.indices) {
        val v = p.data[i] + amount * (p.data[i] - b.data[i])
        p.data[i] = if (v < 0f) 0f else if (v > 255f) 255f else v
    }
}
