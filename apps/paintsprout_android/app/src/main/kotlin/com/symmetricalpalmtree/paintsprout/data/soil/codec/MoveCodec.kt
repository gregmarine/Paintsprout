package com.symmetricalpalmtree.paintsprout.data.soil.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A move op: the region that was lifted, and the transform it was laid back down
 * under.
 *
 * ```
 * byte 0     : version u8 (= 1)
 * bytes 1–36 : 9 × f32, Matrix.getValues order
 * bytes 37+  : a mask blob, with its own version byte
 * ```
 *
 * The matrix rides in the blob rather than in `params` for one reason: JSON would
 * mean nine floats through decimal text on every save, and a transform that
 * drifts in its last bits re-lays the lifted paint a hair off where the user put
 * it, every time the page is replayed. Binary is exact.
 *
 * The mask keeps its own version byte inside the composite, so the two halves can
 * be versioned independently.
 */
object MoveCodec {

    const val VERSION: Byte = 1

    /** `Matrix.getValues` writes 9 floats; nothing here needs to know what they mean. */
    const val MATRIX_FLOATS = 9

    private const val HEADER = 1 + MATRIX_FLOATS * 4

    data class Move(val matrix: FloatArray, val mask: MaskCodec.Mask) {
        override fun equals(other: Any?): Boolean =
            other is Move && other.matrix.contentEquals(matrix) && other.mask == mask

        override fun hashCode(): Int = matrix.contentHashCode() * 31 + mask.hashCode()
    }

    fun encode(move: Move): ByteArray {
        require(move.matrix.size == MATRIX_FLOATS) {
            "A transform is $MATRIX_FLOATS floats, not ${move.matrix.size}"
        }
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.put(VERSION)
        for (v in move.matrix) header.putFloat(v)
        return header.array() + MaskCodec.encode(move.mask)
    }

    fun decode(bytes: ByteArray?): Move? {
        if (bytes == null || bytes.size <= HEADER) return null
        if (bytes[0] != VERSION) return null

        val buffer = ByteBuffer.wrap(bytes, 1, MATRIX_FLOATS * 4).order(ByteOrder.LITTLE_ENDIAN)
        val matrix = FloatArray(MATRIX_FLOATS) { buffer.float }
        val mask = MaskCodec.decode(bytes.copyOfRange(HEADER, bytes.size)) ?: return null
        return Move(matrix, mask)
    }
}
