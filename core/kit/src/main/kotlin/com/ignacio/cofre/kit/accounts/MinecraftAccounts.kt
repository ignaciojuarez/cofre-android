package com.ignacio.cofre.kit.accounts

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.modrinth.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636, using only APIs available on Android 28. */
class Pkce(val verifier: String = random()) {
    init { require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}"))) }
    val challenge: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    override fun toString(): String = "Pkce(redacted)"
    companion object {
        fun random(): String { val bytes = ByteArray(32); SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }
    }
}

sealed class AccountError(message: String) : Exception(message) {
    data object NoXboxAccount : AccountError("Create an Xbox account first")
    data object ChildAccount : AccountError("The Xbox child account needs family approval")
    data object NoMinecraft : AccountError("This account does not own Minecraft Java")
    data object AppNotApproved : AccountError("The client application is not approved for Minecraft")
    data class XboxDenied(val code: Long?) : AccountError("Xbox authorization denied")
    data class Http(val stage: String, val status: Int) : AccountError("$stage failed (HTTP $status)")
    data class InvalidResponse(val stage: String) : AccountError("Invalid $stage response")
}

/**
 * login.live.com consumer OAuth accepts public clients with PKCE, XboxLive.signin and offline_access,
 * without a client secret. It excludes work/school tenants, which cannot own Minecraft Java.
 * The UI must validate the returned OAuth state before passing the code here.
 * Secrets are returned to the caller for platform secure storage; this class never persists/logs them.
 */
