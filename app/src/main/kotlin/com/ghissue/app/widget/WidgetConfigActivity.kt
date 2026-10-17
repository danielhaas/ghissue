package com.ghissue.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ghissue.app.MainActivity
import com.ghissue.app.R
import com.ghissue.app.storage.Forges
import com.ghissue.app.storage.PrefsStore
import com.ghissue.app.storage.RepoRef
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

        val forges = Forges(this)
        if (forges.loggedInAccounts().isEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val (repos, errors) = forges.allRepos()
                if (repos.isEmpty()) {
                    if (errors.isNotEmpty()) throw Exception(errors.joinToString("\n"))
                    Toast.makeText(
                        this@WidgetConfigActivity,
                        R.string.no_repos_found,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@launch
                }

                val displayNames = repos.map { (account, repo) ->
                    forges.displayName(RepoRef(account.id, repo.owner, repo.name))
                }.toTypedArray()

                MaterialAlertDialogBuilder(this@WidgetConfigActivity)
                    .setTitle(R.string.select_repo_title)
                    .setItems(displayNames) { _, which ->
                        val (account, selected) = repos[which]
                        val prefsStore = PrefsStore(this@WidgetConfigActivity)
                        prefsStore.setWidgetRepo(
                            appWidgetId, RepoRef(account.id, selected.owner, selected.name)
                        )
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
                showDefaultLabelsPicker(prefsStore)
            }
        }

        dialog.show()
    }

    private fun showDefaultLabelsPicker(prefsStore: PrefsStore) {
        val widgetRepo = prefsStore.getWidgetRepo(appWidgetId)
        if (widgetRepo == null) {
            finalizeWidget()
            return
        }
        val client = Forges(this).client(widgetRepo.account)
        if (client == null) {
            finalizeWidget()
            return
        }
        lifecycleScope.launch {
            try {
                val labels = client.listLabels(widgetRepo.owner, widgetRepo.name)
                if (labels.isEmpty()) {
                    finalizeWidget()
                    return@launch
                }
                val labelNames = labels.map { it.name }.toTypedArray()
                val currentDefaults = prefsStore.defaultLabels
                val checked = BooleanArray(labelNames.size) { labelNames[it] in currentDefaults }
                MaterialAlertDialogBuilder(this@WidgetConfigActivity)
                    .setTitle(R.string.btn_select_default_labels)
                    .setMultiChoiceItems(labelNames, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        prefsStore.defaultLabels = labelNames.filterIndexed { i, _ -> checked[i] }.toSet()
                        finalizeWidget()
                    }
                    .setNegativeButton(R.string.btn_skip) { _, _ ->
                        finalizeWidget()
                    }
                    .setOnCancelListener { finalizeWidget() }
                    .show()
            } catch (_: Exception) {
                finalizeWidget()
            }
        }
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
