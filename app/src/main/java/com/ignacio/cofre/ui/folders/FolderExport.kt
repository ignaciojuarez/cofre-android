package com.ignacio.cofre.ui.folders

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Zip
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * A mod folder as a Modrinth pack (`.mrpack`): jars and packs Modrinth knows (by SHA-1, one request) are listed in
 * `modrinth.index.json` with their CDN download; everything else goes in `overrides/`. Turned-off jars are left out,
 * since a pack can't say "off". Cofre's own files (`.cofre*`, the mod index) never leave.
 */
object FolderExport {
    private val parts = listOf("mods", "resourcepacks", "shaderpacks")

    suspend fun write(dir: File, folder: ModFolder, loaderVersion: String?, modrinth: Modrinth, destination: File) {
        val files = withContext(Dispatchers.IO) {
            parts.flatMap { part ->
                File(dir, part).listFiles().orEmpty()
                    .filter { it.isFile && !it.name.startsWith('.') && !it.name.endsWith(".disabled") }
                    .sortedBy { it.name }
                    .map { "$part/${it.name}" to it }
            }
        }
        val sha1 = withContext(Dispatchers.IO) { files.associate { (path, file) -> path to Hashes.sha1(file) } }
        // Offline or refused: everything becomes an override, which still makes a working pack.
        val known = try {
            sha1.values.distinct().chunked(500).flatMap { modrinth.versionFiles(it).entries }.associate { it.key to it.value }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        withContext(Dispatchers.IO) {
            val listed = mutableListOf<Pair<String, Modrinth.File>>()
            val overrides = mutableListOf<Pair<String, File>>()
            for ((path, file) in files) {
                val hash = sha1.getValue(path)
                val remote = known[hash]?.files?.firstOrNull { it.hashes["sha1"] == hash && it.url != null && it.hashes["sha512"] != null }
                if (remote != null) listed += path to remote else overrides += "overrides/$path" to file
            }
            val index = buildJsonObject {
                put("formatVersion", 1)
                put("game", "minecraft")
                put("versionId", "1.0.0")
                put("name", folder.name)
                putJsonArray("files") {
                    for ((path, remote) in listed) addJsonObject {
                        put("path", path)
                        putJsonObject("hashes") {
                            put("sha1", remote.hashes.getValue("sha1"))
                            put("sha512", remote.hashes.getValue("sha512"))
                        }
                        putJsonArray("downloads") { add(remote.url) }
                        put("fileSize", remote.size)
                    }
                }
                putJsonObject("dependencies") {
                    put("minecraft", folder.gameVersion)
                    dependency(folder.loader)?.let { key -> loaderVersion?.let { put(key, it) } }
                }
            }
            val json = File(destination.absoluteFile.parentFile!!.apply { mkdirs() }, ".cofre-index-${java.util.UUID.randomUUID()}.json")
            json.writeText(Kit.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), index))
            try {
                Zip.write(listOf("modrinth.index.json" to json) + overrides, destination, listOf(dir))
            } finally {
                json.delete()
            }
        }
    }

    /** The `dependencies` key Modrinth packs use for each loader. */
    fun dependency(loader: Loader): String? = when (loader) {
        Loader.NeoForge -> "neoforge"
        Loader.Fabric -> "fabric-loader"
        Loader.Quilt -> "quilt-loader"
        Loader.Forge -> "forge"
        else -> null
    }
}
