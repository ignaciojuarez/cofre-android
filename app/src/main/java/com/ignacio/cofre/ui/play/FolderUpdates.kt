package com.ignacio.cofre.ui.play

import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.mods.ModUpdate
import com.ignacio.cofre.kit.mods.ModUpdates
import com.ignacio.cofre.kit.mods.check
import com.ignacio.cofre.onIo
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A mod folder's available updates, cached on disk per folder: Home asks once when it shows a folder, never per
 * recomposition. The cache holds while the folder's jars stay the same and for [MAX_AGE_MS]. Offline, the last
 * answer stays (with its time) and nothing is reported as an error.
 */
object FolderUpdates {
    data class Result(val updates: List<ModUpdate>, val checkedAt: Long?, val offline: Boolean = false)

    @Serializable
    private data class Cached(val checkedAt: Long, val jars: String, val updates: List<ModUpdate>)

    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** The cached answer when it's still good, else a fresh Modrinth check ([force] skips the cache). */
    suspend fun get(graph: AppGraph, folder: ModFolder, force: Boolean = false): Result {
        val dir = graph.onIo { graph.modFolders.directory(folder) }
        val jars = graph.onIo { fingerprint(dir) }
        val cached = graph.onIo { read(graph, folder.id) }
        val fresh = cached != null && cached.jars == jars && System.currentTimeMillis() - cached.checkedAt < MAX_AGE_MS
        if (fresh && !force) return Result(cached.updates, cached.checkedAt)
        return try {
            val updates = ModUpdates.check(dir, graph.modrinth, folder.gameVersion, folder.loader, folder.includeBeta)
            graph.onIo { save(graph, folder.id, jars, updates) }
            Result(updates, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result(cached?.updates.orEmpty().takeIf { cached?.jars == jars }.orEmpty(), cached?.checkedAt, offline = true)
        }
    }

    /** Drops [folderId]'s cached answer (after updating or rolling back its mods). */
    fun forget(graph: AppGraph, folderId: String) {
        runCatching { file(graph, folderId).delete() }
    }

    /** Stores [updates] as [folder]'s current answer. Tests use it to stay off the network. */
    fun save(graph: AppGraph, folder: ModFolder, updates: List<ModUpdate>) =
        save(graph, folder.id, fingerprint(graph.modFolders.directory(folder)), updates)

    private fun save(graph: AppGraph, folderId: String, jars: String, updates: List<ModUpdate>) {
        val file = file(graph, folderId).apply { parentFile?.mkdirs() }
        FileOps.atomic(file, Kit.json.encodeToString(Cached(System.currentTimeMillis(), jars, updates)).toByteArray())
    }

    private fun read(graph: AppGraph, folderId: String): Cached? =
        runCatching { Kit.json.decodeFromString<Cached>(file(graph, folderId).readText()) }.getOrNull()

    /** Names, sizes and times of the folder's jars: any install, removal or toggle changes it. */
    private fun fingerprint(dir: File): String =
        File(dir, "mods").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
            .joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }.hashCode().toString()

    private fun file(graph: AppGraph, folderId: String): File = File(graph.context.cacheDir, "mods/updates/$folderId.json")
}
