package com.ghissue.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ghissue.app.databinding.ActivityMainBinding
import com.ghissue.app.network.ApiClient
import com.ghissue.app.network.DeviceCodeRequest
import com.ghissue.app.network.DeviceTokenRequest
import com.ghissue.app.storage.PrefsStore
import com.ghissue.app.storage.TokenStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsStore: PrefsStore
    private lateinit var tokenStore: TokenStore
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsStore = PrefsStore(this)
        tokenStore = TokenStore(this)

        loadSettings()
        updateLoginStatus()

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnLogin.setOnClickListener { startOAuthFlow() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnSelectRepo.setOnClickListener { selectRepo() }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
    }

    private fun loadSettings() {
        binding.editClientId.setText(prefsStore.clientId)
        updateSelectedRepoLabel()
    }

    private fun saveSettings() {
        prefsStore.clientId = binding.editClientId.text.toString().trim()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun updateSelectedRepoLabel() {
        val owner = prefsStore.repoOwner
        val name = prefsStore.repoName
        if (owner.isNotBlank() && name.isNotBlank()) {
            binding.textSelectedRepo.text = "$owner/$name"
        } else {
            binding.textSelectedRepo.setText(R.string.no_repo_selected)
        }
    }

    private fun selectRepo() {
        val token = tokenStore.accessToken ?: return
        lifecycleScope.launch {
            try {
                val repos = ApiClient.gitHubApi.listRepos("Bearer $token")
                val repoNames = repos.map { it.fullName }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.select_repo_title)
                    .setItems(repoNames) { _, which ->
                        val selected = repos[which]
                        prefsStore.repoOwner = selected.owner.login
                        prefsStore.repoName = selected.name
                        updateSelectedRepoLabel()
                    }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_loading_repos, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startOAuthFlow() {
        val clientId = binding.editClientId.text.toString().trim()
        if (clientId.isBlank()) {
            Toast.makeText(this, R.string.error_missing_oauth_config, Toast.LENGTH_SHORT).show()
            return
        }

        saveSettings()

        lifecycleScope.launch {
            try {
                val deviceCode = ApiClient.gitHubOAuthApi.requestDeviceCode(
                    DeviceCodeRequest(clientId = clientId, scope = "repo")
                )
                startDeviceCodePolling(clientId, deviceCode.deviceCode, deviceCode.userCode,
                    deviceCode.verificationUri, deviceCode.interval)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.oauth_error, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startDeviceCodePolling(
        clientId: String,
        deviceCode: String,
        userCode: String,
        verificationUri: String,
        interval: Int
    ) {
        binding.textDeviceCode.text = getString(R.string.device_flow_code_label, userCode)
        binding.textDeviceCode.visibility = View.VISIBLE
        binding.btnOpenBrowser.visibility = View.VISIBLE
        binding.textWaiting.visibility = View.VISIBLE

        binding.btnOpenBrowser.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri)))
        }

        pollJob = lifecycleScope.launch {
            var currentInterval = interval.toLong()
            while (true) {
                delay(currentInterval * 1000)
                try {
                    val response = ApiClient.gitHubOAuthApi.pollForToken(
                        DeviceTokenRequest(clientId = clientId, deviceCode = deviceCode)
                    )
                    if (!response.accessToken.isNullOrBlank()) {
                        tokenStore.accessToken = response.accessToken
                        hideDeviceCodeViews()
                        Toast.makeText(this@MainActivity, R.string.oauth_success, Toast.LENGTH_SHORT).show()
                        updateLoginStatus()
                        return@launch
                    }
                    when (response.error) {
                        "authorization_pending" -> { /* keep polling */ }
                        "slow_down" -> currentInterval += 5
                        "expired_token" -> {
                            hideDeviceCodeViews()
                            Toast.makeText(this@MainActivity, R.string.device_flow_expired, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        else -> {
                            hideDeviceCodeViews()
                            val msg = response.errorDescription ?: response.error ?: "Unknown error"
                            Toast.makeText(this@MainActivity, getString(R.string.oauth_error, msg), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    hideDeviceCodeViews()
                    Toast.makeText(this@MainActivity, getString(R.string.oauth_error, e.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
        }
    }

    private fun hideDeviceCodeViews() {
        binding.textDeviceCode.visibility = View.GONE
        binding.btnOpenBrowser.visibility = View.GONE
        binding.textWaiting.visibility = View.GONE
    }

    private fun logout() {
        tokenStore.clear()
        updateLoginStatus()
    }

    private fun updateLoginStatus() {
        val loggedIn = tokenStore.isLoggedIn
        binding.textLoginStatus.setText(
            if (loggedIn) R.string.status_logged_in else R.string.status_not_logged_in
        )
        binding.btnLogin.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnLogout.visibility = if (loggedIn) View.VISIBLE else View.GONE
        binding.groupRepoSection.visibility = if (loggedIn) View.VISIBLE else View.GONE
    }
}
