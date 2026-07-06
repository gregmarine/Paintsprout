package com.symmetricalpalmtree.paintsprout.paint

/**
 * Edge-aware magic-wand flood fill over the PAINT layer only (surface texture
 * never participates). Port of `_wandFloodFill` from the Flutter reference's
 * drawing_canvas.dart.
 *
 * From the seed it grows over pixels within [WandRequest.tolerance] of the
 * seed's RGBA AND not blocked by a drawn edge. Boundaries come from two signals:
 * paint coverage (the alpha channel), which catches even a faint pencil line the
 * way a colour signal can't, and luminance, which separates touching opaque
 * colours.
 *
 * For speed the heavy passes run at half resolution ([DOWNSAMPLE]), downsampling
 * by keeping the MAX-alpha sample of each 2×2 block (never averaging — that
 * would erase a faint speckly line). Steps: reduce, Sobel → barrier, dilate the
 * barrier by [WandRequest.gap] (seals speckle holes), scanline-fill inside it,
 * grow the result back up to the edge. Returns a half-res mask.
 *
 * Pure and off-UI-thread by design — a JUnit-testable counterpart of the Dart
 * isolate `compute(_wandFloodFill, …)`.
 */
object WandFloodFill {

    /** Block factor for the half-resolution passes (mask res = buffer / this). */
    const val DOWNSAMPLE = 2

    /**
     * @param pixels ARGB_8888 pixels of the flattened paint layer, row-major,
     *   length == width*height (as from `Bitmap.getPixels`).
     */
    class Request(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val seedX: Int,
        val seedY: Int,
        /** 0..1: colour spread from the seed (0 = exact, 1 = everything). */
        val tolerance: Float,
        /** 0..1: how faint a drawn edge still counts as a wall. */
        val edgeSensitivity: Float,
        /** px: bridge grain gaps in a boundary up to this radius. */
        val gap: Int,
    )

    /** A selection bounding box in MASK pixels (right/bottom are exclusive). */
    class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = right <= left || bottom <= top

