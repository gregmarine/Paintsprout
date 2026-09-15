package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

private const val TAG = "RasterImage"

/**
 * A page's pixels turned into bytes, and back. The half of the raster border that needs Android —
 * [RasterRows] is the half that does not, and the rules this obeys are written down there.
 *
 * **PNG, because the drawing has to come back exactly.** It is lossless, so a page saved and
 * reopened is the page that was drawn rather than a very good likeness of it; a raster book has no
 * rows to re-render from, so what is in the blob is the only copy of the artist's work and there is
 * no version of "near enough" here. The family measured PNG against raw-plus-zlib on real artwork
 * and found PNG both smaller and self-describing (`docs/soil-format.md` § The raster cache), which
 * is the second reason: the guard below reads the size out of the picture's own header, and a format
 * that did not carry one would need the size stored beside it and trusted.
 */
object RasterImage {

    /**
     * The page image as PNG bytes.
     *
     * The quality argument is ignored by the PNG encoder — it is lossless — and 100 is passed for the
     * reader rather than the codec, so nobody later reads a lower number as a tuning that was chosen.
     *
     * A picture over [RasterRows.SIZE_CEILING_BYTES] gets a line in the log and is **written
     * anyway**. The ceiling is here to tell us what a real book costs before R5 asks, not to decide
     * which of the artist's drawings are worth keeping. [pageId] travels in only so that line can say
     * which leaf it was.
     */
    fun encode(bitmap: Bitmap, pageId: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        if (bytes.size > RasterRows.SIZE_CEILING_BYTES) {
            Log.w(TAG, "page $pageId is ${bytes.size} bytes, over the ${RasterRows.SIZE_CEILING_BYTES}-byte watch line; written anyway")
        }
        return bytes
    }

    /**
     * The bytes as a page image, or null when they are not this page's.
     *
     * **The guard runs before the decoder, always, and that order is the whole of this function.**
     * [RasterRows.fitsPage] answers from the PNG's own twenty-four-byte header; only once it has said
     * yes does anything ask `BitmapFactory` for memory. The other order — decode and then check the
     * dimensions — hands a damaged or foreign blob's idea of how big it is straight to the allocator,
     * on a device where the honest page is already eighteen megabytes, which is a way to take the
     * process down while somebody is drawing. The session asks the same guard a moment earlier so it
     * can shelve the offending row; asking it twice costs a header read and means no future caller
     * can get here past it.
     *
     * `ARGB_8888` because the page image is a **layer over the paper**: its unmarked pixels are
     * transparent so the white and any tooth beneath can show through, and the eraser clears to
     * transparent rather than painting white. A config without an alpha channel would turn every
     * erased patch into a white hole in whatever one day sits under it.
     */
    fun decode(bytes: ByteArray, pageWidth: Int, pageHeight: Int): Bitmap? {
        if (!RasterRows.fitsPage(bytes, pageWidth, pageHeight)) {
            Log.e(
                TAG,
                "a stored page image is ${RasterRows.pngSize(bytes)} and the page is " +
                    "${pageWidth}x$pageHeight — refused before decoding",
            )
            return null
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            // Throwable, not Exception, because the one that matters on this device — not enough
            // memory for the page — is an Error, and for two releases this caught everything but
            // it. A null here opens the leaf blank and shelves nothing: the guard passed, so the
            // picture is this page's, and a decode that failed for want of memory is not a reason
            // to put a good drawing on the shelf.
            Log.e(TAG, "a stored page image passed the guard and would not decode", t)
            null
        }
        if (bitmap == null) Log.e(TAG, "a stored page image decoded to nothing")
        return bitmap
    }

    /**
     * What the encoder's buffer starts at. A page's PNG has measured in the hundreds of kilobytes on
     * real artwork, so starting at the default handful of bytes means a dozen array copies of a
     * growing buffer on the write queue for every save.
     */
    private const val INITIAL_BUFFER_BYTES = 512 * 1024
}
