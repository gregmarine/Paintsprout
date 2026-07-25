package com.symmetricalpalmtree.paintsprout.data

import java.io.File
import java.io.IOException

/**
 * What can go wrong opening a database, said out loud.
 *
 * Each of these replaces a failure mode that is worse than an exception: a
 * fabricated empty database, a deleted notebook, or ciphertext read as garbage.
 * Callers that legitimately treat "cannot read this" as "nothing to show" —
 * pickers, thumbnails, library cards — catch these and degrade; nobody ignores
 * them.
 */
sealed class SoilOpenException(message: String) : IOException(message)

/**
 * The file isn't there, or is zero bytes.
 *
 * This must be reported as itself and never funnelled back into a passphrase
 * prompt: the user would retry a file that does not exist until the rate limiter
 * locks them out of a library that is perfectly fine.
 */
class DatabaseMissingException(val file: File) :
    SoilOpenException("Database missing or empty: ${file.name}")

/**
 * SQLite reported corruption. The file has NOT been touched.
 *
 * Remember that a wrong key is indistinguishable from corruption at this layer,
 * so this is the normal outcome of opening an encrypted file with the wrong
 * passphrase — and the reason the default handler, which deletes, can never be
 * allowed anywhere near it.
 */
class DatabaseCorruptException(val name: String?) :
    SoilOpenException("Corruption reported for ${name ?: "database"} — refusing to delete it")

/** A keyless open was attempted on a file that is actually encrypted. */
class DatabaseLockedException(val file: File) :
    SoilOpenException("Database is encrypted and no key was supplied: ${file.name}")

/**
 * The guard every open and verification helper starts with.
 *
 * Nearly every SQLite open API is create-if-missing. Point one at a path where a
 * sketchbook *should* be but isn't and it fabricates an empty database there,
 * with three consequences that have all shipped in this family before: the stub
 * masquerades as the real document (it opens fine, it's just blank); it blocks a
 * manual restore of the real file; and — worst — an empty encrypted database
 * **verifies any passphrase you type**, because it was created keyed to whatever
 * was passed.
 *
 * So: everything that opens or verifies goes through here first, and creation
 * gets its own explicitly named entry points used only by the bootstrap.
 */
fun requireExistingDatabase(file: File): File {
    if (!file.exists() || file.length() == 0L) throw DatabaseMissingException(file)
    return file
}

/** The verification form: a missing or empty file is `false`, never `true`. */
fun existsAsDatabase(file: File): Boolean = file.exists() && file.length() > 0L
