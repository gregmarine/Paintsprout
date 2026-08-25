package com.symmetricalpalmtree.paintsproutonyx.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The single door to the app's EncryptedSharedPreferences files — one lock, one
 * cached instance per file name.
 *
 * Two failure modes force this shape. First, androidx.security's keyset creation
 * is not safe when two threads first-create the same file at once: both mint a
 * keyset, one clobbers the other, and everything the loser wrote is sealed under
 * a key that no longer exists. One lock around construction removes the race.
 * Second, the Keystore can throw transiently in the first moments after boot;
 * a single short retry absorbs that instead of turning a cold morning launch
 * into a crash that looks like data loss.
 */
internal object SecurePrefs {

    private val cache = mutableMapOf<String, SharedPreferences>()

    fun get(context: Context, fileName: String): SharedPreferences = synchronized(cache) {
        cache.getOrPut(fileName) {
            try {
                create(context, fileName)
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences create failed for $fileName — retrying once", e)
                Thread.sleep(150)
                create(context, fileName)
            }
        }
    }

    private fun create(context: Context, fileName: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            fileName,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private const val TAG = "SecurePrefs"
}
