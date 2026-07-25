package com.symmetricalpalmtree.paintsprout

import android.app.Application

/**
 * Application entry point. Intentionally minimal for now — a hook for future
 * process-wide setup (pigment tables, brush resources, crash reporting).
 */
class PaintsproutApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // SQLCipher 4.x ships its native library unloaded; every keyed open in the
        // app depends on this having happened first, so it happens here rather than
        // in whichever screen opens a database earliest.
        System.loadLibrary("sqlcipher")
    }
}
