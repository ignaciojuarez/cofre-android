package com.ignacio.cofre.kit.versions

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.modrinth.HttpFailure
import com.ignacio.cofre.kit.modrinth.await
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable data class VersionManifest(val versions: List<Entry>, val latest: Latest? = null) {
    @Serializable data class Latest(val release: String, val snapshot: String)
    @Serializable data class Entry(val id: String, val type: String, val url: String? = null, val sha1: String? = null, val time: String? = null, val releaseTime: String? = null, val complianceLevel: Int? = null) {
        enum class Kind { Release, Snapshot, Old }
        val kind: Kind get() = when (type) { "release" -> Kind.Release; "snapshot" -> Kind.Snapshot; else -> Kind.Old }
    }
    fun entry(id: String): Entry? = versions.firstOrNull { it.id == id }
    fun versions(kind: Entry.Kind): List<Entry> = versions.filter { it.kind == kind }
    companion object { const val URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" }
}

class Versions(private val http: OkHttpClient = Kit.http) {
    suspend fun manifest(): VersionManifest = Kit.json.decodeFromString(get(VersionManifest.URL))
    suspend fun version(entry: VersionManifest.Entry): VersionJson = VersionJson.decode(get(requireNotNull(entry.url)))
    suspend fun assetIndex(reference: AssetIndexReference): AssetIndex = Kit.json.decodeFromString(get(reference.url))
    /** Resolve recursively, rejecting malformed cycles before fetching a parent twice. */
    suspend fun resolve(version: VersionJson, parent: suspend (String) -> VersionJson): VersionJson {
        val seen = mutableSetOf<String>()
        suspend fun visit(current: VersionJson): VersionJson {
            require(seen.add(current.id)) { "Version inheritance cycle" }
            return current.inheritsFrom?.let { id -> current.merge(visit(parent(id))) } ?: current
        }
        return visit(version)
    }
    suspend fun neoForgeVersions(): List<NeoForgeVersion> = NeoForgeVersion.parseMetadata(get(NeoForgeVersion.METADATA_URL))
    private suspend fun get(url: String): String = http.newCall(Request.Builder().url(url).build()).await().use {
        if (!it.isSuccessful) throw HttpFailure(it.code)
        it.body.string()
    }
}

@Serializable data class NeoForgeVersion(val version: String) {
    init { require(version.matches(Regex("26\\.2\\.0\\.[0-9]+(?:-beta)?"))) }
    val beta: Boolean get() = version.endsWith("-beta")
    val installerUrl: String get() = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar"
    companion object {
        const val METADATA_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        fun parseMetadata(xml: String): List<NeoForgeVersion> = Regex("<version>\\s*(26\\.2\\.0\\.[0-9]+(?:-beta)?)\\s*</version>").findAll(xml)
            .map { NeoForgeVersion(it.groupValues[1]) }.distinct().sortedByDescending { it.version.removeSuffix("-beta").substringAfterLast('.').toInt() }.toList()
    }
}

/** A disk estimate, not a launch plan; native replacement is the runtime's responsibility. */
data class VersionDownloads(val clientBytes: Long = 0, val libraryBytes: Long = 0, val assetBytes: Long = 0) {
    val totalBytes: Long get() = clientBytes + libraryBytes + assetBytes
    companion object {
        fun missing(version: VersionJson, root: File, context: RuleContext = RuleContext()): VersionDownloads {
            fun has(path: String): Boolean = File(root, path).isFile
            return VersionDownloads(
                if (has("versions/${version.jar ?: version.id}/${version.jar ?: version.id}.jar")) 0 else version.downloads["client"]?.size ?: 0,
                version.libraries.filter { context.allows(it.rules) }.flatMap { library ->
                    listOfNotNull(library.downloads?.artifact) + library.natives[context.osName]?.let { classifier -> library.downloads?.classifiers?.get(classifier.replace("\${arch}", if (context.arch.contains("64")) "64" else "32")) }.let(::listOfNotNull)
                }.distinctBy { it.path }.filter { it.path != null && !has("libraries/${it.path}") }.sumOf { it.size ?: 0 },
                version.assetIndex?.let { if (has("assets/indexes/${it.id}.json")) 0 else it.totalSize ?: 0 } ?: 0,
            )
        }
        suspend fun estimate(root: File, id: String = Target.GAME_VERSION, load: suspend (String) -> String): VersionDownloads {
            val local = File(root, "versions/$id/$id.json")
            val version = if (local.isFile) VersionJson.decode(local.readText()) else {
                val entry = Kit.json.decodeFromString<VersionManifest>(load(VersionManifest.URL)).entry(id) ?: error("Version missing from manifest")
                VersionJson.decode(load(requireNotNull(entry.url)))
            }
            return missing(version, root)
        }
    }
}
