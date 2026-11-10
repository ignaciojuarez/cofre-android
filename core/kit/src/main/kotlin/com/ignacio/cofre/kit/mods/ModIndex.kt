package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Instance-local identities. The files, including disabled jars, remain the source of truth. Entries are keyed by
 * the jar's [key], so turning a mod off and on keeps its identity.
 */
class ModIndex(val instance: File) {
    @Serializable data class Entry(val projectId: String, val versionId: String, val source: String = "modrinth", val channel: VersionChannel = VersionChannel.Release, val installedDirectly: Boolean = true, val sha1: String? = null, val pinned: Boolean = false)
    val file: File get() = File(instance, "cofre-mods.json")
    fun read(): Map<String, Entry> = if (file.isFile) Kit.json.decodeFromString<Map<String, Entry>>(file.readText()).onEach { safeFilename(it.key) }.mapKeys { key(it.key) } else emptyMap()
    fun write(entries: Map<String, Entry>) {
        entries.keys.forEach(::safeFilename)
        FileOps.atomic(file, Kit.json.encodeToString(entries).toByteArray())
    }
    fun jars(): List<File> = File(instance, "mods").listFiles()?.filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled")) }?.sortedBy { it.name }.orEmpty()
    fun installed(): List<InstalledMod> {
        val entries = read()
        return jars().map { jar ->
            val metadata = ModJarMetadata.read(jar); val entry = entries[key(jar.name)]
            InstalledMod(jar.name, metadata?.name ?: jar.name.removeSuffix(".disabled").removeSuffix(".jar"), entry?.projectId, entry?.versionId, metadata?.version,
                metadata?.modIds.orEmpty(), !jar.name.endsWith(".disabled"), metadata?.dependencies.orEmpty(), entry?.installedDirectly ?: true, channel = entry?.channel ?: VersionChannel.Release)
        }
    }
    suspend fun identifyUnknown(client: Modrinth): Map<String, Entry> = withContext(Dispatchers.IO) {
        InstanceStore.lock(instance).lock()
        try {
            val entries = read().toMutableMap()
            val hashes = jars().associate { key(it.name) to Hashes.sha1(it) }
            hashes.forEach { (name, sha) -> entries[name]?.takeIf { it.sha1 == null }?.let { entries[name] = it.copy(sha1 = sha) } }
            val unknown = hashes.filter { (name, sha) -> entries[name]?.sha1 != sha }
            val found = unknown.values.distinct().chunked(500).flatMap { client.versionFiles(it).entries }.associate { it.key to it.value }
            for ((name, hash) in unknown) {
                entries.remove(name)
                val version = found[hash] ?: continue
                entries[name] = Entry(version.projectId, version.id, channel = VersionChannel.from(version.versionType), sha1 = hash)
            }
            entries.keys.retainAll(hashes.keys); write(entries); entries
        } finally { InstanceStore.lock(instance).unlock() }
    }
    /**
     * Pins (or unpins) the jar with index [key]: [ModUpdates.check] skips pinned jars. False when the index doesn't know
     * the jar (run [identifyUnknown] first).
     */
    suspend fun pin(key: String, pinned: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        InstanceStore.lock(instance).withLock {
            val entries = read(); val name = key(key)
            val entry = entries[name] ?: return@withLock false
            if (entry.pinned != pinned) write(entries + (name to entry.copy(pinned = pinned)))
            true
        }
    }
    companion object {
        /** The index key of a jar in `mods/`: its name without `.disabled`. */
        fun key(jar: String): String = jar.removeSuffix(".disabled")
    }
}
/** A single file name (no folders, no `..`): mod files from APIs and indexes go through this before touching disk. */
fun safeFilename(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' || it == ':' }) { "Unsafe mod filename" }
}
