package com.symmetricalpalmtree.paintsprout.data.soil.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** The mask, move and wet-state codecs, and the inflate guard underneath them. */
class BlobCodecsTest {

    private fun mask(w: Int = 8, h: Int = 4, seed: Int = 1) =
        MaskCodec.Mask(w, h, Random(seed).nextBytes(w * h))

    // --- Masks --------------------------------------------------------------

    @Test
    fun `a mask round-trips`() {
        val m = mask()
        assertEquals(m, MaskCodec.decode(MaskCodec.encode(m)))
    }

    @Test
    fun `a mask of one pixel round-trips`() {
        val m = MaskCodec.Mask(1, 1, byteArrayOf(0xFF.toByte()))
        assertEquals(m, MaskCodec.decode(MaskCodec.encode(m)))
    }

    /** Coverage is 8-bit because a frisket with a 1-bit edge is a jagged frisket. */
    @Test
    fun `partial coverage survives`() {
        val m = MaskCodec.Mask(3, 1, byteArrayOf(0, 0x7F, 0xFF.toByte()))
        val back = MaskCodec.decode(MaskCodec.encode(m))!!
        assertEquals(0x7F.toByte(), back.alpha[1])
    }

    /** A mask is mostly empty, which is exactly what zlib is good at. */
    @Test
    fun `an empty mask compresses to almost nothing`() {
        val m = MaskCodec.Mask(512, 512, ByteArray(512 * 512))
        assertTrue(MaskCodec.encode(m).size < 1024)
    }

