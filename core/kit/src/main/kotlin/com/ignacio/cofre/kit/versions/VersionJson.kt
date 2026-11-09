package com.ignacio.cofre.kit.versions

import com.ignacio.cofre.kit.common.Kit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable data class Artifact(val url: String = "", val sha1: String? = null, val size: Long? = null, val path: String? = null, val id: String? = null)
@Serializable data class Rule(val action: String, val os: Os? = null, val features: Map<String, Boolean> = emptyMap()) {
    @Serializable data class Os(val name: String? = null, val arch: String? = null, val version: String? = null, val versionRange: VersionRange? = null)
    @Serializable data class VersionRange(val min: String? = null, val max: String? = null)
}

/** Mojang's ordered rules: no rules permits, otherwise the last matching rule wins. */
data class RuleContext(val osName: String = "linux", val arch: String = "arm64", val osVersion: String = "", val features: Map<String, Boolean> = emptyMap()) {
    fun allows(rules: List<Rule>): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            val os = rule.os
            if (os?.name != null && os.name != osName) continue
            if (os?.arch != null && !matches(os.arch, arch) && !(os.arch == "aarch64" && arch == "arm64")) continue
            if (os?.version != null && !matches(os.version, osVersion)) continue
            if (os?.versionRange != null) {
                val range = os.versionRange
                if (range.min != null && compareVersions(osVersion, range.min) < 0) continue
                if (range.max != null && compareVersions(osVersion, range.max) >= 0) continue
            }
            if (rule.features.any { (name, expected) -> (features[name] ?: false) != expected }) continue
            allowed = rule.action == "allow"
        }
        return allowed
    }
    private fun matches(pattern: String, value: String): Boolean = runCatching { Regex(pattern).containsMatchIn(value) }.getOrDefault(false)
    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }; val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) { val diff = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0); if (diff != 0) return diff }
        return 0
    }
}

@Serializable(with = Argument.Serializer::class)
sealed interface Argument {
    data class Text(val value: String) : Argument
    data class Conditional(val values: List<String>, val rules: List<Rule> = emptyList(), val single: Boolean = false) : Argument
    fun evaluate(context: RuleContext): List<String> = when (this) {
        is Text -> listOf(value)
        is Conditional -> if (context.allows(rules)) values else emptyList()
    }
    object Serializer : KSerializer<Argument> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
        override fun deserialize(decoder: Decoder): Argument {
            val input = decoder as JsonDecoder
            val element = input.decodeJsonElement()
            if (element is JsonPrimitive) return Text(element.jsonPrimitive.content)
            val obj = element.jsonObject
            val value = obj.getValue("value")
            return Conditional(if (value is JsonArray) value.map { it.jsonPrimitive.content } else listOf(value.jsonPrimitive.content),
                obj["rules"]?.let { input.json.decodeFromJsonElement<List<Rule>>(it) } ?: emptyList(), value is JsonPrimitive)
        }
        override fun serialize(encoder: Encoder, value: Argument) {
            val output = encoder as JsonEncoder
            val element = when (value) {
                is Text -> JsonPrimitive(value.value)
                is Conditional -> buildJsonObject {
                    if (value.rules.isNotEmpty()) put("rules", output.json.encodeToJsonElement(value.rules))
                    put("value", if (value.single) JsonPrimitive(value.values.single()) else JsonArray(value.values.map(::JsonPrimitive)))
                }
            }
            output.encodeJsonElement(element)
        }
    }
}

