package com.ghissue.app

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ghissue.app.databinding.ActivityCreateIssueBinding
import com.ghissue.app.network.ApiClient
import com.ghissue.app.network.CreateIssueRequest
import com.ghissue.app.network.QueuedIssue
import com.ghissue.app.storage.IssueQueueManager
import com.ghissue.app.storage.PrefsStore
import com.ghissue.app.storage.TokenStore
import com.ghissue.app.widget.CreateIssueWidgetProvider
import android.appwidget.AppWidgetManager
import android.content.res.ColorStateList
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

class CreateIssueActivity : AppCompatActivity() {

    companion object {
        private const val KEY_TITLE = "issue_title"
        private const val KEY_BODY = "issue_body"
        private const val KEY_SELECTED_LABELS = "selected_labels"
    }

    private lateinit var binding: ActivityCreateIssueBinding
    private lateinit var prefsStore: PrefsStore
    private lateinit var tokenStore: TokenStore
    private lateinit var issueQueueManager: IssueQueueManager
    private var repoOwner: String = ""
    private var repoName: String = ""
    private var pendingSelectedLabels: List<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateIssueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsStore = PrefsStore(this)
        tokenStore = TokenStore(this)
        issueQueueManager = (application as GhIssueApp).issueQueueManager

        val widgetId = intent.getIntExtra(
            CreateIssueWidgetProvider.EXTRA_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val widgetRepo = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            prefsStore.getWidgetRepo(widgetId)
        } else null

        repoOwner = widgetRepo?.first ?: prefsStore.repoOwner
        repoName = widgetRepo?.second ?: prefsStore.repoName

