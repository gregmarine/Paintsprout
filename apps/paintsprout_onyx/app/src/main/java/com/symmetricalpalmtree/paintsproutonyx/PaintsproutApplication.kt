package com.symmetricalpalmtree.paintsproutonyx

import android.app.Application
import android.util.Log

/**
 * Process start. Today it does exactly one thing, and it has to happen here.
 *
 * SQLCipher's native library must be loaded before anything opens a database, and every
 * database this app has is encrypted — including the index, which the launcher opens before
 * any other screen exists. There is no ordering in which a screen could load it in time for
 * itself, so it is loaded once, at the top, where nothing can get in front of it.
 *
 * The failure this avoids is not a subtle one: without it the first open dies inside the
 * native layer with a message about a missing symbol, on a device, with no hint that the
 * library is fine and only the loading order is wrong.
 *
 * **G3 adds `OnyxEngine.register(this)` here**, and that call belongs in this method too and
 * for a related reason — besides registering the engine it installs the hidden-API bypass
 * the BOOX SDK needs and heals EPD state left behind by a process killed mid-pen-stroke.
 * That state is keyed by name rather than by process, so a stroke interrupted by a crash
 * ghosts the whole panel until the tablet is rebooted. It is not here yet because there is
 * nothing to draw on until G3, and an engine registered for a view that does not exist is a
 * device SDK holding the panel open for no reason.
 */
class PaintsproutApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            // Nothing useful can be done about this at runtime — every screen past the
            // launcher needs an encrypted database open. Log it plainly so the crash that
            // follows has an explanation sitting above it rather than looking like a
            // corrupt library file, which is the conclusion that leads someone to delete
            // one.
            Log.e(TAG, "SQLCipher native library failed to load", e)
        }
    }

    companion object {
        private const val TAG = "PaintsproutApplication"
    }
}
