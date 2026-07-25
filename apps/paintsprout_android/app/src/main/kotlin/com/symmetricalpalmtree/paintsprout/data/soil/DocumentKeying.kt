package com.symmetricalpalmtree.paintsprout.data.soil

import android.content.Context
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores
import com.symmetricalpalmtree.paintsprout.crypto.KeyScope
import com.symmetricalpalmtree.paintsprout.crypto.PassphraseVault
import com.symmetricalpalmtree.paintsprout.crypto.RawKeyCache
import com.symmetricalpalmtree.paintsprout.crypto.ReKey
import com.symmetricalpalmtree.paintsprout.data.SoilFiles
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * How one sketchbook is locked, and changing it.
 *
 * Three states, and the user picks between them:
 *
 * - **[Keying.DEVICE]** — encrypted with this device's key. Opens without being
 *   asked anything, which is what makes it the default.
 * - **[Keying.PRIVATE]** — its own passphrase, asked for every time it opens.
 *   The point of it is that the device key does *not* open this one, so it is
 *   also the one state where no cover may be cached: the index is encrypted with
 *   the global key, and a thumbnail there would cross exactly the boundary the
 *   user drew.
 * - **[Keying.NONE]** — not encrypted. A real choice with a real use (a file
 *   another program can read) and a real cost, which the UI states plainly.
 *
 * Every conversion is [ReKey]'s single `sqlcipher_export` path, and every one of
 * them refuses while the document is open: the file is renamed under the swap,
 * and a live connection would be writing into the copy about to be moved aside.
 */
object DocumentKeying {

    enum class Keying { DEVICE, PRIVATE, NONE }

    /** What the index believes about a document. */
    suspend fun current(documentId: String): Keying {
        val row = IndexGate.awaitReady().byId(documentId) ?: return Keying.DEVICE
        return when {
            !row.isEncrypted -> Keying.NONE
            row.isPrivateScope -> Keying.PRIVATE
            else -> Keying.DEVICE
        }
    }

    /**
     * Converts [documentId] to [to].
     *
     * [currentPassphrase] is needed only when the document is currently private —
     * the device key is on hand, and a plaintext document needs none. [newPassphrase]
     * likewise only when [to] is [Keying.PRIVATE].
     */
    suspend fun convert(
        context: Context,
        documentId: String,
        to: Keying,
        currentPassphrase: String? = null,
        newPassphrase: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (OpenDocuments.isOpen(documentId)) {
            throw IOException("Refusing to change the key of a sketchbook that is open")
        }
        val file = SoilFiles.soilFile(context, documentId)
        if (!file.exists()) return@withContext false

        val secrets = CryptoStores.secrets(context)
        val device = PassphraseVault(secrets).globalOrNull()
            ?: throw IOException("This device has no key yet")

        val from = when (current(documentId)) {
            Keying.DEVICE -> ReKey.Keying.of(device)
            Keying.NONE -> ReKey.Keying.PLAINTEXT
            Keying.PRIVATE -> ReKey.Keying.of(
                currentPassphrase ?: throw IOException("That sketchbook's passphrase is needed"),
            )
        }
        // Trusted only after it opens the file. The index says how a document is
        // keyed, and the index can be restored from a different backup than the
        // document beside it — so the file gets the last word.
        if (!ReKey.opens(file, from)) return@withContext false

        val target = when (to) {
            Keying.DEVICE -> ReKey.Keying.of(device)
            Keying.NONE -> ReKey.Keying.PLAINTEXT
            Keying.PRIVATE -> ReKey.Keying.of(
                newPassphrase?.takeIf { it.isNotBlank() }
                    ?: throw IOException("A private sketchbook needs a passphrase"),
            )
        }

        ReKey.convert(file, from, target)

        // The derived key cached for this document is of the *old* passphrase.
        // Leaving it means the next open tries a key that no longer works, which
        // is indistinguishable from corruption by the time SQLite sees it.
        RawKeyCache(CryptoStores.derivedKeys(context)).invalidate(documentId)

        val scope = when (to) {
            Keying.DEVICE -> KeyScope.GLOBAL
            Keying.PRIVATE -> KeyScope.NOTEBOOK
            Keying.NONE -> null
        }

        // The document's own record has to agree with what the document now is.
        // Caught by exporting a decrypted book and reading it in `sqlite3`: the
        // manifest still said `"encrypted": true`, which is the one thing a
        // self-describing file must not be wrong about — a reader trusts it
        // precisely so it does not have to open anything.
        runCatching { restamp(context, documentId, file, target, to, scope) }

        // And the library row last, so a crash mid-convert leaves the index
        // describing the state the file was actually left in by the swap.
        IndexGate.awaitReady().setEncryption(documentId, encrypted = to != Keying.NONE, keyScope = scope)
        true
    }

    private fun restamp(
        context: Context,
        documentId: String,
        file: java.io.File,
        keying: ReKey.Keying,
        to: Keying,
        scope: KeyScope?,
    ) {
        val factory = if (keying.isPlaintext) {
            com.symmetricalpalmtree.paintsprout.crypto.SoilCrypto.plaintextFactory()
        } else {
            com.symmetricalpalmtree.paintsprout.crypto.SoilCrypto.roomFactory(keying.passphrase!!)
        }
        val soil = SoilDatabase.open(context, file, documentId, factory)
        try {
            soil.readMeta()?.let { meta ->
                soil.writeMeta(
                    meta.copy(
                        encrypted = to != Keying.NONE,
                        keyScope = scope?.name,
                        // A book that just became private must not carry a picture
                        // of itself out of the boundary the user just drew.
                        cover = if (to == Keying.PRIVATE) null else meta.cover,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        } finally {
            runCatching { soil.seal() }
        }
    }
}
