package com.symmetricalpalmtree.paintsproutonyx.sketchbook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.paintsproutonyx.data.soil.SoilSchema
import com.symmetricalpalmtree.paintsproutonyx.library.NameRules
import java.security.MessageDigest

/**
 * What the copy is going to be, worked out before a single pixel exists.
 *
 * Baking a stroke book to a raster one is the only thing this app does that turns one of the
 * artist's sketchbooks into another, and it is **one-way**: the marks become graphite and the
 * graphite cannot be taken apart again. That is precisely why the whole of the deciding happens
 * here, in a file with no `Bitmap` in it, no `Context` and no database — every rule about what the
 * copy is, which page it opens on, what it is called and how its rows are shaped can then be proved
 * on a laptop, and what is left for the device to prove is only the drawing. [RasterRows] is split
 * from [RasterImage] on exactly this line and for exactly this reason.
 *
 * Three decisions are worth stating, because each of them was a choice and not an inevitability.
 *
 * **A copy, beside the original, and the original is never opened for writing.** The artist asked
 * for a raster book; they did not ask to lose the stroke book they already had. A conversion in
 * place would be the one act in this app that destroys work irreversibly and does it *silently* —
 * the pages would look much the same afterwards, and the thing that had gone (every mark as a mark)
 * is invisible until the moment somebody reaches for undo and finds the page is one flat image. A
 * copy costs disk, which this device has, and costs nothing that cannot be got back by deleting it.
 *
 * **The plan is deterministic given its ids and its clock.** Nothing here reads the time, mints a
 * UUID or asks anything about the world: [plan] is handed `now` and an id-minter, so the same source
 * planned twice is the same plan, and a test can say so. The bake that follows it is a drawing
 * operation and the panel is the judge of that, but nothing about *which rows go where* is left to
 * be discovered on a tablet.
 *
 * **A page with nothing on it stays a blank leaf.** It gets a page row and no picture at all, which
 * is what an untouched page of a raster book already is — `SketchbookSession.loadPageRaster` reads
 * "no row" as "nobody drew here", silently, because that is the ordinary state of every new page. A
 * PNG of pure transparency written for each empty leaf would cost bytes to say the same nothing, and
 * would make an untouched copy of an untouched page distinguishable from the page it copied.
 */
object RasterBake {

    /**
     * What the bake read out of the source before it drew anything.
     *
     * Everything is already-decoded rows and already-decoded marks, because by the time the plan is
     * made the source file is **closed**. The bake takes twenty seconds on a real book, and holding
     * an encrypted file open across all of it — the artist's original, the thing this must not
     * damage — for the sake of a read that finished in the first half-second would be leaving the
     * one door open that has no reason to be.
     */
    data class Source(
        /** The source's own sketchbook row: its title, its flags, and the page it was last open on. */
        val book: SoilObjectEntity,
        /** Live pages in `order` — a deleted page is not a page the copy should have either. */
        val pages: List<SoilObjectEntity>,
        /** The marks on each live page, by the page's id, in stacking order. Absent or empty is a blank leaf. */
        val marksByPage: Map<String, List<Stroke>>,
    )

    /**
     * One leaf of the copy: the row it gets, the leaf it came from, and what is to be drawn on it.
     *
     * [sourcePageId] is carried even though nothing in the writing needs it, because a bake that
     * goes wrong in the hand is diagnosed by putting the two books side by side, and "the fourth
     * page of the copy is the fourth page of the original" is the thing that then has to be checked.
     */
    data class PagePlan(val row: SoilObjectEntity, val sourcePageId: String, val marks: List<Stroke>)

    /** The copy entire, before any pixel exists. */
    data class Plan(val book: SoilObjectEntity, val pages: List<PagePlan>)

    /**
     * Work out the copy: a raster sketchbook with a leaf for every live leaf of [source], in the
     * same order and at the same size.
     *
     * [newIds] mints the copy's page ids from the source's, and it is a parameter rather than a call
     * to `UUID` inside here for one reason: it is what makes this testable. On the device it is
     * `{ UUID.randomUUID().toString() }` and the argument is ignored; in a test it is a function that
     * answers the same way twice, which is how "the same source planned twice is the same plan" can
     * be a statement anybody can check rather than a hope.
     *
     * **Every page keeps its `order` value, not merely its position.** A book whose leaves have been
     * torn out has gaps in its numbering — a new leaf is numbered past every leaf there has ever
     * been, the dead ones included, so a restored page comes back where it was — and the gaps are
     * not damage, they are the book's history. Renumbering the copy 0, 1, 2… would be a change
     * nobody asked for, and the one kind of change this plan must not make: a difference between
     * the two books that has no reason, in a phase whose whole point is comparing them.
     *
     * **`refId = ""` on every page** — the page's paper, and arc 1's paper is nothing at all. The
     * same "asked and answered" the create writes; see `createSketchbook`.
     *
     * **The copy opens where the original was left.** Whoever bakes a book has been working in it,
     * and landing them on page one of the copy would be the copy pretending to be a different
     * sketchbook. If the page the source pointed at is not among the living — thrown away since, or
     * a pointer from before there were pointers — the first leaf is the honest fallback, the same
     * one `SketchbookSession.open` makes.
     *
     * Throws when there are no pages at all. A sketchbook with no leaves is a file this app did not
     * write; `SketchbookSession.open` refuses to open one, and making a copy of one would be putting
     * a card on the shelf for a book that cannot be turned to.
     */
    fun plan(
        source: Source,
        name: String,
        newBookId: String,
        newIds: (String) -> String,
        now: Long,
    ): Plan {
        check(source.pages.isNotEmpty()) {
            "a sketchbook with no pages is not one this app wrote, and not one it can copy"
        }
        val newIdOf = LinkedHashMap<String, String>(source.pages.size)
        for (page in source.pages) newIdOf[page.id] = newIds(page.id)

        val pages = source.pages.map { page ->
            PagePlan(
                row = SoilObjectEntity(
                    id = newIdOf.getValue(page.id),
                    parentId = newBookId,
                    type = SoilSchema.TYPE_PAGE,
                    order = page.order,
                    createdAt = now,
                    updatedAt = now,
                    refId = "",
                    width = page.width,
                    height = page.height,
                ),
                sourcePageId = page.id,
                marks = source.marksByPage[page.id].orEmpty(),
            )
        }

        val openOn = source.book.refId?.let { newIdOf[it] } ?: pages.first().row.id
        return Plan(
            book = SoilObjectEntity(
                id = newBookId,
                parentId = SoilSchema.ROOT_PARENT,
                type = SoilSchema.TYPE_SKETCHBOOK,
                createdAt = now,
                updatedAt = now,
                text = name,
                refId = openOn,
                // The whole point of the exercise, and the one row that carries it. Read back at
                // every open by `SketchbookSession.open`; nothing else in the file or the index says
                // which kind of book this is, so nothing else can disagree with it.
                flags = RasterRows.flagsFor(true),
            ),
            pages = pages,
        )
    }

