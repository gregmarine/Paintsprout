package com.symmetricalpalmtree.paintsprout.data.soil

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OpenDocumentsTest {

    @After
    fun tearDown() = OpenDocuments.clear()

    @Test
    fun `a document is open between register and unregister`() {
        assertFalse(OpenDocuments.isOpen("a"))
        OpenDocuments.register("a")
        assertTrue(OpenDocuments.isOpen("a"))
        OpenDocuments.unregister("a")
        assertFalse(OpenDocuments.isOpen("a"))
    }

    @Test
    fun `registering twice still leaves one entry`() {
        OpenDocuments.register("a")
        OpenDocuments.register("a")
        assertEquals(setOf("a"), OpenDocuments.ids())
    }

    @Test
    fun `unregistering something that was never open is harmless`() {
        OpenDocuments.unregister("never")
        assertTrue(OpenDocuments.ids().isEmpty())
    }

    /** Documents open and close from several screens; the set has to survive that. */
    @Test
    fun `concurrent registration is safe`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(200) { i ->
            pool.submit {
                start.await()
                OpenDocuments.register("doc$i")
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(200, OpenDocuments.ids().size)
    }

    /** `ids()` is a snapshot: iterating it must not see later changes. */
    @Test
    fun `ids returns a copy`() {
        OpenDocuments.register("a")
        val snapshot = OpenDocuments.ids()
        OpenDocuments.register("b")
        assertEquals(setOf("a"), snapshot)
    }
}
