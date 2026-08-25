package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val TAG = "SoilWriter"

/**
 * Every write to an open sketchbook, in a queue, one at a time.
 *
 * **One writer, and the point is the order, not the thread safety.** SQLite would serialize these
 * anyway; what it would not do is keep them in the order the hand made them. A mark and the erase
 * that takes it out again are two writes about the same row, and there is no version of "the erase
 * landed first" that is survivable — the row comes back alive and the mark the artist rubbed out is
 * still on the page when they reopen it. Anything that can happen concurrently here can happen in
 * the wrong order, so nothing here is allowed to happen concurrently.
 *
 * It is also the only place that knows a write failed. A failed write cannot be reported to the
 * artist mid-stroke — there is no honest thing to say and no moment to say it in — so it is logged
 * and the queue carries on. Losing one mark is bad; a dialog that interrupts a drawing to say so is
 * worse, and stopping the queue would silently lose every mark after it as well.
 *
 * **[drain] before the file closes, always.** The queue is asynchronous by design, so at the instant
 * a sketchbook is closed there are usually marks in it that are not yet rows. Closing the database
 * out from under them loses exactly the last thing the artist drew — the part they most expect to
 * still be there. `drain` puts a marker on the end of the queue and waits for it, which by the FIFO
 * ordering means everything ahead of it has run.
 */
class SoilWriter(scope: CoroutineScope) {

    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val pump = scope.launch(Dispatchers.IO) {
        for (task in queue) {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "a write to the sketchbook failed and was dropped", e)
            }
        }
    }

    /** Put a write on the end of the queue. Never blocks; never throws. */
    fun submit(task: suspend () -> Unit) {
        val result = queue.trySend(task)
        if (result.isFailure) {
            // Only reachable after close(), which the session does once and last.
            Log.w(TAG, "a write arrived after the sketchbook was closed and was dropped")
        }
    }

    /** Wait until everything already queued has been written. */
    suspend fun drain() {
        val done = CompletableDeferred<Unit>()
        val accepted = queue.trySend { done.complete(Unit) }.isSuccess
        if (accepted) done.await()
    }

    /**
     * No more writes. Call [drain] first — this does not wait, and a task still in the queue when
     * the channel closes never runs.
     */
    fun close() {
        queue.close()
        pump.cancel()
    }
}
