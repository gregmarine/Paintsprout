package com.symmetricalpalmtree.paintsprout.data.soil

import java.util.Locale

/**
 * Colours in the `color` column: `#AARRGGBB`.
 *
 * Text rather than an integer because it is the one payload a human reading the
 * file with a `sqlite3` shell will actually want to read, and because the format
 * family already spells colours this way.
 *
 * **Formatted with the root locale, always.** A format string that is both a
 * number and a database value must never see the device's locale: `%08X` under
 * `ar` or `fa` writes Eastern-Arabic digits, and the user's colours would then
 * decode to black the day they changed their device language. The parse side is
 * equally strict, so a value written by a locale-broken build is rejected rather
 * than silently misread.
 */
object ArgbHex {

    fun encode(argb: Int): String = String.format(Locale.ROOT, "#%08X", argb)

    /** Null for anything that is not `#AARRGGBB` or `#RRGGBB`. */
    fun decodeOrNull(text: String?): Int? {
        val hex = text?.removePrefix("#") ?: return null
        if (hex.length != 8 && hex.length != 6) return null
        if (!hex.all { it in "0123456789abcdefABCDEF" }) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (value or 0xFF000000L).toInt() else value.toInt()
    }

    fun decode(text: String?, default: Int): Int = decodeOrNull(text) ?: default
}
