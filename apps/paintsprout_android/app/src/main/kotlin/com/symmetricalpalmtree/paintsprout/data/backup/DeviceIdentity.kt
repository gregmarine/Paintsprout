package com.symmetricalpalmtree.paintsprout.data.backup

import android.os.Build
import java.util.UUID

/**
 * What this device calls itself in a shared backup root.
 *
 * Deliberately **not** the hardware serial: `Build.getSerial()` needs a
 * privileged permission and returns `"UNKNOWN"` on an ordinary sideloaded build,
 * which would give every device the same folder.
 *
 * So: the model, made safe for a path, plus enough randomness that two of the
 * same tablet never collide. The name doubles as a Drive folder name, which is
 * the reason the filter exists at all — a path separator in here writes
 * somewhere else entirely.
 */
object DeviceIdentity {

    fun defaultDeviceFolderName(): String {
        val sanitized = Build.MODEL
            .replace(Regex("[^a-zA-Z0-9_-]+"), "-")
            .trim('-')
        val shortId = UUID.randomUUID().toString().replace("-", "").take(8)
        return "$sanitized-$shortId"
    }

    /**
     * The looser filter the settings screen applies to a hand-typed name.
     *
     * Only the characters that cannot be in a path are replaced, so somebody who
     * types "Greg's studio tablet" keeps the space and the apostrophe the
     * generated default would have flattened. Returns blank when there was
     * nothing left worth keeping, which the caller refuses.
     */
    fun sanitizeTypedName(raw: String): String =
        raw.trim().replace(Regex("[/\\\\:*?\"<>|]+"), "-").trim('-')
}
