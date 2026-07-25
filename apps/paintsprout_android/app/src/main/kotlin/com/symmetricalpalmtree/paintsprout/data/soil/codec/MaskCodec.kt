package com.symmetricalpalmtree.paintsprout.data.soil.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A selection mask: 8-bit coverage, cropped, zlib'd.
 *
 * ```
 * byte 0   : version u8 (= 1)
 * bytes 1+ : zlib{ w u32 | h u32 | alpha u8 × w*h }
 * ```
 *
 * Eight bits rather than one because a wand selection has soft edges and a
 * frisket needs them — a 1-bit mask would give every constrained stroke a jagged
 * boundary. Cropped to the op's bounds before encoding, because a mask is mostly
 * empty and cropping is the cheapest win available; the crop's position lives in
 * the row's `x`/`y` and its downsample factor in `amount`, so the codec itself
 * stays a pure rectangle of coverage.
 */
object MaskCodec {

    const val VERSION: Byte = 1

    /**
     * A corrupt header must not become a 40 GB allocation. Generous enough for a
     * full-canvas mask on any plausible display, and nowhere near enough to hurt.
     */
    const val MAX_DIMENSION = 16_384
    const val MAX_PIXELS = 64 * 1024 * 1024

    data class Mask(val width: Int, val height: Int, val alpha: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Mask &&
            other.width == width && other.height == height && other.alpha.contentEquals(alpha)

        override fun hashCode(): Int = (width * 31 + height) * 31 + alpha.contentHashCode()
    }

    fun encode(mask: Mask): ByteArray {
        require(mask.alpha.size == mask.width * mask.height) {
            "Mask is ${mask.alpha.size} bytes but ${mask.width}×${mask.height} needs " +
                "${mask.width * mask.height}"
        }
        val payload = ByteBuffer.allocate(8 + mask.alpha.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(mask.width)
        payload.putInt(mask.height)
        payload.put(mask.alpha)
        return byteArrayOf(VERSION) + Deflate.compress(payload.array())
    }

    /** Null for anything unreadable — a lost selection, never a lost page. */
    fun decode(bytes: ByteArray?): Mask? {
        if (bytes == null || bytes.size < 2) return null
        if (bytes[0] != VERSION) return null

        val payload = Deflate.inflate(bytes.copyOfRange(1, bytes.size)) ?: return null
        if (payload.size < 8) return null

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val width = buffer.int
        val height = buffer.int

        // Believe the header only as far as the bytes actually present back it up.
        if (width <= 0 || height <= 0) return null
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return null
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_PIXELS) return null
        if (payload.size - 8 < pixels) return null

        val alpha = ByteArray(pixels.toInt())
        buffer.get(alpha)
        return Mask(width, height, alpha)
    }
}
