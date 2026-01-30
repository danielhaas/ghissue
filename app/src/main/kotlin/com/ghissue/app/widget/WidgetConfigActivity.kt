package com.ghissue.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ghissue.app.R
import com.ghissue.app.network.ApiClient
import com.ghissue.app.storage.PrefsStore
import com.ghissue.app.storage.TokenStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val tokenStore = TokenStore(this)
        if (!tokenStore.isLoggedIn) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val token = "Bearer ${tokenStore.accessToken}"
                val repos = ApiClient.gitHubApi.listRepos(token = token)
                if (repos.isEmpty()) {
                    Toast.makeText(
                        this@WidgetConfigActivity,
                        R.string.no_repos_found,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@launch
                }

                val displayNames = repos.map { it.fullName }.toTypedArray()

                MaterialAlertDialogBuilder(this@WidgetConfigActivity)
                    .setTitle(R.string.select_repo_title)
                    .setItems(displayNames) { _, which ->
                        val selected = repos[which]
                        val prefsStore = PrefsStore(this@WidgetConfigActivity)
                        prefsStore.setWidgetRepo(appWidgetId, selected.owner.login, selected.name)

                        val appWidgetManager = AppWidgetManager.getInstance(this@WidgetConfigActivity)
                        CreateIssueWidgetProvider.updateWidget(
                            this@WidgetConfigActivity,
                            appWidgetManager,
                            appWidgetId
                        )

                        val resultValue = Intent().putExtra(
                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                            appWidgetId
                        )
                        setResult(RESULT_OK, resultValue)
                        finish()
                    }
                    .setOnCancelListener { finish() }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@WidgetConfigActivity,
                    getString(R.string.error_loading_repos, e.message),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }
}
