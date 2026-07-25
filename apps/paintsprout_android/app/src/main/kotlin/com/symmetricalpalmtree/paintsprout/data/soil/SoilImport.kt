package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import android.net.Uri
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.crypto.PassphraseVault
import com.symmetricalpalmtree.paintsprout.crypto.RawKeyCache
import com.symmetricalpalmtree.paintsprout.crypto.SoilCrypto
import com.symmetricalpalmtree.paintsprout.data.CommitSwap
import com.symmetricalpalmtree.paintsprout.data.DbProbe
import com.symmetricalpalmtree.paintsprout.data.DbState
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Bringing a `.soil` in from outside.
 *
 * This is the only path where the app acts on **a file it did not write**, and
 * the shape of it follows from that. Nothing touches the library until the file
 * has been copied somewhere safe, identified, opened, and had every id in its
 * manifest checked; and the staged copy is deleted on every exit — success,
 * refusal, cancel and crash-adjacent failure alike.
 *
 * The order that matters most is at the end: **the file is installed, then the
 * index is written, and only then is a replaced document retired.** Retiring
 * first would leave a library row pointing at nothing if the install failed, and
 * a card that opens onto nothing is how an empty ghost document gets minted.
 */
object SoilImport {

    /** Where an incoming file is staged. Cleared on the way in and the way out. */
    const val STAGE_DIR = "import"

    /**
     * The id a staged file is opened under while its manifest is read.
     *
     * A fixed non-UUID, deliberately: the real id is what we are here to find out,
     * and this must never collide with a document's — [OpenDocuments] is keyed by
     * id, and a staged read that registered under a library document's id would
     * make that document look open to everything that checks.
     */
    private const val STAGED_ID = "staged-import"

    /**
     * One page. A SQLite file is a whole number of pages and the default is 4 KB,
     * so nothing shorter than this is a database — encrypted, damaged or
     * otherwise. Without it, a text file somebody renamed gets a passphrase
     * prompt, because a file with no SQLite header is indistinguishable from an
     * encrypted one until a key is tried.
     */
    const val MIN_DOCUMENT_BYTES = 4096L

    /** How far a file got, and what the caller has to do about it. */
    sealed interface Step {
        /** Refused. [reason] is already user-facing. */
        class Refused(val reason: ImportPlan.Verdict, val detail: String? = null) : Step

        /** Needs a passphrase; call [SoilImport.unlock] with one. */
        class NeedsKey(val staged: File, val lockedUntil: Long) : Step

        /** Identified and openable. [collision] says what the library already has. */
        class Ready(
            val staged: File,
            val meta: NotebookMeta,
            val collision: ImportPlan.Collision,
            val passphrase: String?,
            val plaintext: Boolean,
        ) : Step
    }

    /**
     * Stages [uri] and works out what it is.
     *
     * The copy comes first because everything after it needs a *file*: the probe
     * reads the header, the opener needs a path, and a content URI is a stream
     * that may be a network mount, a cloud provider, or gone by the second read.
     */
    suspend fun inspect(context: Context, uri: Uri): Step = withContext(Dispatchers.IO) {
        val staged = stage(context, uri) ?: return@withContext Step.Refused(ImportPlan.Verdict.NO_MANIFEST)
        // Too small to be a database of any kind, so it is not one that needs a
        // key. The check lives here rather than in the shared probe on purpose:
        // the probe also decides whether the *index* is a fresh install, and
        // "INVALID" there means "create an empty library" — a truncated index
        // must keep asking for a passphrase rather than be declared absent. The
        // worst a wrong "no" can do on this path is tell somebody their file
        // isn't a sketchbook.
        if (staged.length() < MIN_DOCUMENT_BYTES) {
            discard(staged)
            return@withContext Step.Refused(ImportPlan.Verdict.NO_MANIFEST)
        }
        when (DbProbe.probe(staged)) {
            DbState.INVALID -> {
                discard(staged)
                Step.Refused(ImportPlan.Verdict.NO_MANIFEST)
            }

            DbState.PLAINTEXT -> readManifest(context, staged, passphrase = null, plaintext = true)

            // The device's own key first: a file exported from this device, or
            // from another one restored from the same recovery key, opens without
            // anybody being asked anything.
            DbState.ENCRYPTED -> {
                val global = PassphraseVault(CryptoStores.secrets(context)).globalOrNull()
                if (global != null && SoilCrypto.verifyPassphrase(staged, global)) {
                    readManifest(context, staged, passphrase = global, plaintext = false)
                } else {
                    Step.NeedsKey(staged, lockedUntil = 0L)
                }
            }
        }
    }

