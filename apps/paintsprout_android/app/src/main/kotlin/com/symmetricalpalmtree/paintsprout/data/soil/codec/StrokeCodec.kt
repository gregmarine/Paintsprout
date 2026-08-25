package com.symmetricalpalmtree.paintsprout.data.soil.codec

import com.symmetricalpalmtree.paintsprout.paint.INHERIT_COLOR
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.Vec2
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stroke geometry — "format B", with Paintsprout's channel profile.
 *
 * ```
 * byte 0   : version u8 (= 1)                  ← PLAINTEXT, outside the compression
 * bytes 1+ : zlib{ flags u8 | channels × N }   ← little-endian
 * ```
 *
 * The version byte sits outside the zlib stream on purpose: the format can be
 * identified without decompressing anything.
 *
 * ### Channels
 *
 * | bit | channel | written here? |
 * |---|---|---|
 * | — | `x`, `y` (f32 each) | always — the 8-byte base stride |
 * | 0 | pressure (f32) | **no** — see below |
 * | 1 | tilt (f32) | **no** |
 * | 2 | width (f32) | yes |
 * | 3 | density (f32) | when any point differs from 1.0 |
 * | 4 | colour (u32 ARGB) | when any point carries its own colour |
 * | 5 | load (f32) | when any point differs from 1.0 |
 * | 6–7 | free | — |
 *
 * Pressure and tilt are the two channels Notesprout defined and this app never
 * writes, because they are already *spent*: width and density are resolved from
 * pressure and tilt at capture time, so re-deriving a mark from raw pressure at
 * paint time would mean re-running the brush and hoping to arrive at the same
 * answer. Storing the resolved values is what makes replay honest.
 *
 * ### Why every channel is exactly 4 bytes
 *
 * `stride = 8 + 4 × popcount(flags)`, which means a decoder can **skip a channel
 * it has never heard of** and still read the ones it knows. That is the whole
 * forward-compatibility story: a new per-point channel costs no version bump and
 * no migration, and a Notesprout-era decoder can still read a Paintsprout stroke.
 * Bump [VERSION] only if the *geometry* encoding changes — f64 for very large
 * canvases, say.
 *
 * ### Why the optional channels are optional
 *
 * A pen stroke has density 1, no per-point colour and a full brush at every
 * point. Omitting those three channels takes it from 24 bytes a point to 12, and
 * the flags mechanism exists precisely so that costs nothing to express. The
 * defaults are fixed constants of the format ([DEFAULT_DENSITY], [INHERIT_COLOR],
 * [DEFAULT_LOAD]) rather than values read from the row — a codec that depended on
 * a column's meaning would decode old blobs differently the day that column's
 * meaning shifted.
 *
 * Width is always written: there is no constant it could default to, and zlib
 * flattens a column of identical floats to nearly nothing anyway.
 *
 * **Float32, not quantised int16.** Lossy quantisation was considered and
 * rejected upstream for handwriting and the same reasoning holds harder for
 * paint: you do not silently alter the user's marks to save bytes that
 * compression was going to save anyway.
 */
object StrokeCodec {

    const val VERSION: Byte = 1

    const val FLAG_PRESSURE = 0x01
    const val FLAG_TILT = 0x02
    const val FLAG_WIDTH = 0x04
    const val FLAG_DENSITY = 0x08
    const val FLAG_COLOR = 0x10
    const val FLAG_LOAD = 0x20

    const val DEFAULT_DENSITY = 1.0f
    const val DEFAULT_LOAD = 1.0f

    /** x + y. */
    private const val BASE_STRIDE = 8

    /** Every channel, known or not. */
    private const val CHANNEL_BYTES = 4

    /** A corrupt flags byte must not make us believe in a million-point stroke. */
    private const val MAX_POINTS = 4_000_000

    fun encode(points: List<StrokePoint>): ByteArray {
        var flags = FLAG_WIDTH
        if (points.any { it.density != DEFAULT_DENSITY }) flags = flags or FLAG_DENSITY
        if (points.any { it.color != INHERIT_COLOR }) flags = flags or FLAG_COLOR
        if (points.any { it.load != DEFAULT_LOAD }) flags = flags or FLAG_LOAD

        val stride = strideFor(flags)
        val payload = ByteBuffer.allocate(1 + points.size * stride).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(flags.toByte())
        for (p in points) {
            payload.putFloat(p.position.x)
            payload.putFloat(p.position.y)
            payload.putFloat(p.width)
            if (flags and FLAG_DENSITY != 0) payload.putFloat(p.density)
            if (flags and FLAG_COLOR != 0) payload.putInt(p.color)
            if (flags and FLAG_LOAD != 0) payload.putFloat(p.load)
        }
        return byteArrayOf(VERSION) + Deflate.compress(payload.array())
    }

    /**
     * Decodes, or returns null for anything it cannot make sense of.
     *
     * Null is not a failure to be propagated — it is a stroke that will not be
     * drawn. One damaged blob degrades to a stroke-less render, never to a page
     * that refuses to open (and, through launch-restore of the last-open surface,
     * never to an app that refuses to start).
     *
     * A **truncated tail** is not treated as fatal either: whole points are kept
     * and a partial one at the end is dropped. Half a stroke is better than none.
     */
    fun decode(bytes: ByteArray?): List<StrokePoint>? {
        if (bytes == null || bytes.isEmpty()) return null
        if (bytes[0] != VERSION) return null // a format we do not know; do not guess

        val payload = Deflate.inflate(bytes.copyOfRange(1, bytes.size)) ?: return null
        if (payload.isEmpty()) return null

        val flags = payload[0].toInt() and 0xFF
        val stride = strideFor(flags)
        val available = payload.size - 1
        val count = available / stride
        if (count < 0 || count > MAX_POINTS) return null
        if (count == 0) return emptyList() // an empty stroke is valid, not damage

        val buffer = ByteBuffer.wrap(payload, 1, available).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<StrokePoint>(count)
        repeat(count) {
            val x = buffer.float
            val y = buffer.float

            var width = 0f
            var density = DEFAULT_DENSITY
            var color = INHERIT_COLOR
            var load = DEFAULT_LOAD

            // Channels appear in ascending bit order. Anything we do not recognise
            // is still 4 bytes, so it can be stepped over rather than guessed at.
            for (bit in 0 until 8) {
                val mask = 1 shl bit
                if (flags and mask == 0) continue
                when (mask) {
                    FLAG_WIDTH -> width = buffer.float
                    FLAG_DENSITY -> density = buffer.float
                    FLAG_COLOR -> color = buffer.int
                    FLAG_LOAD -> load = buffer.float
                    // Cast to Buffer deliberately: ByteBuffer's covariant
                    // position(int) is a Java 9 addition, and calling it through
                    // ByteBuffer compiles to a method some Android runtimes do not
                    // have. Through Buffer it resolves the same everywhere.
                    else -> (buffer as java.nio.Buffer).position(buffer.position() + CHANNEL_BYTES)
                }
            }
            out.add(StrokePoint(Vec2(x, y), width, density, color, load))
        }
        return out
    }

    /** `8 + 4 × popcount(flags)` — the reason unknown channels are skippable. */
    fun strideFor(flags: Int): Int =
        BASE_STRIDE + CHANNEL_BYTES * Integer.bitCount(flags and 0xFF)
}
