package com.ghissue.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * An account on one forge. Accounts are keyed by host, e.g. "git.project.li".
 *
 * kind: "github" (device flow), "gitea" (personal access token) or
 * "mindblown" (email/password exchanged for a long-lived token).
 */
data class Account(
    val id: String,
    val kind: String,
    val baseUrl: String,
    val username: String = ""
) {
    val title: String
        get() = when (kind) {
            KIND_GITHUB -> "GitHub"
            KIND_GITEA -> "Gitea"
            else -> "MindBlown"
        }

    companion object {
        const val KIND_GITHUB = "github"
        const val KIND_GITEA = "gitea"
        const val KIND_MINDBLOWN = "mindblown"
        const val GITHUB_ID = "github.com"

        val GITHUB = Account(GITHUB_ID, KIND_GITHUB, "https://github.com")
    }
}

/** A repo (or MindBlown map: owner = map id, name = map name). */
data class RepoItem(val owner: String, val name: String, val fullName: String)

data class LabelItem(val name: String, val color: String)

data class CreatedIssue(
    /** null for a MindBlown node without a linked issue */
    val number: Int?,
    val url: String,
    val title: String,
    /** Created, but something secondary (e.g. the linked issue) failed. */
    val warning: String? = null
) {
    val summary: String
        get() = if (number != null) "#$number: $title" else title
}

class ForgeException(message: String, val code: Int = 0) : Exception(message)

/** Same surface for GitHub, Gitea and MindBlown. */
interface ForgeClient {
    suspend fun listRepos(): List<RepoItem>
    suspend fun listLabels(owner: String, repo: String): List<LabelItem>
    suspend fun createIssue(
        owner: String, repo: String, title: String, body: String?, labels: List<String>
    ): CreatedIssue

    companion object {
        /** [linkIssue] decides, per MindBlown map id, whether to file a linked issue. */
        fun forAccount(
            account: Account,
            token: String,
            linkIssue: (String) -> Boolean = { true }
        ): ForgeClient = when (account.kind) {
            Account.KIND_GITHUB -> GitHubClient(token)
            Account.KIND_GITEA -> GiteaClient(account.baseUrl, token)
            Account.KIND_MINDBLOWN -> MindBlownClient(account.baseUrl, token, linkIssue)
            else -> throw IllegalArgumentException("Unknown account kind: ${account.kind}")
        }
    }
}

class GitHubClient(token: String) : ForgeClient {
    private val auth = "Bearer $token"

    override suspend fun listRepos() =
        ApiClient.gitHubApi.listRepos(auth).map {
            RepoItem(it.owner.login, it.name, it.fullName)
        }

    override suspend fun listLabels(owner: String, repo: String) =
        ApiClient.gitHubApi.listLabels(auth, owner, repo).map { LabelItem(it.name, it.color) }

    override suspend fun createIssue(
        owner: String, repo: String, title: String, body: String?, labels: List<String>
    ): CreatedIssue {
        val issue = ApiClient.gitHubApi.createIssue(
            auth, owner, repo,
            CreateIssueRequest(title = title, body = body, labels = labels.ifEmpty { null })
        )
        return CreatedIssue(issue.number, issue.htmlUrl, issue.title)
    }
}

/** Small blocking-OkHttp JSON helper shared by the Gitea and MindBlown clients. */
internal class JsonHttp(
    private val client: OkHttpClient,
    private val authHeader: String
) {
    suspend fun get(url: String): String = call(Request.Builder().url(url).get())

    suspend fun send(method: String, url: String, json: Any?): String {
        // Fastify rejects an empty body sent as application/json
        val body = (json?.toString() ?: "{}").toRequestBody(JSON)
        return call(Request.Builder().url(url).method(method, body))
    }

    /** Returns null on 404 instead of throwing. */
    suspend fun getOrNull(url: String): String? = try {
        get(url)
    } catch (e: ForgeException) {
        if (e.code == 404) null else throw e
    }

    private suspend fun call(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        if (authHeader.isNotEmpty()) builder.header("Authorization", authHeader)
        val request = builder.header("Accept", "application/json").build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                        ?: JSONObject(text).optString("message")
                }.getOrNull()?.ifBlank { null } ?: "HTTP ${resp.code}"
                throw ForgeException(message, resp.code)
            }
            text
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/** Gitea REST API v1 with a personal access token. */
class GiteaClient(baseUrl: String, token: String) : ForgeClient {
    private val api = "${baseUrl.trimEnd('/')}/api/v1"
    private val http = JsonHttp(ApiClient.okHttpClient, "token $token")

