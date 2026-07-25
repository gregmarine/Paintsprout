package com.symmetricalpalmtree.paintsprout.crypto

/**
 * What to do with each file when the global key changes.
 *
 * A rotation touches every document in the library plus the index, one at a
 * time, and **it will be interrupted** — by a battery, by the system reclaiming
 * memory, by the user leaving. So the interesting logic is not "convert a file",
 * it is "given a file in an unknown state, what should happen to it now", and
 * that is a pure function of two facts: does it open with the old key, and does
 * it open with the new one.
 *
 * | opens old | opens new | verdict |
 * |---|---|---|
 * | yes | — | [Verdict.CONVERT] |
 * | no | yes | [Verdict.SKIP] — already done, by the run that was interrupted |
 * | no | no | [Verdict.QUARANTINE] — it is not ours to guess about |
 *
 * The middle row is why a resumed rotation is safe to run over everything: a
 * file that was already converted is recognised rather than converted twice
 * (which would fail, since the old key no longer opens it). The last row is the
 * one that keeps a rotation from stopping: a document keyed to something else
 * entirely — imported under its own passphrase, restored from a foreign backup —
 * is set aside and reported, and every other document still gets its new key.
 */
object RotationPlan {

    enum class Verdict { CONVERT, SKIP, QUARANTINE }

    fun verdictFor(opensWithOld: Boolean, opensWithNew: Boolean): Verdict = when {
        opensWithOld -> Verdict.CONVERT
        opensWithNew -> Verdict.SKIP
        else -> Verdict.QUARANTINE
    }

    /**
     * The rotation's state between steps, as it is written down.
     *
     * Held in the secure store, because it contains the passphrase the library is
     * being rotated *to* — and if that is lost mid-run, half the documents are
     * keyed to something nobody knows. It is the one piece of state whose loss is
     * unrecoverable, so it is written **before the first file is touched**.
     */
    class Progress(val pending: List<String>, val quarantined: List<String>) {
        val isFinished: Boolean get() = pending.isEmpty()

        /** After [id] is dealt with, however it went. */
        fun done(id: String): Progress = Progress(pending - id, quarantined)

        fun quarantine(id: String): Progress = Progress(pending - id, quarantined + id)
    }

    /**
     * Ids as one string, for a store that holds strings.
     *
     * Newline-separated: a UUID cannot contain one, and a separator that cannot
     * appear in the data needs no escaping to be unambiguous.
     */
    fun encode(ids: List<String>): String = ids.joinToString("\n")

    fun decode(raw: String?): List<String> =
        raw?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
