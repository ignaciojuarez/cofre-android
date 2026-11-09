package com.ignacio.cofre.kit.servercards

import com.ignacio.cofre.kit.servercards.ServerJoinError.Reason
import com.ignacio.cofre.kit.servers.MinecraftServerAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.net.URI
import java.net.URLDecoder

object URLPolicy {
    fun canonicalHost(host: String): String? = runCatching {
        val trimmed = host.trim().trimEnd('.')
        require(trimmed.isNotBlank() && '*' !in trimmed && trimmed.none { it in "/\\@?#\u0000" })
        IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).lowercase()
    }.getOrNull()
    fun validate(url: String, allowedHosts: List<String>): HttpUrl {
        val checked = url.toHttpUrlOrNull() ?: fail(Reason.InsecureURL)
        if (!checked.isHttps || checked.username.isNotEmpty() || checked.password.isNotEmpty() || URI(url).rawUserInfo != null) fail(Reason.InsecureURL)
        val host = canonicalHost(checked.host) ?: fail(Reason.InsecureURL)
        val allowed = allowedHosts.any { raw ->
            if ('*' in raw) return@any false
            val parts = raw.split(':'); if (parts.size !in 1..2) return@any false
            val name = canonicalHost(parts[0]) ?: return@any false
            val port = if (parts.size == 1) 443 else parts[1].toIntOrNull() ?: return@any false
            host == name && checked.port == port
        }
        if (!allowed) fail(Reason.HostNotAllowed, "$host:${checked.port}")
        return checked.newBuilder().host(host).build()
    }
    fun origin(url: String): String {
        val u = url.toHttpUrlOrNull() ?: fail(Reason.InsecureURL)
        val checked = validate(url, listOf(u.host + if (u.port == 443) "" else ":${u.port}"))
        return checked.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }
    /** Call for every redirect before fetching; never let the HTTP client follow unvalidated redirects. */
    fun redirect(from: String, location: String, allowedHosts: List<String>, count: Int): HttpUrl {
        if (count !in 1..3) fail(Reason.InvalidCard, "redirect limit")
        val current = validate(from, allowedHosts)
        return validate(current.resolve(location)?.toString() ?: fail(Reason.InsecureURL), allowedHosts)
    }
}

object Discovery {
    data class JoinLink(val cardURL: HttpUrl, val server: String?, val pin: String?)
    fun parse(link: String): JoinLink {
        val uri = try { URI(link) } catch (_: Exception) { fail(Reason.InvalidDeepLink) }
        if (!uri.scheme.equals("cofre", true) || !uri.host.equals("join", true) || uri.rawUserInfo != null || uri.port != -1) fail(Reason.InvalidDeepLink)
        val params = try { uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") } } catch (_: Exception) { fail(Reason.InvalidDeepLink) }
        if (params.map { it.first }.distinct().size != params.size) fail(Reason.InvalidDeepLink)
        val values = params.toMap(); val url = values["card"]?.toHttpUrlOrNull() ?: fail(Reason.InvalidDeepLink)
        if (!url.isHttps) fail(Reason.InvalidDeepLink)
        URLPolicy.validate(url.toString(), listOf(url.host + if (url.port == 443) "" else ":${url.port}"))
        return JoinLink(url, values["server"], values["pin"])
    }
    fun deepLink(link: String, card: ServerCard? = null): HttpUrl {
        val parsed = parse(link)
        if (card != null && parsed.server != null) {
            val address = MinecraftServerAddress.parse(parsed.server) ?: fail(Reason.InvalidDeepLink)
            if (card.server.addresses.none { MinecraftServerAddress(it.host, it.port).key == address.key }) fail(Reason.InvalidDeepLink)
        }
        return parsed.cardURL
    }
    fun wellKnown(host: String): HttpUrl {
        val name = URLPolicy.canonicalHost(host) ?: fail(Reason.InvalidDeepLink)
        return "https://$name/.well-known/minecraft-server-card.json".toHttpUrlOrNull() ?: fail(Reason.InvalidDeepLink)
    }
    fun verifyPin(card: VerifiedCard, pin: String?) {
        if (pin == null) return
        val key = base64url(card.envelope.publicKey)
        val sha = com.ignacio.cofre.kit.common.Hashes.sha256(key)
        if (pin != card.envelope.publicKey && !pin.equals(sha, true)) fail(Reason.KeyChanged)
    }
}
