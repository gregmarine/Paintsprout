package com.symmetricalpalmtree.paintsproutonyx

import android.app.Application
import android.util.Log
import com.symmetricalpalmtree.gpaper.onyx.OnyxEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process start. Two things happen here, and both have to happen here rather than anywhere
 * a screen could put them.
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
 * **`OnyxEngine.register(this)` joined it in G3**, and it belongs in this method for a
 * related reason. Besides registering the engine it installs the hidden-API bypass the BOOX
 * SDK needs on Android 14 and up, and it heals EPD state left behind by a process killed
 * mid-pen-stroke. That state is keyed by *name* rather than by process, so a stroke
 * interrupted by a crash ghosts the whole panel — every app on the device, until it is
 * rebooted — and the only thing that can undo it is the next start of a process with the
 * same name. It has to be given the `Application` rather than any other Context: it outlives
 * every screen, and an Activity handed to it would be leaked for the life of the process.
 */
class PaintsproutApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        OnyxEngine.register(this)
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

        /**
         * For work that has to finish after the screen that asked for it is gone.
         *
         * There is exactly one such job in arc 1 and it is the important one: closing a sketchbook
         * drains its write queue first, and the marks in that queue are the last ones the artist
         * drew. An Activity's own scope is cancelled the moment `onDestroy` returns, so a close
         * running on it would abandon precisely the strokes the artist most expects to find when
         * they open the page again.
         *
         * A [SupervisorJob] so one failed close cannot take the scope down with it, and IO because
         * everything on it is a database.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
