package com.symmetricalpalmtree.paintsprout.paint

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/**
 * Saves a bitmap as a PNG into the device gallery (Pictures/Paintsprout), the
 * native counterpart of the Flutter reference's `gal` save. Uses MediaStore
 * scoped storage — no runtime permission needed for the app's own inserts on
 * API 29+.
 */
object GalleryExport {

    private const val ALBUM = "Paintsprout"

    /**
     * Writes [bitmap] as a PNG and returns a short human-readable location. When
     * [dpi] is positive, the file is stamped with that physical resolution (via a
     * `pHYs` chunk) so it opens and prints at a real-world size.
     */
    fun savePng(context: Context, bitmap: Bitmap, displayName: String, dpi: Float = 0f): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")

        resolver.openOutputStream(uri).use { out ->
            checkNotNull(out) { "could not open output stream" }
            if (dpi > 0f) {
                // Encode to memory so a physical-resolution chunk can be spliced in.
                val encoded = ByteArrayOutputStream().also {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) error("PNG encode failed")
                }
                out.write(PngDensity.embedDpi(encoded.toByteArray(), dpi))
            } else if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                error("PNG encode failed")
            }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return "Pictures/$ALBUM/$displayName.png"
    }
}
