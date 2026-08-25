package com.symmetricalpalmtree.paintsprout.data

import java.io.File
import java.io.IOException

/**
 * Repairs a swap that was cut short, before anything probes or opens a database.
 *
 * [CommitSwap] guarantees at least one intact copy exists at every instant, under
 * one of three names. This is the other half of that guarantee: the pass that
 * reads the on-disk state and finishes what was started.
 *
 * | On disk | What happened | What we do |
 * |---|---|---|
 * | Real file present | Swap completed, or never started | Drop the stale aside |
 * | Real file missing, aside present | Killed between the two renames | Rename the aside back |
 * | Real file missing, only a temp | An older delete-then-rename window | Rename the temp in |
 * | Real file missing, only an install | An import never committed | Drop it — it was never verified |
 *
 * **This runs before any probe.** A probe of a missing file returns
 * [DbState.INVALID], and INVALID means "fresh install" — which for the global
 * index means silently replacing the user's entire library with an empty one.
 * The order is not a nicety.
 *
 * An empty real file counts as missing. A create-capable open can fabricate a
 * zero-byte stub at the real name, and that stub must not be allowed to outrank
 * an aside holding the actual data.
 */
object SwapRecovery {

    sealed interface Action {
        val file: File
    }

    /** The interrupted swap's original was renamed home. */
    data class AsideRestored(override val file: File) : Action

    /** A verified replacement was renamed in. */
    data class TempInstalled(override val file: File) : Action

    data class StaleAsideDropped(override val file: File) : Action
    data class StaleTempDropped(override val file: File) : Action
    data class StaleInstallDropped(override val file: File) : Action

    /** One file could not be repaired. The pass continued. */
    data class RepairFailed(override val file: File, val cause: Throwable) : Action

    /**
     * Repairs the whole install: the index first — it is the file whose loss reads
     * as a fresh install — then every document in the garden.
     */
    fun repairAll(root: File): List<Action> = repairAll(root, ::repair)

    /** Injectable seam so the guard-per-file behaviour can be tested directly. */
    internal fun repairAll(root: File, repairOne: (File) -> List<Action>): List<Action> {
        val bases = buildList {
            add(SoilFiles.indexFile(root))
            addAll(documentBases(root))
        }
        // Guard per file, not per pass. One malformed entry must not mean the rest
        // of the library goes unrepaired on this launch and every launch after it.
        return bases.flatMap { base ->
            try {
                repairOne(base)
            } catch (t: Throwable) {
                listOf(RepairFailed(base, t))
            }
        }
    }

    /**
     * Every document path the garden implies — derived from real files, asides,
     * temps and installs alike, since the whole point is that the real file may be
     * the one that's missing.
     */
    internal fun documentBases(root: File): List<File> {
        val garden = SoilFiles.garden(root)
        val suffixes = listOf(
            SoilFiles.ASIDE_SUFFIX, SoilFiles.TEMP_SUFFIX, SoilFiles.INSTALL_SUFFIX,
        )
        return (garden.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                val stripped = suffixes.firstOrNull { f.name.endsWith(it) }
                    ?.let { f.name.dropLast(it.length) }
                    ?: f.name
                val base = File(garden, stripped)
                if (SoilFiles.documentIdOf(base) != null) base else null
            }
            .distinctBy { it.path }
            .sortedBy { it.path }
    }

    fun repair(base: File): List<Action> {
        val aside = SoilFiles.asideOf(base)
        val temp = SoilFiles.tempOf(base)
        val install = SoilFiles.installOf(base)
        val actions = mutableListOf<Action>()

        if (existsAsDatabase(base)) {
            // The swap finished, or never began. Everything else is debris.
            if (aside.exists() && aside.delete()) actions += StaleAsideDropped(aside)
            if (temp.exists() && temp.delete()) actions += StaleTempDropped(temp)
            if (install.exists() && install.delete()) actions += StaleInstallDropped(install)
            return actions
        }

        when {
            existsAsDatabase(aside) -> {
                // Killed between the two renames: the original never left, it just
                // isn't wearing its own name. The replacement did not commit, so
                // the original wins and the temp is debris.
                moveIn(aside, base)
                actions += AsideRestored(base)
                if (temp.exists() && temp.delete()) actions += StaleTempDropped(temp)
                if (install.exists() && install.delete()) actions += StaleInstallDropped(install)
            }

            existsAsDatabase(temp) -> {
                // A temp only survives the verification gate, so installing it is
                // safe — this is the delete-then-rename window an older build left.
                moveIn(temp, base)
                actions += TempInstalled(base)
                if (install.exists() && install.delete()) actions += StaleInstallDropped(install)
            }

            install.exists() -> {
                // An incoming copy that never reached its rename. Nothing verified
                // it and nothing references it; the real file was never touched.
                if (install.delete()) actions += StaleInstallDropped(install)
            }
        }
        return actions
    }

    /**
     * Renames [from] onto [to], carrying its sidecars with it.
     *
     * Both halves matter. Any sidecar already sitting at the destination is
     * orphaned from a file that is gone, and pairing a restored database with a
     * stranger's `-wal` is corruption — so those go first. And a `-wal` belonging
     * to [from] holds that file's most recent commits, so it travels rather than
     * being dropped.
     */
    private fun moveIn(from: File, to: File) {
        to.delete() // an empty stub, if a create-capable open fabricated one
        SoilFiles.sidecars(to).forEach { it.delete() }
        if (!from.renameTo(to)) {
            throw IOException("Could not restore ${from.name} to ${to.name}")
        }
        SoilFiles.sidecars(from).zip(SoilFiles.sidecars(to)).forEach { (src, dst) ->
            if (src.exists()) src.renameTo(dst)
        }
    }
}
