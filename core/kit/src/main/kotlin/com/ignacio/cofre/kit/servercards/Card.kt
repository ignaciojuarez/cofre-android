package com.ignacio.cofre.kit.servercards

import com.ignacio.cofre.kit.common.Loader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ServerJoinError(val reason: Reason, detail: String = "") : IllegalArgumentException("$reason${if (detail.isEmpty()) "" else ": $detail"}") {
    enum class Reason { InvalidCard, InvalidEnvelope, InsecureURL, HostNotAllowed, SizeLimit, PathTraversal, InvalidDeepLink, SignatureInvalid, KeyChanged, HashMismatch }
}
@Serializable data class ServerAddress(val host: String, val port: Int)
@Serializable data class LoaderRequirement(val type: Loader, val version: String)
@Serializable data class PackRequirement(val type: String, val url: String, val sha512: String, val version: String, @SerialName("size_bytes") val sizeBytes: Long? = null)
@Serializable data class CardSecurity(val origin: String, @SerialName("allowed_hosts") val allowedHosts: List<String>, val trust: String, @SerialName("max_bytes") val maxBytes: Long)
@Serializable data class ContentItem(val id: String, val name: String? = null, val required: Boolean? = null, val sizeBytes: Long? = null)
@Serializable data class Replacement(val id: String, val with: String)
@Serializable data class PlatformOverride(val exclude: List<String> = emptyList(), val replace: List<Replacement> = emptyList(), val compatibility: Map<String, String>? = null)
@Serializable data class PlatformOverrides(val ios: PlatformOverride? = null, val android: PlatformOverride? = null)
@Serializable data class ServerCard(
    @SerialName("schema_version") val schemaVersion: String,
    val id: String,
    val server: Server,
    val game: Game,
    val loader: LoaderRequirement,
    val pack: PackRequirement,
    val content: Content? = null,
    @SerialName("recommended_ram_mb") val recommendedRAMMB: Int? = null,
    @SerialName("platform_overrides") val platformOverrides: PlatformOverrides? = null,
    @SerialName("update_policy") val updatePolicy: String,
    val security: CardSecurity,
    val changelog: String? = null,
) {
    @Serializable data class Server(val name: String, val addresses: List<ServerAddress>, val icon: String? = null, val network: String? = null)
    @Serializable data class Game(val version: String, @SerialName("java_version") val javaVersion: Int)
    @Serializable data class Content(val resourcePacks: List<ContentItem>? = null, val shaders: List<ContentItem>? = null, val clientMods: List<ContentItem>? = null, val optionalMods: List<ContentItem>? = null)
}
@Serializable data class CardEnvelope(val format: String, val payload: String, @SerialName("key_id") val keyID: String, @SerialName("public_key") val publicKey: String, val signature: String, @SerialName("previous_key_signature") val previousKeySignature: String? = null)

/** Only CardDecoder constructs verified cards. Payload bytes are the bytes the owner signed. */
class VerifiedCard internal constructor(val card: ServerCard, val envelope: CardEnvelope, payload: ByteArray) {
    private val bytes = payload.copyOf()
    val payload: ByteArray get() = bytes.copyOf()
}
