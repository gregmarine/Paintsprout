package com.symmetricalpalmtree.paintsproutonyx.data.index

/**
 * Sentinel ids in the global index.
 *
 * A pinned shelf is a list like any other list, which means it needs a row, which means it needs an
 * id — and an id that has to be *found* again on every launch cannot be a fresh UUID. So it is
 * written down here as a constant and the row is created on demand by an idempotent `ensure…` call
 * the first time the shelf is opened.
 *
 * It is deliberately not created by a migration. A migration runs once, against whatever the file
 * happens to be at that moment, and a sketchbook library restored from somewhere else — or an index
 * created by a build that predates the shelf — would arrive without it and never get one. An
 * `ensure…` on the read path cannot miss.
 */
object ListIds {
    /**
     * The pinned-sketchbooks list. The last UUID group spells "pinned" in hex (70 69 6e 6e 65 64),
     * so anyone who ever opens this file with a stock `sqlcipher` CLI and sees the id can read what
     * it is without a lookup table. Everything else in it is zeroes, which is not a UUID any
     * generator would ever hand out — it cannot collide with a real sketchbook or folder.
     */
    const val PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"
}
