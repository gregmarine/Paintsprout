package com.symmetricalpalmtree.paintsprout.data.index

import android.content.Context
import com.symmetricalpalmtree.paintsprout.crypto.AttemptLimiter
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores
import com.symmetricalpalmtree.paintsprout.crypto.PassphraseVault
import com.symmetricalpalmtree.paintsprout.crypto.RawKeyCache
import com.symmetricalpalmtree.paintsprout.crypto.RecoveryKey
import com.symmetricalpalmtree.paintsprout.crypto.SoilCrypto
import com.symmetricalpalmtree.paintsprout.data.DbProbe
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.SwapRecovery
import com.symmetricalpalmtree.paintsprout.data.WalConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Where the index is, from a consumer's point of view. */
sealed interface IndexStatus {
    /** Not open yet. Nothing may read. */
    data object Starting : IndexStatus

    /** Open. [IndexGate.repository] is safe to use. */
    data object Ready : IndexStatus

    /** A passphrase is needed. [lockedUntil] is epoch ms, or 0 when an attempt is allowed. */
    data class NeedsUnlock(val lockedUntil: Long = 0L) : IndexStatus

    /** Something went wrong, and it is worth telling the user about. Retry-able. */
    data class Failed(val cause: Throwable) : IndexStatus
}

/**
 * Opens the global index — once — and gates everything that reads it.
 *
 * Opening is **potentially slow and potentially interactive**: a fresh install
 * mints a key and creates the file, an upgrade might have to repair an
 * interrupted swap, and a device with no cached secret has to ask the user. None
 * of that can be a synchronous step in application startup, which is why this is
 * a gate rather than a lazy singleton: the app kicks it off, `BootstrapActivity`
 * drives the UI, and every consumer suspends on [awaitReady] before touching a
 * DAO.
 *
 * The failure mode that gate prevents is not a blank screen. It is a consumer
 * reading a database that has not been decrypted or migrated yet, and drawing
 * conclusions from it.
 *
 * One [mutex] serializes every open, unlock and seal. Sealing part-way through an
 * open (or the reverse) interleaves a close with a fresh open and throws on the
 * closed instance.
 */
object IndexGate {

    private val mutex = Mutex()

    /** Outlives any screen: a background key derivation must survive the activity. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<IndexStatus>(IndexStatus.Starting)
    val status: StateFlow<IndexStatus> = _status

    private var database: IndexDatabase? = null
    private var repository: IndexRepository? = null

    /**
     * The recovery key, when this device still needs the user to write it down.
     * Read from the vault rather than held in RAM, so force-quitting the
     * onboarding screen doesn't lose it.
     */
    fun pendingRecoveryKey(context: Context): String? {
        val store = CryptoStores.secrets(context)
        if (store.getInt(KEY_RECOVERY_ACK, 0) == 1) return null
        val phrase = PassphraseVault(store).globalOrNull() ?: return null
        return if (phrase.startsWith("${RecoveryKey.PREFIX}-")) phrase else null
    }

    fun acknowledgeRecoveryKey(context: Context) =
        CryptoStores.secrets(context).putInt(KEY_RECOVERY_ACK, 1)

    /** Suspends until the index is open. Every consumer calls this before any read. */
    suspend fun awaitReady(): IndexRepository {
        _status.first { it is IndexStatus.Ready }
        return repository ?: error("Index reported ready without a repository")
    }

    fun repositoryOrNull(): IndexRepository? = repository

    /**
     * The raw connection under the index, for the document-shaped tables it also
     * holds — `scratchpad` and `clipboard`.
     *
     * Those are not Room's: they are the universal object row, created from
     * [com.symmetricalpalmtree.paintsprout.data.SchemaSql] so that every codec and
     * subtree walk written for a sketchbook file works on them unchanged. They
     * still have to come through this gate, because reading a database that has
     * not been decrypted yet is the failure this whole class exists to prevent.
     */
    suspend fun awaitConnection(): androidx.sqlite.db.SupportSQLiteDatabase {
        _status.first { it is IndexStatus.Ready }
        val db = database ?: error("Index reported ready without a database")
        return db.openHelper.writableDatabase
    }

    /**
     * Idempotent: returns immediately if the index is already open. Safe to call
     * from several places at once — the bootstrap screen, a deep link, a share
     * intent — which is the point, because those can all start the app.
     */
    suspend fun ensureReady(context: Context): IndexStatus = mutex.withLock {
        database?.let { return@withLock IndexStatus.Ready }
        val app = context.applicationContext
        val result = withContext(Dispatchers.IO) { open(app) }
        _status.value = result
        result
    }

