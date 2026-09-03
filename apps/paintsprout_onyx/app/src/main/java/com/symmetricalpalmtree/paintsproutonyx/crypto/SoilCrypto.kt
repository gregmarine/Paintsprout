package com.symmetricalpalmtree.paintsproutonyx.crypto

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.paintsproutonyx.data.NonDestructiveOpenHelperFactory
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * What a header probe of a database file found. This app has no plaintext mode,
 * so [Plaintext] is only ever a diagnosis — a file we refuse, never one we open.
 */
enum class SoilFileKind { Plaintext, Encrypted, Invalid }

/** Thrown when a database is asked to open but cannot be — missing file, or no key that fits. */
class SoilLockedException(message: String) : RuntimeException(message)

/**
 * The one place SQLCipher factories and handles are made — the crypto twin of
 * `soilFile()`. Every open of the index or a `.soil`, Room or raw, comes through
 * here, because the data-loss bugs this family has actually suffered all begin
 * with an open constructed somewhere else with local assumptions:
 *
 *  - Every Room factory is wrapped in [NonDestructiveOpenHelperFactory], and
 *    every key Room is handed has first opened the file read-only
 *    ([verifyRawKey] / [verifyPassphrase]). A wrong key makes SQLite read
 *    ciphertext as corruption, and the stock answer to corruption is to delete
 *    the file. With SQLCipher's factory the wrapper's own handler is never
 *    reached — that file walks the bytecode — and what spares the file is
 *    SQLCipher's codec guard instead. The verified-key rule is what makes the
 *    safety this app's own rather than a default in somebody else's library:
 *    a key that fits cannot report corruption, whichever handler is on duty.
 *  - Every non-creating open passes [requireExisting] first. The underlying opens
 *    are create-capable, and pointed at a missing path they would mint an empty
 *    database that then masquerades as the real one — an "unlock" that appears to
 *    succeed against a library that no longer contains anything. Creation gets
 *    exactly one named door, [createRaw], and that door refuses existing files.
 *  - The passphrase becomes key bytes one way only ([keyBytes], UTF-8), and the
 *    cipher settings are stock SQLCipher 4 throughout — no `kdf_iter`, no page
 *    size. Stock defaults are not a convenience, they are the format: a pulled
 *    file must open in an unmodified sqlcipher CLI with nothing but the string.
 *
 * The native library must be loaded (`System.loadLibrary("sqlcipher")`) before
 * anything here runs — that belongs to the application's onCreate, the same
 * place the Wacom app does it.
 */
object SoilCrypto {

    /** The canonical passphrase-to-bytes encoding. UTF-8, everywhere, forever — this is on-disk compatibility. */
    fun keyBytes(passphrase: String): ByteArray = passphrase.toByteArray(Charsets.UTF_8)

    // ── Room factories. Room itself can create; callers guard existence (see the index and soil owners).

    /** Room factory keyed by the passphrase — SQLCipher runs its own KDF on this connection. */
    fun roomFactory(passphrase: String): SupportSQLiteOpenHelper.Factory =
        NonDestructiveOpenHelperFactory(SupportOpenHelperFactory(keyBytes(passphrase)))

    /**
     * Room factory keyed by a pre-derived raw key — the KDF is skipped and the
     * open costs milliseconds. The key travels as the `x'<hex>'` literal in
     * ASCII bytes, which SQLCipher recognises as raw-key syntax; hand it the 32
     * key bytes directly and it would run the KDF over them as if they were a
     * passphrase, deriving a key that opens nothing.
     */
    fun roomFactoryRawKey(rawKey: ByteArray): SupportSQLiteOpenHelper.Factory =
        NonDestructiveOpenHelperFactory(
            SupportOpenHelperFactory(RawKeyDerivation.rawKeyLiteral(rawKey).toByteArray(Charsets.US_ASCII))
        )

    // ── Raw (non-Room) opens — exists-guarded ────────────────────────────────

    /** Raw encrypted open with the passphrase. [file] must exist and be non-empty. */
    fun openRaw(file: File, passphrase: String): SQLiteDatabase {
        requireExisting(file)
        return SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null)
    }

    /** Raw encrypted open with a raw key (KDF skipped). [file] must exist and be non-empty. */
    fun openRawKey(file: File, rawKey: ByteArray): SQLiteDatabase {
        requireExisting(file)
        return SQLiteDatabase.openOrCreateDatabase(file, RawKeyDerivation.rawKeyLiteral(rawKey), null, null)
    }

    /**
     * True iff [passphrase] opens [file]. FALSE for a missing or empty file — not
     * an exception, and never true. A create-capable open pointed at a missing
     * path would manufacture an empty database keyed to whatever was typed and
     * then "verify" against nothing, which is how a typo becomes a fresh library
     * wearing the old one's name.
     */
    fun verifyPassphrase(file: File, passphrase: String): Boolean =
        verifyWith { openRaw(file, passphrase) }

    /** True iff [rawKey] opens [file]. Same missing-file rule as [verifyPassphrase]. */
    fun verifyRawKey(file: File, rawKey: ByteArray): Boolean =
        verifyWith { openRawKey(file, rawKey) }

    private inline fun verifyWith(open: () -> SQLiteDatabase): Boolean = try {
        val db = open()
        try {
            // Reading sqlite_master forces a real page decrypt; a wrong key throws here.
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            true
        } finally {
            runCatching { db.close() }
        }
    } catch (_: Exception) {
        false
    }

    /** Throws [SoilLockedException] unless [file] exists and is non-empty. */
    fun requireExisting(file: File) {
        if (!file.exists() || file.length() == 0L) {
            throw SoilLockedException("Database file is missing or empty: ${file.name}")
        }
    }

    // ── The one creation door ────────────────────────────────────────────────

    /**
     * Bring a brand-new encrypted database into existence at [file] — the
     * first-launch index and the new-sketchbook flow, and nothing else. It
     * refuses an existing non-empty file because creation is never a repair:
     * a create that "fixes" an unopenable file fixes it by destroying it.
     */
    fun createRaw(file: File, passphrase: String): SQLiteDatabase {
        require(!file.exists() || file.length() == 0L) {
            "refusing to create over an existing file: ${file.name}"
        }
        file.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null)
    }

    // ── Probe ────────────────────────────────────────────────────────────────

    /**
     * Header-only probe — the file is never opened. Plaintext SQLite begins with
     * the 16-byte magic ("SQLite format 3" and a NUL); SQLCipher encrypts the
     * entire first page, so on an encrypted file that magic is absent and the
     * header is the KDF salt. Missing, empty, unreadable or too short all read
     * as [SoilFileKind.Invalid] — which the bootstrap treats as "nothing here
     * yet", never as something to repair.
     */
    fun probe(file: File): SoilFileKind {
        if (!file.exists() || file.length() == 0L) return SoilFileKind.Invalid
        val header = ByteArray(SQLITE_MAGIC.size)
        val n = try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) {
            return SoilFileKind.Invalid
        }
        if (n < header.size) return SoilFileKind.Invalid
        return if (header.contentEquals(SQLITE_MAGIC)) SoilFileKind.Plaintext else SoilFileKind.Encrypted
    }

    /** "SQLite format 3" followed by a NUL terminator — 16 bytes. */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
