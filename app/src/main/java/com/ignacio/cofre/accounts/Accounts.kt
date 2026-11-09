package com.ignacio.cofre.accounts

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.BuildConfig
import com.ignacio.cofre.R
import com.ignacio.cofre.kit.accounts.AccountError
import com.ignacio.cofre.kit.accounts.AccountStore
import com.ignacio.cofre.kit.accounts.MinecraftAccounts
import com.ignacio.cofre.kit.accounts.Pkce
import com.ignacio.cofre.onIo
import com.ignacio.cofre.work
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Minecraft accounts: Microsoft sign-in in a Custom Tab with PKCE, the account list, and the session a launch
 * needs. Summaries (name, id) sit in plain JSON for [AccountStore]; every token sits in the [AppGraph.vault].
 */
class Accounts(private val graph: AppGraph) {
    private val store = AccountStore(
        graph.home,
        readSelected = { graph.settings.value.selectedAccount },
        writeSelected = { id -> graph.settings.update { it.copy(selectedAccount = id) } },
    )

    /** An account row: [signedOut] when its Microsoft refresh token is gone, so it needs signing in again. */
    data class Entry(val account: AccountStore.Account, val signedOut: Boolean) {
        val offline: Boolean get() = account.kind == "Offline"
    }

    sealed interface SignIn {
        data object Idle : SignIn
        /** The browser is open; the redirect brings the code back. */
        data object Waiting : SignIn
        data object Working : SignIn
        data class Failed(val message: String) : SignIn
    }

    /** What a launch passes to the game. */
    data class Session(val name: String, val uuid: String, val accessToken: String, val offline: Boolean)

    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var signIn by mutableStateOf<SignIn>(SignIn.Idle)
        private set

    /** False when this build has no Microsoft client ID: the UI says so instead of failing. */
    val configured: Boolean get() = graph.microsoftClientId.isNotBlank()

    val ready: Boolean get() = entries.any { !it.signedOut && it.account.state == AccountStore.State.Ready }
    val selected: Entry? get() = entries.firstOrNull { it.account.active } ?: entries.firstOrNull()

    init {
        refresh()
    }

    private val client: MinecraftAccounts by lazy { MinecraftAccounts(graph.microsoftClientId, BuildConfig.MICROSOFT_REDIRECT_URI) }

    fun refresh() {
        entries = runCatching { store.list() }.getOrDefault(emptyList())
            .filter { BuildConfig.DEBUG || it.kind != "Offline" }
            .map { Entry(it, it.kind == "Microsoft" && graph.vault.get(refreshKey(it.id)) == null) }
    }

    /** Opens Microsoft's sign-in page. The PKCE verifier and state wait in the vault for [complete]. */
    fun begin(context: Context) {
        if (!configured) {
            signIn = SignIn.Failed(context.getString(R.string.signin_not_configured))
            return
        }
        val pkce = Pkce()
        val state = UUID.randomUUID().toString()
        graph.vault.set(PENDING_VERIFIER, pkce.verifier)
        graph.vault.set(PENDING_STATE, state)
        signIn = SignIn.Waiting
        CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(context, client.authorizeUrl(pkce, state).toString().toUri())
    }

    /** The `cofre://auth?code=…&state=…` redirect. A state that doesn't match the one sent is rejected. */
    fun complete(uri: Uri) {
        val verifier = graph.vault.get(PENDING_VERIFIER)
        val expected = graph.vault.get(PENDING_STATE)
        graph.vault.set(PENDING_VERIFIER, null)
        graph.vault.set(PENDING_STATE, null)
        val code = uri.getQueryParameter("code")
        if (uri.getQueryParameter("error") != null || code.isNullOrBlank()) {
            signIn = SignIn.Failed(graph.context.getString(R.string.signin_cancelled))
            return
        }
        if (verifier == null || expected == null || uri.getQueryParameter("state") != expected) {
            signIn = SignIn.Failed(graph.context.getString(R.string.signin_state_mismatch))
            return
        }
        signIn = SignIn.Working
        graph.work({ signIn = SignIn.Failed(it) }) {
            val session = withContext(graph.io) { client.signIn(code, Pkce(verifier)) }
            graph.onIo { save(session) }
            graph.settings.update { it.copy(selectedAccount = session.profile.id) }
            refresh()
            signIn = SignIn.Idle
        }
    }

    fun dismissError() {
        signIn = SignIn.Idle
    }

    fun select(id: String) {
        store.select(id)
        refresh()
    }

    fun remove(id: String) {
        store.remove(id)
        graph.vault.removeAll("account.$id.")
        refresh()
    }

    /** Debug builds only: an offline "Dev" account to exercise the launch pipeline without Microsoft. */
    fun addDevAccount() {
        check(BuildConfig.DEBUG)
        store.save(DEV_ID, "Dev", microsoft = false)
        graph.settings.update { it.copy(selectedAccount = DEV_ID) }
        refresh()
    }

    /** The selected account's game session, refreshing its Minecraft token when it has expired. */
    suspend fun session(): Session {
        val entry = selected ?: error(graph.context.getString(R.string.play_need_account))
        val id = entry.account.id
        if (entry.offline) return Session(entry.account.name, offlineUuid(entry.account.name), "0", offline = true)
        val token = graph.vault.get(accessKey(id))
        val expires = graph.vault.get(expiryKey(id))?.toLongOrNull() ?: 0
        if (token != null && expires > System.currentTimeMillis() + 60_000) return Session(entry.account.name, id, token, offline = false)
        val refresh = graph.vault.get(refreshKey(id)) ?: error(graph.context.getString(R.string.accounts_signed_out))
        val session = try {
            withContext(graph.io) { client.refreshSession(refresh) }
        } catch (e: AccountError.Http) {
            // Microsoft no longer accepts the refresh token: the account needs signing in again.
            graph.vault.set(refreshKey(id), null)
            refresh()
            throw IllegalStateException(graph.context.getString(R.string.accounts_signed_out), e)
        }
        graph.onIo { save(session) }
        return Session(session.profile.name, session.profile.id, session.minecraft.accessToken, offline = false)
    }

    private fun save(session: MinecraftAccounts.Session) {
        val id = session.profile.id
        val expiresAt = System.currentTimeMillis() + session.minecraft.expiresIn * 1000
        store.save(id, session.profile.name, microsoft = true, expiresAt = expiresAt)
        graph.vault.set(accessKey(id), session.minecraft.accessToken)
        graph.vault.set(expiryKey(id), expiresAt.toString())
        session.msa.refreshToken?.let { graph.vault.set(refreshKey(id), it) }
    }

    private companion object {
        const val PENDING_VERIFIER = "signin.verifier"
        const val PENDING_STATE = "signin.state"
        const val DEV_ID = "dev-offline"
        fun refreshKey(id: String) = "account.$id.refresh"
        fun accessKey(id: String) = "account.$id.access"
        fun expiryKey(id: String) = "account.$id.expiry"

        /** Minecraft's offline UUID: a name-based (v3) UUID of "OfflinePlayer:<name>". */
        fun offlineUuid(name: String): String =
            UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString().replace("-", "")
    }
}
