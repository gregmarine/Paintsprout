package com.symmetricalpalmtree.paintsproutonyx.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * How a mark is written down: the path the pen actually took, kept exactly.
 *
 * This is **format B** of the `.soil` family, and the bytes it produces are the same bytes
 * Notesprout Paper's `StrokeCodec` produces for the same mark. That is not a coincidence to be
 * tidied away later — it is the point. The two apps are cousins that share a file shape, and a
 * sketchbook that has been carried onto a desk and opened with a stock `sqlcipher` CLI should
 * yield a `blob` column that anything in the family can read. The moment this codec "improves"
 * the layout, every mark drawn after that day is readable by exactly one build of one app, and
 * nobody finds out until the day they need the drawing back.
 *
 * The layout, per mark, independently decodable — no dictionary, no shared table, no reference to
 * a neighbouring row:
 *
 * ```
 *   version : u8  (= 1)                                                        -- plaintext
 *   payload : zlib{ flags:u8 | (x:f32, y:f32[, pressure:f32][, tilt:f32]) * N }   little-endian
 * ```
 *
 * A few of those choices cost something and are worth the cost:
 *
 * - **The version byte is outside the compression.** One byte of the blob can be read without
 *   inflating anything, so a reader that meets a format it does not know says so instead of
 *   feeding a stranger's bytes to zlib and reporting whatever comes out.
 * - **float32, not a quantised integer.** Pen coordinates arrive as sub-pixel floats and a mark's
 *   smoothness lives in exactly those fractions. Rounding them to a device grid saves a little
 *   space and permanently coarsens every line already drawn; zlib recovers most of the space
 *   anyway, because consecutive points of a real stroke share their high bytes.
 * - **The channels are optional and the flags say which are present.** Arc 1 always has pressure
 *   and tilt to write, but a mark recorded by something that had neither must still be a legal
 *   mark. The stride is derived from the flags rather than assumed, so a reader never walks the
 *   payload with the wrong step and turns a hand-drawn curve into confetti.
 *
 * Deliberately pure Kotlin: no Android types, no g-paper types. Points travel as parallel float
 * arrays rather than a list of point objects, so a hundred thousand samples cost four arrays
 * instead of a hundred thousand allocations, and so this file can be tested on the JVM where the
 * failures are cheap to find. The sketchbook layer is what maps these arrays to and from g-paper's
 * own point type — that translation belongs there, next to the engine, not in here.
 */
object MarkCodec {

    /** Format B, float32 + zlib. The only version this codec writes. */
    const val VERSION_FLOAT32: Byte = 1

    const val FLAG_PRESSURE = 0x01
    const val FLAG_TILT = 0x02

    /** Both coordinates, always present, four bytes each. */
    private const val XY_BYTES = 8
    private const val CHANNEL_BYTES = 4

    /**
     * A decoded mark's geometry.
     *
     * [pressure] and [tilt] are null when the blob did not carry that channel — null meaning
     * "never recorded", which is a different fact from an array of zeroes meaning "recorded as
     * nothing". A renderer asked to draw a mark with no pressure should choose its own default
     * rather than draw a line of zero weight.
     */
    class Points(
        val x: FloatArray,
        val y: FloatArray,
        val pressure: FloatArray?,
        val tilt: FloatArray?,
    ) {
        val size: Int get() = x.size
    }

    /**
     * Write a mark down. [pressure] and [tilt] are each either null — the channel is omitted from
     * the blob entirely — or exactly as long as [x].
     *
     * The length checks are hard requirements rather than a quiet truncation. A mismatch here means
     * the caller lost track of its own samples, and the honest outcome is a crash in the session
     * that made the mistake, not a mark that is silently the wrong shape forever.
     */
    fun encode(
        x: FloatArray,
        y: FloatArray,
        pressure: FloatArray? = null,
        tilt: FloatArray? = null,
    ): ByteArray {
        require(x.size == y.size) { "x/y length mismatch (${x.size}/${y.size})" }
        require(pressure == null || pressure.size == x.size) {
            "pressure length mismatch (${pressure?.size}/${x.size})"
        }
        require(tilt == null || tilt.size == x.size) { "tilt length mismatch (${tilt?.size}/${x.size})" }

        var flags = 0
        if (pressure != null) flags = flags or FLAG_PRESSURE
        if (tilt != null) flags = flags or FLAG_TILT
        val stride = strideFor(pressure != null, tilt != null)

        // Little-endian throughout, stated rather than inherited. ByteBuffer defaults to big-endian
        // and every device this will ever run on is little-endian, so an omitted order() would work
        // on the tablet and betray the format the first time a file was read anywhere else.
        val payload = ByteBuffer.allocate(1 + x.size * stride).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(flags.toByte())
        for (i in x.indices) {
            payload.putFloat(x[i])
            payload.putFloat(y[i])
            if (pressure != null) payload.putFloat(pressure[i])
            if (tilt != null) payload.putFloat(tilt[i])
        }

        val compressed = deflate(payload.array())
        val blob = ByteArray(1 + compressed.size)
        blob[0] = VERSION_FLOAT32
        System.arraycopy(compressed, 0, blob, 1, compressed.size)
        return blob
    }