        if (repoOwner.isBlank() || repoName.isBlank()) {
            Toast.makeText(this, R.string.error_not_configured, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!tokenStore.isLoggedIn) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.textRepoHeader.text = repoName
        binding.textUsernameHeader.text = repoOwner

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSubmit.setOnClickListener { submitIssue() }
        binding.btnSendBody.setOnClickListener { submitIssue() }
        binding.btnReset.setOnClickListener { resetForm() }

        if (savedInstanceState != null) {
            binding.editIssueTitle.setText(savedInstanceState.getString(KEY_TITLE, ""))
            binding.editIssueBody.setText(savedInstanceState.getString(KEY_BODY, ""))
            pendingSelectedLabels = savedInstanceState.getStringArrayList(KEY_SELECTED_LABELS)
        }
        binding.editIssueTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitIssue()
                true
            } else false
        }

        fetchLabels()
        drainQueueIfNeeded()
    }

    private fun fetchLabels() {
        lifecycleScope.launch {
            try {
                val token = "Bearer ${tokenStore.accessToken}"
                val labels = ApiClient.gitHubApi.listLabels(
                    token = token,
                    owner = repoOwner,
                    repo = repoName
                )
                for (label in labels) {
                    val chip = Chip(this@CreateIssueActivity)
                    chip.text = label.name
                    chip.isCheckable = true
                    val bg = Color.parseColor("#${label.color}")
                    chip.chipBackgroundColor = ColorStateList.valueOf(bg)
                    val lum = Color.luminance(bg)
                    chip.setTextColor(if (lum > 0.5) Color.BLACK else Color.WHITE)
                    binding.chipGroupLabels.addView(chip)
                }
                if (labels.isNotEmpty()) {
                    binding.chipGroupLabels.visibility = View.VISIBLE
                }
                if (pendingSelectedLabels != null) {
                    for (i in 0 until binding.chipGroupLabels.childCount) {
                        val chip = binding.chipGroupLabels.getChildAt(i) as Chip
                        chip.isChecked = chip.text.toString() in pendingSelectedLabels!!
                    }
                    pendingSelectedLabels = null
                } else {
                    val defaults = prefsStore.defaultLabels
                    if (defaults.isNotEmpty()) {
                        for (i in 0 until binding.chipGroupLabels.childCount) {
                            val chip = binding.chipGroupLabels.getChildAt(i) as Chip
                            chip.isChecked = chip.text.toString() in defaults
                        }
                    }
                }
            } catch (_: Exception) {
                // Silently skip if label fetch fails
            }
        }
    }

    private fun submitIssue() {
        val title = binding.editIssueTitle.text.toString().trim()
        if (title.isBlank()) {
            Toast.makeText(this, R.string.error_title_required, Toast.LENGTH_SHORT).show()
            return
        }

        val body = binding.editIssueBody.text.toString().trim().ifBlank { null }

        val selectedLabels = (0 until binding.chipGroupLabels.childCount)
            .map { binding.chipGroupLabels.getChildAt(it) as Chip }
            .filter { it.isChecked }
            .map { it.text.toString() }
            .ifEmpty { null }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val token = "Bearer ${tokenStore.accessToken}"
                ApiClient.gitHubApi.createIssue(
                    token = token,
                    owner = repoOwner,
                    repo = repoName,
                    request = CreateIssueRequest(title = title, body = body, labels = selectedLabels)
                )
                Toast.makeText(
                    this@CreateIssueActivity,
                    R.string.issue_created,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: IOException) {
                val queued = QueuedIssue(
                    id = UUID.randomUUID().toString(),
                    owner = repoOwner,
                    repo = repoName,
                    title = title,
                    body = body,
                    labels = selectedLabels,
                    createdAt = System.currentTimeMillis()
                )
                issueQueueManager.enqueue(queued)
                val count = issueQueueManager.count()
                Toast.makeText(
                    this@CreateIssueActivity,
                    getString(R.string.issue_queued, count),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@CreateIssueActivity,
                    getString(R.string.error_creating_issue, e.message),
                    Toast.LENGTH_LONG
                ).show()
                setLoading(false)
            }
        }
    }

    private fun drainQueueIfNeeded() {
        val count = issueQueueManager.count()
        if (count == 0) return
        updatePendingCount()
        lifecycleScope.launch {
            val result = issueQueueManager.drainQueue(tokenStore)
            if (result.submitted > 0) {
                Toast.makeText(
                    this@CreateIssueActivity,
                    getString(R.string.queued_issues_submitted, result.submitted),
                    Toast.LENGTH_SHORT
                ).show()
            }
            updatePendingCount()
        }
    }

    private fun updatePendingCount() {
        val count = issueQueueManager.count()
        if (count > 0) {
            binding.textPendingCount.text = resources.getQuantityString(
                R.plurals.pending_issues, count, count
            )
            binding.textPendingCount.visibility = View.VISIBLE
        } else {
            binding.textPendingCount.visibility = View.GONE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TITLE, binding.editIssueTitle.text.toString())
        outState.putString(KEY_BODY, binding.editIssueBody.text.toString())
        val selected = (0 until binding.chipGroupLabels.childCount)
            .map { binding.chipGroupLabels.getChildAt(it) as Chip }
            .filter { it.isChecked }
            .map { it.text.toString() }
        outState.putStringArrayList(KEY_SELECTED_LABELS, ArrayList(selected))
    }

    private fun resetForm() {
        binding.editIssueTitle.text?.clear()
        binding.editIssueBody.text?.clear()
        for (i in 0 until binding.chipGroupLabels.childCount) {
            (binding.chipGroupLabels.getChildAt(i) as Chip).isChecked = false
        }
        binding.editIssueTitle.requestFocus()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.btnSendBody.isEnabled = !loading
        binding.btnReset.isEnabled = !loading
        binding.btnCancel.isEnabled = !loading
        binding.editIssueTitle.isEnabled = !loading
        binding.editIssueBody.isEnabled = !loading
        binding.chipGroupLabels.isEnabled = !loading
        for (i in 0 until binding.chipGroupLabels.childCount) {
            binding.chipGroupLabels.getChildAt(i).isEnabled = !loading
        }
    }
}
