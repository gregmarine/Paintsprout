package com.symmetricalpalmtree.paintsproutonyx.library

import androidx.annotation.StringRes
import com.symmetricalpalmtree.paintsproutonyx.R

/**
 * What a folder or a sketchbook may be called.
 *
 * The rule is narrower than anything the storage actually requires. A name in this app is a label in
 * an encrypted index and never a path — the file is a UUID in a flat directory — so almost any string
 * would round-trip safely. It is still narrow, and for reasons that have nothing to do with SQLite:
 *
 *  - **A name that cannot be typed back cannot be found again.** The artist types this on a BOOX
 *    keyboard and reads it on a panel with one font and no colour. A name carrying a right-to-left
 *    mark, a zero-width space or a combining accent renders as something they cannot reproduce, and a
 *    library is a place you go looking for things.
 *  - **A name that looks like a path invites being treated as one.** `..` and `/` are not dangerous
 *    here today. They would be the moment anything ever exports, and the cost of refusing them now is
 *    that nobody can name a sketchbook `..` — which nobody wants to.
 *  - **Leading and trailing space is a name that is not the name it appears to be.** The caller trims
 *    before it gets here, so the only way to arrive with one is deliberately.
 *
 * [MAX_CHARS] is a card's problem, not a database's: past roughly this length the name is ellipsised
 * on every card and every breadcrumb it appears in, so the artist would be naming something they can
 * then never read.
 *
 * Returns null when the name is fine, and otherwise the string resource of the sentence to put in
 * front of the artist. A resource rather than a literal, even though it makes a pure function reach
 * for `R`: every one of these is a specific answer to "why did nothing happen when I tapped Create",
 * which makes them screen text like any other, and screen text lives in one file where it can be read
 * as a whole and heard for tone. Returning the id rather than the string keeps this callable — and
 * testable — without a Context.
 */
object NameRules {

    const val MAX_CHARS = 64

    private val ALLOWED = Regex("^[a-zA-Z0-9_\\-. ]+$")

    @StringRes
    fun validate(name: String): Int? = when {
        name.isEmpty() -> R.string.name_problem_empty
        name != name.trim() -> R.string.name_problem_padded
        name == "." || name == ".." -> R.string.name_problem_reserved
        name.length > MAX_CHARS -> R.string.name_problem_long
        !ALLOWED.matches(name) -> R.string.name_problem_characters
        else -> null
    }
}
