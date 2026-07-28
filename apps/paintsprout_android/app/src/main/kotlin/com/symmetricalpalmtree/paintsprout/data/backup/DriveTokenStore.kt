package com.symmetricalpalmtree.paintsprout.data.backup

import android.content.Context
import com.symmetricalpalmtree.paintsprout.crypto.CryptoStores

/**
 * Where the Drive refresh token lives.
 *
 * Its own keystore-backed file, separate from the passphrase vault and from the
 * derived-key cache, for the reason those two are separate from each other:
 * disconnecting Drive must not be able to disturb the secret that opens the
 * library. Access tokens are never stored — they are fetched per run and held in
 * RAM. Nothing here is ever logged.
 */
object DriveTokenStore {

    private const val KEY_REFRESH_TOKEN = "drive_refresh_token"

    fun storeRefreshToken(context: Context, token: String) {
        CryptoStores.driveTokens(context).putString(KEY_REFRESH_TOKEN, token)
    }

    fun getRefreshToken(context: Context): String? =
        CryptoStores.driveTokens(context).getString(KEY_REFRESH_TOKEN)

    fun clear(context: Context) {
        CryptoStores.driveTokens(context).remove(KEY_REFRESH_TOKEN)
    }
}
