package com.symmetricalpalmtree.paintsproutonyx.data.prefs

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.paintsproutonyx.library.RecentEntry
import com.symmetricalpalmtree.paintsproutonyx.library.RecentsList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "RecentsPrefs"

/**
 * The sketchbooks that were opened, most recent first.
 *
 * **Ids and timestamps, never names** — the same rule [LibraryPrefs] is built on, and it matters
 * more here. Ordinary SharedPreferences is plain XML in the app's data directory; a recents file
 * that listed titles would be a plaintext list of exactly the work the artist has been doing lately,
 * sitting outside the encrypted index that the whole library exists to keep shut. An id says nothing
 * to anyone who cannot already open that index.
 *
 * **Prefs rather than the index, because opening is not an edit.** The index's `updatedAt` means
 * "worked on", and it is what "Last worked on" sorts by — that is a promise about the artist's own
 * work, and a sketchbook opened to look something up and closed again has not been worked on. The
 * only other place to put "looked at" would be a second timestamp column, which is a schema change
 * and a migration for what is, in the end, a preference about how one screen is arranged. It lives
 * in prefs beside the sort field and the last folder — its own file, since it is rewritten on every
 * open and the rest of the shelf's memory is not — as one more thing the shelf remembers about how
 * it was left.
 *
 * The JSON is written by [kotlinx.serialization] and read back defensively: anything that will not
 * parse reads as an empty list. This file outlives the build that wrote it, and a recents list that
 * cannot be decoded must cost the artist a short list, never the library.
 */
class RecentsPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * What was opened, newest first.
     *
     * Never sorted by the caller. Recents is the one shelf whose order is not the library's sort —
     * it is the order things actually happened in, and re-sorting it by name would leave a screen
     * called "Recent" that says nothing about recency at all.
     */
    fun entries(): List<RecentEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<RecentEntry>>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "the recents list could not be read and is being started again", e)
            emptyList()
        }
    }

    /** This sketchbook was just opened. See [RecentsList.record] for what that does to the list. */
    fun record(id: String, now: Long = System.currentTimeMillis()) {
        write(RecentsList.record(entries(), id, now))
    }

    /**
     * Forget everything not in [alive].
     *
     * Called by the shelf as it reads, because that is the only moment this file and the index are
     * both open and can be compared. Nothing is written when nothing changed — the common case by
     * far, and rewriting an unchanged file on every visit to the library is a disk write for no
     * reason.
     */
    fun prune(alive: Set<String>) {
        val current = entries()
        val kept = RecentsList.prune(current, alive)
        if (kept.size != current.size) write(kept)
    }

    private fun write(entries: List<RecentEntry>) {
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(entries)).apply()
    }

    private companion object {
        const val FILE = "recents"
        const val KEY_ENTRIES = "entries"

        val json = Json { ignoreUnknownKeys = true }
    }
}
