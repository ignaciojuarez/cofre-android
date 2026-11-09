package com.ignacio.cofre.kit.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mod loaders. Cofre for Android creates NeoForge 26.2 instances; the others are only recognised (imports, mod files). */
@Serializable
enum class Loader(val displayName: String) {
    @SerialName("vanilla") Vanilla("Vanilla"),
    @SerialName("neoforge") NeoForge("NeoForge"),
    @SerialName("fabric") Fabric("Fabric"),
    @SerialName("quilt") Quilt("Quilt"),
    @SerialName("forge") Forge("Forge"),
    @SerialName("unknown") Unknown("Unknown");

    /** The id Modrinth uses in `loaders` facets and version files. */
    val modrinthId: String get() = name.lowercase()

    companion object {
        fun from(id: String?): Loader = entries.firstOrNull { it.modrinthId == id?.lowercase() } ?: Unknown
    }
}

/** What a project or installed file is. The folder is Minecraft's own, inside the instance (data packs: inside each world). */
@Serializable
enum class ContentKind(val folderName: String?) {
    @SerialName("mods") Mods("mods"),
    @SerialName("shaders") Shaders("shaderpacks"),
    @SerialName("resourcePacks") ResourcePacks("resourcepacks"),
    @SerialName("dataPacks") DataPacks("datapacks"),
    @SerialName("modpacks") Modpacks(null),
}

/** The only game and loader line Cofre for Android targets. See PLAN.md. */
object Target {
    const val GAME_VERSION = "26.2"
    /** 26.2's network protocol, for the server status handshake (PrismarineJS minecraft-data `protocolVersions.json`). */
    const val PROTOCOL_VERSION = 776
    const val JAVA_MAJOR = 25
    val LOADER = Loader.NeoForge
}
