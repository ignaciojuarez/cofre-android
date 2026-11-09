package com.ignacio.cofre.kit.modrinth

import com.ignacio.cofre.kit.common.Kit
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class Modrinth(
    private val http: OkHttpClient = Kit.http,
    private val baseUrl: HttpUrl = "https://api.modrinth.com/v2/".toHttpUrl(),
) {
    @Serializable
    data class SearchHit(
        @SerialName("project_id") val projectId: String,
        val slug: String? = null, val title: String, val description: String = "",
        @SerialName("project_type") val projectType: String = "mod",
        val categories: List<String> = emptyList(), val downloads: Long = 0,
        val versions: List<String> = emptyList(),
        @SerialName("icon_url") val icon: String? = null,
        @SerialName("date_modified") val dateModified: String? = null,
    ) {
        val iconUrl: String? get() = icon?.takeIf { it.isNotBlank() }
        val updated: java.time.Instant? get() = dateModified?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        val pageUrl: String? get() = slug?.let { "https://modrinth.com/$projectType/$it" }
    }
    @Serializable
    data class Project(
        val id: String, val title: String, val slug: String? = null, val description: String = "",
        @SerialName("project_type") val projectType: String = "mod",
        @SerialName("icon_url") val icon: String? = null, val body: String? = null,
        val license: License? = null, val gallery: List<Shot> = emptyList(),
        @SerialName("source_url") val sourceUrl: String? = null,
        @SerialName("issues_url") val issuesUrl: String? = null,
        @SerialName("wiki_url") val wikiUrl: String? = null,
        @SerialName("discord_url") val discordUrl: String? = null,
        @SerialName("donation_urls") val donations: List<Donation> = emptyList(),
    )
    @Serializable data class License(val id: String, val name: String? = null)
    @Serializable data class Donation(val url: String, val platform: String? = null)
    @Serializable data class Shot(
        val url: String, @SerialName("raw_url") val rawUrl: String? = null,
        val featured: Boolean = false, val title: String? = null,
        val description: String? = null, val ordering: Int = 0,
    )
    @Serializable
    data class Version(
        val id: String, @SerialName("project_id") val projectId: String,
        val name: String = "", @SerialName("version_number") val versionNumber: String = "",
        @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
        val loaders: List<String> = emptyList(),
        @SerialName("version_type") val versionType: String = "release",
        val dependencies: List<Dependency> = emptyList(), val files: List<File> = emptyList(),
        val changelog: String? = null, @SerialName("date_published") val datePublished: String? = null,
    ) {
        val primaryFile: File? get() = files.firstOrNull { it.primary } ?: files.firstOrNull()
    }
    @Serializable data class Dependency(
        @SerialName("version_id") val versionId: String? = null,
        @SerialName("project_id") val projectId: String? = null,
        @SerialName("dependency_type") val dependencyType: String,
    )
    @Serializable data class File(
        val hashes: Map<String, String> = emptyMap(), val url: String? = null,
        val filename: String, val size: Long = 0, val primary: Boolean = false,
    )
    @Serializable private data class SearchResponse(val hits: List<SearchHit>)
    @Serializable private data class HashRequest(val hashes: List<String>, val algorithm: String)
    data class RateLimit(val limit: Int?, val remaining: Int?, val resetAtMillis: Long)

    private val lock = Any()
    private val cache = LinkedHashMap<String, Pair<Long, String>>()
    @Volatile var rateLimit: RateLimit? = null
        private set

    suspend fun search(query: String, facets: List<List<String>> = emptyList(), index: String = "relevance", offset: Int = 0, limit: Int = 50): List<SearchHit> {
        require(offset >= 0 && limit in 1..100)
        val url = endpoint("search").newBuilder().addQueryParameter("query", query)
            .addQueryParameter("index", index).addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
        if (facets.isNotEmpty()) url.addQueryParameter("facets", Kit.json.encodeToString(facets))
        return Kit.json.decodeFromString<SearchResponse>(request(url.build())).hits
    }
    suspend fun project(id: String): Project = Kit.json.decodeFromString(request(endpoint("project", id)))
    suspend fun version(id: String): Version = Kit.json.decodeFromString(request(endpoint("version", id)))
    suspend fun versions(projectId: String, loaders: List<String> = emptyList(), gameVersions: List<String> = emptyList(), includeChangelog: Boolean = true): List<Version> =
        Kit.json.decodeFromString(request(versionsUrl(projectId, loaders, gameVersions, includeChangelog)))

    fun versionsUrl(projectId: String, loaders: List<String> = emptyList(), gameVersions: List<String> = emptyList(), includeChangelog: Boolean = true): HttpUrl {
        val url = endpoint("project", projectId, "version").newBuilder()
        if (loaders.isNotEmpty()) url.addQueryParameter("loaders", Kit.json.encodeToString(loaders))
        if (gameVersions.isNotEmpty()) url.addQueryParameter("game_versions", Kit.json.encodeToString(gameVersions))
        if (!includeChangelog) url.addQueryParameter("include_changelog", "false")
        return url.build()
    }
    suspend fun matchingFirst(projectId: String, loaders: List<String>, gameVersions: List<String>): List<Version> =
        mergeVersions(versions(projectId, loaders, gameVersions), versions(projectId, includeChangelog = false))

    suspend fun versionFile(hash: String, algorithm: String = "sha1"): Version? {
        require(algorithm == "sha1" || algorithm == "sha512")
        return try {
            Kit.json.decodeFromString(request(endpoint("version_file", hash).newBuilder().addQueryParameter("algorithm", algorithm).build()))
        } catch (e: HttpFailure) { if (e.status == 404) null else throw e }
    }
    suspend fun versionFiles(hashes: List<String>, algorithm: String = "sha1"): Map<String, Version> {
        require(algorithm == "sha1" || algorithm == "sha512")
        if (hashes.isEmpty()) return emptyMap()
        return Kit.json.decodeFromString(request(endpoint("version_files"), Kit.json.encodeToString(HashRequest(hashes, algorithm))))
    }
    suspend fun projects(ids: List<String>): List<Project> {
        if (ids.isEmpty()) return emptyList()
        // Modrinth v2 bulk projects is GET with a JSON array query parameter.
        return Kit.json.decodeFromString(request(endpoint("projects").newBuilder().addQueryParameter("ids", Kit.json.encodeToString(ids)).build()))
    }
    private fun endpoint(vararg segments: String): HttpUrl = baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private suspend fun request(url: HttpUrl, body: String? = null): String {
        val key = url.toString()
        if (body == null) synchronized(lock) { cache[key]?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.second }?.let { return it }
        repeat(3) { attempt ->
            val rate = rateLimit
            if (rate?.remaining == 0) delay((rate.resetAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
            val request = Request.Builder().url(url).apply {
                if (body != null) post(body.toRequestBody("application/json".toMediaType()))
            }.build()
            val response = http.newCall(request).await()
            response.use {
                val reset = it.header("X-Ratelimit-Reset")?.toDoubleOrNull()
                rateLimit = RateLimit(it.header("X-Ratelimit-Limit")?.toIntOrNull(), it.header("X-Ratelimit-Remaining")?.toIntOrNull(), System.currentTimeMillis() + ((reset ?: 0.0) * 1000).toLong())
                if (it.code == 429 && attempt < 2) {
                    val seconds = it.header("Retry-After")?.toDoubleOrNull() ?: reset ?: 1.0
                    rateLimit = rateLimit?.copy(remaining = 0, resetAtMillis = System.currentTimeMillis() + (seconds * 1000).toLong())
                } else {
                    if (!it.isSuccessful) throw HttpFailure(it.code)
                    val text = it.body.string()
                    if (body == null) synchronized(lock) {
                        cache[key] = System.currentTimeMillis() to text
                        if (cache.size > 128) cache.remove(cache.keys.first())
                    }
                    return text
                }
            }
        }
        throw HttpFailure(429)
    }
    companion object {
        fun mergeVersions(matching: List<Version>, all: List<Version>): List<Version> = (matching + all).distinctBy { it.id }
    }
}
