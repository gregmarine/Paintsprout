package com.symmetricalpalmtree.paintsproutonyx.data.index

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.paintsproutonyx.crypto.GlobalKey
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeyMaterial
import com.symmetricalpalmtree.paintsproutonyx.crypto.KeySession
import com.symmetricalpalmtree.paintsproutonyx.crypto.PassphraseStore
import com.symmetricalpalmtree.paintsproutonyx.crypto.SoilCrypto
import com.symmetricalpalmtree.paintsproutonyx.crypto.SoilFileKind
import com.symmetricalpalmtree.paintsproutonyx.data.indexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one thing that opens the global index, and the one place that decides what to do when it will
 * not open.
 *
 * Getting into `paintsprout.db` is not a single step — it can be a first launch with no file at all,
 * a normal launch that unwraps a cached key, a launch that has to ask for the recovery key back, or a
 * file that is not ours. All four answers come out of [ensureReady] as a [PrepareOutcome], and
 * `BootstrapActivity` is the only screen that ever sees them. Every other screen asks [isReady] and
 * bounces back to Bootstrap if the answer is no — which is what Android does to a task it rebuilt
 * after killing the process, and the reason that check has to exist at all.
 *
 * **Opened once per process, never closed.** That is what makes one `onCreate` check enough: if it
 * was ready when the screen opened, it is still ready.
 *
 * The state machine, and why it is shaped this way:
 *
 *  - The file's header is **probed**, never opened, to find out what it is. A create-capable open
 *    asked "is this ours?" answers by making a brand-new empty database where the artist's library
 *    used to be. The probe reads bytes and touches nothing.
 *  - `Invalid` — no file, or an empty one — is a **first launch**. Mint (or reuse) the global key,
 *    create the index encrypted from its first byte, then derive and cache the raw key so no later
 *    launch pays for the key derivation again.
 *  - `Encrypted` is the ordinary case. With no cached passphrase there is nothing to try, so ask for
 *    it. With one, the cached raw key is **verified against this file** before Room is allowed near
 *    it; a key that no longer fits (the library was restored from another install, the debug build's
 *    key against the release build's file) is dropped and the passphrase re-derives one. Only if the
 *    passphrase itself no longer fits do we ask.
 *  - `Plaintext` is somebody else's file, or a damaged one. It is **never opened**. The framework's
 *    corruption path deletes, and there is no version of "we deleted your library while working out
 *    what it was" that is acceptable.
 *
 * All of it runs on IO. Nothing here may be reached from the main thread through `runBlocking`.
 */
object PaintsproutIndex {

    /**
     * What Bootstrap has to do next.
     *
     * [FIRST_LAUNCH] is distinct from [READY] because a freshly minted recovery key has to be shown to
     * the artist once — a library encrypted under a key nobody has written down is a library with a
     * countdown on it.
     */
    enum class PrepareOutcome { READY, FIRST_LAUNCH, NEEDS_UNLOCK, FOREIGN_FILE }

    private const val TAG = "PaintsproutIndex"

    @Volatile
    private var instance: IndexDatabase? = null

    /**
     * Two launches can race — Bootstrap resumed twice, or a warm-up that overlaps it. Without this,
     * both would find no instance and both would build one, and the loser's connection would sit open
     * on the same file holding a WAL nobody will ever checkpoint.
     */
    private val prepareMutex = Mutex()

    fun isReady(): Boolean = instance != null

    fun db(): IndexDatabase =
        instance ?: throw IllegalStateException("the index is not open — BootstrapActivity has to run first")

    fun dao(): ObjectDao = db().objectDao()

    /** Bring the index to an open state. Idempotent, safe to call concurrently, IO. */
    suspend fun ensureReady(context: Context): PrepareOutcome = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            if (instance != null) return@withContext PrepareOutcome.READY
            val app = context.applicationContext
            val file = indexFile(app)

            when (SoilCrypto.probe(file)) {
                SoilFileKind.Invalid -> {
                    val passphrase = GlobalKey.ensure(app)
                    file.parentFile?.mkdirs()
                    val db = build(app, file, SoilCrypto.roomFactory(passphrase))
                    forceOpen(db)
                    finishOpen(db, passphrase)
                    // The file now carries a salt, so the raw key can be derived and kept. Any key
                    // already cached under the index's id belongs to a file that no longer exists —
                    // this one was just created, and its salt is brand new — so it is dropped first;
                    // otherwise the cache would hand back the old file's key and this step would
                    // "cache" something that opens nothing. This is the one time this process pays
                    // for the key derivation; if it fails the library still works, every launch is
                    // just slower, and that is not worth failing a first run over.
                    KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
                    runCatching { KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, passphrase) }
                        .onFailure { Log.w(TAG, "could not cache the index key after creating it", it) }
                    PrepareOutcome.FIRST_LAUNCH
                }

                SoilFileKind.Encrypted -> {
                    val passphrase = PassphraseStore.getGlobalPassphrase(app)
                        ?: return@withContext PrepareOutcome.NEEDS_UNLOCK
                    val key = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, passphrase)
                    if (SoilCrypto.verifyRawKey(file, key)) {
                        val db = build(app, file, SoilCrypto.roomFactoryRawKey(key))
                        forceOpen(db)
                        finishOpen(db, passphrase)
                        return@withContext PrepareOutcome.READY
                    }
                    // The cached key does not open this file. That does not mean the passphrase is
                    // wrong — a library carried over from another install has a different salt, so the
                    // same recovery key derives a different raw key against it. Throw the stale one
                    // away and let the passphrase speak for itself.
                    KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
                    if (!SoilCrypto.verifyPassphrase(file, passphrase)) {
                        return@withContext PrepareOutcome.NEEDS_UNLOCK
                    }
                    val fresh = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, passphrase)
                    val db = build(app, file, SoilCrypto.roomFactoryRawKey(fresh))
                    forceOpen(db)
                    finishOpen(db, passphrase)
                    PrepareOutcome.READY
                }

                SoilFileKind.Plaintext -> PrepareOutcome.FOREIGN_FILE
            }
        }
    }

    /**
     * The unlock path: try a recovery key the artist typed.
     *
     * The passphrase is checked **read-only, against the file, before anything is built** — Room is
     * never handed a key that has not already been shown to fit. An unverified key reaches SQLCipher
     * as a file that will not decrypt, which is indistinguishable from a corrupt one, and the reaction
     * to a corrupt file is the thing we most need never to happen.
     *
     * A wrong key returns false and leaves the file byte-for-byte as it was. IO.
     */
    suspend fun unlockAndOpen(context: Context, passphrase: String): Boolean = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            if (instance != null) return@withContext true
            val app = context.applicationContext
            val file = indexFile(app)
            if (!SoilCrypto.verifyPassphrase(file, passphrase)) return@withContext false
            PassphraseStore.setGlobalPassphrase(app, passphrase)
            // Whatever was cached did not fit, or we would not be here.
            KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
            val key = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, passphrase)
            val db = build(app, file, SoilCrypto.roomFactoryRawKey(key))
            forceOpen(db)
            finishOpen(db, passphrase)
            true
        }
    }

    /**
     * The index is open, so the passphrase behind it is known-good — hand it to [KeySession] for the
     * sketchbook files, which are encrypted under the same key and must not each go asking for it.
     */
    private fun finishOpen(db: IndexDatabase, passphrase: String) {
        instance = db
        KeySession.set(passphrase)
    }

    private fun build(
        context: Context,
        file: File,
        factory: SupportSQLiteOpenHelper.Factory,
    ): IndexDatabase =
        Room.databaseBuilder(context.applicationContext, IndexDatabase::class.java, file.absolutePath)
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Connection settings, not file settings — they have to be reapplied every open.
                    // A short autocheckpoint keeps the WAL from growing into something that takes a
                    // visible pause to fold back in; the busy timeout means a write that collides with
                    // the checkpoint waits instead of failing.
                    db.query("PRAGMA wal_autocheckpoint = 100").use { it.moveToFirst() }
                    db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
                }
            })
            .build()

    /**
     * Room builds lazily and would not touch the file until the first query — which would move the
     * create, the key derivation and any failure out of [ensureReady] and into whatever screen
     * happened to read first. A trivial PRAGMA makes the open happen here, where it can be answered.
     */
    private fun forceOpen(db: IndexDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA user_version").use { it.moveToFirst() }
    }

    /**
     * Fold the WAL back into the file itself, so what is on disk is the whole library — before a debug
     * pull, or anything else that reads the file from outside the app. Never throws: a failed
     * checkpoint costs nothing, and there is no caller for whom it is worth an exception. IO.
     */
    suspend fun checkpoint() = withContext(Dispatchers.IO) {
        try {
            db().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "checkpoint failed", e)
        }
    }
}
