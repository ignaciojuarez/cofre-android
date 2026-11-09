package com.ignacio.cofre.kit.servercards

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.servercards.ServerJoinError.Reason
import com.ignacio.cofre.kit.servers.MinecraftServerAddress
import kotlinx.serialization.json.*
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.util.Base64

internal fun fail(reason: Reason, detail: String = ""): Nothing = throw ServerJoinError(reason, detail)
internal fun base64url(value: String): ByteArray {
    if (!value.matches(Regex("[A-Za-z0-9_-]+"))) fail(Reason.InvalidEnvelope, "base64url")
    val bytes = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) { fail(Reason.InvalidEnvelope, "base64url") }
    if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) fail(Reason.InvalidEnvelope, "noncanonical base64url")
    return bytes
}

object CardDecoder {
    const val MAX_BYTES: Int = 512 * 1024
    fun decodeEnvelope(bytes: ByteArray): VerifiedCard {
        if (bytes.size > MAX_BYTES) fail(Reason.SizeLimit)
        val envelope = try { Kit.json.decodeFromString<CardEnvelope>(bytes.decodeToString(throwOnInvalidSequence = true)) } catch (_: Exception) { fail(Reason.InvalidEnvelope) }
        if (envelope.format != "cofre-card-envelope/1" || envelope.keyID.isBlank() || envelope.publicKey.length != 43 || envelope.signature.length != 86 || envelope.previousKeySignature?.length?.let { it != 86 } == true) fail(Reason.InvalidEnvelope, "format/key/signature")
        val payload = base64url(envelope.payload)
        verify(envelope, payload)
        return VerifiedCard(decodeCard(payload), envelope, payload)
    }
    fun verify(envelope: CardEnvelope, payload: ByteArray) {
        if (payload.size > MAX_BYTES) fail(Reason.SizeLimit)
        val key = base64url(envelope.publicKey); val sig = base64url(envelope.signature)
        if (key.size != 32 || sig.size != 64 || !Ed25519.verify(sig, 0, key, 0, payload, 0, payload.size)) fail(Reason.SignatureInvalid)
    }
    fun decodeCard(bytes: ByteArray): ServerCard {
        if (bytes.size > MAX_BYTES) fail(Reason.SizeLimit)
        val obj = try { Kit.json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject } catch (_: Exception) { fail(Reason.InvalidCard, "JSON") }
        val allowed = setOf("schema_version", "id", "server", "game", "loader", "pack", "content", "recommended_ram_mb", "min_launcher_version", "platform_overrides", "changelog", "update_policy", "security", "signing")
        if (obj.keys.any { it !in allowed }) fail(Reason.InvalidCard, "unknown field")
        val card = try { Kit.json.decodeFromJsonElement<ServerCard>(obj) } catch (_: Exception) { fail(Reason.InvalidCard, "missing or malformed field") }
        if (card.schemaVersion != "1.0.0" || !card.id.matches(Regex("[a-z0-9][a-z0-9.-]{0,62}"))) fail(Reason.InvalidCard, "schema/id")
        if (card.server.name.isBlank() || card.server.addresses.isEmpty() || card.server.addresses.any { it.port !in 1..65535 || MinecraftServerAddress.parse(if (':' in it.host) "[${it.host}]:${it.port}" else "${it.host}:${it.port}") == null }) fail(Reason.InvalidCard, "server address")
        if (card.game.version.isBlank() || card.game.javaVersion <= 0 || card.loader.type == Loader.Unknown || card.loader.version.isBlank()) fail(Reason.InvalidCard, "game/loader")
        if (card.security.allowedHosts.isEmpty() || card.security.allowedHosts.any { '*' in it }) fail(Reason.InvalidCard, "wildcard or missing hosts")
        URLPolicy.validate(card.security.origin, card.security.allowedHosts)
        URLPolicy.validate(card.pack.url, card.security.allowedHosts)
        if (!card.pack.sha512.matches(Regex("[A-Fa-f0-9]{128}")) || card.security.maxBytes !in 1..2_147_483_648L || (card.pack.sizeBytes ?: card.security.maxBytes) !in 1..card.security.maxBytes) fail(Reason.InvalidCard, "pack hash/size")
        for (override in listOfNotNull(card.platformOverrides?.ios, card.platformOverrides?.android)) {
            (override.exclude + override.replace.flatMap { listOf(it.id, it.with) }).forEach(::validateOverride)
        }
        return card
    }
    private fun validateOverride(path: String) {
        // mod: identifiers are part of the portable card format, not filesystem paths.
        if (path.isBlank() || path.contains("..") || path.startsWith('/') || path.contains('\\') || '\u0000' in path || Regex("^[A-Za-z]:/").containsMatchIn(path)) fail(Reason.PathTraversal, path)
    }
}
