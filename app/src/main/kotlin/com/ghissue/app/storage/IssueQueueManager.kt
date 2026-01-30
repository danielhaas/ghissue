package com.ghissue.app.storage

import android.content.Context
import android.content.SharedPreferences
import com.ghissue.app.network.ApiClient
import com.ghissue.app.network.CreateIssueRequest
import com.ghissue.app.network.QueuedIssue
import com.squareup.moshi.Types
import java.io.IOException

class IssueQueueManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ghissue_queue", Context.MODE_PRIVATE)

    private val adapter = ApiClient.moshi.adapter<List<QueuedIssue>>(
        Types.newParameterizedType(List::class.java, QueuedIssue::class.java)
    )

    @Synchronized
    fun enqueue(issue: QueuedIssue) {
        val list = getAll().toMutableList()
        list.add(issue)
        prefs.edit().putString(KEY_QUEUED_ISSUES, adapter.toJson(list)).apply()
    }

    @Synchronized
    fun remove(id: String) {
        val list = getAll().filter { it.id != id }
        prefs.edit().putString(KEY_QUEUED_ISSUES, adapter.toJson(list)).apply()
    }

    @Synchronized
    fun getAll(): List<QueuedIssue> {
        val json = prefs.getString(KEY_QUEUED_ISSUES, null) ?: return emptyList()
        return adapter.fromJson(json) ?: emptyList()
    }

    fun count(): Int = getAll().size

    suspend fun drainQueue(tokenStore: TokenStore): DrainResult {
        var submitted = 0
        var failed = 0
        var stopReason: String? = null

        val queue = getAll()
        for (issue in queue) {
            try {
                val token = "Bearer ${tokenStore.accessToken}"
                ApiClient.gitHubApi.createIssue(
                    token = token,
                    owner = issue.owner,
                    repo = issue.repo,
                    request = CreateIssueRequest(
                        title = issue.title,
                        body = issue.body,
                        labels = issue.labels
                    )
                )
                remove(issue.id)
                submitted++
            } catch (e: IOException) {
                stopReason = "network"
                failed++
                break
            } catch (e: retrofit2.HttpException) {
                when (e.code()) {
                    401 -> {
                        stopReason = "auth"
                        failed++
                        break
                    }
                    else -> {
                        remove(issue.id)
                        failed++
                    }
                }
            }
        }

        return DrainResult(submitted, failed, stopReason)
    }

    data class DrainResult(
        val submitted: Int,
        val failed: Int,
        val stopReason: String?
    )

    companion object {
        private const val KEY_QUEUED_ISSUES = "queued_issues"
    }
}
