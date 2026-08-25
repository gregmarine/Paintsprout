package com.symmetricalpalmtree.paintsproutonyx.data.prefs

import android.content.Context
import com.symmetricalpalmtree.paintsproutonyx.library.SortField
import com.symmetricalpalmtree.paintsproutonyx.library.SortOrder

/**
 * The two things the shelf remembers between visits: which folder was open, and how it was sorted.
 *
 * **Ids and enum names, never display names.** Everything the artist typed lives in the encrypted
 * index; ordinary SharedPreferences is world-readable to anything with the app's data directory, and
 * a preference file listing "Life drawing, Tuesdays" would be a plaintext table of contents for a
 * library whose whole point is that it is encrypted. An id says nothing to anyone who cannot already
 * open the index.
 *
 * A stored enum that no longer exists reads back as its default rather than throwing. This file
 * outlives the build that wrote it — a sort field removed in a later arc would otherwise crash the
 * library on launch for exactly the people who had been using it.
 */
class LibraryPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The folder the shelf was last browsing; null is the root.
     *
     * Restored on launch because a library is a place, and being put back at the root every time is
     * being made to walk down to your own work again. The caller checks that the folder is still
     * alive before trusting it — a folder deleted in the last session would otherwise open onto a
     * breadcrumb pointing at nothing.
     */
    var folderId: String?
        get() = prefs.getString(KEY_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_FOLDER, value).apply()

    var sortField: SortField
        get() = prefs.getString(KEY_SORT_FIELD, null)
            ?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME
        set(value) = prefs.edit().putString(KEY_SORT_FIELD, value.name).apply()

    var sortOrder: SortOrder
        get() = prefs.getString(KEY_SORT_ORDER, null)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.ASC
        set(value) = prefs.edit().putString(KEY_SORT_ORDER, value.name).apply()

    private companion object {
        const val FILE = "library_state"
        const val KEY_FOLDER = "folderId"
        const val KEY_SORT_FIELD = "sortField"
        const val KEY_SORT_ORDER = "sortOrder"
    }
}
