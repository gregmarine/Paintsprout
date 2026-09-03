package com.symmetricalpalmtree.paintsproutonyx.library

import android.content.Context
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectSummary
import com.symmetricalpalmtree.paintsproutonyx.data.prefs.LibraryPrefs
import com.symmetricalpalmtree.paintsproutonyx.data.prefs.RecentsPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Where the cards come from, for each of the three shelves.
 *
 * **Its own file because [LibraryActivity] is at the line this repo draws at about eight hundred
 * lines, and this is the part that lifts out whole.** Nothing here touches a view, a dialog, the
 * grid or the panel; it is three questions about the index and the sentence each card says under its
 * name. The screen keeps what is genuinely a screen's job — which shelf is showing, what the bars
 * say, what a tap does — and asks this for the contents.
 *
 * The three shelves are deliberately not one query with a filter. They are three different ideas
 * about what a library is for: where a thing is filed, which things the artist singled out, and what
 * they were just doing. Writing them as one parameterised listing would hide that the middle one has
 * no folders in it and the last one is not sorted at all.
 *
 * Ordinary reads on Room's own executor, so a caller on the main thread is safe — with the one
 * exception of the recents preference file, which is real disk and is read on IO.
 */
class ShelfListing(
    private val context: Context,
    private val repo: IndexRepository,
    private val prefs: LibraryPrefs,
    private val recents: RecentsPrefs,
) {

    suspend fun cards(mode: BrowseMode, folderId: String?): List<CardItem> = when (mode) {
        BrowseMode.NORMAL -> normal(folderId)
        BrowseMode.PINNED -> pinned()
        BrowseMode.RECENTS -> recent()
    }

    /** The shelf itself: folders first, then sketchbooks, both in the artist's chosen order. */
    private suspend fun normal(here: String?): List<CardItem> {
        val folders = Sorting.sort(repo.folders(here), prefs.sortField, prefs.sortOrder)
        val sketchbooks = Sorting.sort(repo.sketchbooks(here), prefs.sortField, prefs.sortOrder)
        return folders.map { CardItem.Folder(it) } + sketchbooks.map { CardItem.Sketchbook(it, metaLine(it)) }
    }

    /**
     * The pinned sketchbooks, in the shelf's own sort rather than the order they were pinned in.
     *
     * The pinned order is real and the index keeps it, but the Sort button is sitting in the bar the
     * whole time this mode is on, and a shelf that ignored it would read as a broken button rather
     * than as a deliberate second ordering. No folders: a pin is a thing said about a sketchbook,
     * and there is nowhere to walk to from here.
     */
    private suspend fun pinned(): List<CardItem> =
        Sorting.sort(repo.pinnedSketchbooks(), prefs.sortField, prefs.sortOrder)
            .map { CardItem.Sketchbook(it, metaLine(it)) }

    /**
     * The last twenty sketchbooks opened, newest first, and never sorted.
     *
     * **The order is the information.** Every other shelf here is a set of cards the artist can ask
     * to be arranged; this one is a record of what happened, and re-arranging it by name would leave
     * a mode called "Recent" that says nothing about recency at all.
     *
     * The prune happens here because this is the one moment the preference file and the index are
     * both open and can be compared: the list has no way to hear about a delete, so a sketchbook
     * thrown away would otherwise sit in Recent forever as a card that opens onto nothing.
     *
     * The second line says which folder each one lives in, because a card here has been lifted out
     * of the shelf and there is nothing else on the screen to say where it came from. Those names
     * come out of the index in one read for the whole listing rather than one per card — the same
     * reason the covers and the pin badges are gathered in one read apiece.
     */
    private suspend fun recent(): List<CardItem> {
        val entries = withContext(Dispatchers.IO) { recents.entries() }
        val summaries = repo.summariesAlive(entries.map { it.id })
        withContext(Dispatchers.IO) { recents.prune(summaries.map { it.id }.toSet()) }
        val folderNames = repo.summariesAlive(summaries.mapNotNull { it.parentId }.distinct())
            .associate { it.id to it.name }
        return summaries.map { CardItem.Sketchbook(it, folderLine(it, folderNames)) }
    }

    /** "6 pages · 25 Aug 2026" — what the card says under the name on the shelf and in Pinned. */
    private fun metaLine(summary: ObjectSummary): String {
        val pages = summary.pageCount ?: 1
        val pagesText = if (pages == 1) {
            context.getString(R.string.card_meta_pages_one)
        } else {
            context.getString(R.string.card_meta_pages, pages)
        }
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(summary.updatedAt))
        return context.getString(R.string.card_meta, pagesText, date)
    }

    /**
     * "In Studies" — what a card says under its name in Recent, instead of its page count.
     *
     * A sketchbook whose folder cannot be named falls back to the library line. That means a folder
     * that went while its sketchbook stayed, which the delete sweep is written specifically to make
     * impossible — so it is a file edited from outside or a half-applied restore, and "In Library"
     * is the honest reading of it: the root is where anything with no living folder above it has to
     * be looked for.
     */
    private fun folderLine(summary: ObjectSummary, folderNames: Map<String, String>): String {
        val name = summary.parentId?.let { folderNames[it] } ?: return context.getString(R.string.recents_in_root)
        return context.getString(R.string.recents_in_folder, name)
    }
}