class MinecraftAccounts(
    private val clientId: String,
    private val redirectUri: String,
    private val http: OkHttpClient = Kit.http,
    private val endpoints: Endpoints = Endpoints(),
) {
    init { require(clientId.isNotBlank() && redirectUri.isNotBlank()) }
    data class Endpoints(
        val authorize: String = "https://login.live.com/oauth20_authorize.srf",
        val token: String = "https://login.live.com/oauth20_token.srf",
        val xbox: String = "https://user.auth.xboxlive.com/user/authenticate",
        val xsts: String = "https://xsts.auth.xboxlive.com/xsts/authorize",
        val minecraft: String = "https://api.minecraftservices.com/authentication/login_with_xbox",
        val entitlements: String = "https://api.minecraftservices.com/entitlements/mcstore",
        val profile: String = "https://api.minecraftservices.com/minecraft/profile",
    )
    @Serializable data class MsaToken(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("token_type") val tokenType: String = "Bearer",
        val scope: String? = null,
    ) { override fun toString(): String = "MsaToken(redacted)" }
    @Serializable data class MinecraftToken(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("token_type") val tokenType: String = "Bearer",
    ) { override fun toString(): String = "MinecraftToken(redacted)" }
    @Serializable data class Profile(val id: String, val name: String, val skins: List<JsonObject> = emptyList(), val capes: List<JsonObject> = emptyList()) {
        override fun toString(): String = "Profile(redacted)"
    }
    data class Session(val msa: MsaToken, val minecraft: MinecraftToken, val profile: Profile) {
        override fun toString(): String = "Session(redacted)"
    }
    @Serializable private data class XboxToken(@SerialName("Token") val token: String, @SerialName("DisplayClaims") val claims: Claims) {
        @Serializable data class Claims(val xui: List<User>)
        @Serializable data class User(val uhs: String)
        override fun toString(): String = "XboxToken(redacted)"
    }
    fun authorizeUrl(pkce: Pkce, state: String): HttpUrl {
        require(state.isNotBlank())
        return endpoints.authorize.toHttpUrl().newBuilder().addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri).addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPE).addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256").addQueryParameter("state", state).build()
    }
    suspend fun exchangeCode(code: String, pkce: Pkce): MsaToken {
        require(code.isNotBlank())
        val form = form().add("grant_type", "authorization_code").add("code", code).add("code_verifier", pkce.verifier).build()
        return decode("Microsoft", request("Microsoft", endpoints.token, form))
    }
    suspend fun refresh(refreshToken: String): MsaToken {
        require(refreshToken.isNotBlank())
        val token = decode<MsaToken>("Microsoft", request("Microsoft", endpoints.token, form().add("grant_type", "refresh_token").add("refresh_token", refreshToken).build()))
        return if (token.refreshToken == null) token.copy(refreshToken = refreshToken) else token
    }
    suspend fun signIn(code: String, pkce: Pkce): Session = minecraft(exchangeCode(code, pkce))
    suspend fun refreshSession(refreshToken: String): Session = minecraft(refresh(refreshToken))
    suspend fun minecraft(msa: MsaToken): Session {
        val xbox = decode<XboxToken>("Xbox", request("Xbox", endpoints.xbox, jsonBody(buildJsonObject {
            put("Properties", buildJsonObject { put("AuthMethod", "RPS"); put("SiteName", "user.auth.xboxlive.com"); put("RpsTicket", "d=${msa.accessToken}") })
            put("RelyingParty", "http://auth.xboxlive.com"); put("TokenType", "JWT")
        })))
        val xsts = decode<XboxToken>("XSTS", request("XSTS", endpoints.xsts, jsonBody(buildJsonObject {
            put("Properties", buildJsonObject { put("SandboxId", "RETAIL"); put("UserTokens", JsonArray(listOf(JsonPrimitive(xbox.token)))) })
            put("RelyingParty", "rp://api.minecraftservices.com/"); put("TokenType", "JWT")
        })))
        val uhs = xsts.claims.xui.firstOrNull()?.uhs ?: throw AccountError.InvalidResponse("XSTS")
        val minecraft = decode<MinecraftToken>("Minecraft", request("Minecraft", endpoints.minecraft, jsonBody(buildJsonObject {
            put("identityToken", "XBL3.0 x=$uhs;${xsts.token}")
        })))
        val entitlements = decode<JsonObject>("Entitlements", request("Entitlements", endpoints.entitlements, bearer = minecraft.accessToken))
        val items = entitlements["items"]?.jsonArray ?: throw AccountError.InvalidResponse("Entitlements")
        if (items.none { it.jsonObject["name"]?.jsonPrimitive?.content in setOf("game_minecraft", "product_minecraft") }) throw AccountError.NoMinecraft
        val profile = decode<Profile>("Profile", request("Profile", endpoints.profile, bearer = minecraft.accessToken))
        return Session(msa, minecraft, profile)
    }
    private inline fun <reified T> decode(stage: String, text: String): T = try {
        Kit.json.decodeFromString<T>(text)
    } catch (_: IllegalArgumentException) { throw AccountError.InvalidResponse(stage) }
    private fun form(): FormBody.Builder = FormBody.Builder().add("client_id", clientId).add("redirect_uri", redirectUri).add("scope", SCOPE)
    private fun jsonBody(value: JsonObject): RequestBody = Kit.json.encodeToString(value).toRequestBody("application/json".toMediaType())
    private suspend fun request(stage: String, url: String, body: RequestBody? = null, bearer: String? = null): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (body != null) post(body)
            if (bearer != null) header("Authorization", "Bearer $bearer")
        }.build()
        return http.newCall(request).await().use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                if (it.code == 403 && stage in setOf("Minecraft", "Entitlements", "Profile")) throw AccountError.AppNotApproved
                if (it.code == 404 && stage == "Profile") throw AccountError.NoMinecraft
                if (stage == "XSTS" || stage == "Xbox") {
                    val code = runCatching { Kit.json.parseToJsonElement(text).jsonObject["XErr"]?.jsonPrimitive?.longOrNull }.getOrNull()
                    when (code) {
                        2148916233L -> throw AccountError.NoXboxAccount
                        2148916238L -> throw AccountError.ChildAccount
                        else -> if (code != null) throw AccountError.XboxDenied(code)
                    }
                }
                // Never put provider response bodies (which may contain credentials) in exceptions.
                throw AccountError.Http(stage, it.code)
            }
            text
        }
    }
    companion object { const val SCOPE = "XboxLive.signin offline_access" }
}
