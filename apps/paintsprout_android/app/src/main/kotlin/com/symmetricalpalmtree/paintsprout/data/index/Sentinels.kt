package com.symmetricalpalmtree.paintsprout.data.index

/**
 * The handful of rows whose ids are known in advance.
 *
 * Each is the all-zero UUID with an ASCII word spelled out in hex as its last
 * group, which makes them recognisable in a hex dump and impossible to collide
 * with a real UUIDv4 (whose version nibble is never 0).
 *
 * They are created by an idempotent `ensure…` at every launch and **never by a
 * migration**. A migration that inserts data is a migration that can fail on a
 * user's device, halfway, with no good outcome; an idempotent bootstrap simply
 * runs again.
 */
object Sentinels {

    /** The pinned-sketchbooks list. Membership hangs off it as `list_item` rows. */
    const val PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564" // "pinned"

    /** The clipboard's metadata singleton in `objects`. */
    const val CLIPBOARD_ID = "00000000-0000-0000-0000-636c69706264" // "clipbd"

    /** Root of the scratchpad tree, in the `scratchpad` table. */
    const val SCRATCHPAD_ROOT_ID = "00000000-0000-0000-0000-736372746368" // "scrtch"

    /** Root of the copied-objects tree, in the `clipboard` table. */
    const val CLIPBOARD_ROOT_ID = "00000000-0000-0000-0000-636c69706272" // "clipbr"

    val ALL = listOf(PINNED_LIST_ID, CLIPBOARD_ID, SCRATCHPAD_ROOT_ID, CLIPBOARD_ROOT_ID)

    /** Decodes a sentinel's last group back to the word it spells. For tests and hex dumps. */
    fun wordOf(id: String): String {
        val group = id.substringAfterLast('-')
        return String(ByteArray(group.length / 2) {
            group.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }, Charsets.US_ASCII)
    }
}
