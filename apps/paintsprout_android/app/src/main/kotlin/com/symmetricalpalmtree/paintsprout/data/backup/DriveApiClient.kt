package com.symmetricalpalmtree.paintsprout.data.backup

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "DriveApiClient"

@Serializable private data class DriveFile(val id: String, val name: String? = null)

@Serializable private data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable private data class CreateFolderBody(
    val name: String,
    val mimeType: String,
    val parents: List<String>,
)

@Serializable private data class UploadMeta(
    val name: String? = null,
    val parents: List<String>? = null,
)

@Serializable private data class DriveUser(val emailAddress: String? = null)

@Serializable private data class DriveAbout(val user: DriveUser? = null)

/** One child of a Drive folder: a stable id and a display name. */
data class DriveEntry(val id: String, val name: String)

private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val FILES = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
private const val ABOUT = "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)"

/** The app-created folder every device's backups sit under. */
const val ROOT_BACKUP_FOLDER = "Paintsprout Backups"

private val codec = Json { ignoreUnknownKeys = true }

/**
 * Drive REST v3, hand-rolled over `HttpURLConnection`.
 *
 * No Google API client library and no Play Services: the target devices do not
 * reliably have the latter, and the four calls backup actually needs — find,
 * list, upload, download — are a page of code each.
 *
 * Almost everything here answers null or false on failure, because the engine's
 * job is to report a destination that did not work and carry on with the one that
 * did. The exception is [listChildren], and it is deliberate.
 */
class DriveApiClient(private val accessToken: String) {

    /** The connected account, for the settings screen. Null on any failure. */
    fun accountEmail(): String? = try {
        val conn = open("GET", ABOUT)
        val body = readBody(conn)
        if (conn.responseCode == 200) {
            codec.decodeFromString(DriveAbout.serializer(), body).user?.emailAddress
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "accountEmail failed: ${e.message}")
        null
    }

    /** The first child of [parentId] named [name], or null. */
    fun findChild(name: String, parentId: String, foldersOnly: Boolean): String? = try {
        val q = buildChildQuery(name, parentId, foldersOnly)
        val url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&spaces=drive&fields=files(id,name)&pageSize=10"
        val conn = open("GET", url)
        val body = readBody(conn)
        if (conn.responseCode == 200) {
            codec.decodeFromString(DriveFileList.serializer(), body).files.firstOrNull()?.id
        } else {
            Log.e(TAG, "findChild HTTP ${conn.responseCode}")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "findChild failed: ${e.message}")
        null
    }

    fun ensureFolder(name: String, parentId: String): String? =
        findChild(name, parentId, foldersOnly = true) ?: createFolder(name, parentId)

