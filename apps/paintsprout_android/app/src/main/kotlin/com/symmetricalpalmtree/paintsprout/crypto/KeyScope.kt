package com.symmetricalpalmtree.paintsprout.crypto

/**
 * Which secret opens a file, and therefore how it behaves.
 *
 * The distinction is not about strength — both scopes use the same cipher and the
 * same KDF. It is about *whose* passphrase it is, and that single fact decides
 * three things: whether the user is prompted, whether the derived key may rest on
 * disk, and whether a cover thumbnail may be cached in the index.
 */
enum class KeyScope {
    /**
     * The device's global passphrase. Prompted for once per device, then cached;
     * the derived raw key is persisted in the keystore-backed store so cold
     * launches skip the KDF.
     *
     * Scope is a property of the (file, device) pair, not of the file. A
     * global-scope sketchbook carried to another device is prompted for there,
     * once, and then cached — same passphrase, different cache.
     */
    GLOBAL,

    /**
     * The sketchbook's own passphrase, chosen by the user. Prompted on **every**
     * open; the raw key lives in RAM and is dropped on close. Never persisted,
     * anywhere — that is the entire difference between the two scopes.
     *
     * Written verbatim into the portable `sketchbook_meta` record and into the
     * index's `keyScope` column, so the name is part of the format rather than an
     * implementation detail. It means "the sketchbook's own key".
     */
    SKETCHBOOK;

    companion object {
        /** Lenient parse — an unknown or absent value is treated as global. */
        fun parse(value: String?): KeyScope =
            entries.firstOrNull { it.name == value } ?: GLOBAL
    }
}
