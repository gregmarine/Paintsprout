package com.symmetricalpalmtree.paintsproutonyx.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The `color` column is text so a person can read it. These tests are mostly about keeping it
 * readable in the exact form the rest of the `.soil` family writes — upper-case hex, `#RRGGBB`
 * when opaque — and about making sure anything that is not a colour lands on black instead of
 * sneaking through as a colour nobody chose.
 */
class InkColorCodecTest {

    private val black = 0xFF000000.toInt()

    @Test
    fun `graphite is written the short way`() {
        // The only colour arc 1 ever writes. If this row ever reads anything but `#000000`, the
        // encoder has grown an opinion it should not have.
        assertEquals("#000000", InkColorCodec.encode(black))
        assertEquals("#FFFFFF", InkColorCodec.encode(0xFFFFFFFF.toInt()))
        assertEquals("#123456", InkColorCodec.encode(0xFF123456.toInt()))
    }

    @Test
    fun `hex is upper-case`() {
        // Lower-case would round-trip perfectly and still be wrong: the family writes upper-case,
        // and a column where half the rows shout and half whisper is a column nobody can eyeball.
        assertEquals("#ABCDEF", InkColorCodec.encode(0xFFABCDEF.toInt()))
        assertEquals("#80ABCDEF", InkColorCodec.encode(0x80ABCDEF.toInt()))
    }

    @Test
    fun `a colour that is not opaque keeps its alpha`() {
        assertEquals("#80FF0000", InkColorCodec.encode(0x80FF0000.toInt()))
        assertEquals("#00000000", InkColorCodec.encode(0x00000000))
        assertEquals("#01020304", InkColorCodec.encode(0x01020304))
    }

    @Test
    fun `the six-digit form means opaque`() {
        assertEquals(black, InkColorCodec.decode("#000000"))
        assertEquals(0xFFFFFFFF.toInt(), InkColorCodec.decode("#FFFFFF"))
        assertEquals(0xFF123456.toInt(), InkColorCodec.decode("#123456"))
    }

    @Test
    fun `the eight-digit form is taken at its word`() {
        assertEquals(0x80FF0000.toInt(), InkColorCodec.decode("#80FF0000"))
        assertEquals(0x00000000, InkColorCodec.decode("#00000000"))
    }

    @Test
    fun `lower case and stray whitespace still parse`() {
        // A hand-edited row from a sqlcipher session is a real way for these strings to arrive, and
        // the file format's whole promise is that editing it by hand is reasonable.
        assertEquals(0xFFABCDEF.toInt(), InkColorCodec.decode("#abcdef"))
        assertEquals(0xFFABCDEF.toInt(), InkColorCodec.decode("  #AbCdEf  "))
        assertEquals(0x80FF0000.toInt(), InkColorCodec.decode("\t#80ff0000\n"))
    }

    @Test
    fun `every colour survives the round trip`() {
        val colours = intArrayOf(
            black,
            0xFFFFFFFF.toInt(),
            0x00000000,
            0x7F808182,
            0xFF0A0B0C.toInt(),
            0x01FFFFFF,
            0xFEFEFEFE.toInt(),
        )
        for (colour in colours) {
            assertEquals(
                "round trip of ${String.format(Locale.ROOT, "%08X", colour)}",
                colour,
                InkColorCodec.decode(InkColorCodec.encode(colour)),
            )
        }
    }

    @Test
    fun `anything that is not a colour reads as black`() {
        // Black, not an exception. A damaged colour cell must not stop the page drawing the mark —
        // the artist's line is still in the blob, and arc 1's ink is black anyway.
        val junk = listOf(
            null,
            "",
            "   ",
            "#",
            "000000",          // no hash
            "#00000",          // five digits
            "#0000000",        // seven digits
            "#000000000",      // nine digits
            "#GGGGGG",         // not hex
            "#00 000",         // a space in the middle
            "red",
            "rgb(0,0,0)",
        )
        for (text in junk) {
            assertEquals("expected black for ${text ?: "null"}", black, InkColorCodec.decode(text))
        }
    }

    @Test
    fun `a sign character is not a colour`() {
        // Kotlin's radix parsing would read these as signed numbers and hand back a real value, so
        // `#-00001` would arrive on the page as a deliberate-looking shade. Junk belongs on the
        // fallback where it is recognisably junk.
        assertEquals(black, InkColorCodec.decode("#-00001"))
        assertEquals(black, InkColorCodec.decode("#+F0000"))
        assertEquals(black, InkColorCodec.decode("#-0000001"))
    }

    @Test
    fun `BLACK is the opaque black everything else agrees on`() {
        assertEquals(0xFF000000.toInt(), InkColorCodec.BLACK)
        assertEquals(InkColorCodec.BLACK, InkColorCodec.decode(InkColorCodec.encode(InkColorCodec.BLACK)))
    }
}