    @Test
    fun `a mismatched mask is refused at the door`() {
        var threw = false
        try {
            MaskCodec.encode(MaskCodec.Mask(4, 4, ByteArray(3)))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `damaged mask bytes decode to nothing`() {
        val good = MaskCodec.encode(mask())
        assertNull(MaskCodec.decode(null))
        assertNull(MaskCodec.decode(ByteArray(0)))
        assertNull(MaskCodec.decode(byteArrayOf(9, 9, 9)))
        assertNull(MaskCodec.decode(good.copyOf().also { it[0] = 42 }))
        assertNull(MaskCodec.decode(good.copyOfRange(0, good.size / 2)))
        assertNull(MaskCodec.decode(byteArrayOf(MaskCodec.VERSION) + Random(2).nextBytes(64)))
    }

    /**
     * A header claiming a huge mask must not become a huge allocation. This is
     * the difference between "one selection failed to load" and "the app died
     * reading a corrupt row".
     */
    @Test
    fun `an absurd size in the header is refused rather than allocated`() {
        for (dims in listOf((1 shl 20) to (1 shl 20), 0 to 4, -1 to 4, 4 to -1)) {
            val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(dims.first).putInt(dims.second).array()
            val blob = byteArrayOf(MaskCodec.VERSION) + Deflate.compress(payload)
            assertNull("accepted ${dims.first}x${dims.second}", MaskCodec.decode(blob))
        }
    }

    /** A header that promises more pixels than the payload holds is a lie. */
    @Test
    fun `a truncated pixel array is refused`() {
        val payload = ByteBuffer.allocate(8 + 10).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8).putInt(4).put(ByteArray(10)).array()
        assertNull(MaskCodec.decode(byteArrayOf(MaskCodec.VERSION) + Deflate.compress(payload)))
    }

    // --- Move ---------------------------------------------------------------

    @Test
    fun `a move round-trips both halves`() {
        val move = MoveCodec.Move(
            floatArrayOf(1.5f, 0f, 120.25f, 0f, 1.5f, -40.5f, 0f, 0f, 1f),
            mask(seed = 3),
        )
        assertEquals(move, MoveCodec.decode(MoveCodec.encode(move)))
    }

    /** A transform that drifts re-lays the lifted paint a hair off, every replay. */
    @Test
    fun `the transform is exact`() {
        val awkward = floatArrayOf(
            0.70710678f, -0.70710678f, 1919.9999f, 0.70710678f, 0.70710678f,
            -0.00001f, 0f, 0f, 1.0000001f,
        )
        val back = MoveCodec.decode(MoveCodec.encode(MoveCodec.Move(awkward, mask())))!!
        assertTrue(awkward.contentEquals(back.matrix))
    }

    @Test
    fun `a wrong-sized transform is refused at the door`() {
        var threw = false
        try {
            MoveCodec.encode(MoveCodec.Move(FloatArray(6), mask()))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `damaged move bytes decode to nothing`() {
        val good = MoveCodec.encode(MoveCodec.Move(FloatArray(9) { it.toFloat() }, mask()))
        assertNull(MoveCodec.decode(null))
        assertNull(MoveCodec.decode(ByteArray(0)))
        assertNull(MoveCodec.decode(good.copyOfRange(0, 20)))
        assertNull(MoveCodec.decode(good.copyOf().also { it[0] = 7 }))
        assertNull("a good header over a broken mask", MoveCodec.decode(good.copyOfRange(0, 40)))
    }

    // --- Wet state ----------------------------------------------------------

    @Test
    fun `wet state round-trips`() {
        val state = WetStateCodec.WetState(
            schedule = intArrayOf(0, 3, 7, 12, 12, 40),
            crop = intArrayOf(10, 20, 300, 400),
            dryFreeze = floatArrayOf(0f, 0.25f, 0.5f, 1f),
        )
        assertEquals(state, WetStateCodec.decode(WetStateCodec.encode(state)))
    }

    /** A wash that dried fully has no freeze, and a dry tool has no wet state at all. */
    @Test
    fun `the absent parts stay absent`() {
        val dried = WetStateCodec.WetState(schedule = intArrayOf(0, 5), crop = intArrayOf(0, 0, 10, 10))
        val back = WetStateCodec.decode(WetStateCodec.encode(dried))!!
        assertNull(back.dryFreeze)
        assertNotNull(back.crop)

        val nothing = WetStateCodec.WetState()
        val emptyBack = WetStateCodec.decode(WetStateCodec.encode(nothing))!!
        assertNull(emptyBack.crop)
        assertNull(emptyBack.dryFreeze)
        assertEquals(0, emptyBack.schedule.size)
    }

    @Test
    fun `a crop that is not four ints is refused`() {
        var threw = false
        try {
            WetStateCodec.encode(WetStateCodec.WetState(crop = intArrayOf(1, 2)))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `damaged wet state decodes to nothing`() {
        val good = WetStateCodec.encode(
            WetStateCodec.WetState(intArrayOf(1, 2, 3), intArrayOf(0, 0, 1, 1), floatArrayOf(0.5f)),
        )
        assertNull(WetStateCodec.decode(null))
        assertNull(WetStateCodec.decode(byteArrayOf(WetStateCodec.VERSION)))
        assertNull(WetStateCodec.decode(good.copyOf().also { it[0] = 3 }))
        assertNull(WetStateCodec.decode(byteArrayOf(WetStateCodec.VERSION) + Random(5).nextBytes(80)))
    }

    /** A count the payload cannot back up must not become an allocation. */
    @Test
    fun `an absurd count is refused rather than allocated`() {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Int.MAX_VALUE).putInt(0).array()
        assertNull(WetStateCodec.decode(byteArrayOf(WetStateCodec.VERSION) + Deflate.compress(payload)))
    }

    // --- The inflate guard --------------------------------------------------

    @Test
    fun `compress and inflate round-trip`() {
        val bytes = Random(6).nextBytes(10_000)
        assertTrue(bytes.contentEquals(Deflate.inflate(Deflate.compress(bytes))))
    }

    /**
     * The one that matters: an FDICT-flagged stream makes `inflate()` return zero
     * bytes forever without ever reporting "finished". Unguarded, this test does
     * not fail — it never returns.
     */
    @Test(timeout = 5_000)
    fun `a stream that can never make progress returns instead of spinning`() {
        val fdict = byteArrayOf(0x78, 0x20.toByte()) + ByteArray(32)
        assertNull(Deflate.inflate(fdict))
        assertNull(Deflate.inflate(ByteArray(0)))
        assertNull(Deflate.inflate(ByteArray(64)))
        assertNull(Deflate.inflate(Random(7).nextBytes(500)))
    }

    /** A tiny compressed payload must not be able to demand unbounded memory. */
    @Test
    fun `inflation is bounded`() {
        val bomb = Deflate.compress(ByteArray(1_000_000))
        assertTrue(bomb.size < 10_000)
        assertNull(Deflate.inflate(bomb, limit = 1024))
        assertNotNull(Deflate.inflate(bomb))
    }

    /** Every codec answers damage the same way: null, not an exception. */
    @Test
    fun `no codec throws on hostile input`() {
        val rng = Random(11)
        repeat(300) {
            val junk = rng.nextBytes(rng.nextInt(0, 200))
            StrokeCodec.decode(junk)
            MaskCodec.decode(junk)
            MoveCodec.decode(junk)
            WetStateCodec.decode(junk)
        }
        assertFalse("reached without an exception escaping", false)
    }
}
