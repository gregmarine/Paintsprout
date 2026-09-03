package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import java.io.ByteArrayOutputStream

private const val TAG = "CoverSnapshot"

/**
 * The picture on the front of a sketchbook: the page that was on the glass when it was put down,
 * baked once and kept on the shelf's own row.
 *
 * **It is a bake of the real marks, not a photograph of the screen.** g-paper's live EPD ink never
 * reaches a framebuffer anything else can read, so there is nothing to screenshot even while the
 * page is open — and by the time a cover is wanted the artist is usually already leaving. The rows
 * are the drawing, so the rows are what gets drawn, through the same renderer the page itself bakes
 * through. Fleck for fleck the marks the hand made, because the grain seeds off the stroke id.
 *
 * ## Full size first, then averaged down
 *
 * The obvious saving — rasterise straight into a 620×827 canvas — is the one thing that must not be
 * done. Arc 1's lead is a 1.2 px hairline and its grain is single flecks; at a third of the size
 * both fall below one pixel, and what comes back is a likeness of the page rather than the page.
 * Baking at full size and averaging afterwards keeps a hairline as a faint grey line, which is what
 * a pencil drawing actually looks like from across a room.
 *
 * The averaging is written out here rather than handed to [Bitmap.createScaledBitmap] for the same
 * reason. That is a bilinear 2×2 tap; at 3:1 it samples two of every three rows and columns, so a
 * one-pixel line can land entirely in the gap and simply not be in the cover. A box average over
 * every pixel in the block cannot miss anything — a mark that is there comes back as tone even when
 * it is too fine to come back as a line.
 *
 * ## Nothing here is allowed to be the reason a sketchbook fails to close
 *
 * A cover is a convenience. Every failure — a page with no recorded size, a bitmap that will not
 * allocate, an encoder that refuses — is logged and answered with null, and the shelf goes on
 * showing whatever cover it already had. The alternative is a crash on the way out of a screen that
 * was in the middle of saving the artist's last marks.
 */
object CoverSnapshot {

    /**
     * How many page pixels go into one cover pixel, each way.
     *
     * An integer, and three. The panel's page is 1860×2480, so three gives 620×827 — every output
     * pixel the average of exactly nine input ones, with no interpolation and no partial blocks
     * anywhere but the last row and column. A fractional factor would have to weight blocks by how
     * much of each source pixel they cover, which is a great deal of arithmetic in aid of a
     * thumbnail.
     */
    const val SHRINK = 3

    /** Lossy at full quality: the file is a few tens of kilobytes and the grain survives it. */
    const val WEBP_QUALITY = 100

    /** Paper white. The page has no template in arc 1, so the ground is the only thing behind the ink. */
    private const val PAPER = 0xFFFFFFFF.toInt()

    /**
     * Bake [marks] onto a page of [pageWidth] × [pageHeight], shrink it, and encode it — or null.
     *
     * Null for a page with no living marks, because **a blank page's cover is no cover**: the card's
     * empty white frame is already the honest picture of one, and storing an image of nothing would
     * cost a WEBP per sketchbook to say the same thing less clearly. Null again for a page that
     * never recorded a size, which is a file this app did not write; there is no rectangle to draw
     * it in, and inventing one would put the artist's marks in a frame nobody chose.
     *
     * **IO thread only.** It allocates two bitmaps, one of them the size of the whole page, and runs
     * the renderer over every mark on it. On the main thread that is a visibly stalled screen at the
     * exact moment the artist is trying to leave it.
     */
    fun render(marks: List<Stroke>, pageWidth: Int, pageHeight: Int): ByteArray? {
        if (marks.isEmpty()) return null
        if (pageWidth <= 0 || pageHeight <= 0) {
            Log.w(TAG, "a page with no recorded size ($pageWidth×$pageHeight) cannot be a cover")
            return null
        }
        var page: Bitmap? = null
        var cover: Bitmap? = null
        return try {
            page = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
            page.eraseColor(PAPER)
            StrokeRasterizer.draw(Canvas(page), marks)

            val pixels = IntArray(pageWidth * pageHeight)
            page.getPixels(pixels, 0, pageWidth, 0, 0, pageWidth, pageHeight)
            // Freed before the second bitmap is asked for. Both alive at once is the page's 18 MB
            // plus the pixel array's 18 MB plus the cover, and this runs while a sketchbook is
            // closing on a device that is already unhappy about memory.
            page.recycle()
            page = null

            val small = shrink(pixels, pageWidth, pageHeight, SHRINK)
            val w = ceilDiv(pageWidth, SHRINK)
            val h = ceilDiv(pageHeight, SHRINK)
            cover = Bitmap.createBitmap(small, w, h, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            cover.compress(webpFormat(), WEBP_QUALITY, out)
            out.toByteArray()
        } catch (t: Throwable) {
            // Throwable rather than Exception, and deliberately: the failure with any real chance of
            // happening here is an OutOfMemoryError from the full-size bitmap. Letting that escape
            // would take down the close that is draining the write queue, so the last marks drawn
            // would be lost to a failure to draw a thumbnail of them.
            Log.w(TAG, "the page could not be made into a cover; the shelf keeps the old one", t)
            null
        } finally {
            page?.recycle()
            cover?.recycle()
        }
    }

    /**
     * Average every [factor]×[factor] block of ARGB pixels down to one, per channel.
     *
     * Pure arithmetic on an `IntArray` so it can be tested with no device in the room — the bitmap
     * either side of it cannot be, and this is the half where a mistake would be silent rather than
     * loud. Output is `ceil(w/factor)` × `ceil(h/factor)`: a page whose size is not a multiple of
     * the factor keeps its last row and column, averaged over however few pixels are actually there
     * rather than over nine, which would darken the two edges of every cover by counting pixels that
     * do not exist as black.
     *
     * Alpha is averaged with the rest. Nothing in arc 1 draws anything but opaque ink on opaque
     * paper, so in practice it is 255 in and 255 out — but a channel quietly left at whatever the
     * first pixel of the block happened to be is the kind of thing that only shows up years later,
     * in the one format that does use it.
     */
    fun shrink(src: IntArray, width: Int, height: Int, factor: Int): IntArray {
        require(factor >= 1) { "a shrink factor below one is an enlargement, which this is not" }
        val outW = ceilDiv(width, factor)
        val outH = ceilDiv(height, factor)
        val out = IntArray(outW * outH)
        for (blockY in 0 until outH) {
            val top = blockY * factor
            val bottom = minOf(top + factor, height)
            for (blockX in 0 until outW) {
                val left = blockX * factor
                val right = minOf(left + factor, width)
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (y in top until bottom) {
                    var i = y * width + left
                    for (x in left until right) {
                        val p = src[i++]
                        a += (p ushr 24) and 0xFF
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                out[blockY * outW + blockX] =
                    ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    private fun ceilDiv(value: Int, by: Int): Int = (value + by - 1) / by

    /**
     * WEBP, by whichever name this Android knows it.
     *
     * `WEBP_LOSSY` arrived in API 30 and the NA5C is well past it, but this app's floor is 29 and a
     * constant that does not exist there is a `NoSuchFieldError` on a device nobody tested on rather
     * than a compile error here. The old `WEBP` is the same encoder reached through the name it had
     * before the split, and at quality 100 it makes the same kind of file.
     */
    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
}
