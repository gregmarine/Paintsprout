package com.symmetricalpalmtree.paintsprout.paint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.zip.CRC32

class PngDensityTest {

    /** A minimal PNG: 8-byte signature, a 13-byte IHDR, then a stand-in IDAT tail. */
    private fun fakePng(): ByteArray {
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = chunk("IHDR", ByteArray(13))
        val idat = chunk("IDAT", byteArrayOf(1, 2, 3, 4))
        return sig + ihdr + idat
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArray(4 + 4 + data.size + 4)
        writeInt(out, 0, data.size)
        for (i in 0..3) out[4 + i] = type[i].code.toByte()
        System.arraycopy(data, 0, out, 8, data.size)
        val crc = CRC32().apply { update(type.toByteArray(Charsets.US_ASCII)); update(data) }.value
        writeInt(out, 8 + data.size, crc.toInt())
        return out
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    @Test
    fun insertsPhysChunkAfterIhdr() {
        val png = fakePng()
        val out = PngDensity.embedDpi(png, 96f)

        // A full pHYs chunk (4+4+9+4 = 21 bytes) was added.
        assertEquals(png.size + 21, out.size)

        // It sits right after IHDR: signature(8) + IHDR(12 + 13) = 33.
        val at = 33
        assertEquals(9, readInt(out, at)) // data length
        assertEquals("pHYs", String(out, at + 4, 4, Charsets.US_ASCII))

        // 96 dpi -> round(96 / 0.0254) = 3780 px/metre, on both axes; unit = metre.
        assertEquals(3780, readInt(out, at + 8))
        assertEquals(3780, readInt(out, at + 12))
        assertEquals(1, out[at + 16].toInt())

        // The IDAT tail is preserved after the inserted chunk.
        assertArrayEquals(png.copyOfRange(33, png.size), out.copyOfRange(33 + 21, out.size))
    }

    @Test
    fun physChunkCrcIsValid() {
        val out = PngDensity.embedDpi(fakePng(), 300f)
        val at = 33
        val len = readInt(out, at)
        val crcOff = at + 4 + 4 + len
        val expected = CRC32().apply {
            update(out, at + 4, 4 + len) // type + data
        }.value.toInt()
        assertEquals(expected, readInt(out, crcOff))
    }

    @Test
    fun nonPositiveDpiReturnsInputUnchanged() {
        val png = fakePng()
        assertSame(png, PngDensity.embedDpi(png, 0f))
        assertSame(png, PngDensity.embedDpi(png, -50f))
    }
}
