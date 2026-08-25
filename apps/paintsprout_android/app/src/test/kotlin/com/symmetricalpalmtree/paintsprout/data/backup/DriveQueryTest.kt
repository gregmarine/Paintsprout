package com.symmetricalpalmtree.paintsprout.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drive's query language is a string with single-quoted literals in it, so a
 * folder name containing an apostrophe changes what the query *means* unless it
 * is escaped. A device folder is user-editable and a sketchbook is user-named:
 * both reach this code.
 *
 * No network here — the token is a placeholder and only the string building is
 * exercised.
 */
class DriveQueryTest {

    private val client = DriveApiClient("not-a-real-token")

    @Test
    fun `an ordinary name passes through untouched`() {
        assertEquals("hello", client.escapeDriveString("hello"))
    }

    @Test
    fun `an apostrophe is escaped`() {
        assertEquals("it\\'s", client.escapeDriveString("it's"))
    }

    @Test
    fun `a backslash is escaped`() {
        assertEquals("a\\\\b", client.escapeDriveString("a\\b"))
    }

    /** Backslash first, or it escapes the escapes added after it. */
    @Test
    fun `a backslash before an apostrophe survives both passes`() {
        assertEquals("a\\\\\\'b", client.escapeDriveString("a\\'b"))
    }

    @Test
    fun `a file query names the file and its parent`() {
        assertEquals(
            "name = 'a-uuid.soil' and 'parent123' in parents and trashed = false",
            client.buildChildQuery("a-uuid.soil", "parent123", foldersOnly = false),
        )
    }

    @Test
    fun `a folder query adds the folder mime type`() {
        val q = client.buildChildQuery(ROOT_BACKUP_FOLDER, "root", foldersOnly = true)
        assertTrue(q.contains("mimeType = 'application/vnd.google-apps.folder'"))
        assertTrue(q.contains("'root' in parents"))
    }

    @Test
    fun `a device folder full of apostrophes still builds one query`() {
        val q = client.buildChildQuery("Greg's studio's tablet", "parent456", foldersOnly = false)
        assertTrue(q.contains("name = 'Greg\\'s studio\\'s tablet'"))
    }
}
