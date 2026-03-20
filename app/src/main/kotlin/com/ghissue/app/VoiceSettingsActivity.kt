package com.ghissue.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ghissue.app.databinding.ActivityVoiceSettingsBinding
import com.ghissue.app.speech.WakeWordService
import com.ghissue.app.storage.PrefsStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceSettingsBinding
    private lateinit var prefsStore: PrefsStore

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkOverlayPermission()
        } else {
            binding.switchWakeWord.isChecked = false
            Toast.makeText(this, R.string.mic_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestMicForWakeWord()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            enableWakeWord()
        } else {
            binding.switchWakeWord.isChecked = false
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private val LANGUAGES = listOf(
            "" to "Device default",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
            "de-DE" to "Deutsch",
            "es-ES" to "Español",
            "fr-FR" to "Français",
            "it-IT" to "Italiano",
            "pt-BR" to "Português (Brasil)",
            "nl-NL" to "Nederlands",
            "ja-JP" to "日本語",
            "ko-KR" to "한국어",
            "zh-CN" to "中文 (简体)",
            "ru-RU" to "Русский",
            "ar-SA" to "العربية",
            "hi-IN" to "हिन्दी",
            "sv-SE" to "Svenska",
            "da-DK" to "Dansk",
            "pl-PL" to "Polski",
            "tr-TR" to "Türkçe",
            "uk-UA" to "Українська",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsStore = PrefsStore(this)

        updateLanguageDisplay()
        binding.btnSelectLanguage.setOnClickListener { showLanguagePicker() }

        binding.switchWakeWord.isChecked = prefsStore.wakeWordEnabled
        binding.switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestNotificationPermissionThenMic()
            } else {
                prefsStore.wakeWordEnabled = false
                WakeWordService.stop(this)
            }
        }
    }

    private fun updateLanguageDisplay() {
        val current = prefsStore.speechLanguage
        val label = LANGUAGES.firstOrNull { it.first == current }?.second
            ?: getString(R.string.speech_language_default)
        binding.textSelectedLanguage.text = label
    }

    private fun showLanguagePicker() {
        val labels = LANGUAGES.map { it.second }.toTypedArray()
        val currentIndex = LANGUAGES.indexOfFirst { it.first == prefsStore.speechLanguage }
            .coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speech_language_label)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefsStore.speechLanguage = LANGUAGES[which].first
                updateLanguageDisplay()
                dialog.dismiss()
            }
            .show()
    }

    private fun requestNotificationPermissionThenMic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMicForWakeWord()
        }
    }

    private fun requestMicForWakeWord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            checkOverlayPermission()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            enableWakeWord()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun enableWakeWord() {
        prefsStore.wakeWordEnabled = true
        WakeWordService.start(this)
    }
}
