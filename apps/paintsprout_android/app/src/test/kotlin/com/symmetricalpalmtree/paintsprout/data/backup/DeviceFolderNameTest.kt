package com.symmetricalpalmtree.paintsprout.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed name doubles as a Drive folder name, so the only characters it truly
 * must not contain are the ones that would make it a *path*. Everything else the
 * user typed is theirs.
 */
class DeviceFolderNameTest {

    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("Studio tablet", DeviceIdentity.sanitizeTypedName("Studio tablet"))
    }

    /** Looser than the generated default's filter on purpose — see [DeviceIdentity]. */
    @Test
    fun `spaces and punctuation the generated default would strip are kept`() {
        assertEquals("Greg's tablet (no. 2)", DeviceIdentity.sanitizeTypedName("Greg's tablet (no. 2)"))
    }

    @Test
    fun `path separators become a dash`() {
        assertEquals("a-b", DeviceIdentity.sanitizeTypedName("a/b"))
        assertEquals("a-b", DeviceIdentity.sanitizeTypedName("a\\b"))
        assertEquals("a-b", DeviceIdentity.sanitizeTypedName("a:b"))
    }

    /** A run collapses to one dash rather than one per character. */
    @Test
    fun `a run of illegal characters collapses`() {
        assertEquals("a-b", DeviceIdentity.sanitizeTypedName("a//\\::b"))
    }

    @Test
    fun `leading and trailing junk is trimmed away entirely`() {
        assertEquals("tablet", DeviceIdentity.sanitizeTypedName("  /tablet/  "))
    }

    /** Blank is how the caller learns to refuse it rather than write an empty name. */
    @Test
    fun `a name of nothing but separators comes back blank`() {
        assertTrue(DeviceIdentity.sanitizeTypedName("///").isBlank())
        assertTrue(DeviceIdentity.sanitizeTypedName("   ").isBlank())
    }
}
