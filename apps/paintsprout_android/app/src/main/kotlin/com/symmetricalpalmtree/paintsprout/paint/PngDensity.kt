package com.symmetricalpalmtree.paintsprout.paint

import java.util.zip.CRC32

/**
 * Stamps a physical resolution into an already-encoded PNG so it opens and prints
 * at a real-world size — the last link in the 1:1 screen↔print chain.
 *
 * Android's PNG encoder writes no density, so we splice in a `pHYs` chunk (PNG
 * spec 11.3.5.3) carrying pixels-per-metre in X and Y. It must sit after `IHDR`
 * and before the first `IDAT`; we insert it immediately after `IHDR`.
 */
object PngDensity {

    private const val SIG_LEN = 8
    private const val METERS_PER_INCH = 0.0254

    /**
     * Returns [png] with a `pHYs` chunk encoding [dpi]. If [dpi] is non-positive
     * or [png] is too short / malformed to locate IHDR, the input is returned
     * unchanged (a missing density is better than a corrupt file).
     */
    fun embedDpi(png: ByteArray, dpi: Float): ByteArray {
        if (dpi <= 0f || png.size < SIG_LEN + 12) return png

        // IHDR is always the first chunk: [4 len][4 type][len data][4 crc].
        val ihdrLen = readInt(png, SIG_LEN)
        val insertAt = SIG_LEN + 4 + 4 + ihdrLen + 4
        if (ihdrLen < 0 || insertAt > png.size) return png

        val ppm = Math.round(dpi / METERS_PER_INCH).toInt()
        val chunk = physChunk(ppm)

        return ByteArray(png.size + chunk.size).also { out ->
            System.arraycopy(png, 0, out, 0, insertAt)
            System.arraycopy(chunk, 0, out, insertAt, chunk.size)
            System.arraycopy(png, insertAt, out, insertAt + chunk.size, png.size - insertAt)
        }
    }

    /** A complete `pHYs` chunk (length + type + data + CRC) for [ppm] px/metre. */
    private fun physChunk(ppm: Int): ByteArray {
        val data = ByteArray(9)
        writeInt(data, 0, ppm) // pixels per unit, X axis
        writeInt(data, 4, ppm) // pixels per unit, Y axis
        data[8] = 1            // unit specifier: 1 = metre

        val type = byteArrayOf(
            'p'.code.toByte(), 'H'.code.toByte(), 'Y'.code.toByte(), 's'.code.toByte(),
        )
        val crc = CRC32().apply { update(type); update(data) }.value

        return ByteArray(4 + 4 + 9 + 4).also { chunk ->
            writeInt(chunk, 0, data.size)            // chunk data length (9)
            System.arraycopy(type, 0, chunk, 4, 4)
            System.arraycopy(data, 0, chunk, 8, 9)
            writeInt(chunk, 17, crc.toInt())
        }
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
