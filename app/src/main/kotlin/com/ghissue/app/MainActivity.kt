package com.ghissue.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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

    companion object {
        private const val TAG = "GhIssue"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsStore = PrefsStore(this)
        tokenStore = TokenStore(this)

        Log.d(TAG, "onCreate: isLoggedIn=${tokenStore.isLoggedIn}, hasPendingFlow=${prefsStore.hasPendingDeviceFlow}")
        loadSettings()
        updateLoginStatus()

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnLogin.setOnClickListener { startOAuthFlow() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnSelectRepo.setOnClickListener { selectRepo() }
    }

    override fun onResume() {
        super.onResume()
        val hasPending = prefsStore.hasPendingDeviceFlow
        Log.d(TAG, "onResume: pollJob=${pollJob}, hasPendingFlow=$hasPending, " +
            "pendingDeviceCode=${prefsStore.pendingDeviceCode != null}, " +
            "expiresAt=${prefsStore.pendingExpiresAt}, now=${System.currentTimeMillis()}")
        if (pollJob == null && hasPending) {
            Log.d(TAG, "onResume: resuming device code polling")
            startDeviceCodePolling(
                prefsStore.clientId,
                prefsStore.pendingDeviceCode!!,
                prefsStore.pendingUserCode!!,
                prefsStore.pendingVerificationUri!!,
                prefsStore.pendingInterval
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called, cancelling pollJob")
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
            binding.btnSelectRepo.setText(R.string.btn_change_repo)
        } else {
            binding.textSelectedRepo.setText(R.string.no_repo_selected)
            binding.btnSelectRepo.setText(R.string.btn_select_repo)
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
        Log.d(TAG, "startOAuthFlow: requesting device code for clientId=$clientId")

        lifecycleScope.launch {
            try {
                val deviceCode = ApiClient.gitHubOAuthApi.requestDeviceCode(
                    DeviceCodeRequest(clientId = clientId, scope = "repo")
                )
                Log.d(TAG, "startOAuthFlow: got userCode=${deviceCode.userCode}, " +
                    "expiresIn=${deviceCode.expiresIn}, interval=${deviceCode.interval}")
                prefsStore.pendingDeviceCode = deviceCode.deviceCode
                prefsStore.pendingUserCode = deviceCode.userCode
                prefsStore.pendingVerificationUri = deviceCode.verificationUri
                prefsStore.pendingInterval = deviceCode.interval
                prefsStore.pendingExpiresAt = System.currentTimeMillis() + deviceCode.expiresIn * 1000L

                startDeviceCodePolling(clientId, deviceCode.deviceCode, deviceCode.userCode,
                    deviceCode.verificationUri, deviceCode.interval)
            } catch (e: Exception) {
                Log.e(TAG, "startOAuthFlow: failed to request device code", e)
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
        Log.d(TAG, "startDeviceCodePolling: userCode=$userCode, interval=$interval")
        binding.textDeviceCode.text = userCode
        binding.textDeviceCodeLabel.visibility = View.VISIBLE
        binding.textDeviceCode.visibility = View.VISIBLE
        binding.btnCopyCode.visibility = View.VISIBLE
        binding.btnOpenBrowser.visibility = View.VISIBLE
        binding.textWaiting.visibility = View.VISIBLE

        binding.btnCopyCode.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("device code", userCode))
            Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenBrowser.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri)))
        }

        pollJob = lifecycleScope.launch {
            var currentInterval = interval.toLong()
            Log.d(TAG, "poll coroutine started, interval=${currentInterval}s")
            while (true) {
                delay(currentInterval * 1000)
                Log.d(TAG, "polling for token...")
                try {
                    val response = ApiClient.gitHubOAuthApi.pollForToken(
                        DeviceTokenRequest(clientId = clientId, deviceCode = deviceCode)
                    )
                    Log.d(TAG, "poll response: accessToken=${response.accessToken?.take(4)}..., " +
                        "error=${response.error}, errorDesc=${response.errorDescription}")
                    if (!response.accessToken.isNullOrBlank()) {
                        Log.d(TAG, "poll: got access token, login successful")
                        tokenStore.accessToken = response.accessToken
                        hideDeviceCodeViews()
                        Toast.makeText(this@MainActivity, R.string.oauth_success, Toast.LENGTH_SHORT).show()
                        updateLoginStatus()
                        return@launch
                    }
                    when (response.error) {
                        "authorization_pending" -> {
                            Log.d(TAG, "poll: authorization_pending, continuing")
                        }
                        "slow_down" -> {
                            currentInterval += 5
                            Log.d(TAG, "poll: slow_down, new interval=${currentInterval}s")
                        }
                        "expired_token" -> {
                            Log.w(TAG, "poll: expired_token")
                            hideDeviceCodeViews()
                            Toast.makeText(this@MainActivity, R.string.device_flow_expired, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        else -> {
                            val msg = response.errorDescription ?: response.error ?: "Unknown error"
                            Log.w(TAG, "poll: unexpected error: $msg")
                            hideDeviceCodeViews()
                            Toast.makeText(this@MainActivity, getString(R.string.oauth_error, msg), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "poll: transient network error, will retry", e)
                    // Network errors are transient (e.g. DNS failure while backgrounded),
                    // keep retrying
                } catch (e: Exception) {
                    Log.e(TAG, "poll: fatal exception during polling", e)
                    hideDeviceCodeViews()
                    Toast.makeText(this@MainActivity, getString(R.string.oauth_error, e.message), Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
        }
    }

    private fun hideDeviceCodeViews() {
        Log.d(TAG, "hideDeviceCodeViews: clearing pending flow")
        prefsStore.clearPendingDeviceFlow()
        pollJob = null
        binding.textDeviceCodeLabel.visibility = View.GONE
        binding.textDeviceCode.visibility = View.GONE
        binding.btnCopyCode.visibility = View.GONE
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
