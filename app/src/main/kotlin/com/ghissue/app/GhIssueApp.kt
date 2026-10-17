package com.ghissue.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ghissue.app.speech.WakeWordService
import com.ghissue.app.storage.IssueQueueManager
import com.ghissue.app.storage.Forges
import com.ghissue.app.storage.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GhIssueApp : Application() {

    lateinit var issueQueueManager: IssueQueueManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        issueQueueManager = IssueQueueManager(this)

        if (PrefsStore(this).wakeWordEnabled) {
            WakeWordService.start(this)
        }

        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (issueQueueManager.count() == 0) return
                appScope.launch {
                    val result = issueQueueManager.drainQueue(Forges(this@GhIssueApp))
                    if (result.submitted > 0) {
                        mainHandler.post {
                            Toast.makeText(
                                this@GhIssueApp,
                                getString(R.string.queued_issues_submitted, result.submitted),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }
}
