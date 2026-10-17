package com.ghissue.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ghissue.app.databinding.ActivityCreateIssueBinding
import com.ghissue.app.network.CreatedIssue
import com.ghissue.app.network.ForgeClient
import com.ghissue.app.network.IntakeExisting
import com.ghissue.app.network.IntakeQuestion
import com.ghissue.app.network.IntakeTurn
import com.ghissue.app.network.MindBlownClient
import com.ghissue.app.network.QueuedIssue
import com.ghissue.app.speech.SpeechHelper
import com.ghissue.app.storage.Forges
import com.ghissue.app.storage.IssueQueueManager
import com.ghissue.app.storage.PrefsStore
import com.ghissue.app.storage.RepoRef
import com.ghissue.app.widget.CreateIssueWidgetProvider
import android.appwidget.AppWidgetManager
import android.content.res.ColorStateList
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

class CreateIssueActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_SPEECH = "auto_speech"
        const val EXTRA_VOICE_TITLE = "voice_title"
        private const val VOICE_LABEL = "voice"
        private const val KEY_TITLE = "issue_title"
        private const val KEY_BODY = "issue_body"
        private const val KEY_SELECTED_LABELS = "selected_labels"
    }

    private lateinit var binding: ActivityCreateIssueBinding
    private lateinit var prefsStore: PrefsStore
    private lateinit var forges: Forges
    private lateinit var issueQueueManager: IssueQueueManager
    private lateinit var repo: RepoRef
    private lateinit var client: ForgeClient
    private var repoOwner: String = ""
    private var repoName: String = ""

    // Ticket intake state (MindBlown only)
    private var intakeId: String? = null
    private var intakeTurn: IntakeTurn? = null
    private var pendingSelectedLabels: List<String>? = null

    private lateinit var speechHelper: SpeechHelper
    private var activeMicTarget: MicTarget? = null
    private var autoSpeechMode: Boolean = false

    private enum class MicTarget { TITLE, BODY }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            activeMicTarget?.let { startSpeechFor(it) }
        } else {
            Toast.makeText(this, R.string.mic_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateIssueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsStore = PrefsStore(this)
        forges = Forges(this)
        issueQueueManager = (application as GhIssueApp).issueQueueManager

        val widgetId = intent.getIntExtra(
            CreateIssueWidgetProvider.EXTRA_WIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val widgetRepo = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            prefsStore.getWidgetRepo(widgetId)
        } else null

        val selectedRepo = widgetRepo ?: prefsStore.defaultRepo
        if (selectedRepo == null) {
            Toast.makeText(this, R.string.error_not_configured, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        repo = selectedRepo
        repoOwner = repo.owner
        repoName = repo.name

        val repoClient = forges.client(repo.account)
        if (repoClient == null) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        client = repoClient

        binding.textRepoHeader.text = repoName
        // MindBlown's owner is a map id; show the host instead
        binding.textUsernameHeader.text =
            if (client is MindBlownClient) repo.account else repoOwner

        if (client is MindBlownClient) {
            binding.btnRefine.visibility = View.VISIBLE
            binding.btnRefine.setOnClickListener { startRefine(null) }
        }

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSubmit.setOnClickListener { submitIssue() }
        binding.btnSendBody.setOnClickListener { submitIssue() }
        binding.btnReset.setOnClickListener { resetForm() }

        speechHelper = SpeechHelper(this, object : SpeechHelper.Listener {
            override fun onListeningStarted() {
                val layout = when (activeMicTarget) {
                    MicTarget.TITLE -> binding.editIssueTitle.parent.parent as TextInputLayout
                    MicTarget.BODY -> binding.editIssueBody.parent.parent as TextInputLayout
                    null -> return
                }
                layout.setEndIconDrawable(R.drawable.ic_mic_active)
            }

            override fun onResult(text: String) {
                when (activeMicTarget) {
                    MicTarget.TITLE -> {
                        binding.editIssueTitle.setText(text)
                        if (autoSpeechMode) {
                            submitIssue()
                        }
                    }
                    MicTarget.BODY -> {
                        val current = binding.editIssueBody.text?.toString().orEmpty()
                        val appended = if (current.isBlank()) text else "$current $text"
                        binding.editIssueBody.setText(appended)
                        binding.editIssueBody.setSelection(binding.editIssueBody.text?.length ?: 0)
                    }
                    null -> {}
                }
            }

            override fun onError(message: String) {
                Toast.makeText(this@CreateIssueActivity, message, Toast.LENGTH_SHORT).show()
            }

            override fun onListeningStopped() {
                val layout = when (activeMicTarget) {
                    MicTarget.TITLE -> binding.editIssueTitle.parent.parent as TextInputLayout
                    MicTarget.BODY -> binding.editIssueBody.parent.parent as TextInputLayout
                    null -> return
                }
                layout.setEndIconDrawable(R.drawable.ic_mic)
            }
        })

        val lang = prefsStore.speechLanguage
        if (lang.isNotBlank()) {
            speechHelper.language = lang
        }

        val titleLayout = binding.editIssueTitle.parent.parent as TextInputLayout
        titleLayout.setEndIconOnClickListener { onMicTap(MicTarget.TITLE) }

        val bodyLayout = binding.editIssueBody.parent.parent as TextInputLayout
        bodyLayout.setEndIconOnClickListener { onMicTap(MicTarget.BODY) }

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

        val voiceTitle = intent.getStringExtra(EXTRA_VOICE_TITLE)
        if (voiceTitle != null) {
            binding.editIssueTitle.setText(voiceTitle)
            submitIssue()
        } else if (intent.getBooleanExtra(EXTRA_AUTO_SPEECH, false)) {
            autoSpeechMode = true
            onMicTap(MicTarget.TITLE)
        }
    }

    private fun onMicTap(target: MicTarget) {
        if (speechHelper.isListening) {
            speechHelper.stop()
            return
        }
        activeMicTarget = target
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startSpeechFor(target)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSpeechFor(target: MicTarget) {
        activeMicTarget = target
        speechHelper.startListening()
    }

    override fun onDestroy() {
        speechHelper.stop()
        super.onDestroy()
    }

    private fun fetchLabels() {
        lifecycleScope.launch {
            try {
                val labels = client.listLabels(repoOwner, repoName)
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
            .toMutableList()

        val isVoice = autoSpeechMode || intent.hasExtra(EXTRA_VOICE_TITLE)
        if (isVoice && VOICE_LABEL !in selectedLabels) {
            selectedLabels.add(VOICE_LABEL)
        }

        setLoading(true)

        val turn = intakeTurn
        val draft = turn?.draft
        lifecycleScope.launch {
            try {
                val result = if (draft != null && client is MindBlownClient) {
                    // Submit what the user sees; keep the draft's hidden placement
                    val known = chips().map { it.text.toString() }.toSet()
                    val draftTags = draft.optJSONArray("tags")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    }.orEmpty()
                    val tags = selectedLabels + draftTags.filter { it !in known && it !in selectedLabels }
                    val related = turn.existing.firstOrNull().takeIf { turn.verdict != "new" }
                    (client as MindBlownClient).intakeCreate(
                        repoOwner, draft, title, body.orEmpty(), tags, intakeId,
                        createIssue = turn.repoConnected, existing = related
                    )
                } else {
                    client.createIssue(repoOwner, repoName, title, body, selectedLabels)
                }
                showCreated(result)
                finish()
            } catch (e: IOException) {
                val queued = QueuedIssue(
                    id = UUID.randomUUID().toString(),
                    owner = repoOwner,
                    repo = repoName,
                    title = title,
                    body = body,
                    labels = selectedLabels.ifEmpty { null },
                    createdAt = System.currentTimeMillis(),
                    account = repo.account
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
            val result = issueQueueManager.drainQueue(forges)
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

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
        }
    }

    private fun resetForm() {
        intakeId = null
        intakeTurn = null
        binding.refinePanel.visibility = View.GONE
        binding.questionsContainer.removeAllViews()
        binding.existingContainer.removeAllViews()
        binding.btnSubmit.setText(R.string.btn_submit)
        binding.editIssueTitle.text?.clear()
        binding.editIssueBody.text?.clear()
        for (i in 0 until binding.chipGroupLabels.childCount) {
            (binding.chipGroupLabels.getChildAt(i) as Chip).isChecked = false
        }
        binding.editIssueTitle.requestFocus()
    }

    private fun chips(): List<Chip> =
        (0 until binding.chipGroupLabels.childCount).map { binding.chipGroupLabels.getChildAt(it) as Chip }

    private fun showCreated(result: CreatedIssue) {
        val message = when {
            result.warning != null -> "${result.summary}\n${result.warning}"
            result.number != null -> "${getString(R.string.issue_created)} ${result.summary}"
            else -> "${getString(R.string.node_created)}: ${result.summary}"
        }
        Toast.makeText(
            this, message,
            if (result.warning != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    // ── Refine (MindBlown ticket intake) ──

    /** [message] null = send the current title and body. */
    private fun startRefine(message: String?) {
        val mb = client as? MindBlownClient ?: return
        val text = message ?: listOf(
            binding.editIssueTitle.text.toString().trim(),
            binding.editIssueBody.text.toString().trim()
        ).filter { it.isNotEmpty() }.joinToString("\n\n")
        if (text.isBlank()) {
            Toast.makeText(this, R.string.refine_empty, Toast.LENGTH_SHORT).show()
            return
        }
        binding.refinePanel.visibility = View.VISIBLE
        binding.textRefineStatus.setText(R.string.refine_working)
        setLoading(true)
        lifecycleScope.launch {
            try {
                showIntake(mb.intake(repoOwner, text, intakeId))
            } catch (e: Exception) {
                // An expired session is the usual cause; the next Refine starts fresh
                intakeId = null
                binding.textRefineStatus.text = getString(R.string.refine_failed, e.message)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showIntake(turn: IntakeTurn) {
        intakeTurn = turn
        intakeId = turn.intakeId

        binding.questionsContainer.removeAllViews()
        for (q in turn.questions) binding.questionsContainer.addView(questionView(q))

        val draft = turn.draft
        if (draft != null) {
            binding.editIssueTitle.setText(draft.optString("title"))
            binding.editIssueBody.setText(draft.optString("description"))
            val tags = draft.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }.orEmpty()
            for (chip in chips()) {
                if (chip.text.toString() in tags) chip.isChecked = true
            }
        }
        showExisting(turn)

        val status = buildString {
            append(turn.text.ifBlank { if (draft != null) getString(R.string.refine_done) else "" })
            if (turn.stepLimit) append("\n").append(getString(R.string.refine_step_limit))
        }
        binding.textRefineStatus.text = status.trim()
    }

    private fun questionView(q: IntakeQuestion): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(MaterialTextView(this).apply {
            text = q.question
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val options = com.google.android.material.chip.ChipGroup(this)
        for (option in q.options) {
            options.addView(Chip(this).apply {
                text = option
                setOnClickListener { startRefine("${q.question} → $option") }
            })
        }
        box.addView(options)
        return box
    }

    private fun showExisting(turn: IntakeTurn) {
        val container = binding.existingContainer
        container.removeAllViews()
        val existing = turn.existing
        if (turn.draft == null || turn.verdict == "new" || existing.isEmpty()) {
            binding.btnSubmit.setText(R.string.btn_submit)
            return
        }

        container.addView(MaterialTextView(this).apply {
            setText(
                when (turn.verdict) {
                    "covered" -> R.string.verdict_covered
                    "extends" -> R.string.verdict_extends
                    "regression" -> R.string.verdict_regression
                    else -> R.string.verdict_similar
                }
            )
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        // Top three keep the dialog on screen
        for (ex in existing.take(3)) container.addView(existingView(ex))
        container.addView(actionButton(
            R.string.btn_nothing_to_do, existing.any { it.recommendation == "nothing" }
        ) { existingAction("none", null) })
        binding.btnSubmit.setText(R.string.btn_create_anyway)
    }

    private fun existingView(ex: IntakeExisting): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val ref = ex.issueNumber?.let { "#$it " } ?: ""
        val facts = listOfNotNull(ex.status, if (ex.fixedByPr) "fixed by PR" else null)
        box.addView(MaterialTextView(this).apply {
            text = buildString {
                append(ref).append(ex.text)
                if (facts.isNotEmpty()) append(" (").append(facts.joinToString(", ")).append(")")
                if (ex.reason.isNotBlank()) append("\n").append(ex.reason)
            }
            ex.url?.let { url ->
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton(R.string.btn_comment, ex.recommendation == "comment") {
            existingAction("comment", ex)
        })
        buttons.addView(actionButton(R.string.btn_reopen, ex.recommendation == "reopen") {
            existingAction("reopen", ex)
        })
        box.addView(buttons)
        return box
    }

    /** Recommended actions get a filled button, the rest a text button. */
    private fun actionButton(text: Int, recommended: Boolean, onClick: () -> Unit): MaterialButton {
        val style = if (recommended) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.borderlessButtonStyle
        return MaterialButton(this, null, style).apply {
            setText(text)
            setOnClickListener { onClick() }
        }
    }

    private fun existingAction(action: String, ex: IntakeExisting?) {
        val mb = client as? MindBlownClient ?: return
        val note = binding.editIssueBody.text.toString().trim()
            .ifBlank { binding.editIssueTitle.text.toString().trim() }
        setLoading(true)
        lifecycleScope.launch {
            try {
                mb.intakeExistingAction(repoOwner, action, ex, note, intakeId)
                val ref = ex?.issueNumber?.let { "#$it" } ?: ex?.text.orEmpty()
                val message = when (action) {
                    "comment" -> getString(R.string.existing_commented, ref)
                    "reopen" -> getString(R.string.existing_reopened, ref)
                    else -> getString(R.string.existing_nothing)
                }
                Toast.makeText(this@CreateIssueActivity, message, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@CreateIssueActivity,
                    getString(R.string.action_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.btnRefine.isEnabled = !loading
        for (container in listOf(binding.questionsContainer, binding.existingContainer)) {
            setEnabledDeep(container, !loading)
        }
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