    /**
     * Read a mark back. Throws on a blob this codec cannot account for.
     *
     * Throwing is the right answer here and returning an empty mark is not. An empty mark drawn
     * onto a page looks exactly like a mark the artist never made, so a page full of unreadable
     * blobs would open as a blank sheet and read as lost work with no explanation. A thrown error
     * reaches the log with the version byte in it and the page can say something truthful.
     */
    fun decode(blob: ByteArray): Points {
        require(blob.isNotEmpty()) { "empty mark blob" }
        return when (blob[0]) {
            VERSION_FLOAT32 -> decodeFloat32(blob)
            else -> error("unknown mark blob version ${blob[0]}")
        }
    }

    private fun decodeFloat32(blob: ByteArray): Points {
        val payload = inflate(blob, offset = 1)
        require(payload.isNotEmpty()) { "mark payload missing its flags byte" }

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.get().toInt() and 0xFF
        val hasPressure = flags and FLAG_PRESSURE != 0
        val hasTilt = flags and FLAG_TILT != 0
        val stride = strideFor(hasPressure, hasTilt)

        // Whole points only. Integer division here is the entire defence against a truncated write:
        // a payload cut off mid-point loses that one point rather than being read one channel out
        // of step, which would drag every remaining coordinate of the mark somewhere it never was.
        // A dropped trailing sample is invisible to the eye; a mark shifted by four bytes is not.
        //
        // Flag bits this build does not know are ignored rather than refused, so a mark carrying a
        // harmless marker still draws. That tolerance has a hard edge and it is worth naming: a bit
        // that stood for a real extra channel would mean the writer used a wider stride than this
        // arithmetic derives, and every point after the first would be read out of step. So a new
        // channel takes a new version byte, never a spare flag bit. The version is the one field a
        // reader can honestly refuse; a flag bit it has never heard of, it cannot.
        val n = (payload.size - 1) / stride

        val x = FloatArray(n)
        val y = FloatArray(n)
        val p = if (hasPressure) FloatArray(n) else null
        val t = if (hasTilt) FloatArray(n) else null
        for (i in 0 until n) {
            x[i] = buf.float
            y[i] = buf.float
            if (p != null) p[i] = buf.float
            if (t != null) t[i] = buf.float
        }
        return Points(x, y, p, t)
    }

    private fun strideFor(hasPressure: Boolean, hasTilt: Boolean): Int =
        XY_BYTES +
            (if (hasPressure) CHANNEL_BYTES else 0) +
            (if (hasTilt) CHANNEL_BYTES else 0)

    /**
     * BEST_COMPRESSION, and it is part of the format rather than a tuning knob.
     *
     * Marks are written once and read for as long as the sketchbook exists, so the trade is a few
     * milliseconds on a background writer against every future open and every future backup. The
     * points of a real stroke are near-neighbours whose upper bytes barely move, which is precisely
     * the redundancy the deeper search finds.
     */
    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(maxOf(16, data.size / 2))
        val chunk = ByteArray(4096)
        try {
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk))
            }
        } finally {
            // These hold native memory the collector cannot see, so an un-ended Deflater is a leak
            // the JVM has no reason to hurry about — and this runs once per mark, all day long.
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, offset: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data, offset, data.size - offset)
        val out = ByteArrayOutputStream(maxOf(16, (data.size - offset) * 3))
        val chunk = ByteArray(4096)
        try {
            // Bail out of ANY round that made no progress, not just the ones that look like the
            // end. A zlib header with FDICT set — which a single flipped bit is enough to produce —
            // leaves inflate() returning 0 forever with more input still waiting, and the obvious
            // `while (!finished())` loop then spins a real thread at full tilt until Android kills
            // the app. One corrupt mark row is a corrupt mark row; the same row hanging the opener
            // is a sketchbook that can never be opened again.
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0) break
                out.write(chunk, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
