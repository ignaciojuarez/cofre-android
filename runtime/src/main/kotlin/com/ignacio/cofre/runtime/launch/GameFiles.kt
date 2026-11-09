package com.ignacio.cofre.runtime.launch

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.versions.AssetIndex
import com.ignacio.cofre.kit.versions.Library
import com.ignacio.cofre.kit.versions.RuleContext
import com.ignacio.cofre.kit.versions.VersionJson
import com.ignacio.cofre.kit.versions.Versions
import java.io.File
import java.io.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The shared game store under [root] (`filesDir/minecraft`): `versions/<id>/<id>.{json,jar}`,
 * `libraries/` in Maven layout and `assets/{indexes,objects}`. Instances only own their game dir.
 *
 * Downloads go through kit's [DownloadQueue] with every hash Mojang or Maven publishes.
 * LWJGL's desktop natives are never downloaded: ours come from the natives release.
 */
class GameFiles(val root: File, private val http: OkHttpClient = Kit.http) {
    val libraries = File(root, "libraries")
    val assets = File(root, "assets")
    val versions = File(root, "versions")

    /** What [install] (or the NeoForge installer) is doing; the UI shows it. */
    data class Progress(val step: String = "", val files: Int = 0, val totalFiles: Int = 0, val bytes: Long = 0, val totalBytes: Long = 0)

    private val mutableProgress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = mutableProgress.asStateFlow()

    internal fun step(step: String) {
        mutableProgress.value = Progress(step)
    }

    fun versionJson(id: String) = File(versions, "$id/$id.json")
    fun clientJar(version: VersionJson): File = (version.jar ?: version.id).let { File(versions, "$it/$it.jar") }
    fun library(coordinates: String) = File(libraries, mavenPath(coordinates))

    /** The version with its parents merged. Vanilla JSONs come from Mojang's manifest the first time. */
    suspend fun resolve(id: String): VersionJson = Versions(http).resolve(read(id)) { read(it) }

    private suspend fun read(id: String): VersionJson {
        val file = versionJson(id)
        if (!file.isFile) {
            val entry = Versions(http).manifest().entry(id) ?: throw IOException("Minecraft $id isn't in Mojang's version list")
            download("Version $id", listOf(DownloadItem(requireNotNull(entry.url), file.path, sha1 = entry.sha1)))
        }
        return VersionJson.decode(file.readText())
    }

    /** Downloads the client jar, libraries and (with [withAssets]) assets of [id]; returns it merged. */
    suspend fun install(id: String, withAssets: Boolean = true): VersionJson {
        step("Reading Minecraft $id")
        val version = resolve(id)
        val client = version.downloads["client"] ?: throw IOException("$id has no client download")
        download("Libraries", listOf(DownloadItem(client.url, clientJar(version).path, size = client.size, sha1 = client.sha1)) + libraryDownloads(version.libraries))
        if (withAssets) installAssets(version)
        step("Ready")
        return version
    }

    private suspend fun installAssets(version: VersionJson) {
        val reference = version.assetIndex ?: return
        val index = File(assets, "indexes/${reference.id}.json")
        download("Asset index", listOf(DownloadItem(reference.url, index.path, size = reference.size, sha1 = reference.sha1)))
        val objects = Kit.json.decodeFromString(AssetIndex.serializer(), index.readText()).objects.values.distinctBy { it.hash }
        download("Assets", objects.map { DownloadItem(it.url, File(assets, "objects/${it.path}").path, size = it.size, sha1 = it.hash) })
    }

    /** Download items for these libraries (rules and natives filtered by [select]). */
    fun libraryDownloads(libraries: List<Library>): List<DownloadItem> = select(libraries).mapNotNull { library ->
        val artifact = library.downloads?.artifact
        val path = artifact?.path ?: mavenPath(library.name)
        val url = artifact?.url?.takeIf { it.isNotEmpty() } ?: if (artifact == null) (library.url ?: MOJANG_LIBRARIES) + path else null
        url?.let { DownloadItem(it, File(this.libraries, path).path, size = artifact?.size, sha1 = artifact?.sha1) }
    }

    /** The JVM classpath: selected libraries, then the client jar. */
    fun classpath(version: VersionJson): List<File> =
        (select(version.libraries).map { File(libraries, it.downloads?.artifact?.path ?: mavenPath(it.name)) } + clientJar(version)).distinct()

    /**
     * Runs [items] through kit's queue. Files already on disk with the right size are skipped;
     * the rest are fetched in chunks, because the queue rewrites its journal on every change.
     */
    suspend fun download(step: String, items: List<DownloadItem>) {
        val missing = items.distinctBy { it.destination }.filter { item ->
            val file = File(item.destination)
            !file.isFile || (item.size != null && file.length() != item.size)
        }
        val totalBytes = missing.sumOf { it.size ?: 0 }
        var doneFiles = 0
        var doneBytes = 0L
        mutableProgress.value = Progress(step, 0, missing.size, 0, totalBytes)
        val journal = File(root, ".downloads/queue.json")
        for (chunk in missing.chunked(CHUNK)) {
            journal.delete()
            val queue = DownloadQueue(journal, http, concurrency = 8)
            chunk.forEach { queue.enqueue(it) }
            val result = coroutineScope {
                val watcher = launch {
                    queue.progress.collect { p -> mutableProgress.value = Progress(step, doneFiles + p.files, missing.size, doneBytes + p.bytes, totalBytes) }
                }
                queue.run().also { watcher.cancel() }
            }
            if (result !is DownloadQueue.Result.Completed) {
                val failed = queue.progress.value.items.firstOrNull { it.state == DownloadItem.State.Failed }
                throw IOException("Couldn't download ${failed?.title ?: "game files"}: ${failed?.error ?: result}")
            }
            doneFiles += chunk.size
            doneBytes += chunk.sumOf { it.size ?: 0 }
        }
        journal.delete()
        mutableProgress.value = Progress(step, missing.size, missing.size, totalBytes, totalBytes)
    }

    companion object {
        /** Cofre is a Linux arm64 machine as far as Mojang's rules go. */
        val rules = RuleContext(osName = "linux", arch = "arm64")
        private const val MOJANG_LIBRARIES = "https://libraries.minecraft.net/"
        private const val CHUNK = 256

        /** Libraries the rules allow, minus desktop natives (`natives-*`, x86 builds). */
        fun select(libraries: List<Library>): List<Library> = libraries.filter { library ->
            val classifier = library.name.substringBefore('@').split(':').getOrNull(3).orEmpty()
            rules.allows(library.rules) && !classifier.startsWith("natives-") && "x86" !in classifier
        }

        /** `group:artifact:version[:classifier][@ext]` → `group/path/artifact/version/artifact-version[-classifier].ext`. */
        fun mavenPath(coordinates: String): String {
            val ext = coordinates.substringAfter('@', "jar")
            val parts = coordinates.substringBefore('@').split(':')
            require(parts.size in 3..4) { "Not Maven coordinates: $coordinates" }
            val (group, artifact, version) = parts
            val classifier = parts.getOrNull(3)?.let { "-$it" }.orEmpty()
            return "${group.replace('.', '/')}/$artifact/$version/$artifact-$version$classifier.$ext"
        }
    }
}
