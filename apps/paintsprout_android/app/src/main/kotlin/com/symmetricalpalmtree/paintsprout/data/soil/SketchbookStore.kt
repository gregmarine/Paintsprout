package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.paintsprout.BuildConfig
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.crypto.PassphraseVault
import com.symmetricalpalmtree.paintsprout.crypto.RawKeyCache
import com.symmetricalpalmtree.paintsprout.crypto.SoilCrypto
import com.symmetricalpalmtree.paintsprout.data.SchemaSql
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Getting a keyed connection to a sketchbook, and nothing else.
 *
 * The two key scopes differ here and only here. A `GLOBAL` document opens with
 * the device's own passphrase and its derived key is cached on disk, so opening
 * is fast from the second time onwards. A `NOTEBOOK` document is prompted for
 * every single time and its derived key lives in RAM until the document closes —
 * the user chose a separate passphrase precisely so that content is not reachable
 * with the global key, and a persisted derived key would quietly undo that.
 *
 * Creating the *file* is what happens here. Creating a sketchbook as the user
 * means it — file plus index row plus a first page — composes this with the index
 * repository, and lands with the library in Phase 14.
 */
object SketchbookStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Creates a new sketchbook file, encrypted from its first byte.
     *
     * [passphrase] is required for [KeyScope.NOTEBOOK] and ignored for
     * [KeyScope.GLOBAL], which mints or reuses the device key.
     */
    fun create(
        context: Context,
        name: String,
        keyScope: KeyScope = KeyScope.GLOBAL,
        passphrase: String? = null,
        documentId: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
    ): SoilDatabase {
        val file = SoilFiles.soilFile(context, documentId)
        val secret = secretFor(context, keyScope, passphrase)

        val soil = SoilDatabase.create(
            context = context,
            file = file,
            documentId = documentId,
            // The passphrase factory, not a raw key: the salt lives in a file that
            // does not exist yet, so there is nothing to derive from until after
            // this call. The derivation happens behind the user, below.
            factory = SoilCrypto.roomFactory(secret),
            meta = NotebookMeta(
                notebookId = documentId,
                name = name,
                createdAt = now,
                updatedAt = now,
                encrypted = true,
                keyScope = keyScope.name,
                appVersionCode = BuildConfig.VERSION_CODE,
            ),
        )
        deriveInBackground(context, documentId, file, secret, keyScope)
        return soil
    }

    /**
     * Opens an existing sketchbook.
     *
     * Tries the cached derived key first and **verifies it before trusting it** —
     * a key that no longer opens the file (the document was re-keyed elsewhere,
     * the library was restored) is indistinguishable from corruption once SQLite
     * sees it.
     */
    fun open(
        context: Context,
        documentId: String,
        keyScope: KeyScope = KeyScope.GLOBAL,
        passphrase: String? = null,
    ): SoilDatabase {
        val file = SoilFiles.soilFile(context, documentId)
        val secret = secretFor(context, keyScope, passphrase)
        val keys = RawKeyCache(CryptoStores.derivedKeys(context))

        val cached = if (keyScope == KeyScope.GLOBAL) keys.peekOrLoad(documentId) else keys.peek(documentId)
        val factory: SupportSQLiteOpenHelper.Factory =
            if (cached != null && SoilCrypto.verifyRawKey(file, cached)) {
                SoilCrypto.roomFactoryRawKey(cached)
            } else {
                if (cached != null) keys.invalidate(documentId)
                SoilCrypto.roomFactory(secret).also {
                    deriveInBackground(context, documentId, file, secret, keyScope)
                }
            }
        return SoilDatabase.open(context, file, documentId, factory)
    }

    /**
     * Deletes a sketchbook file and its sidecars.
     *
     * Refuses while the document is open: unlinking a file out from under a live
     * connection leaves the connection writing into nothing. The index row is the
     * caller's to retire.
     */
    fun delete(context: Context, documentId: String): Boolean {
        if (OpenDocuments.isOpen(documentId)) {
            throw IOException("Refusing to delete a sketchbook that is open: $documentId")
        }
        val file = SoilFiles.soilFile(context, documentId)
        RawKeyCache(CryptoStores.derivedKeys(context)).invalidate(documentId)
        SoilFiles.sidecars(file).forEach { it.delete() }
        return file.delete()
    }

    /** Every document file in the garden, whatever the index believes. */
    fun listFiles(context: Context): List<File> =
        SoilFiles.listDocuments(SoilFiles.storageRoot(context))

    /** Whether this file carries a Paintsprout sketchbook at all. */
    fun carriesSketchbook(soil: SoilDatabase): Boolean =
        SchemaSql.SKETCHBOOK_TABLE in soil.tables()

    private fun secretFor(context: Context, keyScope: KeyScope, passphrase: String?): String =
        when (keyScope) {
            KeyScope.GLOBAL -> PassphraseVault(CryptoStores.secrets(context)).ensureGlobal()
            KeyScope.NOTEBOOK -> passphrase
                ?: throw IllegalArgumentException("A private sketchbook needs its own passphrase")
        }

    /**
     * The KDF costs most of a second, and this open has already paid for a
     * passphrase connection. Deriving behind the user makes the *next* open fast
     * without making this one slower.
     */
    private fun deriveInBackground(
        context: Context,
        documentId: String,
        file: File,
        secret: String,
        keyScope: KeyScope,
    ) {
        scope.launch {
            runCatching {
                val keys = RawKeyCache(CryptoStores.derivedKeys(context))
                when (keyScope) {
                    KeyScope.GLOBAL -> keys.global(documentId, file, secret)
                    KeyScope.NOTEBOOK -> keys.ephemeral(documentId, file, secret)
                }
            }
        }
    }
}
