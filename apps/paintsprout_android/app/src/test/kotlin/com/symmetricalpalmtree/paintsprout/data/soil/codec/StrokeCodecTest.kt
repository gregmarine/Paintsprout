package com.symmetricalpalmtree.paintsprout.data.soil.codec

import com.symmetricalpalmtree.paintsprout.paint.INHERIT_COLOR
import com.symmetricalpalmtree.paintsprout.paint.StrokePoint
import com.symmetricalpalmtree.paintsprout.paint.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StrokeCodecTest {

    private fun point(
        x: Float, y: Float,
        width: Float = 3f,
        density: Float = 1f,
        color: Int = INHERIT_COLOR,
        load: Float = 1f,
    ) = StrokePoint(Vec2(x, y), width, density, color, load)

    // --- Round trips --------------------------------------------------------

    @Test
    fun `a plain stroke round-trips exactly`() {
        val points = listOf(point(0f, 0f), point(10.5f, -20.25f, width = 4.75f))
        assertEquals(points, StrokeCodec.decode(StrokeCodec.encode(points)))
    }

    @Test
    fun `every channel round-trips`() {
        val points = listOf(
            point(1f, 2f, width = 3.5f, density = 0.25f, color = 0xFF1B1BB3.toInt(), load = 0.75f),
            point(4f, 5f, width = 6.5f, density = 0.5f, color = 0xFFE30022.toInt(), load = 0.1f),
        )
        assertEquals(points, StrokeCodec.decode(StrokeCodec.encode(points)))
    }

    /** An empty stroke encodes to a valid tiny blob. Do not special-case it. */
    @Test
    fun `an empty stroke is valid, not damage`() {
        val encoded = StrokeCodec.encode(emptyList())
        assertNotNull(encoded)
        assertEquals(emptyList<StrokePoint>(), StrokeCodec.decode(encoded))
    }

    @Test
    fun `float coordinates are not quantised`() {
        val points = listOf(point(257.97621f, 390.00003f, width = 0.30000001f))
        val back = StrokeCodec.decode(StrokeCodec.encode(points))!!
        assertEquals(257.97621f, back[0].position.x, 0f)
        assertEquals(390.00003f, back[0].position.y, 0f)
        assertEquals(0.30000001f, back[0].width, 0f)
    }

    @Test
    fun `a long stroke round-trips`() {
        val rng = Random(4)
        val points = List(5_000) {
            point(rng.nextFloat() * 2000f, rng.nextFloat() * 1400f, width = rng.nextFloat() * 20f)
        }
        assertEquals(points, StrokeCodec.decode(StrokeCodec.encode(points)))
    }

    // --- The flags ----------------------------------------------------------

    /** Pressure and tilt are spent at capture time; storing them again would be noise. */
    @Test
    fun `pressure and tilt are never written`() {
        val flags = flagsOf(StrokeCodec.encode(listOf(point(1f, 1f))))
        assertEquals(0, flags and StrokeCodec.FLAG_PRESSURE)
        assertEquals(0, flags and StrokeCodec.FLAG_TILT)
    }

    @Test
    fun `a constant channel is omitted, a varying one is not`() {
        val plain = flagsOf(StrokeCodec.encode(listOf(point(1f, 1f), point(2f, 2f))))
        assertEquals(StrokeCodec.FLAG_WIDTH, plain)

        val wet = flagsOf(
            StrokeCodec.encode(
                listOf(point(1f, 1f, load = 0.5f), point(2f, 2f, color = 0xFF00FF00.toInt(), density = 0.5f)),
            ),
        )
        assertTrue(wet and StrokeCodec.FLAG_LOAD != 0)
        assertTrue(wet and StrokeCodec.FLAG_COLOR != 0)
        assertTrue(wet and StrokeCodec.FLAG_DENSITY != 0)
    }

    /** Omitted channels come back as the format's constants, not as zero. */
    @Test
    fun `omitted channels decode to their defaults`() {
        val back = StrokeCodec.decode(StrokeCodec.encode(listOf(point(1f, 1f))))!!
        assertEquals(1f, back[0].density, 0f)
        assertEquals(1f, back[0].load, 0f)
        assertEquals(INHERIT_COLOR, back[0].color)
    }

    /** Dropping three constant channels is most of a pen stroke's payload. */
    @Test
    fun `omitting constant channels is smaller than not`() {
        val plain = List(500) { point(it.toFloat(), it.toFloat()) }
        val varied = List(500) { point(it.toFloat(), it.toFloat(), load = it / 500f) }
        assertTrue(StrokeCodec.encode(plain).size < StrokeCodec.encode(varied).size)
    }

    /** `8 + 4 × popcount` is what makes an unknown channel skippable. */
    @Test
    fun `the stride formula holds for every flag combination`() {
        assertEquals(8, StrokeCodec.strideFor(0))
        assertEquals(12, StrokeCodec.strideFor(StrokeCodec.FLAG_WIDTH))
        assertEquals(
            24,
            StrokeCodec.strideFor(
                StrokeCodec.FLAG_WIDTH or StrokeCodec.FLAG_DENSITY or
                    StrokeCodec.FLAG_COLOR or StrokeCodec.FLAG_LOAD,
            ),
        )
        assertEquals(40, StrokeCodec.strideFor(0xFF))
    }

    /**
     * The forward-compatibility promise: a channel this build has never heard of
     * costs 4 bytes it can step over, and the channels it does know still land in
     * the right fields.
     */
    @Test
    fun `an unknown channel is skipped, not guessed at`() {
        val points = listOf(point(1f, 2f, width = 3f), point(4f, 5f, width = 6f))
        val fromTheFuture = reencodeWithExtraChannel(points, extraBit = 0x40)

        val back = StrokeCodec.decode(fromTheFuture)
        assertNotNull("a future channel must not make the stroke unreadable", back)
        assertEquals(2, back!!.size)
        assertEquals(1f, back[0].position.x, 0f)
        assertEquals(3f, back[0].width, 0f)
        assertEquals(4f, back[1].position.x, 0f)
        assertEquals(6f, back[1].width, 0f)
    }

    // --- Damage -------------------------------------------------------------

    @Test
    fun `nothing at all decodes to nothing`() {
        assertNull(StrokeCodec.decode(null))
        assertNull(StrokeCodec.decode(ByteArray(0)))
    }

    @Test
    fun `an unknown version is refused rather than guessed`() {
        val encoded = StrokeCodec.encode(listOf(point(1f, 1f)))
        encoded[0] = 99
        assertNull(StrokeCodec.decode(encoded))
    }

    /** The case that hangs rather than throws if the inflate loop is unguarded. */
    @Test
    fun `a corrupt zlib stream returns null instead of spinning`() {
        val encoded = StrokeCodec.encode(List(100) { point(it.toFloat(), it.toFloat()) })
        for (corrupt in listOf(
            encoded.copyOf().also { it[1] = 0x00 },            // broken CMF
            encoded.copyOf().also { it[2] = 0x20 },            // FDICT set: the infinite-zero case
            byteArrayOf(StrokeCodec.VERSION) + ByteArray(64),  // all zeros
            byteArrayOf(StrokeCodec.VERSION) + Random(1).nextBytes(200),
        )) {
            assertNull(StrokeCodec.decode(corrupt))
        }
    }

    @Test
    fun `a truncated blob keeps the whole points and drops the partial tail`() {
        val points = List(100) { point(it.toFloat(), it.toFloat()) }
        val encoded = StrokeCodec.encode(points)

        // Truncating compressed bytes usually breaks the stream outright; what
        // must never happen is a throw or a hang.
        for (cut in listOf(2, encoded.size / 2, encoded.size - 1)) {
            val back = StrokeCodec.decode(encoded.copyOfRange(0, cut))
            if (back != null) assertTrue(back.size <= points.size)
        }
    }

    /** A payload truncated *after* inflation must still yield whole points. */
    @Test
    fun `a partial trailing point is dropped`() {
        val points = List(10) { point(it.toFloat(), it.toFloat()) }
        val payload = inflatedPayload(StrokeCodec.encode(points))
        val short = payload.copyOfRange(0, payload.size - 5) // half a point
        val rebuilt = byteArrayOf(StrokeCodec.VERSION) + Deflate.compress(short)

        val back = StrokeCodec.decode(rebuilt)!!
        assertEquals(9, back.size)
        assertEquals(0f, back[0].position.x, 0f)
    }

    // --- Helpers ------------------------------------------------------------

    private fun inflatedPayload(encoded: ByteArray): ByteArray =
        Deflate.inflate(encoded.copyOfRange(1, encoded.size))!!

    private fun flagsOf(encoded: ByteArray): Int = inflatedPayload(encoded)[0].toInt() and 0xFF

    /**
     * Rewrites a blob as a future build might: same channels, plus one this build
     * does not know, in its correct ascending-bit position.
     */
    private fun reencodeWithExtraChannel(points: List<StrokePoint>, extraBit: Int): ByteArray {
        val flags = StrokeCodec.FLAG_WIDTH or extraBit
        val stride = StrokeCodec.strideFor(flags)
        val payload = java.nio.ByteBuffer.allocate(1 + points.size * stride)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        payload.put(flags.toByte())
        for (p in points) {
            payload.putFloat(p.position.x)
            payload.putFloat(p.position.y)
            payload.putFloat(p.width)
            payload.putFloat(-1f) // the channel from the future
        }
        return byteArrayOf(StrokeCodec.VERSION) + Deflate.compress(payload.array())
    }
}
