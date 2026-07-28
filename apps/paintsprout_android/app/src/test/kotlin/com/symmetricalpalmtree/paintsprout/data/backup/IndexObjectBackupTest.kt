package com.symmetricalpalmtree.paintsprout.data.backup

import com.symmetricalpalmtree.paintsprout.data.index.IndexEdit
import com.symmetricalpalmtree.paintsprout.data.index.IndexObject
import com.symmetricalpalmtree.paintsprout.data.index.IndexType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a library row says about backup, and what it must not say. */
class IndexObjectBackupTest {

    private fun sketchbook(flags: Int? = null) = IndexObject(
        id = "3f2a1b8c-0000-4000-8000-000000000001",
        type = IndexType.SKETCHBOOK,
        name = "Rope and tide",
        parentId = null,
        createdAt = 1L,
        updatedAt = 2L,
        flags = flags,
    )

    /** A row from before the columns existed reads as "never sent, not excluded". */
    @Test
    fun `a row with no backup state yet is included and unstamped`() {
        val row = sketchbook()
        assertFalse(row.isExcludedFromBackup)
        assertNull(row.lastBackedUpLocal)
        assertNull(row.lastBackedUpDrive)
        assertNull(row.params)
    }

    /**
     * The two bits share one column, so setting either must leave the other
     * exactly as it was — an exclusion that quietly cleared the encrypted bit
     * would make an encrypted book look plaintext to every reader of the index.
     */
    @Test
    fun `encrypted and excluded are independent bits`() {
        val encryptedOnly = sketchbook(flags = IndexObject.FLAG_ENCRYPTED)
        assertTrue(encryptedOnly.isEncrypted)
        assertFalse(encryptedOnly.isExcludedFromBackup)

        val excludedOnly = sketchbook(flags = IndexObject.FLAG_EXCLUDE_BACKUP)
        assertFalse(excludedOnly.isEncrypted)
        assertTrue(excludedOnly.isExcludedFromBackup)

        val both = sketchbook(
            flags = IndexObject.FLAG_ENCRYPTED or IndexObject.FLAG_EXCLUDE_BACKUP,
        )
        assertTrue(both.isEncrypted)
        assertTrue(both.isExcludedFromBackup)
    }

    @Test
    fun `the stamps are per destination`() {
        val row = sketchbook().copy(lastBackedUpLocal = 500L)
        assertEquals(500L, row.lastBackedUpLocal)
        assertNull(row.lastBackedUpDrive)
    }

    /**
     * The rule the whole incremental scheme rests on: neither stamping a backup
     * nor excluding a book is a modification. Bumping `updatedAt` for either would
     * re-flag, on the spot, the file the run had just finished sending.
     */
    @Test
    fun `neither backup edit moves updatedAt`() {
        assertFalse(IndexEdit.BACKUP_STAMP.bumpsUpdatedAt)
        assertFalse(IndexEdit.BACKUP_EXCLUSION.bumpsUpdatedAt)
    }
}
