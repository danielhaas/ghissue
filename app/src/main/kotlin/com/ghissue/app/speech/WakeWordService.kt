package com.ghissue.app.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ghissue.app.CreateIssueActivity
import com.ghissue.app.R
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16000.0f
        private const val WAKE_WORD = "computer"
        const val ACTION_RESUME = "com.ghissue.app.speech.RESUME"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var triggered = false
    private var capturingTitle = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LibVosk.setLogLevel(LogLevel.INFO)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESUME) {
            Log.d(TAG, "Resuming wake word detection")
            triggered = false
            capturingTitle = false
            startKeywordListening()
        }
        return START_STICKY
    }

    private fun initModel() {
        StorageService.unpack(this, "model-en-us", "model",
            { model ->
                this.model = model
                startKeywordListening()
            },
            { exception ->
                Log.e(TAG, "Failed to unpack model", exception)
                stopSelf()
            }
        )
    }

    private fun startKeywordListening() {
        stopSpeechService()
        val model = this.model ?: return

        try {
            val recognizer = Recognizer(model, SAMPLE_RATE,
                """["$WAKE_WORD", "[unk]"]"""
            )
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(this)
            Log.i(TAG, "Wake word detection started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start keyword listening", e)
            stopSelf()
        }
    }

    private fun startTitleCapture() {
        stopSpeechService()
        val model = this.model ?: return

        try {
            // Switch to free-form recognition to capture the issue title
            val recognizer = Recognizer(model, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            capturingTitle = true
            speechService?.startListening(this)
            Log.i(TAG, "Title capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start title capture", e)
            // Fall back to launching with auto-speech
            launchCreateIssue(null)
        }
    }

    private fun stopSpeechService() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping speech service", e)
        }
        speechService = null
    }

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis == null) return
        try {
            val partial = JSONObject(hypothesis).optString("partial", "")
            if (!triggered && !capturingTitle && partial.contains(WAKE_WORD)) {
                Log.i(TAG, "Wake word detected on partial!")
                triggered = true
                startTitleCapture()
            }
        } catch (_: Exception) {}
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis == null || !capturingTitle) return
        try {
            val text = JSONObject(hypothesis).optString("text", "").trim()
            if (text.isNotBlank()) {
                Log.i(TAG, "Captured title: $text")
                launchCreateIssue(text)
            }
            // If empty, keep listening for the title
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing title result", e)
        }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Recognition error", exception)
        if (capturingTitle) {
            launchCreateIssue(null)
        } else {
            startKeywordListening()
        }
    }

    override fun onTimeout() {}

    private fun launchCreateIssue(title: String?) {
        capturingTitle = false
        stopSpeechService()

        val activityIntent = Intent(this, CreateIssueActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            if (title != null) {
                putExtra(CreateIssueActivity.EXTRA_VOICE_TITLE, title)
            } else {
                putExtra(CreateIssueActivity.EXTRA_AUTO_SPEECH, true)
            }
        }

        try {
            startActivity(activityIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity failed, using notification", e)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(getString(R.string.wake_word_triggered_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_word_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.wake_word_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(getString(R.string.wake_word_notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopSpeechService()
        model = null
        super.onDestroy()
    }
}
