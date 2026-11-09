package com.ignacio.cofre.kit.curseforge

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.modrinth.HttpFailure
import com.ignacio.cofre.kit.modrinth.await
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The caller supplies its own API key at runtime. It is never persisted. */
class CurseForge(
    private val apiKey: String,
    private val http: OkHttpClient = Kit.http,
    private val baseUrl: HttpUrl = "https://api.curseforge.com/v1/".toHttpUrl(),
) {
    @Serializable data class Links(val websiteUrl: String = "", val wikiUrl: String? = null, val issuesUrl: String? = null, val sourceUrl: String? = null)
    @Serializable data class Image(val id: Int = 0, val url: String, val thumbnailUrl: String? = null, val title: String? = null, val description: String? = null)
    @Serializable data class Mod(val id: Int, val name: String, val slug: String = "", val summary: String = "", val links: Links = Links(), val logo: Image? = null, val screenshots: List<Image> = emptyList())
    @Serializable data class Hash(val value: String, val algo: Int)
    @Serializable data class Dependency(val modId: Int, val relationType: Int)
    @Serializable data class File(
        val id: Int, val modId: Int, val displayName: String = "", val fileName: String,
        val releaseType: Int = 1, val fileLength: Long = 0, val downloadUrl: String? = null,
        val hashes: List<Hash> = emptyList(), val gameVersions: List<String> = emptyList(),
        val dependencies: List<Dependency> = emptyList(), val fileDate: String? = null,
    )
    sealed interface Download {
        data class Available(val url: String) : Download
        data class Disabled(val websiteUrl: String) : Download
    }
    @Serializable private data class Envelope<T>(val data: T)

    suspend fun search(query: String, gameVersion: String? = null, neoForge: Boolean = true, index: Int = 0, pageSize: Int = 50): List<Mod> {
        require(index >= 0 && pageSize in 1..50)
        val url = endpoint("mods", "search").newBuilder().addQueryParameter("gameId", "432")
            .addQueryParameter("searchFilter", query).addQueryParameter("index", index.toString()).addQueryParameter("pageSize", pageSize.toString())
        if (gameVersion != null) url.addQueryParameter("gameVersion", gameVersion)
        if (neoForge) url.addQueryParameter("modLoaderType", "6")
        return Kit.json.decodeFromString<Envelope<List<Mod>>>(get(url.build())).data
    }
    suspend fun mod(id: Int): Mod = Kit.json.decodeFromString<Envelope<Mod>>(get(endpoint("mods", id.toString()))).data
    suspend fun files(modId: Int, gameVersion: String? = null, neoForge: Boolean = true, index: Int = 0): List<File> {
        val url = endpoint("mods", "$modId", "files").newBuilder().addQueryParameter("index", "$index").addQueryParameter("pageSize", "50")
        if (gameVersion != null) url.addQueryParameter("gameVersion", gameVersion)
        if (neoForge) url.addQueryParameter("modLoaderType", "6")
        return Kit.json.decodeFromString<Envelope<List<File>>>(get(url.build())).data
    }
    suspend fun file(modId: Int, fileId: Int): File = Kit.json.decodeFromString<Envelope<File>>(get(endpoint("mods", "$modId", "files", "$fileId"))).data
    suspend fun description(modId: Int): String = Kit.json.decodeFromString<Envelope<String>>(get(endpoint("mods", "$modId", "description"))).data
    suspend fun changelog(modId: Int, fileId: Int): String = Kit.json.decodeFromString<Envelope<String>>(get(endpoint("mods", "$modId", "files", "$fileId", "changelog"))).data
    suspend fun downloadUrl(modId: Int, fileId: Int, websiteUrl: String): Download {
        val url = Kit.json.decodeFromString<Envelope<String?>>(get(endpoint("mods", "$modId", "files", "$fileId", "download-url"))).data
        return url?.takeIf { it.isNotBlank() }?.let { Download.Available(it) } ?: Download.Disabled(websiteUrl)
    }
    fun download(file: File, websiteUrl: String): Download = file.downloadUrl?.takeIf { it.isNotBlank() }?.let { Download.Available(it) } ?: Download.Disabled(websiteUrl)
    private fun endpoint(vararg segments: String): HttpUrl = baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
    private suspend fun get(url: HttpUrl): String {
        require(apiKey.isNotBlank()) { "A CurseForge API key is required" }
        return http.newCall(Request.Builder().url(url).header("x-api-key", apiKey).build()).await().use {
            if (!it.isSuccessful) throw HttpFailure(it.code)
            it.body.string()
        }
    }
}
