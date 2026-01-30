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
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
    }

    private fun loadSettings() {
        binding.editClientId.setText(prefsStore.clientId)
        binding.editRepoOwner.setText(prefsStore.repoOwner)
        binding.editRepoName.setText(prefsStore.repoName)
    }

    private fun saveSettings() {
        prefsStore.clientId = binding.editClientId.text.toString().trim()
        prefsStore.repoOwner = binding.editRepoOwner.text.toString().trim()
        prefsStore.repoName = binding.editRepoName.text.toString().trim()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
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
                showDeviceCodeDialog(clientId, deviceCode.deviceCode, deviceCode.userCode,
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

    private fun showDeviceCodeDialog(
        clientId: String,
        deviceCode: String,
        userCode: String,
        verificationUri: String,
        interval: Int
    ) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.device_flow_title)
            .setMessage(getString(R.string.device_flow_message, userCode, verificationUri))
            .setPositiveButton(R.string.device_flow_open_browser) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri)))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .setOnDismissListener { pollJob?.cancel() }
            .show()

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
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, R.string.oauth_success, Toast.LENGTH_SHORT).show()
                        updateLoginStatus()
                        return@launch
                    }
                    when (response.error) {
                        "authorization_pending" -> { /* keep polling */ }
                        "slow_down" -> currentInterval += 5
                        "expired_token" -> {
                            dialog.dismiss()
                            Toast.makeText(this@MainActivity, R.string.device_flow_expired, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        else -> {
                            dialog.dismiss()
                            val msg = response.errorDescription ?: response.error ?: "Unknown error"
                            Toast.makeText(this@MainActivity, getString(R.string.oauth_error, msg), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, getString(R.string.oauth_error, e.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
        }
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
    }
}
