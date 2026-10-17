package com.ghissue.app.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.ghissue.app.network.Account

/** One token per account, keyed by account id (host). */
class TokenStore(context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "ghissue_secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        // Before accounts there was a single GitHub token
        prefs.getString(KEY_LEGACY_ACCESS_TOKEN, null)?.let { legacy ->
            prefs.edit()
                .putString(keyFor(Account.GITHUB_ID), legacy)
                .remove(KEY_LEGACY_ACCESS_TOKEN)
                .apply()
        }
    }

    fun getToken(accountId: String): String? =
        prefs.getString(keyFor(accountId), null)?.ifBlank { null }

    fun setToken(accountId: String, token: String?) {
        if (token != null) {
            prefs.edit().putString(keyFor(accountId), token).apply()
        } else {
            prefs.edit().remove(keyFor(accountId)).apply()
        }
    }

    fun isLoggedIn(accountId: String): Boolean = getToken(accountId) != null

    /** GitHub token, for the device-flow login code. */
    var accessToken: String?
        get() = getToken(Account.GITHUB_ID)
        set(value) = setToken(Account.GITHUB_ID, value)

    private fun keyFor(accountId: String) = "token_$accountId"

    companion object {
        private const val KEY_LEGACY_ACCESS_TOKEN = "access_token"
    }
}
