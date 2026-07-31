package com.symmetricalpalmtree.paintsprout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The typed half of the colour picker: what a hex code means, and what a channel
 * out of range comes to.
 *
 * Pure integer arithmetic on purpose — no `android.graphics.Color` — so the part
 * of a colour that is a number rather than a pixel can be checked here rather than
 * by squinting at a tablet.
 */
class ColorFieldsTest {

    private val opaque = 0xFF shl 24

    @Test
    fun `six digits, with or without the hash, either case`() {
        assertEquals(opaque or 0x3366CC, parseHexColor("#3366CC"))
        assertEquals(opaque or 0x3366CC, parseHexColor("3366CC"))
        assertEquals(opaque or 0x3366CC, parseHexColor("3366cc"))
        assertEquals(opaque or 0x3366CC, parseHexColor("  #3366cc  "))
    }

    @Test
    fun `three digits mean each digit twice`() {
        assertEquals(opaque or 0xFF8800, parseHexColor("#F80"))
        assertEquals(opaque or 0x000000, parseHexColor("000"))
        assertEquals(opaque or 0xFFFFFF, parseHexColor("fff"))
    }

    @Test
    fun `eight digits keep the colour and drop the alpha`() {
        assertEquals(opaque or 0x3366CC, parseHexColor("#803366CC"))
        // Fully transparent in, fully opaque out: this picker makes no other kind.
        assertEquals(opaque or 0x112233, parseHexColor("00112233"))
    }

    @Test
    fun `black and white survive the trip`() {
        assertEquals(opaque, parseHexColor("#000000"))
        assertEquals(opaque or 0xFFFFFF, parseHexColor("#FFFFFF"))
    }

    @Test
    fun `half a hex code is not a colour yet`() {
        // Every one of these is a moment in the middle of typing "#3366CC", and
        // the colour has to sit still through all of them.
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#"))
        assertNull(parseHexColor("#3"))
        assertNull(parseHexColor("#33"))
        assertNull(parseHexColor("#3366"))
        assertNull(parseHexColor("#33667"))
    }

    @Test
    fun `nonsense is not a colour either`() {
        assertNull(parseHexColor("#GGGGGG"))
        assertNull(parseHexColor("mauve"))
        assertNull(parseHexColor("#12 34 56"))
        assertNull(parseHexColor("#3366CC7"))
        assertNull(parseHexColor("#3366CCDD1"))
        // A leading sign is not a digit, and 0x is not a hash.
        assertNull(parseHexColor("-33CC66"))
        assertNull(parseHexColor("0x3366CC"))
    }

    @Test
    fun `hexOf writes six upper-case digits and no hash`() {
        assertEquals("3366CC", hexOf(opaque or 0x3366CC))
        assertEquals("000000", hexOf(opaque))
        assertEquals("FFFFFF", hexOf(opaque or 0xFFFFFF))
        // The label carries the hash; alpha is not the field's business.
        assertEquals("112233", hexOf(0x44112233))
    }

    @Test
    fun `a hex code round trips through the field and back`() {
        for (c in listOf(0x000000, 0xFFFFFF, 0x3366CC, 0x010203, 0xFEDCBA)) {
            val argb = opaque or c
            assertEquals(argb, parseHexColor(hexOf(argb)))
        }
    }

    @Test
    fun `rgbOf packs three channels opaque`() {
        assertEquals(opaque or 0x3366CC, rgbOf(0x33, 0x66, 0xCC))
        assertEquals(opaque, rgbOf(0, 0, 0))
        assertEquals(opaque or 0xFFFFFF, rgbOf(255, 255, 255))
    }

    @Test
    fun `a channel out of range is held to a byte rather than wrapping`() {
        // 999 shifted left would spill into the channel above it; 256 would land
        // as a 1 there and a 0 here. Both have to come out as full instead.
        assertEquals(opaque or 0xFF00FF, rgbOf(999, -5, 256))
        assertEquals(opaque or 0x00FF00, rgbOf(-1, 300, -300))
    }

    @Test
    fun `every channel keeps its own place`() {
        assertEquals(opaque or 0xFF0000, rgbOf(255, 0, 0))
        assertEquals(opaque or 0x00FF00, rgbOf(0, 255, 0))
        assertEquals(opaque or 0x0000FF, rgbOf(0, 0, 255))
    }
}
