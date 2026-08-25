package com.symmetricalpalmtree.paintsproutonyx.core

import java.util.Locale

/**
 * The one place a mark's colour crosses between the file and the screen.
 *
 * On disk a `mark` row's `color` column is text — `#RRGGBB`, or `#AARRGGBB` when the mark is not
 * fully opaque. In memory it is a packed ARGB Int, because that is what everything that draws
 * wants. Text on disk costs a few bytes per mark and buys something worth more than the bytes: a
 * sketchbook opened in a stock `sqlcipher` CLI shows a colour a person can read and reason about,
 * instead of a signed integer they have to decode in their head to find out whether the ink was
 * black or transparent. That readability is a stated property of the `.soil` family, not a
 * convenience.
 *
 * Arc 1 draws greyscale graphite and nothing else, so in practice every row written here says
 * `#000000`. The column is still text and this codec still exists, because the alternative — a
 * hard-coded black with a note promising to generalise later — means the first coloured pencil
 * arrives needing a migration of every mark ever drawn. One conversion in one file costs nothing
 * now and costs nothing then.
 *
 * Pure Kotlin, JVM-tested. Unreadable input decodes to opaque black rather than throwing: a mark
 * whose colour cell has been damaged is still a mark the artist drew, and it is better to show it
 * in the ink arc 1 uses anyway than to refuse to draw the page.
 */
object InkColorCodec {

    /** Opaque black — the ink of arc 1, and the answer to anything that will not parse. */
    const val BLACK: Int = 0xFF000000.toInt()

    /**
     * `#RRGGBB` when the colour is fully opaque, `#AARRGGBB` when it is not.
     *
     * Dropping a `FF` alpha is not just brevity. Every row arc 1 writes is opaque, so the short
     * form is what a person browsing the table actually sees, and the long form then means
     * something when it turns up. Upper-case hex, and `Locale.ROOT` explicitly: `String.format`
     * otherwise follows the device's locale, and there are locales whose digits are not the digits
     * this format is made of. A sketchbook must not record a different colour because of where its
     * owner was standing.
     */
    fun encode(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        return if (alpha == 0xFF) {
            String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
        } else {
            String.format(Locale.ROOT, "#%08X", argb)
        }
    }

    /**
     * Parse `#RRGGBB` or `#AARRGGBB`, either case. Anything else is [BLACK].
     *
     * Only literal hex digits are accepted after the `#`. Kotlin's own radix parsing would happily
     * read a leading `+` or `-` as a sign, which means a string like `#-00001` — nothing anyone
     * would call a colour — would decode to a real value and be drawn as if it had been chosen.
     * Junk should land on the fallback where it is recognisably junk, not slip through as a shade
     * of something.
     */
    fun decode(text: String?): Int {
        if (text == null) return BLACK
        val trimmed = text.trim()
        if (trimmed.length != 7 && trimmed.length != 9) return BLACK
        if (trimmed[0] != '#') return BLACK

        var value = 0L
        for (i in 1 until trimmed.length) {
            val digit = Character.digit(trimmed[i], 16)
            if (digit < 0) return BLACK
            value = (value shl 4) or digit.toLong()
        }
        // Six digits carried no alpha, so the colour is opaque by definition; eight digits already
        // said what they meant.
        return if (trimmed.length == 7) (0xFF000000L or value).toInt() else value.toInt()
    }
}
