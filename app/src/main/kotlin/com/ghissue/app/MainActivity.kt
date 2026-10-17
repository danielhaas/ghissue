package com.ghissue.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ghissue.app.databinding.ActivityMainBinding
import com.ghissue.app.network.Account
import com.ghissue.app.network.ApiClient
import com.ghissue.app.network.DeviceCodeRequest
import com.ghissue.app.network.DeviceTokenRequest
import com.ghissue.app.network.GiteaClient
import com.ghissue.app.network.MindBlownClient
import com.ghissue.app.storage.Forges
import com.ghissue.app.storage.PrefsStore
import com.ghissue.app.storage.RepoRef
import com.ghissue.app.storage.TokenStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.net.URI
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsStore: PrefsStore
    private lateinit var tokenStore: TokenStore
    private lateinit var forges: Forges
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
        forges = Forges(this)

        Log.d(TAG, "onCreate: isLoggedIn=${tokenStore.isLoggedIn(Account.GITHUB_ID)}, hasPendingFlow=${prefsStore.hasPendingDeviceFlow}")
        loadSettings()
        updateLoginStatus()

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnLogin.setOnClickListener { startOAuthFlow() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnSelectRepo.setOnClickListener { selectRepo() }
        binding.btnSelectDefaultLabels.setOnClickListener { selectDefaultLabels() }
        binding.btnAddGitea.setOnClickListener { showAccountDialog(Account.KIND_GITEA, null) }
        binding.btnAddMindBlown.setOnClickListener { showAccountDialog(Account.KIND_MINDBLOWN, null) }
        binding.btnVoiceSettings.setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }
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
        updateDefaultLabelsDisplay()
    }

    private fun saveSettings() {
        prefsStore.clientId = binding.editClientId.text.toString().trim()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun updateSelectedRepoLabel() {
        val repo = prefsStore.defaultRepo
        if (repo != null) {
            binding.textSelectedRepo.text = forges.displayName(repo)
            binding.btnSelectRepo.setText(R.string.btn_change_repo)
        } else {
            binding.textSelectedRepo.setText(R.string.no_repo_selected)
            binding.btnSelectRepo.setText(R.string.btn_select_repo)
        }
    }

    private fun selectRepo() {
        lifecycleScope.launch {
            try {
                val (repos, errors) = forges.allRepos()
                if (errors.isNotEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_loading_repos, errors.joinToString("\n")),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (repos.isEmpty()) return@launch
                val repoNames = repos.map { (account, repo) ->
                    forges.displayName(RepoRef(account.id, repo.owner, repo.name))
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.select_repo_title)
                    .setItems(repoNames) { _, which ->
                        val (account, selected) = repos[which]
                        prefsStore.defaultRepo = RepoRef(account.id, selected.owner, selected.name)
                        prefsStore.defaultLabels = emptySet()
                        updateSelectedRepoLabel()
                        updateDefaultLabelsDisplay()
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

    private fun selectDefaultLabels() {
        val repo = prefsStore.defaultRepo
        if (repo == null) {
            Toast.makeText(this, R.string.error_select_repo_first, Toast.LENGTH_SHORT).show()
            return
        }
        val client = forges.client(repo.account) ?: return
        lifecycleScope.launch {
            try {
                val labels = client.listLabels(repo.owner, repo.name)
                val labelNames = labels.map { it.name }.toTypedArray()
                val currentDefaults = prefsStore.defaultLabels
                val checked = BooleanArray(labelNames.size) { labelNames[it] in currentDefaults }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.btn_select_default_labels)
                    .setMultiChoiceItems(labelNames, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val selected = labelNames.filterIndexed { i, _ -> checked[i] }.toSet()
                        prefsStore.defaultLabels = selected
                        updateDefaultLabelsDisplay()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_loading_labels, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateDefaultLabelsDisplay() {
        val labels = prefsStore.defaultLabels
        binding.textDefaultLabels.text = if (labels.isEmpty()) {
            getString(R.string.no_default_labels)
        } else {
            labels.sorted().joinToString(", ")
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
        tokenStore.accessToken = null
        updateLoginStatus()
    }

    private fun updateLoginStatus() {
        val loggedIn = tokenStore.isLoggedIn(Account.GITHUB_ID)
        binding.textLoginStatus.setText(
            if (loggedIn) R.string.status_logged_in else R.string.status_not_logged_in
        )
        binding.btnLogin.visibility = if (loggedIn) View.GONE else View.VISIBLE
        binding.btnLogout.visibility = if (loggedIn) View.VISIBLE else View.GONE
        rebuildAccountRows()
        val anyLoggedIn = forges.loggedInAccounts().isNotEmpty()
        binding.groupRepoSection.visibility = if (anyLoggedIn) View.VISIBLE else View.GONE
        updateSelectedRepoLabel()
    }

    // ── Gitea / MindBlown accounts ──

    private fun rebuildAccountRows() {
        val container = binding.accountsContainer
        container.removeAllViews()
        for (account in prefsStore.accounts.filter { it.kind != Account.KIND_GITHUB }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val status = if (tokenStore.isLoggedIn(account.id)) {
                if (account.username.isNotBlank()) getString(R.string.account_status_as, account.username)
                else getString(R.string.status_logged_in)
            } else getString(R.string.account_status_logged_out)
            val label = MaterialTextView(this).apply {
                text = "${account.title} · ${account.id}\n$status"
                setOnClickListener { showAccountDialog(account.kind, account) }
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val remove = MaterialButton(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                setText(R.string.btn_remove)
                setOnClickListener { confirmRemoveAccount(account) }
            }
            row.addView(remove)
            container.addView(row)
        }
    }

    private fun confirmRemoveAccount(account: Account) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.remove_account_confirm, account.id))
            .setPositiveButton(R.string.btn_remove) { _, _ ->
                tokenStore.setToken(account.id, null)
                prefsStore.removeAccount(account.id)
                updateLoginStatus()
                updateDefaultLabelsDisplay()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun textField(hint: Int, inputType: Int, value: String = ""): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this).apply { this.hint = getString(hint) }
        val edit = TextInputEditText(layout.context).apply {
            this.inputType = inputType
            setText(value)
        }
        layout.addView(edit)
        return layout to edit
    }

    /**
     * Gitea takes a personal access token; MindBlown takes email/password,
     * exchanged for a long-lived token (the password is not stored).
     */
    private fun showAccountDialog(kind: String, existing: Account?) {
        val title = if (kind == Account.KIND_GITEA) "Gitea" else "MindBlown"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.activity_margin)
            setPadding(pad, pad / 2, pad, 0)
        }
        val (urlLayout, urlEdit) = textField(
            R.string.hint_server_url, InputType.TYPE_TEXT_VARIATION_URI, existing?.baseUrl ?: "https://"
        )
        urlEdit.isEnabled = existing == null
        content.addView(urlLayout)

        val secretFields = if (kind == Account.KIND_GITEA) {
            val (tokenLayout, tokenEdit) = textField(
                R.string.hint_access_token,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            content.addView(tokenLayout)
            content.addView(MaterialTextView(this).apply { setText(R.string.gitea_token_scopes) })
            content.addView(MaterialButton(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                setText(R.string.btn_create_token)
                setOnClickListener {
                    val url = normalizeBaseUrl(urlEdit.text.toString()) ?: return@setOnClickListener
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GiteaClient.tokenSettingsUrl(url))))
                }
            })
            listOf(tokenEdit)
        } else {
            val (emailLayout, emailEdit) = textField(
                R.string.hint_email, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
            val (pwLayout, pwEdit) = textField(
                R.string.hint_password,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            content.addView(emailLayout)
            content.addView(pwLayout)
            listOf(emailEdit, pwEdit)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (existing != null) getString(R.string.relogin_account_title, existing.id)
                else getString(R.string.add_account_title, title)
            )
            .setView(content)
            .setPositiveButton(R.string.btn_log_in, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val loginBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            // Keep the dialog open until the credentials have been verified
            loginBtn.setOnClickListener {
                val baseUrl = normalizeBaseUrl(urlEdit.text.toString())
                val values = secretFields.map { it.text.toString().trim() }
                if (baseUrl == null || values.any { it.isEmpty() }) {
                    Toast.makeText(this, R.string.error_fields_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                loginBtn.isEnabled = false
                loginBtn.setText(R.string.logging_in)
                lifecycleScope.launch {
                    try {
                        val (token, username) = if (kind == Account.KIND_GITEA) {
                            values[0] to GiteaClient(baseUrl, values[0]).username()
                        } else {
                            MindBlownClient.login(baseUrl, values[0], values[1])
                        }
                        val account = Account(URI(baseUrl).host.lowercase(), kind, baseUrl, username)
                        prefsStore.saveAccount(account)
                        tokenStore.setToken(account.id, token)
                        dialog.dismiss()
                        updateLoginStatus()
                    } catch (e: Exception) {
                        loginBtn.isEnabled = true
                        loginBtn.setText(R.string.btn_log_in)
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.error_login_failed, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun normalizeBaseUrl(text: String): String? {
        var url = text.trim().trimEnd('/')
        if (url.isEmpty() || url == "https:" || url == "http:") return null
        if ("://" !in url) url = "https://$url"
        return url.takeIf { runCatching { URI(it).host }.getOrNull() != null }
    }
}
