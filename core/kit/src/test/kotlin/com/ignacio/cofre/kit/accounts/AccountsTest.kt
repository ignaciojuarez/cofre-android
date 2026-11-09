package com.ignacio.cofre.kit.accounts

import com.ignacio.cofre.kit.common.Kit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class AccountsTest {
    @TempDir lateinit var root: File
    private val msa = MinecraftAccounts.MsaToken("msa-secret", "refresh-secret", 3600)
    private fun client(server: MockWebServer): MinecraftAccounts = MinecraftAccounts("caller-client", "cofre://oauth", endpoints = MinecraftAccounts.Endpoints(
        authorize = server.url("/authorize").toString(), token = server.url("/token").toString(), xbox = server.url("/xbox").toString(), xsts = server.url("/xsts").toString(), minecraft = server.url("/login").toString(), entitlements = server.url("/entitlements").toString(), profile = server.url("/profile").toString(),
    ))
    private fun xbox(token: String): String = """{"Token":"$token","DisplayClaims":{"xui":[{"uhs":"user-hash"}]}}"""
    private fun successChain(server: MockWebServer, entitlement: String = "game_minecraft") {
        listOf(xbox("xbox-secret"), xbox("xsts-secret"), """{"access_token":"minecraft-secret","expires_in":86400}""", """{"items":[{"name":"$entitlement"}]}""", """{"id":"uuid","name":"Player"}""").forEach { server.enqueue(MockResponse(body = it)) }
    }
    @Test fun pkceMatchesRfc7636Vector() {
        val pkce = Pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
        assertEquals(43, Pkce().verifier.length); assertNotEquals(Pkce().verifier, Pkce().verifier)
        assertFailsWith<IllegalArgumentException> { Pkce("short") }
    }
    @Test fun authorizeUrlIncludesStateAndS256() {
        val url = MinecraftAccounts("client", "cofre://oauth").authorizeUrl(Pkce(), "csrf-state")
        assertEquals("login.live.com", url.host); assertEquals("csrf-state", url.queryParameter("state"))
        assertEquals("S256", url.queryParameter("code_challenge_method")); assertEquals("cofre://oauth", url.queryParameter("redirect_uri"))
        assertEquals("XboxLive.signin offline_access", url.queryParameter("scope"))
    }
    @Test fun codeExchangeUsesPkceWithoutSecret() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = """{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""))
            val pkce = Pkce(); assertEquals("access", client(server).exchangeCode("auth-code", pkce).accessToken)
            val body = server.takeRequest().body!!.utf8()
            assertTrue(body.contains("grant_type=authorization_code")); assertTrue(body.contains("code_verifier=${pkce.verifier}")); assertFalse(body.contains("client_secret"))
        }
    }
    @Test fun refreshKeepsPreviousRefreshTokenWhenNotRotated() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = """{"access_token":"new","expires_in":3600}"""))
            val token = client(server).refresh("previous")
            assertEquals("previous", token.refreshToken)
            val body = server.takeRequest().body!!.utf8(); assertTrue(body.contains("grant_type=refresh_token")); assertFalse(body.contains("code_verifier"))
        }
    }
    @Test fun completeChainUsesMinecraftRelyingPartyAndBearer() = runBlocking {
        MockWebServer().use { server ->
            server.start(); successChain(server)
            val session = client(server).minecraft(msa); assertEquals("Player", session.profile.name)
            val xbox = Kit.json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("d=msa-secret", xbox.getValue("Properties").jsonObject.getValue("RpsTicket").jsonPrimitive.content)
            val xsts = Kit.json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("rp://api.minecraftservices.com/", xsts.getValue("RelyingParty").jsonPrimitive.content)
            val login = Kit.json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("XBL3.0 x=user-hash;xsts-secret", login.getValue("identityToken").jsonPrimitive.content)
            assertEquals("Bearer minecraft-secret", server.takeRequest().headers["Authorization"])
            assertEquals("Bearer minecraft-secret", server.takeRequest().headers["Authorization"])
            assertFalse(session.toString().contains("secret")); assertFalse(session.msa.toString().contains("secret")); assertFalse(session.minecraft.toString().contains("secret"))
        }
    }
    @Test fun xboxMissingAccountIsTyped() = runBlocking { assertXboxError(2148916233L, AccountError.NoXboxAccount) }
    @Test fun childAccountIsTyped() = runBlocking { assertXboxError(2148916238L, AccountError.ChildAccount) }
    private suspend fun assertXboxError(code: Long, expected: AccountError) {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = xbox("xbox"))); server.enqueue(MockResponse(code = 401, body = """{"XErr":$code}"""))
            assertEquals(expected, assertFailsWith<AccountError> { client(server).minecraft(msa) })
        }
    }
    @Test fun noMinecraftEntitlementIsTyped() = runBlocking {
        MockWebServer().use { server ->
            server.start(); successChain(server, "unrelated")
            assertEquals(AccountError.NoMinecraft, assertFailsWith<AccountError> { client(server).minecraft(msa) }); assertEquals(4, server.requestCount)
        }
    }
    @Test fun unapprovedClient403IsTyped() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = xbox("xbox"))); server.enqueue(MockResponse(body = xbox("xsts"))); server.enqueue(MockResponse(code = 403, body = "secret-do-not-log"))
            assertEquals(AccountError.AppNotApproved, assertFailsWith<AccountError> { client(server).minecraft(msa) })
        }
    }
    @Test fun providerFailureDoesNotExposeResponseOrTokens() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(code = 400, body = "contains-secret"))
            val error = assertFailsWith<AccountError.Http> { client(server).refresh("refresh-secret") }
            assertFalse(error.toString().contains("secret"))
        }
    }
    @Test fun accountListAndRemovalPortIosBehavior() {
        val dir = File(root, "accounts"); dir.mkdirs()
        File(dir, "ready.json").writeText("""{"accountId":"ready","username":"Ready","xuid":"1","expiresAt":1800000000}""")
        File(dir, "old.json").writeText("""{"accountId":"old","username":"Old","expiresAt":1600000000}""")
        File(dir, "other.json").writeText("""{"accountId":"other","username":"Other"}""")
        var selected: String? = "old"
        val store = AccountStore(root, { selected }, { selected = it })
        assertEquals("Microsoft", store.list().first { it.id == "old" }.kind) // an expired token still means signed in
        store.remove("old"); assertEquals("other", selected); assertFalse(File(dir, "old.json").exists())
        store.select("missing"); assertEquals("other", selected); store.select("ready"); assertEquals("ready", selected)
        assertNull(store.remove("../outside"))
    }
    @Test fun savedSummariesListBackWithoutTokens() {
        val store = AccountStore(root)
        store.save("0123abcd", "Steve", microsoft = true, expiresAt = 1_800_000_000_000)
        store.save("dev-offline", "Dev", microsoft = false)
        assertEquals(listOf("Dev" to "Offline", "Steve" to "Microsoft"), store.list().map { it.name to it.kind })
        store.save("0123abcd", "Alex", microsoft = true)
        assertEquals("Alex", store.list().single { it.id == "0123abcd" }.name)
        assertEquals(setOf("0123abcd.json", "dev-offline.json"), File(root, "accounts").list()!!.toSet())
    }
}