    /**
     * Every non-trashed child of [parentId], paged to the end.
     *
     * A failure **before anything was read** is an empty list — "nothing to
     * restore" — but a failure *mid-pagination* throws. Returning the partial list
     * would silently shorten the set, and restore commits what it is given as the
     * entire library: the sketchbooks on the missing page would simply be gone.
     */
    fun listChildren(parentId: String, foldersOnly: Boolean): List<DriveEntry> {
        val out = mutableListOf<DriveEntry>()
        try {
            var pageToken: String? = null
            var q = "'$parentId' in parents and trashed = false"
            if (foldersOnly) q += " and mimeType = '$FOLDER_MIME'"
            do {
                var url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&spaces=drive" +
                    "&fields=nextPageToken,files(id,name)&pageSize=1000"
                if (pageToken != null) url += "&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
                val conn = open("GET", url)
                val body = readBody(conn)
                if (conn.responseCode != 200) {
                    Log.e(TAG, "listChildren HTTP ${conn.responseCode}")
                    if (out.isEmpty()) return emptyList()
                    throw IOException("Google Drive listing failed partway (HTTP ${conn.responseCode}).")
                }
                val page = codec.decodeFromString(DriveFileList.serializer(), body)
                page.files.forEach { f -> f.name?.let { out.add(DriveEntry(f.id, it)) } }
                pageToken = page.nextPageToken
            } while (pageToken != null)
            return out
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "listChildren failed: ${e.message}")
            if (out.isEmpty()) return emptyList()
            throw IOException("Google Drive listing failed partway: ${e.message}")
        }
    }

    /** True on success, and on already-gone. */
    fun delete(fileId: String): Boolean = try {
        val conn = open("DELETE", "$FILES/$fileId")
        conn.responseCode == 204 || conn.responseCode == 404
    } catch (e: Exception) {
        Log.e(TAG, "delete failed: ${e.message}")
        false
    }

    /**
     * Downloads [fileId] to [dest] through a `.part` sibling.
     *
     * A dropped connection must not leave a truncated file under the real name —
     * staging would then hand a half-sketchbook to the commit.
     */
    fun downloadTo(fileId: String, dest: File): Boolean {
        val part = File("${dest.absolutePath}.part")
        return try {
            val conn = open("GET", "$FILES/$fileId?alt=media")
            if (conn.responseCode == 200) {
                conn.inputStream.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
                if (part.renameTo(dest)) {
                    true
                } else {
                    part.delete()
                    false
                }
            } else {
                Log.e(TAG, "downloadTo HTTP ${conn.responseCode}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadTo failed: ${e.message}")
            part.delete()
            false
        }
    }

    /**
     * Writes [source] into [parentId] as [name], replacing what is there.
     *
     * Drive happily keeps several files with the same name in one folder, so
     * "upload" alone would grow a new copy of every sketchbook on every run. The
     * existing file is found first and **PATCHed** — same file id, same revision
     * history — and only created when it genuinely is not there.
     *
     * Resumable upload throughout (initiate → session URI → streaming PUT), with
     * `setFixedLengthStreamingMode` so a large `.soil` never sits in memory.
     */
    fun uploadOrReplace(name: String, parentId: String, source: File): Boolean {
        return try {
            val existing = findChild(name, parentId, foldersOnly = false)

            val initUrl = if (existing == null) {
                "$UPLOAD?uploadType=resumable&fields=id"
            } else {
                "$UPLOAD/$existing?uploadType=resumable&fields=id"
            }
            val initMethod = if (existing == null) "POST" else "PATCH"
            val metaJson = if (existing == null) {
                codec.encodeToString(
                    UploadMeta.serializer(),
                    UploadMeta(name = name, parents = listOf(parentId)),
                )
            } else {
                codec.encodeToString(UploadMeta.serializer(), UploadMeta())
            }

            val initConn = open(initMethod, initUrl)
            initConn.doOutput = true
            initConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            initConn.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            initConn.setRequestProperty("X-Upload-Content-Length", source.length().toString())
            initConn.outputStream.use { it.write(metaJson.toByteArray(Charsets.UTF_8)) }

            if (initConn.responseCode != 200) {
                Log.e(TAG, "uploadOrReplace initiate HTTP ${initConn.responseCode}")
                return false
            }
            val sessionUri = initConn.getHeaderField("Location")
            if (sessionUri.isNullOrBlank()) {
                Log.e(TAG, "uploadOrReplace: no Location header")
                return false
            }

            val uploadConn = URL(sessionUri).openConnection() as HttpURLConnection
            uploadConn.requestMethod = "PUT"
            uploadConn.setFixedLengthStreamingMode(source.length())
            uploadConn.setRequestProperty("Content-Type", "application/octet-stream")
            uploadConn.connectTimeout = 30_000
            uploadConn.readTimeout = 30_000
            uploadConn.doOutput = true
            uploadConn.outputStream.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
            val code = uploadConn.responseCode
            if (code == 200 || code == 201) {
                true
            } else {
                Log.e(TAG, "uploadOrReplace upload HTTP $code")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadOrReplace failed: ${e.message}")
            false
        }
    }

    private fun createFolder(name: String, parentId: String): String? = try {
        val body = codec.encodeToString(
            CreateFolderBody.serializer(),
            CreateFolderBody(name, FOLDER_MIME, listOf(parentId)),
        )
        val conn = open("POST", "$FILES?fields=id")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val resp = readBody(conn)
        if (conn.responseCode == 200) {
            codec.decodeFromString(DriveFile.serializer(), resp).id
        } else {
            Log.e(TAG, "createFolder HTTP ${conn.responseCode}")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "createFolder failed: ${e.message}")
        null
    }

    // --- Query building -------------------------------------------------------

    /**
     * Drive's query language is a string with single-quoted literals, so a folder
     * or file name containing one has to be escaped or the query means something
     * else entirely. Internal rather than private so a test can prove it.
     */
    internal fun buildChildQuery(name: String, parentId: String, foldersOnly: Boolean): String {
        val escaped = escapeDriveString(name)
        var q = "name = '$escaped' and '$parentId' in parents and trashed = false"
        if (foldersOnly) q += " and mimeType = '$FOLDER_MIME'"
        return q
    }

    /** Backslash first, or it would escape the escapes added after it. */
    internal fun escapeDriveString(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'")

    private fun open(method: String, urlString: String): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.doInput = true
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String = try {
        conn.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        conn.errorStream?.bufferedReader()?.readText() ?: ""
    }
}
