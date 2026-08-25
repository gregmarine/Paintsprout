package com.symmetricalpalmtree.paintsproutonyx.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The pure half of the path constructors. Everything taking a Context needs a
 * device; [sidecarsOf] is plain file arithmetic, and it is the piece whose
 * drift would hurt — a delete that misses a sidecar leaves pages of the old
 * database waiting under the next file to claim the name.
 */
class SoilFileTest {

    @Test
    fun sidecars_sitBesideTheFile_withTheThreeSqliteSuffixes() {
        val db = File("/some/where/Garden/0a1b2c3d.soil")
        val sidecars = sidecarsOf(db)
        assertEquals(
            listOf(
                "/some/where/Garden/0a1b2c3d.soil-wal",
                "/some/where/Garden/0a1b2c3d.soil-shm",
                "/some/where/Garden/0a1b2c3d.soil-journal",
            ),
            sidecars.map { it.path },
        )
    }

    @Test
    fun sidecars_appendToTheFullName_notTheStem() {
        // SQLite names its sidecars by appending to the whole filename,
        // extension included — "paintsprout.db-wal", never "paintsprout-wal.db".
        val index = File("/data/paintsprout.db")
        assertEquals(
            listOf("paintsprout.db-wal", "paintsprout.db-shm", "paintsprout.db-journal"),
            sidecarsOf(index).map { it.name },
        )
    }
}