    private suspend fun paged(path: String): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val sep = if ('?' in path) '&' else '?'
            val batch = JSONArray(http.get("$api$path${sep}limit=$PAGE_SIZE&page=$page")).objects()
            items += batch
            if (batch.size < PAGE_SIZE) return items
            page++
        }
    }

    suspend fun username(): String = JSONObject(http.get("$api/user")).getString("login")

    override suspend fun listRepos() =
        paged("/user/repos")
            .filter { it.optBoolean("has_issues", true) && !it.optBoolean("archived", false) }
            .map {
                RepoItem(
                    it.getJSONObject("owner").getString("login"),
                    it.getString("name"),
                    it.getString("full_name")
                )
            }

    private suspend fun rawLabels(owner: String, repo: String): List<JSONObject> {
        val labels = paged("/repos/$owner/$repo/labels").toMutableList()
        // Org-wide labels are valid on the org's repos too
        try {
            labels += paged("/orgs/$owner/labels")
        } catch (e: ForgeException) {
            if (e.code != 404) throw e
        }
        return labels
    }

    override suspend fun listLabels(owner: String, repo: String) =
        rawLabels(owner, repo).map {
            LabelItem(it.getString("name"), it.getString("color").trimStart('#'))
        }

    override suspend fun createIssue(
        owner: String, repo: String, title: String, body: String?, labels: List<String>
    ): CreatedIssue {
        val payload = JSONObject().put("title", title).put("body", body ?: "")
        if (labels.isNotEmpty()) {
            // Gitea wants label IDs, not names
            val ids = rawLabels(owner, repo).associate { it.getString("name") to it.getLong("id") }
            payload.put("labels", JSONArray(labels.mapNotNull { ids[it] }))
        }
        val d = JSONObject(http.send("POST", "$api/repos/$owner/$repo/issues", payload))
        return CreatedIssue(d.getInt("number"), d.getString("html_url"), d.getString("title"))
    }

    companion object {
        private const val PAGE_SIZE = 50

        fun tokenSettingsUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/user/settings/applications"
    }
}

/**
 * MindBlown: maps stand in for repos, nodes for issues, tags for labels.
 *
 * New nodes go under the map's issue inbox (or root). If the map is bound
 * to a GitHub or Gitea repo, MindBlown files and links the issue itself.
 */
