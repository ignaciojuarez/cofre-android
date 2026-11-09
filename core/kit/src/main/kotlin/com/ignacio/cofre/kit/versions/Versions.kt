package com.ignacio.cofre.kit.versions

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.modrinth.HttpFailure
import com.ignacio.cofre.kit.modrinth.await
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable data class VersionManifest(val versions: List<Entry>, val latest: Latest? = null) {
    @Serializable data class Latest(val release: String, val snapshot: String)
    @Serializable data class Entry(val id: String, val type: String, val url: String? = null, val sha1: String? = null, val time: String? = null, val releaseTime: String? = null, val complianceLevel: Int? = null)
    fun entry(id: String): Entry? = versions.firstOrNull { it.id == id }
    companion object { const val URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" }
}

class Versions(private val http: OkHttpClient = Kit.http) {
    suspend fun manifest(): VersionManifest = Kit.json.decodeFromString(get(VersionManifest.URL))
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
    val installerUrl: String get() = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$version/neoforge-$version-installer.jar"
    companion object {
        const val METADATA_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        fun parseMetadata(xml: String): List<NeoForgeVersion> = Regex("<version>\\s*(26\\.2\\.0\\.[0-9]+(?:-beta)?)\\s*</version>").findAll(xml)
            .map { NeoForgeVersion(it.groupValues[1]) }.distinct().sortedByDescending { it.version.removeSuffix("-beta").substringAfterLast('.').toInt() }.toList()
    }
}
