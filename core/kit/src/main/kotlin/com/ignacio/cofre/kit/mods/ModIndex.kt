package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/** Instance-local identities. The files, including disabled jars, remain the source of truth. */
class ModIndex(val instance: File) {
    @Serializable data class Entry(val projectId: String, val versionId: String, val source: String = "modrinth", val channel: VersionChannel = VersionChannel.Release, val installedDirectly: Boolean = true, val sha1: String? = null)
    val file: File get() = File(instance, "cofre-mods.json")
    fun read(): Map<String, Entry> = if (file.isFile) Kit.json.decodeFromString<Map<String, Entry>>(file.readText()).also { index -> index.keys.forEach(::safeFilename) } else emptyMap()
    fun write(entries: Map<String, Entry>) {
        entries.keys.forEach(::safeFilename); instance.mkdirs()
        val temp = File(instance, "cofre-mods.json.tmp")
        FileOutputStream(temp).use { it.write(Kit.json.encodeToString(entries).toByteArray()); it.fd.sync() }
        DownloadQueue.atomicMove(temp, file)
    }
    fun jars(): List<File> = File(instance, "mods").listFiles()?.filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled")) }?.sortedBy { it.name }.orEmpty()
    fun installed(): List<InstalledMod> {
        val entries = read()
        return jars().map { jar ->
            val metadata = ModJarMetadata.read(jar); val entry = entries[jar.name]
            InstalledMod(jar.name, metadata?.name ?: jar.name.removeSuffix(".disabled").removeSuffix(".jar"), entry?.projectId, entry?.versionId, metadata?.version,
                metadata?.modIds.orEmpty(), !jar.name.endsWith(".disabled"), metadata?.dependencies.orEmpty(), entry?.installedDirectly ?: true, channel = entry?.channel ?: VersionChannel.Release)
        }
    }
    suspend fun identifyUnknown(client: Modrinth): Map<String, Entry> = withContext(Dispatchers.IO) {
        ModInstaller.lock(instance).lock()
        try {
            val entries = read().toMutableMap(); val jars = jars()
            val hashes = jars.associateWith { Hashes.sha1(it) }
            hashes.forEach { (jar, sha) -> entries[jar.name]?.takeIf { it.sha1 == null }?.let { entries[jar.name] = it.copy(sha1 = sha) } }
            val unknown = hashes.filter { (jar, sha) -> entries[jar.name]?.sha1 != sha }
            val found = unknown.values.distinct().chunked(500).flatMap { client.versionFiles(it).entries }.associate { it.key to it.value }
            for ((jar, hash) in unknown) {
                entries.remove(jar.name)
                val version = found[hash] ?: continue
                entries[jar.name] = Entry(version.projectId, version.id, channel = VersionChannel.from(version.versionType), sha1 = hash)
            }
            entries.keys.retainAll(jars.map { it.name }.toSet()); write(entries); entries
        } finally { ModInstaller.lock(instance).unlock() }
    }
}
/** A single file name (no folders, no `..`): mod files from APIs and indexes go through this before touching disk. */
fun safeFilename(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' || it == ':' }) { "Unsafe mod filename" }
}