    /** Tries [passphrase] against a staged file. Wrong answers are rate-limited. */
    suspend fun unlock(context: Context, staged: File, passphrase: String): Step =
        withContext(Dispatchers.IO) {
            val limiter = com.symmetricalpalmtree.paintsprout.crypto.AttemptLimiter(CryptoStores.secrets(context))
            val bucket = com.symmetricalpalmtree.paintsprout.crypto.AttemptLimiter.IMPORT_BUCKET
            if (limiter.isLocked(bucket)) return@withContext Step.NeedsKey(staged, limiter.lockedUntil(bucket))
            if (!SoilCrypto.verifyPassphrase(staged, passphrase)) {
                limiter.recordFailure(bucket)
                return@withContext Step.NeedsKey(staged, limiter.lockedUntil(bucket))
            }
            limiter.recordSuccess(bucket)
            readManifest(context, staged, passphrase, plaintext = false)
        }

    /**
     * Installs a staged file that [inspect] or [unlock] said was [Step.Ready].
     *
     * [resolution] answers a collision. `KEEP_BOTH` gives the incoming document a
     * **new id**, which is the only honest way two copies of the same document can
     * coexist: the id is the filename and the row key, and the one already here
     * got there first.
     */
    suspend fun install(
        context: Context,
        ready: Step.Ready,
        resolution: ImportPlan.Resolution,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): String? = withContext(Dispatchers.IO) {
        if (resolution == ImportPlan.Resolution.CANCEL) {
            discard(ready.staged)
            return@withContext null
        }
        val replacing = ready.collision == ImportPlan.Collision.EXISTS &&
            resolution == ImportPlan.Resolution.REPLACE
        val documentId = if (replacing) ready.meta.notebookId else {
            if (ready.collision == ImportPlan.Collision.NONE) ready.meta.notebookId else newId()
        }

        val index = IndexGate.awaitReady()
        try {
            // The ancestry first, create-only: a folder already here is used as it
            // stands, never renamed or moved to match somebody else's library. The
            // rows are read before the pure planner is asked, so the decision stays
            // a function of what is known rather than a database call per step.
            val known = ready.meta.folderPath.associate { it.id to index.byId(it.id) }
            val steps = ImportPlan.folderSteps(ready.meta.folderPath) { known[it] }
            for (step in steps) {
                if (!step.exists) index.createFolder(step.ref.name, step.ref.parentId, step.ref.id)
            }
            val parentId = ImportPlan.parentOf(ready.meta.folderPath)
            val taken = index.sketchbooks(parentId).filter { it.id != documentId }.map { it.name }
            val name = ImportPlan.uniqueName(ready.meta.name, taken)

            // Install by `.new` + rename, so a half-copied file never sits under
            // the name the library is about to point at.
            val target = SoilFiles.soilFile(context, documentId)
            val incoming = SoilFiles.installOf(target)
            ready.staged.copyTo(incoming, overwrite = true)
            if (target.exists()) CommitSwap.commit(target, incoming) else incoming.renameTo(target)

            // Then the row — and a replaced document keeps its own row, updated,
            // rather than being deleted and recreated: the pins and the history
            // pointing at it are not the incoming file's to discard.
            // A tombstone goes down the create path, which revives it — see
            // IndexRepository.write. Only a live row is an update.
            val existing = index.byId(documentId)?.takeIf { it.isAlive }
            if (existing == null) {
                index.createSketchbook(
                    name = name,
                    parentId = parentId,
                    id = documentId,
                    encrypted = !ready.plaintext,
                    keyScope = scopeOf(context, ready),
                )
            } else {
                index.rename(documentId, name)
                index.setEncryption(documentId, !ready.plaintext, scopeOf(context, ready).takeIf { !ready.plaintext })
            }

            // Everything the library shows, from the file that just landed.
            refreshAfterInstall(context, documentId, ready, name, ready.meta.notebookId)
            documentId
        } catch (t: Throwable) {
            null
        } finally {
            discard(ready.staged)
        }
    }

