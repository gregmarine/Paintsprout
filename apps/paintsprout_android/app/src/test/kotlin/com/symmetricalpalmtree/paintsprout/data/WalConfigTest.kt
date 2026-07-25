package com.symmetricalpalmtree.paintsprout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class WalConfigTest {

    @Test
    fun `open applies WAL, an autocheckpoint, and incremental vacuum`() {
        val issued = mutableListOf<String>()
        WalConfig.applyOnOpen { issued += it }
        assertEquals(
            listOf(
                "PRAGMA journal_mode = WAL",
                "PRAGMA wal_autocheckpoint = 100",
                "PRAGMA auto_vacuum = INCREMENTAL",
            ),
            issued,
        )
    }

    /** Vacuum first, then the truncating checkpoint, then the caller closes. */
    @Test
    fun `seal vacuums before it checkpoints`() {
        val issued = mutableListOf<String>()
        WalConfig.seal { issued += it }
        assertEquals(
            listOf("PRAGMA incremental_vacuum", "PRAGMA wal_checkpoint(TRUNCATE)"),
            issued,
        )
    }

    /**
     * `wal_autocheckpoint` is connection-level and is not persisted in the file,
     * which is exactly why it lives in the open list rather than in a one-time
     * creation step.
     */
    @Test
    fun `the autocheckpoint is an open-time setting, not a seal-time one`() {
        assertTrue(WalConfig.OPEN_PRAGMAS.any { it.contains("wal_autocheckpoint") })
        assertTrue(WalConfig.SEAL_PRAGMAS.none { it.contains("wal_autocheckpoint") })
    }

    /** Every one of these is a real statement a real engine accepts. */
    @Test
    fun `both pragma sets execute against a real sqlite`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            for (sql in WalConfig.OPEN_PRAGMAS + WalConfig.SEAL_PRAGMAS) {
                db.createStatement().use { it.execute(sql) }
            }
        }
    }
}