    private suspend fun open(context: Context): IndexStatus = try {
        val root = SoilFiles.storageRoot(context)

        // ALWAYS FIRST. A swap that was killed mid-rename leaves the index absent
        // with its data under an aside name, and a probe of an absent file says
        // INVALID — which means "fresh install", which would replace the user's
        // whole library with an empty one.
        SwapRecovery.repairAll(root)

        val file = SoilFiles.indexFile(root)
        val vault = PassphraseVault(CryptoStores.secrets(context))
        val keys = RawKeyCache(CryptoStores.derivedKeys(context))
        val passphrase = vault.globalOrNull()
        val rawKey = if (passphrase != null) keys.peekOrLoad(RawKeyCache.INDEX_FILE_ID) else null

        when (IndexOpenPlanner.plan(DbProbe.probe(file), passphrase != null, rawKey != null)) {
            IndexOpenPlan.CREATE_ENCRYPTED -> {
                // Encrypted from the first byte: SQLCipher creates the file keyed,
                // so there is never a moment at which a plaintext index exists.
                val minted = vault.ensureGlobal()
                install(context, file, SoilCrypto.roomFactory(minted))
                deriveInBackground(context, file, minted)
                IndexStatus.Ready
            }

            IndexOpenPlan.OPEN_WITH_RAW_KEY -> {
                // Verify before trusting it. A key that no longer opens the file —
                // rotated elsewhere, restored from a foreign backup — is
                // indistinguishable from corruption once it reaches SQLite.
                if (SoilCrypto.verifyRawKey(file, rawKey!!)) {
                    install(context, file, SoilCrypto.roomFactoryRawKey(rawKey))
                    IndexStatus.Ready
                } else {
                    keys.invalidate(RawKeyCache.INDEX_FILE_ID)
                    needsUnlock(context)
                }
            }

            IndexOpenPlan.OPEN_WITH_PASSPHRASE -> {
                if (SoilCrypto.verifyPassphrase(file, passphrase!!)) {
                    // One slow open; the derivation happens behind it rather than
                    // in front of the user.
                    install(context, file, SoilCrypto.roomFactory(passphrase))
                    deriveInBackground(context, file, passphrase)
                    IndexStatus.Ready
                } else {
                    needsUnlock(context)
                }
            }

            IndexOpenPlan.NEEDS_UNLOCK -> needsUnlock(context)

            IndexOpenPlan.REFUSE_PLAINTEXT -> IndexStatus.Failed(
                IllegalStateException(
                    "The file at ${file.name} is not an encrypted Paintsprout index. " +
                        "It has been left exactly as it is.",
                ),
            )
        }
    } catch (t: Throwable) {
        IndexStatus.Failed(t)
    }

    /**
     * Tries [passphrase] against the index.
     *
     * A **cancelled** prompt never reaches here, which is what keeps a cancel from
     * advancing the lockout counter.
     */
    suspend fun unlock(context: Context, passphrase: String): IndexStatus = mutex.withLock {
        database?.let { return@withLock IndexStatus.Ready }
        val app = context.applicationContext
        val result = withContext(Dispatchers.IO) { tryUnlock(app, passphrase) }
        _status.value = result
        result
    }

    private suspend fun tryUnlock(context: Context, passphrase: String): IndexStatus = try {
        val file = SoilFiles.indexFile(SoilFiles.storageRoot(context))
        val secrets = CryptoStores.secrets(context)
        val limiter = AttemptLimiter(secrets)

        if (limiter.isLocked(AttemptLimiter.GLOBAL_BUCKET)) {
            IndexStatus.NeedsUnlock(limiter.lockedUntil(AttemptLimiter.GLOBAL_BUCKET))
        } else if (!SoilCrypto.verifyPassphrase(file, passphrase)) {
            limiter.recordFailure(AttemptLimiter.GLOBAL_BUCKET)
            IndexStatus.NeedsUnlock(limiter.lockedUntil(AttemptLimiter.GLOBAL_BUCKET))
        } else {
            limiter.recordSuccess(AttemptLimiter.GLOBAL_BUCKET)
            PassphraseVault(secrets).setGlobal(passphrase)
            install(context, file, SoilCrypto.roomFactory(passphrase))
            deriveInBackground(context, file, passphrase)
            IndexStatus.Ready
        }
    } catch (t: Throwable) {
        IndexStatus.Failed(t)
    }

    private fun needsUnlock(context: Context): IndexStatus.NeedsUnlock {
        val limiter = AttemptLimiter(CryptoStores.secrets(context))
        return IndexStatus.NeedsUnlock(limiter.lockedUntil(AttemptLimiter.GLOBAL_BUCKET))
    }

    private suspend fun install(
        context: Context,
        file: File,
        factory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory,
    ) {
        val db = IndexDatabase.open(context, file, factory)
        // Force the open here rather than at the first query, so a bad key fails
        // inside this function — where it becomes a status — instead of inside
        // whichever screen happened to read first.
        db.openHelper.writableDatabase
        val repo = IndexRepository(db)
        // Awaited, not fired off: "ready" has to mean the sentinels are there, or
        // the first consumer through the gate races the bootstrap that creates them.
        repo.ensureSentinels()
        database = db
        repository = repo
    }

    /**
     * The KDF costs 300–700 ms, and this launch has already paid for a passphrase
     * open. Deriving behind the user means the *next* cold launch is the fast one
     * and this one isn't made slower to get there.
     */
    private fun deriveInBackground(context: Context, file: File, passphrase: String) {
        scope.launch {
            runCatching {
                RawKeyCache(CryptoStores.derivedKeys(context))
                    .global(RawKeyCache.INDEX_FILE_ID, file, passphrase)
            }
        }
    }

    /** Retry after a failure: drop the error and run the whole sequence again. */
    suspend fun retry(context: Context): IndexStatus {
        _status.value = IndexStatus.Starting
        return ensureReady(context)
    }

    /**
     * Closes the index. The only file allowed to keep its WAL sidecars during
     * normal use is this one — it never closes — so this runs at shutdown, under
     * the same mutex as every open.
     */
    suspend fun seal() = mutex.withLock {
        val db = database ?: return@withLock
        withContext(Dispatchers.IO) {
            runCatching { WalConfig.seal(db.openHelper.writableDatabase) }
            runCatching { db.close() }
        }
        database = null
        repository = null
        _status.value = IndexStatus.Starting
    }

    private const val KEY_RECOVERY_ACK = "recovery_key_acknowledged"
}
