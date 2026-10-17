package com.ghissue.app.storage

import android.content.Context
import com.ghissue.app.network.Account
import com.ghissue.app.network.ForgeClient
import com.ghissue.app.network.RepoItem

/** Builds API clients for configured, logged-in accounts. */
class Forges(context: Context) {
    val prefs = PrefsStore(context)
    val tokens = TokenStore(context)

    fun client(accountId: String): ForgeClient? {
        val account = prefs.findAccount(accountId) ?: return null
        val token = tokens.getToken(accountId) ?: return null
        return ForgeClient.forAccount(account, token)
    }

    fun loggedInAccounts(): List<Account> = prefs.accounts.filter { tokens.isLoggedIn(it.id) }

    /** "owner/name", or the map name for MindBlown, suffixed with the host off GitHub. */
    fun displayName(repo: RepoRef): String {
        val kind = prefs.findAccount(repo.account)?.kind
        val base = if (kind == Account.KIND_MINDBLOWN) repo.name else "${repo.owner}/${repo.name}"
        return if (repo.account == Account.GITHUB_ID) base else "$base (${repo.account})"
    }

    /** Repos from every logged-in account; per-account failures are collected, not thrown. */
    suspend fun allRepos(): Pair<List<Pair<Account, RepoItem>>, List<String>> {
        val found = mutableListOf<Pair<Account, RepoItem>>()
        val errors = mutableListOf<String>()
        for (account in loggedInAccounts()) {
            val client = client(account.id) ?: continue
            try {
                found += client.listRepos().map { account to it }
            } catch (e: Exception) {
                errors += "${account.id}: ${e.message}"
            }
        }
        return found to errors
    }
}