class MindBlownClient(
    baseUrl: String,
    token: String,
    private val linkIssue: (String) -> Boolean = { true }
) : ForgeClient {
    private val base = baseUrl.trimEnd('/')
    private val http = JsonHttp(ApiClient.okHttpClient, "Bearer $token")
    // An intake turn runs a model, possibly a local one
    private val slowHttp = JsonHttp(
        ApiClient.okHttpClient.newBuilder().readTimeout(180, TimeUnit.SECONDS).build(),
        "Bearer $token"
    )

    private suspend fun getMap(mapId: String) = JSONObject(http.get("$base/api/maps/$mapId"))

    override suspend fun listRepos() =
        JSONArray(http.get("$base/api/maps")).objects().map {
            RepoItem(it.getString("id"), it.getString("name"), it.getString("name"))
        }

    override suspend fun listLabels(owner: String, repo: String): List<LabelItem> {
        val tags = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        for (node in getMap(owner).getJSONArray("nodes").objects()) {
            node.optJSONArray("tags")?.strings()?.let { tags += it }
        }
        return tags.map { LabelItem(it, TAG_COLOR) }
    }

    override suspend fun createIssue(
        owner: String, repo: String, title: String, body: String?, labels: List<String>
    ): CreatedIssue {
        val mapId = owner
        val map = getMap(mapId).getJSONObject("map")
        val parentId = map.optStringOrNull("githubInboxNodeId") ?: map.getString("rootNodeId")
        val node = JSONObject(
            http.send(
                "POST", "$base/api/maps/$mapId/nodes",
                JSONObject().put("parentId", parentId).put("text", title)
            )
        )
        val nodeId = node.getString("id")

        // Node creation ignores description and tags; set them afterwards
        val fields = JSONObject()
        if (!body.isNullOrBlank()) fields.put("description", body)
        if (labels.isNotEmpty()) fields.put("tags", JSONArray(labels))
        if (fields.length() > 0) {
            http.send("PUT", "$base/api/maps/$mapId/nodes/$nodeId", fields)
        }

        val result = CreatedIssue(null, "$base/?map=$mapId", node.getString("text"))
        val boundOwner = map.optStringOrNull("githubRepoOwner")
        val boundRepo = map.optStringOrNull("githubRepoName")
        if (boundOwner == null || boundRepo == null || !linkIssue(mapId)) return result

        // The node exists now; never throw from here, or a retry would duplicate it.
        // Despite the path, this files on whichever forge the map is bound to.
        return try {
            val d = JSONObject(
                http.send("POST", "$base/api/maps/$mapId/nodes/$nodeId/github/create", null)
            )
            val issue = d.getJSONObject("issue")
            CreatedIssue(issue.getInt("number"), issue.getString("html_url"), result.title)
        } catch (e: Exception) {
            result.copy(warning = "Node created, but the issue in $boundOwner/$boundRepo failed: ${e.message}")
        }
    }

    // ── Ticket intake (AI rewrite + duplicate check) ──

    /** One intake turn; nothing is written. Pass [intakeId] to continue a session. */
    suspend fun intake(mapId: String, message: String, intakeId: String?): IntakeTurn {
        val d = JSONObject(
            slowHttp.send(
                "POST", "$base/api/ai/intake",
                JSONObject().put("mapId", mapId).put("message", message)
                    .put("intakeId", intakeId ?: JSONObject.NULL)
            )
        )
        return IntakeTurn(
            intakeId = d.optStringOrNull("intakeId"),
            text = d.optStringOrNull("text").orEmpty(),
            draft = d.optJSONObject("draft"),
            questions = d.optJSONArray("questions")?.objects().orEmpty().map {
                IntakeQuestion(it.getString("question"), it.optJSONArray("options")?.strings().orEmpty())
            },
            stepLimit = d.optBoolean("stepLimit", false),
            repoConnected = d.optBoolean("repoConnected", false)
        )
    }

    /** Create the (edited) draft as a node, plus its forge issue when [createIssue]. */
    suspend fun intakeCreate(
        mapId: String, draft: JSONObject, title: String, description: String, tags: List<String>,
        intakeId: String?, createIssue: Boolean, existing: IntakeExisting?
    ): CreatedIssue {
        val payloadDraft = JSONObject()
            .put("title", title)
            .put("description", description)
            .put("parentId", draft.getString("parentId"))
            .put("priority", draft.opt("priority") ?: JSONObject.NULL)
            .put("versionId", draft.opt("versionId") ?: JSONObject.NULL)
            .put("phaseId", draft.opt("phaseId") ?: JSONObject.NULL)
            .put("tags", JSONArray(tags))
            .put("dependencies", JSONArray(
                draft.optJSONArray("dependencies")?.objects().orEmpty().map {
                    JSONObject().put("nodeId", it.getString("nodeId"))
                }
            ))
        // Same rule as MindBlown's own card: a low-confidence estimate stays a suggestion
        draft.optJSONObject("estimate")?.let { est ->
            if (est.optString("confidence") in setOf("medium", "high")) {
                payloadDraft.put("effortEstimate", est.getDouble("estimate"))
            }
        }
        val d = JSONObject(
            slowHttp.send(
                "POST", "$base/api/ai/intake/accept",
                JSONObject()
                    .put("mapId", mapId)
                    .put("action", "create")
                    .put("draft", payloadDraft)
                    .put("existing", existing?.ref() ?: JSONObject.NULL)
                    .put("intakeId", intakeId ?: JSONObject.NULL)
                    .put("createIssue", createIssue)
            )
        )
        val issue = d.optJSONObject("issue")
        return CreatedIssue(
            number = issue?.getInt("number"),
            url = issue?.getString("html_url") ?: "$base/?map=$mapId",
            title = d.getJSONObject("node").getString("text"),
            warning = d.optStringOrNull("issueError")
        )
    }

    /** "comment" / "reopen" an existing ticket, or "none" (nothing to do). */
    suspend fun intakeExistingAction(
        mapId: String, action: String, existing: IntakeExisting?, note: String, intakeId: String?
    ) {
        slowHttp.send(
            "POST", "$base/api/ai/intake/accept",
            JSONObject()
                .put("mapId", mapId)
                .put("action", action)
                .put("existing", existing?.ref() ?: JSONObject.NULL)
                .put("note", note)
                .put("intakeId", intakeId ?: JSONObject.NULL)
        )
    }

    companion object {
        private const val TAG_COLOR = "8b949e" // tags have no color of their own

        /** Exchange credentials for a 1-year token. Returns (token, display name). */
        suspend fun login(baseUrl: String, email: String, password: String): Pair<String, String> {
            val base = baseUrl.trimEnd('/')
            val anon = JsonHttp(ApiClient.okHttpClient, "")
            val d = JSONObject(
                anon.send(
                    "POST", "$base/api/auth/login",
                    JSONObject().put("email", email).put("password", password)
                )
            )
            val session = JsonHttp(ApiClient.okHttpClient, "Bearer ${d.getString("token")}")
            val token = JSONObject(session.send("POST", "$base/api/auth/long-lived-token", null))
                .getString("token")
            val user = d.getJSONObject("user")
            return token to (user.optStringOrNull("name") ?: user.getString("email"))
        }
    }
}

