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
 * **[close] lets the queue empty before the file is sealed, always.** The queue is asynchronous by
 * design, so at the instant a sketchbook is closed there are usually marks in it that are not yet
 * rows. Closing the database out from under them loses exactly the last thing the artist drew — the
 * part they most expect to still be there. So `close` shuts the queue to new work and then waits for
 * the pump to finish what it already holds, and only then does the session seal the file.
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

    /**
     * Put a write on the end of the queue, wait for it, and hand back what it returned.
     *
     * The difference from [submit] is the honesty about failure, and it is why this exists. A mark
     * that fails to save is logged and dropped because there is nothing to say to an artist
     * mid-stroke — but an undo is not mid-stroke. It is a thing the artist asked for and is
     * watching for, and an undo that did not reach the file must never be allowed to pretend it
     * did: the page would come back showing the mark gone while the row is still alive, and the
     * next time the sketchbook opened the mark would be there again with no explanation.
     * Exceptions therefore travel out to the caller, which can put the entry back on the stack.
     *
     * It is the **same queue**, which is the other half of the point. A page appended by a swipe
     * and the mark drawn on it a second later are two writes about the same book, and ordering
     * between them holds only because everything goes through one line. A second path that
     * "just awaited its own write" would be a second line, and two lines have no order between them.
     *
     * A caller here is never left waiting on a task that will not run. Either the queue takes the
     * task, in which case [close] lets it run before the pump stops, or the queue has already been
     * closed and the call fails at once. That guarantee is what lets the cover snapshot use this from
     * the application scope on the way out of a screen — an earlier version cancelled the pump with
     * tasks still in the channel, which was survivable only while every caller lived on the screen's
     * own scope and died with it. A caller that outlives the screen would have parked for the life of
     * the process.
     */
    suspend fun <T> perform(task: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        val accepted = queue.trySend {
            try {
                result.complete(task())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }.isSuccess
        if (!accepted) error("the sketchbook is closed and this write cannot be made")
        return result.await()
    }

    /**
     * No more writes — and everything already accepted gets written before this returns.
     *
     * Closing the channel is what ends the pump: it finishes the tasks still in the queue and then
     * falls out of its loop on its own, and this waits for that. It used to be a drain followed by a
     * cancel, and the gap between them was a real hole — a task accepted after the drain's marker
     * but before the cancel was simply never run, and if it was a [perform], its caller was left
     * waiting for an answer that would never come. Nothing here cancels anything: the queue is shut
     * at the door, whatever is inside is let through, and the file is sealed only after the last of
     * it has landed. The one thing a caller must not do is submit after this returns; that write is
     * refused and logged, which is the right answer for a mark aimed at a sketchbook that is closed.
     */
    suspend fun close() {
        queue.close()
        pump.join()
    }
}
