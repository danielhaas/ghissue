package com.ghissue.app.storage

import android.content.Context
import android.content.SharedPreferences

class PrefsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ghissue_prefs", Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, DEFAULT_CLIENT_ID) ?: DEFAULT_CLIENT_ID
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value).apply()

    var repoOwner: String
        get() = prefs.getString(KEY_REPO_OWNER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REPO_OWNER, value).apply()

    var repoName: String
        get() = prefs.getString(KEY_REPO_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REPO_NAME, value).apply()

    val isConfigured: Boolean
        get() = clientId.isNotBlank()
                && repoOwner.isNotBlank() && repoName.isNotBlank()

    companion object {
        private const val DEFAULT_CLIENT_ID = "Ov23liDuXSl6yUoPGfue"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_REPO_OWNER = "repo_owner"
        private const val KEY_REPO_NAME = "repo_name"
    }
}
