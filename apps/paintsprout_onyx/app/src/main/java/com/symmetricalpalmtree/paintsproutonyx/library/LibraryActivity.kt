package com.symmetricalpalmtree.paintsproutonyx.library

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.paintsproutonyx.core.IndexGuard
import com.symmetricalpalmtree.paintsproutonyx.core.TopGuard
import com.symmetricalpalmtree.paintsproutonyx.databinding.ActivityLibraryBinding

/**
 * The shelf — as a **shell**. G2 builds the real one: breadcrumb folders, a paginated card
 * grid, create, rename, move, delete, sort, the long-press action sheet.
 *
 * What it does today is the one thing G1 owes it. The crypto phase's whole claim is that a
 * cold launch resolves the key and lands somewhere real, and "somewhere real" cannot be a
 * screen that does not exist. So this is where the boot path ends, it proves the index is
 * open and readable by the screen that will read it, and it carries the debug overflow — the
 * only route back to the Unlock screen without wiping app data, which would take the
 * sketchbooks with it and leave nothing to unlock.
 *
 * It deliberately reads nothing from the index yet. There is nothing to list until G2 can
 * create one, and an empty listing implemented now would be an empty listing rewritten then.
 */
class LibraryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        val binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // Debug build only. The release twin of DebugMenu hides the control instead of
        // filling it, so a shipped build has no path to the recovery key at all.
        DebugMenu.install(this, binding.overflowButton)
    }
}
