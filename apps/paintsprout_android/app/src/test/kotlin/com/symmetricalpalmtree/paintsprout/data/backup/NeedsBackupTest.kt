package com.symmetricalpalmtree.paintsprout.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incremental rule, which decides how much of a library moves on a run — and
 * therefore whether backing up a folder of paintings is a few seconds or a full
 * re-upload every time.
 */
class NeedsBackupTest {

    @Test
    fun `a sketchbook that has never been sent anywhere goes`() {
        assertTrue(needsBackup(updatedAt = 1000L, lastBackedUp = null, excludeFromBackup = false))
    }

    @Test
    fun `a sketchbook painted on since its last copy goes again`() {
        assertTrue(needsBackup(updatedAt = 2000L, lastBackedUp = 1000L, excludeFromBackup = false))
    }

    /** The boundary: stamped at the same instant is stamped, not stale. */
    @Test
    fun `a sketchbook stamped at the moment it was last touched stays put`() {
        assertFalse(needsBackup(updatedAt = 1000L, lastBackedUp = 1000L, excludeFromBackup = false))
    }

    @Test
    fun `a sketchbook untouched since its last copy stays put`() {
        assertFalse(needsBackup(updatedAt = 500L, lastBackedUp = 1000L, excludeFromBackup = false))
    }

    @Test
    fun `exclusion wins over never having been backed up`() {
        assertFalse(needsBackup(updatedAt = 1000L, lastBackedUp = null, excludeFromBackup = true))
    }

    @Test
    fun `exclusion wins over having been painted on`() {
        assertFalse(needsBackup(updatedAt = 2000L, lastBackedUp = 1000L, excludeFromBackup = true))
    }
}