    /**
     * What the copy is called: "<name> raster", or "<name> raster 2", "… 3", the first one nobody
     * else on that shelf has taken.
     *
     * **The suffix is the information and the tail of a long name is not.** A sketchbook can be
     * named up to [NameRules.MAX_CHARS], and a name at that length plus " raster" is over it — so
     * something has to give, and what gives is the *source name*, trimmed from its end a character
     * at a time until the whole thing fits. Cutting the suffix instead would produce a copy called
     * exactly what the original is called, sitting next to it on the shelf, which is the one outcome
     * this function exists to prevent.
     *
     * [pattern] is the caller's `getString(R.string.bake_copy_name, it)`. The word "raster" is screen
     * text and belongs in `strings.xml` with the rest of it; a pure function that reached for `R`
     * could not be tested without a device, and one that hard-coded the word would be a second place
     * the app's vocabulary lives.
     *
     * The counter starts at 2 because the first copy has no number — "Study raster 1" would imply a
     * series the artist did not start. Everything that comes back passes [NameRules.validate]: the
     * whole candidate is trimmed after cutting, so a name cut back to nothing yields "raster" rather
     * than a string with a leading space, which the rules refuse and the artist would never see the
     * reason for.
     *
     * [taken] suspends, and that is the one concession this file makes to the world outside it: the
     * only thing that knows whether a name is free is the index, and asking it is a database read.
     * Nothing else here touches a thread, a clock or a file, so the function is still the pure half
     * in every sense that matters — a test hands it a set of names and gets an answer with no
     * Android in the room.
     */
    suspend fun copyName(
        sourceName: String,
        pattern: (String) -> String,
        taken: suspend (String) -> Boolean,
    ): String {
        var counter = 1
        while (true) {
            val tail = if (counter == 1) "" else " $counter"
            val candidate = fitted(sourceName, pattern, tail)
            if (!taken(candidate)) return candidate
            counter++
        }
    }

    /**
     * [pattern] applied to as much of [sourceName] as leaves room for [tail] inside the name limit.
     *
     * A character at a time rather than an arithmetic subtraction, because the pattern is the
     * caller's and this has no business assuming how much of the result is its own. Trailing space
     * goes with each cut, so a name cut in the middle of a word does not leave "Life drawing " with
     * a gap in front of the suffix.
     */
    private fun fitted(sourceName: String, pattern: (String) -> String, tail: String): String {
        var stem = sourceName.trimEnd()
        while (true) {
            val candidate = (pattern(stem) + tail).trim()
            if (candidate.length <= NameRules.MAX_CHARS) return candidate
            // Nothing left of the source name and still too long: the pattern alone is past the
            // limit, which no string in this app is. Cut it rather than hand back a name the rules
            // will refuse — a name that cannot be stored is worse than one that reads oddly.
            if (stem.isEmpty()) return candidate.take(NameRules.MAX_CHARS).trim()
            stem = stem.substring(0, stem.length - 1).trimEnd()
        }
    }

    /**
     * A short fingerprint of a page's PNG, for the log line the bake writes as each leaf lands.
     *
     * The device walk's only way to say "the copy's third page is the drawing the original's third
     * page had on it" is to bake twice and compare — the pen cannot be injected, so the pictures
     * cannot be made again by hand. Sixteen hex characters of SHA-256 is short enough to read off a
     * logcat line beside another and long enough that two different pages will not collide in a
     * lifetime of sketchbooks.
     */
    fun digest(bytes: ByteArray): String {
        val sum = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(DIGEST_CHARS)
        for (i in 0 until DIGEST_CHARS / 2) {
            out.append(HEX[(sum[i].toInt() ushr 4) and 0xF])
            out.append(HEX[sum[i].toInt() and 0xF])
        }
        return out.toString()
    }

    /** How much of the hash gets written down. Two characters to the byte. */
    private const val DIGEST_CHARS = 16

    private val HEX = "0123456789abcdef".toCharArray()
}
