package com.ghissue.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
                        showColorPicker(prefsStore)
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

    private fun showColorPicker(prefsStore: PrefsStore) {
        val colorOptions = listOf(
            R.id.colorPurple to R.color.widget_color_purple,
            R.id.colorBlue to R.color.widget_color_blue,
            R.id.colorTeal to R.color.widget_color_teal,
            R.id.colorGreen to R.color.widget_color_green,
            R.id.colorOrange to R.color.widget_color_orange,
            R.id.colorRed to R.color.widget_color_red,
            R.id.colorPink to R.color.widget_color_pink,
            R.id.colorIndigo to R.color.widget_color_indigo,
        )

        val pickerView = layoutInflater.inflate(R.layout.widget_color_picker, null)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_widget_color_title)
            .setView(pickerView)
            .setOnCancelListener { finish() }
            .create()

        for ((viewId, colorRes) in colorOptions) {
            pickerView.findViewById<View>(viewId).setOnClickListener {
                val colorInt = ContextCompat.getColor(this, colorRes)
                prefsStore.setWidgetColor(appWidgetId, colorInt)
                dialog.dismiss()
                finalizeWidget()
            }
        }

        dialog.show()
    }

    private fun finalizeWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        CreateIssueWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)

        val resultValue = Intent().putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            appWidgetId
        )
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