        companion object {
            val EMPTY = Bounds(0, 0, 0, 0)
        }
    }

    /**
     * An rgba mask (white = selected) at half the input resolution, its size, and
     * the selection's bounding box in MASK pixels (empty if nothing selected).
     * [mask] holds ARGB ints (0xFFFFFFFF selected / 0 unselected). A plain
     * [Bounds] (not android.graphics.Rect) so the engine stays JVM-testable.
     */
    class Result(
        val mask: IntArray,
        val width: Int,
        val height: Int,
        val bounds: Bounds,
    ) {
        val isEmpty: Boolean get() = bounds.isEmpty
    }

    fun run(r: Request): Result {
        val ds = DOWNSAMPLE
        val fw = r.width
        val fh = r.height
        val w = (fw + ds - 1) / ds
        val h = (fh + ds - 1) / ds
        val n = w * h
        val px = r.pixels

        // Downsample to a representative pixel per block (the most-painted sample),
        // plus the two boundary signals.
        val smallR = IntArray(n)
        val smallG = IntArray(n)
        val smallB = IntArray(n)
        val smallA = IntArray(n)
        val alpha = IntArray(n)
        val lum = IntArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var bestA = -1
                var br = 0
                var bg = 0
                var bb = 0
                for (dy in 0 until ds) {
                    val yy = y * ds + dy
                    if (yy >= fh) break
                    for (dx in 0 until ds) {
                        val xx = x * ds + dx
                        if (xx >= fw) break
                        val c = px[yy * fw + xx]
                        val a = c ushr 24
                        if (a > bestA) {
                            bestA = a
                            br = (c shr 16) and 0xff
                            bg = (c shr 8) and 0xff
                            bb = c and 0xff
                        }
                    }
                }
                val j = y * w + x
                smallR[j] = br
                smallG[j] = bg
                smallB[j] = bb
                smallA[j] = bestA
                alpha[j] = bestA
                lum[j] = (br * 77 + bg * 150 + bb * 29) shr 8
            }
        }

        // Sobel edge barrier. Higher sensitivity → lower thresholds → fainter lines
        // still register as walls. Alpha (paint coverage) gets a much lower
        // threshold than luminance: a light pencil's coverage gradient is small,
        // and because the surface is excluded, empty areas are perfectly flat so
        // there's no noise to trip over. Luminance separates touching opaque colours.
        val alphaThreshold = 24.0f + (4.0f - 24.0f) * r.edgeSensitivity
        val lumThreshold = 60.0f + (6.0f - 60.0f) * r.edgeSensitivity
        val barrier = ByteArray(n)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val aGx = -alpha[i - w - 1] - 2 * alpha[i - 1] - alpha[i + w - 1] +
                    alpha[i - w + 1] + 2 * alpha[i + 1] + alpha[i + w + 1]
                val aGy = -alpha[i - w - 1] - 2 * alpha[i - w] - alpha[i - w + 1] +
                    alpha[i + w - 1] + 2 * alpha[i + w] + alpha[i + w + 1]
                val lGx = -lum[i - w - 1] - 2 * lum[i - 1] - lum[i + w - 1] +
                    lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]
                val lGy = -lum[i - w - 1] - 2 * lum[i - w] - lum[i - w + 1] +
                    lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]
                if (kotlin.math.abs(aGx) + kotlin.math.abs(aGy) > alphaThreshold ||
                    kotlin.math.abs(lGx) + kotlin.math.abs(lGy) > lumThreshold
                ) {
                    barrier[i] = 1
                }
            }
        }

        // Seal small gaps in the boundary so the fill can't leak through speckle.
        val gap = if (r.gap <= 0) 0 else (r.gap + ds - 1) / ds
        val wall = if (gap > 0) dilate(barrier, w, h, gap) else barrier

        val sx = (r.seedX / ds).coerceIn(0, w - 1)
        val sy = (r.seedY / ds).coerceIn(0, h - 1)
        val seedIdx = sy * w + sx
        val sr = smallR[seedIdx]
        val sg = smallG[seedIdx]
        val sb = smallB[seedIdx]
        val sa = smallA[seedIdx]
        val colorThreshold = (r.tolerance * 1020f).roundToIntSafe() // 4 channels incl. alpha
        val visited = ByteArray(n)

        fun fillable(i: Int): Boolean {
            if (wall[i].toInt() == 1) return false
            val d = kotlin.math.abs(smallR[i] - sr) +
                kotlin.math.abs(smallG[i] - sg) +
                kotlin.math.abs(smallB[i] - sb) +
                kotlin.math.abs(smallA[i] - sa)
            return d <= colorThreshold
        }

        // Scanline flood fill.
        val stack = ArrayDeque<Int>()
        stack.addLast(seedIdx)
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            if (visited[p].toInt() == 1) continue
            val y = p / w
            val rowStart = y * w
            var xl = p - rowStart
            var xr = xl
            while (xl > 0 && visited[rowStart + xl - 1].toInt() == 0 && fillable(rowStart + xl - 1)) xl--
            while (xr < w - 1 && visited[rowStart + xr + 1].toInt() == 0 && fillable(rowStart + xr + 1)) xr++
            for (x in xl..xr) visited[rowStart + x] = 1
            var ny = y - 1
            var pass = 0
            while (pass < 2) {
                if (ny in 0 until h) {
                    val nRow = ny * w
                    var x = xl
                    while (x <= xr) {
                        while (x <= xr && (visited[nRow + x].toInt() == 1 || !fillable(nRow + x))) x++
                        if (x > xr) break
                        stack.addLast(nRow + x)
                        while (x <= xr && visited[nRow + x].toInt() == 0 && fillable(nRow + x)) x++
                    }
                }
                pass++
                ny += 2
            }
        }

        // Grow the selection back up to the true edge (recovering the band the
        // barrier dilation ate), but never onto the edge itself.
        val selected = if (gap > 0) dilate(visited, w, h, gap) else visited

        val mask = IntArray(n)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (i in 0 until n) {
            if (selected[i].toInt() == 1 && barrier[i].toInt() == 0) {
                mask[i] = 0xFFFFFFFF.toInt()
                val x = i % w
                val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val bounds = if (maxX < minX) Bounds.EMPTY else Bounds(minX, minY, maxX + 1, maxY + 1)
        return Result(mask, w, h, bounds)
    }

    /** Separable binary morphological dilation (square structuring element). */
    private fun dilate(src: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        val tmp = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = if (x - radius < 0) 0 else x - radius
                val x1 = if (x + radius >= w) w - 1 else x + radius
                var v: Byte = 0
                for (xx in x0..x1) {
                    if (src[row + xx].toInt() == 1) {
                        v = 1
                        break
                    }
                }
                tmp[row + x] = v
            }
        }
        val out = ByteArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                val y0 = if (y - radius < 0) 0 else y - radius
                val y1 = if (y + radius >= h) h - 1 else y + radius
                var v: Byte = 0
                for (yy in y0..y1) {
                    if (tmp[yy * w + x].toInt() == 1) {
                        v = 1
                        break
                    }
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
}
