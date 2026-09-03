package com.symmetricalpalmtree.paintsproutonyx.library

import kotlinx.serialization.Serializable

/**
 * One sketchbook the artist opened, and when.
 *
 * An id and a timestamp, and nothing else — the name, the folder it is in and the cover all come
 * from the encrypted index at the moment the shelf is drawn. See
 * [com.symmetricalpalmtree.paintsproutonyx.data.prefs.RecentsPrefs] for why this list is not allowed
 * to carry anything the artist typed.
 */
@Serializable
data class RecentEntry(val id: String, val at: Long)

/**
 * The rules of the "recently opened" shelf, with no storage and no Android in them.
 *
 * Recents is a small list that is rewritten on every single open, which is exactly the shape of
 * thing that quietly grows duplicates and never lets the oldest entry go. Both failures are silent —
 * the shelf still works, it just gradually stops being a short list of the last few sketchbooks —
 * so the rules live here on their own where they can be checked without a device.
 */
object RecentsList {

    /**
     * How many opens the shelf remembers.
     *
     * Twenty, because recents is for "the thing I was just in" and the one before it, not for
     * browsing. A longer list would start competing with the library itself, which is the thing that
     * already knows where everything is and can sort it; the shorter list is only useful while every
     * entry on it is genuinely recent.
     */
    const val MAX = 20

    /**
     * Put [id] at the front, as of [now].
     *
     * Any earlier entry for the same sketchbook is removed rather than left behind. A sketchbook
     * opened twice is not two recent sketchbooks — it is one, opened more recently — and leaving the
     * old entry would let one busy sketchbook fill the whole shelf with copies of itself and push
     * everything else off the end.
     */
    fun record(entries: List<RecentEntry>, id: String, now: Long, max: Int = MAX): List<RecentEntry> {
        val out = ArrayList<RecentEntry>(minOf(entries.size + 1, max))
        out.add(RecentEntry(id, now))
        for (entry in entries) {
            if (entry.id == id) continue
            if (out.size >= max) break
            out.add(entry)
        }
        return out
    }

    /**
     * Drop entries whose sketchbook is no longer on the shelf, keeping the order of the rest.
     *
     * The index is the authority on what exists and this list has no way to hear about a delete, so
     * a deleted sketchbook would otherwise sit in Recents forever as a card that opens onto nothing.
     * Pruning on the read rather than on the delete also covers the deletes that are not deletes at
     * all: a sketchbook that has moved out of reach for any other reason simply stops being listed,
     * and comes back if it comes back.
     */
    fun prune(entries: List<RecentEntry>, alive: Set<String>): List<RecentEntry> =
        entries.filter { it.id in alive }
}
