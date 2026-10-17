package com.ghissue.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ghissue.app.databinding.ActivityVoiceSettingsBinding
import com.ghissue.app.storage.PrefsStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Dictation language for the mic buttons on the create screen. */
class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceSettingsBinding
    private lateinit var prefsStore: PrefsStore

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
        binding.root.padForSystemBars()

        prefsStore = PrefsStore(this)

        updateLanguageDisplay()
        binding.btnSelectLanguage.setOnClickListener { showLanguagePicker() }
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
}
