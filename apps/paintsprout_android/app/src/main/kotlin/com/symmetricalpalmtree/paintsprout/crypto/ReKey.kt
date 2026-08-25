package com.symmetricalpalmtree.paintsprout.crypto

import com.symmetricalpalmtree.paintsprout.data.CommitSwap
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * Changing what a file is encrypted with. **The only place that does.**
 *
 * SQLCipher cannot re-key a file in place across a change of *kind* — plaintext
 * to encrypted, or either to a different passphrase — so every conversion is
 * "export into a fresh database keyed the new way, then swap it in". Three
 * separate features want that (a per-document passphrase, a global rotation, and
 * decrypting a book to hand to something else), and they all come here, because
 * of what is easy to leave out:
 *
 * > ⚠️ **`sqlcipher_export` does not copy `user_version`.**
 * >
 * > The schema version lives in the database header, the export copies *content*,
 * > and the new file is left at version 0. The next open then sees a brand-new
 * > database and runs `onCreate` over a file full of data — or, worse, an
 * > "upgrade" from 0 that no migration was written for. This bricked real
 * > Notesprout files. It is one line, and it is here, once.
 *
 * The swap is [CommitSwap]'s: the converted copy is verified with its new key
 * *before* anything is renamed, and at no instant does zero intact copies of the
 * user's document exist under a name recovery can find.
 */
object ReKey {

    /**
     * How a file is keyed. `null` [passphrase] is plaintext — a real state a
     * document can be in, not an absence of one.
     */
    class Keying(val passphrase: String?) {
        val isPlaintext: Boolean get() = passphrase == null

        companion object {
            val PLAINTEXT = Keying(null)
            fun of(passphrase: String) = Keying(passphrase)
        }
    }

    /**
     * Converts [file] from [from] to [to], in place, or throws having changed
     * nothing.
     *
     * Nothing may hold this file open. The caller owns that: a live connection
     * would be writing into the copy that is about to be renamed aside.
     */
    fun convert(file: File, from: Keying, to: Keying) {
        if (!file.exists()) throw IOException("Nothing to convert at ${file.name}")
        val temp = SoilFiles.tempOf(file)
        temp.delete()
        SoilFiles.sidecars(temp).forEach { it.delete() }

        try {
            exportInto(file, temp, from, to)
            // Verified with the key it is *supposed* to have, before it replaces
            // anything. A conversion that produced an unopenable file must fail
            // here, where the original is still exactly where it was.
            if (!opens(temp, to)) throw IOException("The converted copy would not open")
            CommitSwap.commit(file, temp)
        } catch (t: Throwable) {
            temp.delete()
            SoilFiles.sidecars(temp).forEach { it.delete() }
            throw t
        }
    }

    /** Whether [file] opens with [keying]. The question every step here asks. */
    fun opens(file: File, keying: Keying): Boolean =
        if (keying.isPlaintext) SoilCrypto.opensAsPlaintext(file) else SoilCrypto.verifyPassphrase(file, keying.passphrase!!)

    private fun exportInto(source: File, target: File, from: Keying, to: Keying) {
        val db = open(source, from)
        try {
            // The key is bound, never interpolated: a passphrase with a quote in
            // it would otherwise end the string and change the statement.
            db.execSQL("ATTACH DATABASE ? AS rekeyed KEY ?", arrayOf(target.absolutePath, to.passphrase.orEmpty()))
            try {
                // A query, not a statement: `sqlcipher_export` returns a row, and
                // the driver refuses anything that does through `execSQL`. It has
                // to be *stepped* as well as prepared — a cursor that is never
                // read is an export that never runs.
                db.rawQuery("SELECT sqlcipher_export('rekeyed')", null).use { it.moveToFirst() }
                // THE line. See the warning above.
                db.execSQL("PRAGMA rekeyed.user_version = ${userVersionOf(db)}")
            } finally {
                db.execSQL("DETACH DATABASE rekeyed")
            }
        } finally {
            runCatching { db.close() }
        }
    }

    private fun open(file: File, keying: Keying): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(file, keying.passphrase.orEmpty(), null, null)

    private fun userVersionOf(db: SQLiteDatabase): Int =
        db.rawQuery("PRAGMA user_version", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
}
