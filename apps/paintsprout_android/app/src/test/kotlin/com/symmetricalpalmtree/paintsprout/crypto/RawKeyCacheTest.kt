package com.symmetricalpalmtree.paintsprout.crypto

import com.symmetricalpalmtree.paintsprout.crypto.RawKeyDerivation.toHex
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawKeyCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store = FakeSecureStore()
    private var derivations = 0
    private val key = ByteArray(32) { it.toByte() }
    private val cache = RawKeyCache(store) { _, _ -> derivations++; key }

    private fun file() = tmp.newFile("book.soil").apply { writeBytes(ByteArray(64)) }

    /**
     * The RAM half of the cache is process-wide — that is what "the key lives in
     * memory until the document closes" has to mean when every screen builds its
     * own instance — so a test that wants a cold start has to say so. In the app
     * a cold start is a new process; here it is this.
     */
    @Before
    fun coldStart() = RawKeyCache(store).clearAll()

    @Test
    fun `a global key is derived once and then comes from RAM`() {
        val f = file()
        val first = cache.global("id", f, "phrase")
        repeat(5) { assertArrayEquals(first, cache.global("id", f, "phrase")) }
        assertEquals(1, derivations)
    }

    /**
     * The launch that matters: a new process, an empty RAM cache, and the key
     * already on disk. This is what turns a 300–700 ms open into a 35 ms one.
     */
    @Test
    fun `a new process finds the global key on disk without deriving`() {
        val f = file()
        cache.global("id", f, "phrase")

        var freshDerivations = 0
        RawKeyCache(store).forgetRam("id") // the process ended; RAM did not survive it
        val afterRestart = RawKeyCache(store) { _, _ -> freshDerivations++; key }

        assertArrayEquals(key, afterRestart.global("id", f, "phrase"))
        assertEquals(0, freshDerivations)
    }

    /**
     * The user chose a separate passphrase precisely so this content isn't
     * reachable with the global key. Persisting its derived key would quietly
     * undo that.
     */
    @Test
    fun `a private key never touches disk`() {
        val f = file()
        cache.ephemeral("private", f, "their own passphrase")

        assertTrue(store.keys().isEmpty())
        assertArrayEquals(key, cache.peek("private"))

        RawKeyCache(store).forgetRam("private") // as above: RAM does not outlive a process
        val afterRestart = RawKeyCache(store) { _, _ -> error("must not derive") }
        assertNull(afterRestart.peekOrLoad("private"))
    }

    @Test
    fun `ephemeral still caches within the session`() {
        val f = file()
        cache.ephemeral("private", f, "phrase")
        cache.ephemeral("private", f, "phrase")
        assertEquals(1, derivations)
    }

    @Test
    fun `peek never derives and peekOrLoad promotes a stored key into RAM`() {
        val f = file()
        assertNull(cache.peek("id"))
        assertNull(cache.peekOrLoad("id"))
        assertEquals(0, derivations)

        cache.global("id", f, "phrase")
        cache.forgetRam("id")

        assertNull("RAM was cleared", cache.peek("id"))
        assertArrayEquals(key, cache.peekOrLoad("id"))
        assertArrayEquals("now promoted", key, cache.peek("id"))
        assertEquals(1, derivations)
    }

    /** A stale key after a re-key looks exactly like corruption. */
    @Test
    fun `invalidate removes both copies`() {
        val f = file()
        cache.global("id", f, "phrase")
        cache.invalidate("id")

        assertNull(cache.peek("id"))
        assertNull(cache.peekOrLoad("id"))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun `forgetRam leaves the persisted key alone`() {
        val f = file()
        cache.global("id", f, "phrase")
        cache.forgetRam("id")
        assertFalse(store.keys().isEmpty())
    }

    /** After a global rotation every salt has changed, so nothing cached survives. */
    @Test
    fun `clearAll empties RAM and disk`() {
        val f = file()
        cache.global("a", f, "phrase")
        cache.global("b", f, "phrase")
        cache.clearAll()

        assertNull(cache.peek("a"))
        assertTrue(store.keys().isEmpty())
    }

    @Test
    fun `a corrupt stored key is discarded rather than trusted`() {
        store.putString("id", "not hex at all")
        assertNull(cache.peekOrLoad("id"))
        assertTrue("the bad entry is dropped", store.keys().isEmpty())
    }

    @Test
    fun `a stored key of the wrong length is discarded`() {
        store.putString("id", ByteArray(16).toHex())
        assertNull(cache.peekOrLoad("id"))
        assertTrue(store.keys().isEmpty())
    }

    /** The index is one more keyed file, under an id no document could ever have. */
    @Test
    fun `the index file id can never collide with a document id`() {
        assertFalse(SoilFiles.isDocumentId(RawKeyCache.INDEX_FILE_ID))
        assertEquals("__paintsprout_index__", RawKeyCache.INDEX_FILE_ID)
    }

    @Test
    fun `keys are stored as hex, not as raw bytes in a string`() {
        cache.global("id", file(), "phrase")
        assertEquals(key.toHex(), store.getString("id"))
        assertEquals(64, store.getString("id")!!.length)
    }
}
