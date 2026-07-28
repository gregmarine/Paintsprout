package com.symmetricalpalmtree.paintsprout.data.backup

import android.content.Context
import android.util.Base64
import android.util.Log
import com.symmetricalpalmtree.paintsprout.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 with PKCE, by hand, over a WebView.
 *
 * No Google Play Services anywhere in it — the target devices ship without a
 * working GMS, so a sign-in flow that depends on it is a backup feature that does
 * not exist on the hardware it is for. The redirect goes to a localhost URL that
 * nothing listens on; `DriveAuthActivity` intercepts it in the WebView and reads
 * the code straight off the URL.
 *
 * The client id and secret come from the environment at build time and are never
 * committed. A build without them fails with a message that says so rather than
 * with an opaque HTTP error.
 */
object DriveAuth {

    /**
     * Per-file scope: the app sees and manages only what it created. That is what
     * keeps this out of Google's restricted-scope review — and it is also simply
     * true, since a backup only ever touches its own folder.
     */
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    /** Intercepted in the WebView. Nothing is ever served here. */
    const val REDIRECT_URI = "http://localhost/oauth2callback"

    sealed interface TokenResult {
        data class Token(val accessToken: String) : TokenResult
        data class Error(val message: String) : TokenResult
    }

    /** RFC 7636: 32 random bytes, base64url, unpadded. */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * `access_type=offline` with `prompt=consent` is what actually returns a
     * refresh token — without the second, Google gives one only on the very first
     * consent and silently omits it on every reconnect afterwards.
     */
    fun buildAuthUrl(codeChallenge: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return AUTH_URL +
            "?client_id=${enc(BuildConfig.DRIVE_CLIENT_ID)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=${enc(SCOPE_DRIVE_FILE)}" +
            "&code_challenge=${enc(codeChallenge)}" +
            "&code_challenge_method=S256" +
            "&access_type=offline" +
            "&prompt=consent"
    }

    fun isConfigured(): Boolean =
        BuildConfig.DRIVE_CLIENT_ID.isNotBlank() && BuildConfig.DRIVE_CLIENT_SECRET.isNotBlank()

    @Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val refresh_token: String? = null,
        val error: String? = null,
        val error_description: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Trades the authorization code for (access, refresh). Null on any failure. */
    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
    ): Pair<String, String?>? = withContext(Dispatchers.IO) {
        try {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val body = "client_id=${enc(BuildConfig.DRIVE_CLIENT_ID)}" +
                "&client_secret=${enc(BuildConfig.DRIVE_CLIENT_SECRET)}" +
                "&code=${enc(code)}" +
                "&code_verifier=${enc(codeVerifier)}" +
                "&redirect_uri=${enc(REDIRECT_URI)}" +
                "&grant_type=authorization_code"
            val resp = postForm(TOKEN_URL, body) ?: return@withContext null
            val parsed = json.decodeFromString(TokenResponse.serializer(), resp)
            val token = parsed.access_token ?: return@withContext null
            token to parsed.refresh_token
        } catch (e: Exception) {
            // The message, never the body: a token response is one field away from
            // a credential.
            Log.e("DriveAuth", "exchangeCodeForTokens failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * A fresh access token from the stored refresh token, with no UI.
     *
     * Every backup run starts here. The errors are worded for the settings screen,
     * because that is the only place the user can do anything about them.
     */
    suspend fun getAccessTokenSilent(context: Context): TokenResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext TokenResult.Error(
                "This build has no Drive credentials — see docs/backup.md.",
            )
        }
        val refreshToken = DriveTokenStore.getRefreshToken(context)
            ?: return@withContext TokenResult.Error("Not connected to Google Drive.")
        try {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val body = "client_id=${enc(BuildConfig.DRIVE_CLIENT_ID)}" +
                "&client_secret=${enc(BuildConfig.DRIVE_CLIENT_SECRET)}" +
                "&refresh_token=${enc(refreshToken)}" +
                "&grant_type=refresh_token"
            val resp = postForm(TOKEN_URL, body)
                ?: return@withContext TokenResult.Error("Token refresh request failed.")
            val parsed = json.decodeFromString(TokenResponse.serializer(), resp)
            val token = parsed.access_token
            if (token.isNullOrBlank()) {
                TokenResult.Error(
                    parsed.error_description ?: parsed.error ?: "No access token in the response.",
                )
            } else {
                TokenResult.Token(token)
            }
        } catch (e: Exception) {
            TokenResult.Error(e.message ?: "Token refresh failed.")
        }
    }

    private fun postForm(url: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.e("DriveAuth", "postForm HTTP $code")
                conn.errorStream?.bufferedReader()?.readText()
            }
        } finally {
            conn.disconnect()
        }
    }
}
