package com.symmetricalpalmtree.paintsprout.data.backup

/**
 * The two destinations, and there are only ever two.
 *
 * Not an arbitrary list: each is a slot with its own mechanism (a SAF tree, the
 * Drive REST API), its own enable switch, and its own per-sketchbook timestamp
 * column. A run writes to every enabled one, and a failure at either is that
 * slot's failure alone.
 */
enum class BackupKind { LOCAL, DRIVE }
