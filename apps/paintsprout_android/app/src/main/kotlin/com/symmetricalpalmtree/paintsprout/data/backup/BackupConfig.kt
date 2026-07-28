package com.symmetricalpalmtree.paintsprout.data.backup

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Where this device backs up to, and whether it does.
 *
 * One singleton row's worth of settings. It is decoded leniently in both
 * directions — `ignoreUnknownKeys`, and defaults on every optional field — for
 * the same reason the document's identity record is: a build that adds a field
 * must not make the row undecodable by the build beside it, and a row written
 * before a field existed must still read.
 *
 * Nothing secret lives here. [driveAccountEmail] is a display label, and the
 * refresh token that actually opens Drive is in [DriveTokenStore], behind the
 * keystore, treated like a passphrase.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BackupConfig(
    /** Stable per-install id. Minted once and never shown. */
    val deviceId: String,

    /** The Drive subfolder this device owns. User-editable; see [DeviceIdentity]. */
    val deviceFolderName: String,

    /** The persisted SAF tree the LOCAL slot writes into. */
    val localTreeUri: String? = null,

    /**
     * Written even when false. A boolean that vanishes from the JSON when it is
     * at its default is a boolean whose absence a reader has to guess about.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val localEnabled: Boolean = false,

    /**
     * Unused. The DRIVE slot briefly went through the SAF picker before it turned
     * out Drive does not register a provider there on these devices; kept so a row
     * written then still decodes.
     */
    val driveTreeUri: String? = null,

    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val driveEnabled: Boolean = false,

    /** Display only, and non-secret. Null means "not connected". */
    val driveAccountEmail: String? = null,

    /** Device-local epoch ms of the last run that actually landed something. */
    val lastRunAt: Long? = null,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): BackupConfig = codec.decodeFromString(serializer(), json)

        fun newDefault(deviceFolderName: String): BackupConfig = BackupConfig(
            deviceId = UUID.randomUUID().toString(),
            deviceFolderName = deviceFolderName,
        )
    }
}
