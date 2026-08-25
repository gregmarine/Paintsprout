package com.symmetricalpalmtree.paintsprout.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The one behaviour that must never regress: corruption does not delete.
 *
 * A wrong-key open of an encrypted sketchbook arrives here looking exactly like a
 * corrupt file, and the framework's default response is to delete and recreate.
 */
class NonDestructiveCallbackTest {

    /** Records what the wrapped callback was asked to do. */
    private class Recording : SupportSQLiteOpenHelper.Callback(7) {
        val calls = mutableListOf<String>()
        override fun onCreate(db: SupportSQLiteDatabase) { calls += "create" }
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            calls += "upgrade $oldVersion->$newVersion"
        }
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            calls += "downgrade $oldVersion->$newVersion"
        }
        override fun onConfigure(db: SupportSQLiteDatabase) { calls += "configure" }
        override fun onOpen(db: SupportSQLiteDatabase) { calls += "open" }
        override fun onCorruption(db: SupportSQLiteDatabase) { calls += "DELETED THE FILE" }
    }

    private val db: SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "fake db"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> null
        }
    } as SupportSQLiteDatabase

    @Test
    fun `corruption throws and never reaches the deleting handler`() {
        val inner = Recording()
        val safe = nonDestructive(inner, "3f2a1b8c.soil")

        var caught: Throwable? = null
        try {
            safe.onCorruption(db)
        } catch (t: Throwable) {
            caught = t
        }

        assertTrue("must throw", caught is DatabaseCorruptException)
        assertEquals("3f2a1b8c.soil", (caught as DatabaseCorruptException).name)
        assertEquals("the delegate's handler must not run", emptyList<String>(), inner.calls)
    }

    @Test
    fun `every other callback is delegated unchanged`() {
        val inner = Recording()
        val safe = nonDestructive(inner, "index")

        safe.onConfigure(db)
        safe.onCreate(db)
        safe.onUpgrade(db, 1, 2)
        safe.onDowngrade(db, 2, 1)
        safe.onOpen(db)

        assertEquals(
            listOf("configure", "create", "upgrade 1->2", "downgrade 2->1", "open"),
            inner.calls,
        )
    }

    @Test
    fun `the schema version is carried through`() {
        assertEquals(7, nonDestructive(Recording(), null).version)
    }
}
