package com.ignacio.cofre.kit.accounts

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.modpacks.FileOps
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

/** Non-secret account summaries only. Tokens belong in the platform's encrypted store. */
class AccountStore(private val directory: File, private val readSelected: () -> String? = { null }, private val writeSelected: (String?) -> Unit = {}) {
    @Serializable enum class State { Ready }
    data class Account(val id: String, val name: String, val kind: String, val active: Boolean, val expiresAt: Long?, val state: State) {
        override fun toString(): String = "Account(redacted)"
    }
    private val accountsDirectory: File get() = File(directory, "accounts")
    fun list(): List<Account> = files().mapNotNull { file ->
        runCatching {
            val obj = Kit.json.parseToJsonElement(file.readText()).jsonObject
            val id = obj["accountId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val name = obj["username"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: id
            val expiry = obj["expiresAt"]?.jsonPrimitive?.longOrNull
            val microsoft = "xuid" in obj || "xboxGamertag" in obj || expiry != null
            // Access-token expiry does not sign the account out: the refresh token renews it at launch.
            Account(id, name, if (microsoft) "Microsoft" else "Offline", id == readSelected(), expiry, State.Ready)
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }
    fun remove(id: String): Account? {
        val account = list().firstOrNull { it.id == id } ?: return null
        val file = files().firstOrNull { file -> runCatching { Kit.json.parseToJsonElement(file.readText()).jsonObject["accountId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension }.getOrNull() == id }
        if (file != null && !file.delete()) error("Could not remove account")
        if (readSelected() == id) writeSelected(list().firstOrNull()?.id)
        return account
    }
    /** Writes [id]'s summary, the only account file: never a token. A Microsoft account carries its token [expiresAt]. */
    fun save(id: String, name: String, microsoft: Boolean, expiresAt: Long? = null) {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' }
        require(safe.isNotEmpty() && name.isNotBlank())
        val summary = buildJsonObject {
            put("accountId", id)
            put("username", name)
            if (microsoft) put("xuid", "")
            if (expiresAt != null) put("expiresAt", expiresAt)
        }
        FileOps.atomic(File(accountsDirectory, "$safe.json"), Kit.json.encodeToString(summary).toByteArray())
    }
    fun select(id: String?) { if (id == null || list().any { it.id == id }) writeSelected(id) }
    private fun files(): List<File> = accountsDirectory.listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty()
}
