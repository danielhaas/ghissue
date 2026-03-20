package com.ghissue.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechHelper(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onListeningStarted()
        fun onResult(text: String)
        fun onError(message: String)
        fun onListeningStopped()
    }

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    var language: String? = null

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("Speech recognition not available")
            return
        }

        stop()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    listener.onListeningStarted()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    isListening = false
                    listener.onListeningStopped()
                    if (text.isNotBlank()) {
                        listener.onResult(text)
                    }
                }

                override fun onError(error: Int) {
                    isListening = false
                    listener.onListeningStopped()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                        else -> "Recognition error ($error)"
                    }
                    listener.onError(msg)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            language?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
            }
        }

        speechRecognizer?.startListening(intent)
    }

    fun stop() {
        speechRecognizer?.apply {
            cancel()
            destroy()
        }
        speechRecognizer = null
        if (isListening) {
            isListening = false
            listener.onListeningStopped()
        }
    }
}
