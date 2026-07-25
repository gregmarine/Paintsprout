package com.symmetricalpalmtree.paintsprout.crypto

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.paintsprout.data.DatabaseLockedException
import com.symmetricalpalmtree.paintsprout.data.DbProbe
import com.symmetricalpalmtree.paintsprout.data.DbState
import com.symmetricalpalmtree.paintsprout.data.NonDestructiveOpenHelperFactory
import com.symmetricalpalmtree.paintsprout.data.existsAsDatabase
import com.symmetricalpalmtree.paintsprout.data.requireExistingDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDb

/**
 * The one crypto-aware way to open a database. Nothing constructs a
 * [SupportOpenHelperFactory] or a SQLCipher connection outside this object.
 *
 * Every factory here is wrapped in [NonDestructiveOpenHelperFactory], with no
 * exceptions and no "this path can't hit an encrypted file" reasoning — that
 * reasoning is exactly what destroyed a notebook in the app this format comes
 * from. And every open is exists-guarded, because the underlying opens are
 * create-capable and an empty encrypted database will happily "verify" any
 * passphrase it is handed.
 *
 * Creation lives at the bottom under names that say so, and those are the only
 * entry points a new sketchbook or a new index may use.
 */
object SoilCrypto {

    /** The canonical passphrase encoding, restated where callers will look for it. */
    fun keyBytes(passphrase: String): ByteArray = RawKeyDerivation.keyBytes(passphrase)

    // --- Room factories -----------------------------------------------------

    /** Opens with the passphrase, paying the KDF. Use on a raw-key cache miss. */
    fun roomFactory(passphrase: String): SupportSQLiteOpenHelper.Factory =
        NonDestructiveOpenHelperFactory(SupportOpenHelperFactory(keyBytes(passphrase)))

    /**
     * Opens a file that carries no key at all.
     *
     * Only import has one of those: a `.soil` that arrived from somewhere else and
     * probed as plaintext. Everything the app *creates* is encrypted from its
     * first byte, so this is a reader, never a writer of new documents — and it
     * keeps the non-destructive wrapper, because an unopenable file must not be
     * deleted by the platform's default handler whatever it was keyed with.
     */
    fun plaintextFactory(): SupportSQLiteOpenHelper.Factory =
        NonDestructiveOpenHelperFactory(androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory())

    /** Opens with a pre-derived key, skipping the KDF. The common path. */
    fun roomFactoryRawKey(rawKey: ByteArray): SupportSQLiteOpenHelper.Factory =
        NonDestructiveOpenHelperFactory(
            SupportOpenHelperFactory(
                RawKeyDerivation.rawKeyLiteral(rawKey).toByteArray(Charsets.US_ASCII),
            ),
        )

    // --- Verification -------------------------------------------------------

    /**
     * Does this passphrase open this file?
     *
     * False for a missing or empty file — **never** true. A create-capable open
     * pointed at a path where the sketchbook should be but isn't would otherwise
     * fabricate an empty database keyed to whatever was typed, and then report
     * success against a document that is not there.
     */
    fun verifyPassphrase(file: File, passphrase: String): Boolean {
        if (!existsAsDatabase(file)) return false
        return canRead { CipherDb.openOrCreateDatabase(file, passphrase, null, null) }
    }

    /** As [verifyPassphrase], for a cached raw key. Verify before trusting one. */
    fun verifyRawKey(file: File, rawKey: ByteArray): Boolean {
        if (!existsAsDatabase(file)) return false
        return canRead {
            CipherDb.openOrCreateDatabase(file, RawKeyDerivation.rawKeyLiteral(rawKey), null, null)
        }
    }

    // --- Raw (non-Room) opens -----------------------------------------------

    /** An existing encrypted file, opened with its passphrase. */
    fun openEncrypted(file: File, passphrase: String): CipherDb {
        requireExistingDatabase(file)
        return CipherDb.openOrCreateDatabase(file, passphrase, null, null)
    }

    /** An existing encrypted file, opened with a pre-derived key. */
    fun openEncryptedRawKey(file: File, rawKey: ByteArray): CipherDb {
        requireExistingDatabase(file)
        return CipherDb.openOrCreateDatabase(file, RawKeyDerivation.rawKeyLiteral(rawKey), null, null)
    }

    /**
     * Refuses to open ciphertext as plaintext.
     *
     * The failure this prevents is subtle: a caller that believes a file is
     * plaintext is usually a caller whose key simply wasn't resolved. Opening it
     * anyway hands ciphertext to a plain SQLite driver, which reads it as a
     * corrupt database — and historically that is precisely how a document got
     * deleted. Callers that legitimately treat "cannot read" as "nothing to show"
     * catch [DatabaseLockedException] and render a lock.
     */
    fun assertNotEncrypted(file: File) {
        requireExistingDatabase(file)
        if (DbProbe.probe(file) == DbState.ENCRYPTED) throw DatabaseLockedException(file)
    }

    // --- Creation -----------------------------------------------------------
    // The only paths permitted to bring a database into existence. Everything
    // above requires the file to already be there.

    /** Creates a new encrypted database. New-sketchbook and new-index bootstrap only. */
    fun createEncrypted(file: File, passphrase: String): CipherDb =
        CipherDb.openOrCreateDatabase(file, passphrase, null, null)

    private inline fun canRead(open: () -> CipherDb): Boolean = try {
        val db = open()
        db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        db.close()
        true
    } catch (e: Exception) {
        // A wrong key lands here, and so does genuine damage. They are
        // indistinguishable without the passphrase, which is the whole reason the
        // deleting corruption handler can never be allowed near this.
        false
    }
}