    /** Drops a staged file the user walked away from. Safe to call twice. */
    fun discard(staged: File) {
        runCatching { SoilFiles.sidecars(staged).forEach { it.delete() } }
        runCatching { staged.delete() }
    }

    // --- Inside --------------------------------------------------------------

    private fun stage(context: Context, uri: Uri): File? {
        val dir = File(context.cacheDir, STAGE_DIR)
        dir.deleteRecursively()
        dir.mkdirs()
        val staged = File(dir, "incoming.${SoilFiles.EXTENSION}")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            staged
        }.getOrNull()
    }

    /**
     * Opens the staged file just far enough to read its manifest, and checks every
     * id in it before anything else is believed.
     */
    private suspend fun readManifest(
        context: Context,
        staged: File,
        passphrase: String?,
        plaintext: Boolean,
    ): Step {
        // Opened under a throwaway id: the real one is what we are here to read,
        // and a staged file is not a library document — the seal unregisters it.
        val stagedId = STAGED_ID
        val meta = runCatching {
            val factory = passphrase?.let(SoilCrypto::roomFactory) ?: SoilCrypto.plaintextFactory()
            val soil = SoilDatabase.open(context, staged, stagedId, factory)
            try {
                soil.readMeta()
            } finally {
                runCatching { soil.seal() }
            }
        }.getOrNull()

        val checked = ImportPlan.check(meta)
        if (!checked.isOk) {
            discard(staged)
            return Step.Refused(checked.verdict, checked.badId)
        }
        val id = checked.meta!!.notebookId
        val index = IndexGate.awaitReady()
        return Step.Ready(
            staged = staged,
            meta = checked.meta,
            collision = ImportPlan.collisionOf(index.byId(id), OpenDocuments.isOpen(id)),
            passphrase = passphrase,
            plaintext = plaintext,
        )
    }

    /**
     * Which key the library will reach for next time.
     *
     * Whatever opened the file, opens the file — this phase converts nothing.
     * A file that took the device's own passphrase is `GLOBAL` and opens
     * silently; one that took a different passphrase is `NOTEBOOK`, which means
     * "ask every time", and that is the truth about it. Changing which key a
     * document uses is a `sqlcipher_export` round-trip and belongs to Phase 24's
     * one shared helper, not to a second copy of it here.
     */
    private fun scopeOf(context: Context, ready: Step.Ready): KeyScope {
        val global = PassphraseVault(CryptoStores.secrets(context)).globalOrNull()
        return if (ready.passphrase != null && ready.passphrase == global) KeyScope.GLOBAL else KeyScope.NOTEBOOK
    }

    /**
     * Page count, cover and the embedded record, from the document as installed.
     *
     * Best effort by design: an import that landed is an import that succeeded,
     * and a card missing its page count until the first open is a cosmetic loss
     * next to refusing a file that is already safely on disk.
     */
    private suspend fun refreshAfterInstall(
        context: Context,
        documentId: String,
        ready: Step.Ready,
        name: String,
        sourceId: String,
    ) {
        runCatching {
            val soil = SketchbookStore.open(
                context,
                documentId,
                keyScope = if (ready.passphrase == null) KeyScope.GLOBAL else KeyScope.NOTEBOOK,
                passphrase = ready.passphrase,
            )
            try {
                // Kept as a second copy means it is a *different* document, and
                // its root row still claims the id it was exported under. One
                // helper does this, shared with duplicate, for the same reason
                // there is one id remapper.
                if (documentId != sourceId) Sketchbooks.reidentify(soil, sourceId, documentId)

                val repo = Sketchbooks.repositoryFor(soil, documentId)
                IndexGate.awaitReady().setPageCount(documentId, repo.pageCount())
                // The record now says what this device calls it and where it
                // sits, so a re-export from here is already true.
                val refresh = MetaUpkeep.from(documentId)
                soil.readMeta()?.let {
                    soil.writeMeta(refresh(it).copy(notebookId = documentId, name = name))
                }
            } finally {
                soil.seal()
            }
        }
    }
}
