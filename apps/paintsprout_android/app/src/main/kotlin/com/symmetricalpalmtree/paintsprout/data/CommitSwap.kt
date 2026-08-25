package com.symmetricalpalmtree.paintsprout.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Replaces a database file with a verified replacement, without ever holding zero
 * copies of the user's data.
 *
 * The naive commit — delete the original, rename the new one in — has a window in
 * which no copy exists under any name this app knows how to find. A process kill
 * in that window is unrecoverable, and on a battery-powered tablet that window
 * gets hit for real. So:
 *
 * ```
 * fsync(temp)
 *   → drop a stale aside from a previous completed swap
 *   → delete the original's sidecars
 *   → rename original → aside        ← the data now lives under the aside name
 *   → rename temp     → original     ← the data now lives under the real name
 *         on failure: rename the aside back, leave the verified temp on disk
 *         if THAT fails too: delete nothing, error out naming both files
 *   → fsync the parent directory     ← the renames themselves must be durable
 *   → delete the aside
 * ```
 *
 * At every instant at least one intact copy exists under a name [SwapRecovery]
 * knows to look for — which is what makes launch-time repair possible at all.
 *
 * **Precondition:** the temp must already be verified (it opens with the key it
 * was written under) and the original must be sealed — checkpointed, with no live
 * connection. Deleting the original's `-wal` is only safe because of that; a WAL
 * holding uncheckpointed commits is data.
 */
object CommitSwap {

    /** Where a swap can be cut short. Test-only; production always passes [NONE]. */
    internal enum class SwapPoint { NONE, AFTER_SIDECAR_DELETE, AFTER_ASIDE_RENAME, AFTER_TEMP_RENAME }

    /** Thrown by an injected interrupt; never escapes production code. */
    internal class SwapInterrupted(point: SwapPoint) : IOException("Swap interrupted at $point")

    fun commit(real: File, verifiedTemp: File) = commit(real, verifiedTemp, SwapPoint.NONE)

    internal fun commit(real: File, verifiedTemp: File, interruptAt: SwapPoint) {
        requireExistingDatabase(verifiedTemp)

        val aside = SoilFiles.asideOf(real)
        fsync(verifiedTemp)

        // A leftover aside means an earlier swap completed but didn't get to tidy
        // up. It is stale by definition — the real file is right there.
        aside.delete()
        SoilFiles.sidecars(real).forEach { it.delete() }
        if (interruptAt == SwapPoint.AFTER_SIDECAR_DELETE) throw SwapInterrupted(interruptAt)

        // First install of a document has nothing to move aside.
        val hadOriginal = real.exists()
        if (hadOriginal && !real.renameTo(aside)) {
            throw IOException("Could not move ${real.name} aside; nothing was changed")
        }
        if (interruptAt == SwapPoint.AFTER_ASIDE_RENAME) throw SwapInterrupted(interruptAt)

        if (!verifiedTemp.renameTo(real)) {
            // Roll back: the original goes home and the verified temp stays on disk
            // so a human — or the next launch — still has both.
            if (hadOriginal && !aside.renameTo(real)) {
                throw IOException(
                    "Swap failed AND rollback failed. Nothing was deleted. " +
                        "The data is at ${aside.name}; the replacement is at ${verifiedTemp.name}",
                )
            }
            throw IOException("Could not install ${verifiedTemp.name}; the original is intact")
        }
        if (interruptAt == SwapPoint.AFTER_TEMP_RENAME) throw SwapInterrupted(interruptAt)

        fsyncDir(real.parentFile)
        aside.delete()
    }

    private fun fsync(file: File) {
        // Append mode: opening must not truncate the very file being made durable.
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    /**
     * Best effort, deliberately.
     *
     * A rename is only durable once the *directory* entry is flushed, and on
     * Android/Linux opening a directory read-only and forcing it does that. Some
     * JVMs (macOS, where these tests run) refuse to open a directory as a channel
     * at all. There is no portable alternative, so a failure here is swallowed:
     * the swap is still correct, just not proven durable against a power cut.
     */
    private fun fsyncDir(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (t: Throwable) {
            // Nothing useful to do; see above.
        }
    }
}
