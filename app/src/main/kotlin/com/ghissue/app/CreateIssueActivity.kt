package com.ghissue.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ghissue.app.databinding.ActivityCreateIssueBinding
import com.ghissue.app.network.ApiClient
import com.ghissue.app.network.CreateIssueRequest
import com.ghissue.app.storage.PrefsStore
import com.ghissue.app.storage.TokenStore
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class CreateIssueActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateIssueBinding
    private lateinit var prefsStore: PrefsStore
    private lateinit var tokenStore: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateIssueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsStore = PrefsStore(this)
        tokenStore = TokenStore(this)

        if (!prefsStore.isConfigured) {
            Toast.makeText(this, R.string.error_not_configured, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!tokenStore.isLoggedIn) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.textRepoHeader.text = prefsStore.repoName
        binding.textUsernameHeader.text = prefsStore.repoOwner

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSubmit.setOnClickListener { submitIssue() }

        fetchLabels()
    }

    private fun fetchLabels() {
        lifecycleScope.launch {
            try {
                val token = "Bearer ${tokenStore.accessToken}"
                val labels = ApiClient.gitHubApi.listLabels(
                    token = token,
                    owner = prefsStore.repoOwner,
                    repo = prefsStore.repoName
                )
                for (label in labels) {
                    val chip = Chip(this@CreateIssueActivity)
                    chip.text = label.name
                    chip.isCheckable = true
                    binding.chipGroupLabels.addView(chip)
                }
                if (labels.isNotEmpty()) {
                    binding.chipGroupLabels.visibility = View.VISIBLE
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
                    owner = prefsStore.repoOwner,
                    repo = prefsStore.repoName,
                    request = CreateIssueRequest(title = title, body = body, labels = selectedLabels)
                )
                Toast.makeText(
                    this@CreateIssueActivity,
                    R.string.issue_created,
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

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.btnCancel.isEnabled = !loading
        binding.editIssueTitle.isEnabled = !loading
        binding.editIssueBody.isEnabled = !loading
        binding.chipGroupLabels.isEnabled = !loading
        for (i in 0 until binding.chipGroupLabels.childCount) {
            binding.chipGroupLabels.getChildAt(i).isEnabled = !loading
        }
    }
}
