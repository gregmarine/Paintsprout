package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilSchema

/**
 * The border between the picture on a raster page and a row in the file, crossed in both directions
 * — [MarkRows]' sibling, and here for the same reason that one is.
 *
 * Everything in this file is pure: bytes in, bytes out, no `Bitmap` anywhere. That is what lets the
 * part of a raster page that can actually be *proved* be proved on a laptop with no tablet in the
 * room — that the image which goes down comes back up, and that an image which does not belong to
 * this page is refused before anything tries to open it. The encoding and the decoding themselves
 * need Android and live next door in [RasterImage]; the rules about them live here.
 *
 * Three decisions are worth stating.
 *
 * **One row per page, replaced in place.** The id of an existing picture is passed in rather than
 * minted here, because a save is a *new picture of the same page*, not a new object. The family
 * measured its own raster cache at three quarters to seven eighths of a document's bytes; a row per
 * save would make a book's size a function of how long the artist worked on it rather than of how
 * many pages they drew, and an evening's sketching would weigh more than the drawing does.
 *
 * **The size guard reads the header, never the image.** [fitsPage] answers from twenty-four bytes,
 * so a row whose picture is the wrong shape for the page is refused before anything has allocated
 * anything — which is the whole point of the family's bounded-decode invariant. A page image at this
 * panel's size is about eighteen megabytes decoded, and a corrupt or foreign header claiming a
 * larger one is a way to take the process down while the artist is drawing.
 *
 * **The mode flag is read and written here alone.** Two places that both know which bit means raster
 * is one place that can be changed and one that will not be, and the failure that follows is a book
 * created as raster which opens as strokes — an empty page where a drawing was.
 */
object RasterRows {

    /**
     * The byte count past which a page's PNG gets a line in the log.
     *
     * Four megabytes, which is several times what the family measured for one heavy page (651 KB).
     * It is a **watch item and not a refusal**: a picture over the line is logged and still written,
     * because the number exists to tell us what a real sketchbook costs before R5 is asked what
     * storage a raster book takes — and dropping an artist's drawing on the floor to make a
     * measurement look tidier is not a trade this app makes anywhere else either.
     */
    const val SIZE_CEILING_BYTES = 4L * 1024 * 1024

    /** The eight bytes every PNG starts with. */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** The signature, the IHDR chunk's length and type, and its width and height: 8 + 4 + 4 + 8. */
    private const val HEADER_BYTES = 24

    /**
     * The page's picture as a row of the sketchbook.
     *
     * [id] is the caller's: the live row's, so the picture is replaced where it already sits, or a
     * fresh UUID for a page being saved for the first time. It is not decided here because only the
     * session can ask the table what is already there, and a border that guessed would either mint a
     * second row per save or reuse an id it had not checked.
     *
     * `order` is [SoilSchema.RASTER_ORDER] — out of the marks' stacking space entirely, since the
     * image is not one of the operations, it is what all of them came to.
     */
    fun toRow(pageId: String, png: ByteArray, id: String, now: Long): SoilObjectEntity =
        SoilObjectEntity(
            id = id,
            parentId = pageId,
            type = SoilSchema.TYPE_RASTER,
            order = SoilSchema.RASTER_ORDER,
            createdAt = now,
            updatedAt = now,
            blob = png,
        )

    /**
     * The PNG a row is carrying, or null when the row is not one of ours or is carrying nothing.
     *
     * A row of the wrong type is refused rather than trusted for its blob: every other type in this
     * file puts something else in that column — a mark's geometry, a paper's WEBP — and handing one
     * of those to an image decoder is how a page comes back as something nobody drew.
     */
    fun pngBytes(row: SoilObjectEntity): ByteArray? {
        if (row.type != SoilSchema.TYPE_RASTER) return null
        val blob = row.blob ?: return null
        return if (blob.isEmpty()) null else blob
    }

    /**
     * The width and height a PNG claims for itself, read straight out of its header — or null for
     * anything that is not a PNG, or is too short to say.
     *
     * The first eight bytes are the signature; the first chunk of a PNG is required by the format to
     * be IHDR, and its width and height are big-endian at offsets 16 and 20. That is the whole of
     * it, and reading it by hand rather than asking a decoder is the point: this is the question
     * that has to be answered *before* anything is willing to allocate, so it cannot be answered by
     * the thing that allocates.
     */
    fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < HEADER_BYTES) return null
        for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return null
        // The first chunk must be IHDR. A file whose first chunk is something else is either not a
        // PNG at all or is one whose dimensions are somewhere this reader would not find them, and
        // "somewhere I did not look" must never come back as an answer.
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = beInt(bytes, 16)
        val height = beInt(bytes, 20)
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    /**
     * Is this picture the picture of *this* page? The bounded-decode guard, and the one rule that
     * stands between a damaged row and the process.
     *
     * Exactly, in both directions — not "no larger than", not "close enough". A page image registers
     * with the page one-to-one, top-left to top-left, so an image of any other size is not this page
     * drawn at the wrong scale, it is some other page, or a blob that is not a page at all. Nothing
     * good can be done with it, and the honest answer is to open the leaf blank and say so in the
     * log rather than to draw part of a stranger's drawing on it.
     *
     * A page with no recorded size (zero, which is g-paper's "fit the view") fails the guard too:
     * there is nothing to check against, and an unbounded decode is exactly what this exists to
     * prevent.
     */
    fun fitsPage(bytes: ByteArray, pageWidth: Int, pageHeight: Int): Boolean {
        if (pageWidth <= 0 || pageHeight <= 0) return false
        val (w, h) = pngSize(bytes) ?: return false
        return w == pageWidth && h == pageHeight
    }

    /**
     * Does this sketchbook keep pixels? Read from the sketchbook row's `flags`.
     *
     * Null is false, and that is not a defensive shrug: every sketchbook this app made before the
     * flag existed is a stroke book, so "nothing was written here" and "strokes" are the same answer.
     */
    fun isRaster(flags: Int?): Boolean = flags != null && (flags and SoilSchema.FLAG_RASTER) != 0

    /**
     * The `flags` value a brand-new sketchbook is stamped with. Zero for a stroke book rather than
     * null — the question was asked and answered.
     */
    fun flagsFor(raster: Boolean): Int = if (raster) SoilSchema.FLAG_RASTER else 0

    private fun beInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
