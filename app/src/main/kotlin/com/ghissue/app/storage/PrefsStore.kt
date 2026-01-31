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

    // Pending device flow state (survives process death)
    var pendingDeviceCode: String?
        get() = prefs.getString(KEY_PENDING_DEVICE_CODE, null)
        set(value) = prefs.edit().putString(KEY_PENDING_DEVICE_CODE, value).apply()

    var pendingUserCode: String?
        get() = prefs.getString(KEY_PENDING_USER_CODE, null)
        set(value) = prefs.edit().putString(KEY_PENDING_USER_CODE, value).apply()

    var pendingVerificationUri: String?
        get() = prefs.getString(KEY_PENDING_VERIFICATION_URI, null)
        set(value) = prefs.edit().putString(KEY_PENDING_VERIFICATION_URI, value).apply()

    var pendingInterval: Int
        get() = prefs.getInt(KEY_PENDING_INTERVAL, 5)
        set(value) = prefs.edit().putInt(KEY_PENDING_INTERVAL, value).apply()

    var pendingExpiresAt: Long
        get() = prefs.getLong(KEY_PENDING_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_EXPIRES_AT, value).apply()

    val hasPendingDeviceFlow: Boolean
        get() = pendingDeviceCode != null && System.currentTimeMillis() < pendingExpiresAt

    fun clearPendingDeviceFlow() {
        prefs.edit()
            .remove(KEY_PENDING_DEVICE_CODE)
            .remove(KEY_PENDING_USER_CODE)
            .remove(KEY_PENDING_VERIFICATION_URI)
            .remove(KEY_PENDING_INTERVAL)
            .remove(KEY_PENDING_EXPIRES_AT)
            .apply()
    }

    fun setWidgetRepo(widgetId: Int, owner: String, name: String) {
        prefs.edit()
            .putString("widget_${widgetId}_repo_owner", owner)
            .putString("widget_${widgetId}_repo_name", name)
            .apply()
    }

    fun getWidgetRepo(widgetId: Int): Pair<String, String>? {
        val owner = prefs.getString("widget_${widgetId}_repo_owner", null) ?: return null
        val name = prefs.getString("widget_${widgetId}_repo_name", null) ?: return null
        return owner to name
    }

    fun setWidgetColor(widgetId: Int, colorInt: Int) {
        prefs.edit()
            .putInt("widget_${widgetId}_color", colorInt)
            .apply()
    }

    fun getWidgetColor(widgetId: Int): Int {
        return prefs.getInt("widget_${widgetId}_color", DEFAULT_WIDGET_COLOR)
    }

    fun clearWidgetRepo(widgetId: Int) {
        prefs.edit()
            .remove("widget_${widgetId}_repo_owner")
            .remove("widget_${widgetId}_repo_name")
            .remove("widget_${widgetId}_color")
            .apply()
    }

    companion object {
        const val DEFAULT_WIDGET_COLOR = 0xFF6750A4.toInt()
        private const val DEFAULT_CLIENT_ID = "Ov23liDuXSl6yUoPGfue"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_REPO_OWNER = "repo_owner"
        private const val KEY_REPO_NAME = "repo_name"
        private const val KEY_PENDING_DEVICE_CODE = "pending_device_code"
        private const val KEY_PENDING_USER_CODE = "pending_user_code"
        private const val KEY_PENDING_VERIFICATION_URI = "pending_verification_uri"
        private const val KEY_PENDING_INTERVAL = "pending_interval"
        private const val KEY_PENDING_EXPIRES_AT = "pending_expires_at"
    }
}
