package com.symmetricalpalmtree.paintsproutonyx.library

/**
 * Which shelf the library is showing.
 *
 * Pinned and Recent are **modes of the one library screen**, not screens of their own. They show the
 * same cards, laid out by the same grid, paged the same way; all that changes is the list of
 * sketchbooks handed to it and what the top bar says. Two more Activities would be two more copies
 * of the paging arithmetic, the long-press sheet and the cover loading — three places to fix the
 * next thing found wrong with a card.
 *
 * [NORMAL] is the shelf itself: folders and sketchbooks in the folder the artist walked to. The
 * other two ignore folders entirely — a pinned sketchbook is pinned wherever it lives, and something
 * opened five minutes ago is not somewhere you should have to go and find.
 *
 * Persisted by name in [com.symmetricalpalmtree.paintsproutonyx.data.prefs.LibraryPrefs], so a
 * library left in a mode comes back in it. A stored name this build does not have reads back as
 * [NORMAL] rather than throwing — a mode dropped in a later arc must not lock the shelf shut for
 * exactly the people who were using it.
 */
enum class BrowseMode { NORMAL, PINNED, RECENTS }
