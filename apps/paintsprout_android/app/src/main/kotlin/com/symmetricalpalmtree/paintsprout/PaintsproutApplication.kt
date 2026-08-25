package com.symmetricalpalmtree.paintsprout

import android.app.Application
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Intentionally minimal for now — a hook for future
 * process-wide setup (pigment tables, brush resources, crash reporting).
 */
class PaintsproutApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // SQLCipher 4.x ships its native library unloaded; every keyed open in the
        // app depends on this having happened first, so it happens here rather than
        // in whichever screen opens a database earliest.
        System.loadLibrary("sqlcipher")

        // Kick the index open — do NOT wait for it. Opening can need a key
        // derivation, a repair, or the user; BootstrapActivity drives that, and
        // this head start just means it is often already done by the time the
        // first frame is drawn.
        scope.launch { IndexGate.ensureReady(this@PaintsproutApplication) }
    }
}
