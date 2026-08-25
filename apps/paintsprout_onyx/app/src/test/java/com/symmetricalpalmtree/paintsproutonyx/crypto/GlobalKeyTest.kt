package com.symmetricalpalmtree.paintsproutonyx.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery key's shape and its transcription forgiveness — the two things
 * that must never drift, because a key minted today has to be typeable years
 * from now, from paper, by hand.
 */
class GlobalKeyTest {

    /** PSPT-, then 8 groups of 4 Crockford characters — the ranges skip I, L, O and U. */
    private val shape = Regex("^PSPT(-[0-9A-HJKMNP-TV-Z]{4}){8}$")

    @Test
    fun mintedKey_isPsptPlus8GroupsOf4_fromTheCrockfordAlphabet() {
        repeat(50) {
            val k = GlobalKey.mint()
            assertTrue(k, shape.matches(k))
            // 5 prefix chars + 32 key chars + 7 inner dashes.
            assertEquals(44, k.length)
        }
    }

    @Test
    fun alphabet_is32Chars_andOmitsTheConfusables() {
        assertEquals(32, GlobalKey.ALPHABET.length)
        for (c in "ILOU") assertFalse("alphabet must omit $c", GlobalKey.ALPHABET.contains(c))
        // No duplicates — 32 distinct characters or the encoding is lossy.
        assertEquals(32, GlobalKey.ALPHABET.toSet().size)
    }

    @Test
    fun format_demandsExactly160BitsOfEntropy() {
        GlobalKey.format(ByteArray(20)) // 160 bits — fine.
        for (bad in intArrayOf(0, 19, 21)) {
            try {
                GlobalKey.format(ByteArray(bad))
                throw AssertionError("format accepted $bad bytes")
            } catch (_: IllegalArgumentException) {
                // Anything but 20 bytes must refuse, not silently pad or truncate.
            }
        }
    }

    @Test
    fun format_isDeterministic_withIndependentlyComputedAnswers() {
        val a = ByteArray(20) { it.toByte() }
        val b = ByteArray(20) { (it + 1).toByte() }
        assertEquals(GlobalKey.format(a), GlobalKey.format(a))
        assertNotEquals(GlobalKey.format(a), GlobalKey.format(b))
        // Both expected strings computed by a separate base32 implementation
        // (Python) over the same alphabet — if either changes, the encoding moved.
        assertEquals("PSPT-000G-40R4-0M30-E209-185G-R38E-1W81-24GK", GlobalKey.format(a))
        assertEquals("PSPT-0410-6105-0R3G-G28A-1C60-T3GF-208H-44RM", GlobalKey.format(b))
    }

    @Test
    fun consecutiveMints_differ() {
        assertNotEquals(GlobalKey.mint(), GlobalKey.mint())
    }

    @Test
    fun normalize_foldsTheConfusables_andUpperCases() {
        // A reader who wrote O for 0 and I/l for 1 still gets back in.
        assertEquals("PSPT-0011-VWXY", GlobalKey.normalize("pspt-oOIl-vwxy"))
    }

    @Test
    fun normalize_neverTouchesACorrectKey() {
        // A genuine key contains no I, L, O or U, so the fold has nothing to bite on.
        repeat(20) {
            val k = GlobalKey.mint()
            assertEquals(k, GlobalKey.normalize(k))
        }
        val known = GlobalKey.format(ByteArray(20) { it.toByte() })
        assertEquals(known, GlobalKey.normalize(known))
    }
}
