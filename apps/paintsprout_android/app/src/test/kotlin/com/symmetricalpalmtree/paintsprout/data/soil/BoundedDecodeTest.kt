package com.symmetricalpalmtree.paintsprout.data.soil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ceiling itself, checked as arithmetic.
 *
 * `BitmapFactory` is a stub on the JVM, so what can be pinned here is the number
 * and the rule — that a bound expressed in *pixels* is the one that matters,
 * because the allocation being refused is width × height × 4 and nothing about
 * the compressed size predicts it. A 12 KB PNG can claim 30,000 × 30,000.
 */
class BoundedDecodeTest {

    @Test
    fun `the ceiling is a pixel count, not a byte count`() {
        assertEquals(40_000_000L, BoundedDecode.MAX_PIXELS)
    }

    /**
     * The number that matters is the decoded allocation. At four bytes a pixel
     * the ceiling is about 160 MB — beyond any buffer this app produces, and
     * still an amount a device can refuse to hand over.
     */
    @Test
    fun `the ceiling is past anything this app makes and short of a kill`() {
        val biggestRealBuffer = 2200L * 1440L // the target device, full screen
        assertTrue(biggestRealBuffer * 12 < BoundedDecode.MAX_PIXELS)

        val bytesWhenDecoded = BoundedDecode.MAX_PIXELS * 4
        assertTrue("would be $bytesWhenDecoded bytes", bytesWhenDecoded in 100_000_000..200_000_000)
    }

    /** The shape of the attack: a small file claiming an enormous picture. */
    @Test
    fun `a header claiming a huge image is over the line`() {
        val claimed = 30_000L * 30_000L
        assertTrue("900 million pixels must not be decodable", claimed > BoundedDecode.MAX_PIXELS)
    }

    @Test
    fun `an ordinary page is well under it`() {
        for (size in listOf(1610L to 1150L, 2200L to 1440L, 4096L to 4096L)) {
            assertTrue("${size.first}x${size.second}", size.first * size.second < BoundedDecode.MAX_PIXELS)
        }
    }
}
