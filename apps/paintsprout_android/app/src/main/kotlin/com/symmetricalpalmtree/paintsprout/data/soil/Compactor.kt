package com.symmetricalpalmtree.paintsprout.data.soil

/**
 * Reclaiming what a document no longer needs.
 *
 * Two jobs, both of them deletions, and both run at the seal where nothing is
 * reading:
 *
 * 1. **Tombstones from previous sessions.** Deleting is soft everywhere in this
 *    format, which is what makes an undelete possible — but a row nobody can
 *    reach any more is just weight. "Previous sessions" is the safety margin:
 *    anything tombstoned while this document was open is still this session's
 *    business, and only a row that was already dead when the file was opened is
 *    purged.
 * 2. **Raster caches beyond the most recent few.** Measured on real artwork, the
 *    cache is **75–88% of a document** — 651 KB for one heavy page, 185 KB across
 *    thirteen sparse ones. It is a rebuildable convenience, and keeping every
 *    page's forever means a fifty-page book carries tens of megabytes of pixels
 *    it could recompute.
 *
 * Nothing here modifies a surviving row, which is deliberate: compaction is not a
 * change to the artwork, and `updatedAt` is the input to the backup predicate.
 * Reclaiming space must not make a document look edited.
 */
object Compactor {

    /**
     * How many pages keep their composited pixels.
     *
     * Enough for the pages a session actually moves between — the current one,
     * the one before it, and a glance either side — and not the whole book. The
     * pages that lose theirs still open; they replay their ops instead, which is
     * the degradation the cache exists to avoid rather than a failure.
     */
    const val CACHE_PAGES = 4

    class Result(val purged: Int, val cachesDropped: Int) {
        val changed: Boolean get() = purged > 0 || cachesDropped > 0
    }

    /**
     * Purges what [openedAt] and [keepCaches] allow, and reports whether anything
     * went. The caller decides what to do about that — a `VACUUM` is only worth
     * its cost when something actually left.
     */
    fun sweep(store: ObjectStore, openedAt: Long, keepCaches: Int = CACHE_PAGES): Result =
        store.transaction {
            val dead = store.tombstonedBefore(openedAt)
            // A tombstoned page keeps its layers and ops — `deletePage` stamps the
            // page alone, because every read filters by parent and they vanish
            // without being written. The compactor is where they actually go.
            val subtrees = dead.flatMap { row ->
                listOf(row) + Subtrees.collect(row.id, store::childrenOf)
            }.distinctBy { it.id }
            if (subtrees.isNotEmpty()) store.hardDelete(subtrees.map { it.id })

            val stale = staleCaches(store.ofType(SoilType.RASTER_CACHE), keepCaches)
                .filter { it.id !in subtrees.map { row -> row.id }.toSet() }
            if (stale.isNotEmpty()) store.hardDelete(stale.map { it.id })

            Result(purged = subtrees.size, cachesDropped = stale.size)
        }

    /**
     * The caches to drop: everything but the [keep] most recently written.
     *
     * Ranked by the cache row's own `updatedAt`, which is not a proxy for
     * anything — a cache is written when you leave a page, so it is exactly "when
     * I last had this page open".
     */
    fun staleCaches(caches: List<SoilObject>, keep: Int): List<SoilObject> {
        if (keep < 0) return emptyList()
        if (caches.size <= keep) return emptyList()
        return caches.sortedByDescending { it.updatedAt }.drop(keep)
    }
}