data class IntakeQuestion(val question: String, val options: List<String>)

data class IntakeTurn(
    val intakeId: String?,
    val text: String,
    val draft: JSONObject?,
    val questions: List<IntakeQuestion>,
    val stepLimit: Boolean,
    val repoConnected: Boolean
) {
    val verdict: String
        get() = draft?.optString("verdict", "new") ?: "new"

    val existing: List<IntakeExisting>
        get() = draft?.optJSONArray("existing")?.objects().orEmpty().map {
            IntakeExisting(
                nodeId = it.optStringOrNull("nodeId"),
                issueNumber = if (it.isNull("issueNumber")) null else it.getInt("issueNumber"),
                url = it.optStringOrNull("url"),
                text = it.getString("text"),
                status = it.optStringOrNull("status"),
                fixedByPr = it.optBoolean("fixedByPr", false),
                reason = it.optString("reason"),
                recommendation = it.optString("recommendation")
            )
        }
}

data class IntakeExisting(
    val nodeId: String?,
    val issueNumber: Int?,
    val url: String?,
    val text: String,
    val status: String?,
    val fixedByPr: Boolean,
    val reason: String,
    val recommendation: String
) {
    fun ref(): JSONObject = JSONObject()
        .put("nodeId", nodeId ?: JSONObject.NULL)
        .put("issueNumber", issueNumber ?: JSONObject.NULL)
}

/** org.json returns "null" for JSON null with optString; this returns null. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }
