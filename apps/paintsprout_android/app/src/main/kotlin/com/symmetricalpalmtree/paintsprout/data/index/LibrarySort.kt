package com.symmetricalpalmtree.paintsprout.data.index

/**
 * How the library is ordered.
 *
 * Applied in memory rather than in SQL, deliberately. The index has no intrinsic
 * sibling order — folders and documents are sorted by whatever the user has
 * chosen, at read time — and a library of this size sorts faster than the query
 * that fetched it. If a tree that the user can drag into an order ever arrives,
 * that wants an `"order"` column of its own rather than another sort mode here.
 */
enum class LibrarySort {
    NAME,
    CREATED,
    UPDATED;

    fun applyTo(rows: List<IndexObject>): List<IndexObject> = when (this) {
        // Case-insensitive, so "apple" and "Apple" sit together rather than in
        // two blocks divided by every capital letter in between.
        NAME -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        CREATED -> rows.sortedByDescending { it.createdAt }
        UPDATED -> rows.sortedByDescending { it.updatedAt }
    }

    companion object {
        /** Lenient: a value written by another build is not worth failing over. */
        fun parse(name: String?): LibrarySort = entries.firstOrNull { it.name == name } ?: NAME
    }
}
