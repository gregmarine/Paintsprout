package com.symmetricalpalmtree.paintsprout.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings row is JSON in a database, which means every build that ever wrote
 * one is a build whose output this has to read. Both directions are tested:
 * today's config survives a round trip, and yesterday's still decodes.
 */
class BackupConfigTest {

    @Test
    fun `a fully populated config survives a round trip`() {
        val config = BackupConfig(
            deviceId = "test-device-id",
            deviceFolderName = "Movink-11-abcd1234",
            localTreeUri = "content://local/tree/uri",
            localEnabled = true,
            driveTreeUri = null,
            driveEnabled = false,
            driveAccountEmail = "painter@example.com",
            lastRunAt = 1_700_000_000_000L,
        )
        assertEquals(config, BackupConfig.fromJson(config.toJson()))
    }

    @Test
    fun `a null Drive account survives a round trip as null`() {
        val config = BackupConfig(
            deviceId = "test-device-id",
            deviceFolderName = "Movink-11-abcd1234",
            driveAccountEmail = null,
        )
        val decoded = BackupConfig.fromJson(config.toJson())
        assertNull(decoded.driveAccountEmail)
        assertEquals(config, decoded)
    }

    @Test
    fun `a row with only the required fields decodes to the off state`() {
        val config = BackupConfig.fromJson("""{"deviceId":"abc","deviceFolderName":"MyDevice"}""")
        assertFalse(config.localEnabled)
        assertFalse(config.driveEnabled)
        assertNull(config.localTreeUri)
        assertNull(config.driveTreeUri)
        assertNull(config.driveAccountEmail)
        assertNull(config.lastRunAt)
    }

    /** A field a newer build adds must not make the row undecodable by an older one. */
    @Test
    fun `an unknown field is ignored rather than fatal`() {
        val config = BackupConfig.fromJson(
            """{"deviceId":"abc","deviceFolderName":"MyDevice","somethingNewer":42}""",
        )
        assertEquals("abc", config.deviceId)
    }

    @Test
    fun `the enabled flags are written even when false`() {
        val json = BackupConfig.newDefault("Device").toJson()
        assertTrue(json.contains("\"localEnabled\":false"))
        assertTrue(json.contains("\"driveEnabled\":false"))
    }

    @Test
    fun `a fresh default is off at both destinations`() {
        val config = BackupConfig.newDefault("TestDevice-abc123")
        assertTrue(config.deviceId.isNotBlank())
        assertEquals("TestDevice-abc123", config.deviceFolderName)
        assertFalse(config.localEnabled)
        assertFalse(config.driveEnabled)
        assertNull(config.driveAccountEmail)
    }

    /** Two devices sharing one Drive root must not share an id. */
    @Test
    fun `each fresh default mints its own device id`() {
        assertNotEquals(
            BackupConfig.newDefault("Device").deviceId,
            BackupConfig.newDefault("Device").deviceId,
        )
    }
}