@Serializable data class Arguments(
    val game: List<Argument> = emptyList(), val jvm: List<Argument> = emptyList(),
    @kotlinx.serialization.SerialName("default-user-jvm") val defaultUserJvm: List<Argument> = emptyList(),
) {
    fun game(context: RuleContext = RuleContext()): List<String> = game.flatMap { it.evaluate(context) }
    fun jvm(context: RuleContext = RuleContext()): List<String> = jvm.flatMap { it.evaluate(context) }
}
@Serializable data class Library(
    val name: String, val downloads: Downloads? = null, val rules: List<Rule> = emptyList(),
    val natives: Map<String, String> = emptyMap(), val extract: Extract? = null,
    val url: String? = null, val checksums: List<String> = emptyList(),
    val clientreq: Boolean? = null, val serverreq: Boolean? = null,
) {
    @Serializable data class Downloads(val artifact: Artifact? = null, val classifiers: Map<String, Artifact> = emptyMap())
    @Serializable data class Extract(val exclude: List<String> = emptyList())
    /** Coordinate identity excluding version; keep separate native classifiers. */
    val key: String get() { val parts = name.split(':'); return parts.take(2).joinToString(":") + ":" + parts.drop(3).joinToString(":") + "@" + name.substringAfter('@', "jar") }
}
@Serializable data class AssetIndexReference(val id: String, val url: String, val sha1: String? = null, val size: Long? = null, val totalSize: Long? = null)
@Serializable data class AssetIndex(val objects: Map<String, Asset>, val virtual: Boolean = false, val map_to_resources: Boolean = false) {
    @Serializable data class Asset(val hash: String, val size: Long) {
        val path: String get() = "${hash.take(2)}/$hash"
        val url: String get() = "https://resources.download.minecraft.net/$path"
    }
}
@Serializable data class VersionJson(
    val id: String, val type: String? = null, val time: String? = null, val releaseTime: String? = null,
    val mainClass: String? = null, val minimumLauncherVersion: Int? = null, val complianceLevel: Int? = null,
    val inheritsFrom: String? = null, val jar: String? = null,
    val libraries: List<Library> = emptyList(), val arguments: Arguments? = null,
    val minecraftArguments: String? = null, val assetIndex: AssetIndexReference? = null, val assets: String? = null,
    val downloads: Map<String, Artifact> = emptyMap(), val javaVersion: JavaVersion? = null,
    val logging: Map<String, Logging> = emptyMap(),
) {
    @Serializable data class JavaVersion(val component: String? = null, val majorVersion: Int)
    @Serializable data class Logging(val argument: String, val file: Artifact, val type: String)

    /** Resolve one inheritance layer. Children override scalar fields and library coordinates; arguments append. */
    fun merge(parent: VersionJson): VersionJson {
        require(inheritsFrom == parent.id) { "Wrong version parent" }
        val args = if (arguments == null && parent.arguments == null) null else Arguments(
            parent.arguments?.game.orEmpty() + arguments?.game.orEmpty(),
            parent.arguments?.jvm.orEmpty() + arguments?.jvm.orEmpty(),
            parent.arguments?.defaultUserJvm.orEmpty() + arguments?.defaultUserJvm.orEmpty(),
        )
        return copy(type = type ?: parent.type, time = time ?: parent.time, releaseTime = releaseTime ?: parent.releaseTime,
            mainClass = mainClass ?: parent.mainClass, minimumLauncherVersion = minimumLauncherVersion ?: parent.minimumLauncherVersion,
            complianceLevel = complianceLevel ?: parent.complianceLevel, inheritsFrom = null, jar = jar ?: parent.jar ?: parent.id,
            libraries = (libraries + parent.libraries).distinctBy { it.key }, arguments = args,
            minecraftArguments = minecraftArguments ?: parent.minecraftArguments, assetIndex = assetIndex ?: parent.assetIndex,
            assets = assets ?: parent.assets, downloads = parent.downloads + downloads, javaVersion = javaVersion ?: parent.javaVersion,
            logging = parent.logging + logging)
    }
    companion object { fun decode(text: String): VersionJson = Kit.json.decodeFromString(text) }
}

@Serializable data class InstallProfile(
    val spec: Int = 1, val profile: String = "", val version: String, val minecraft: String,
    val json: String = "/version.json", val data: Map<String, Data> = emptyMap(),
    val processors: List<Processor> = emptyList(), val libraries: List<Library> = emptyList(),
    val serverJarPath: String? = null, val icon: String? = null, val logo: String? = null,
    val welcome: String? = null, val mirrorList: String? = null, val hideExtract: Boolean = false,
) {
    @Serializable data class Data(val client: String, val server: String)
    @Serializable data class Processor(val jar: String, val classpath: List<String> = emptyList(), val args: List<String> = emptyList(), val outputs: Map<String, String> = emptyMap(), val sides: List<String> = emptyList()) {
        fun runsOn(side: String): Boolean = sides.isEmpty() || side in sides
    }
}
