package com.ignacio.cofre.kit.instances

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.modpacks.FileOps
import com.ignacio.cofre.kit.modpacks.Zip
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/** cofre.json schema 1: gameDirectory is always relative to InstanceStore.root. */
@Serializable
data class Instance(
    val id: String,
    val name: String,
    val gameDirectory: String = id,
    val schema: Int = 1,
    val gameVersion: String = Target.GAME_VERSION,
    val loader: Loader = Target.LOADER,
    val loaderVersion: String? = null,
    val memoryMB: Int? = null,
    val autoMemory: Boolean = true,
    val jvmArguments: String = "",
    val lastGoodMemoryMB: Int? = null,
    val lastGoodAutoMemory: Boolean = true,
    val lastGoodJVMArguments: String = "",
    val notes: String = "",
    val group: String? = null,
    val favorite: Boolean = false,
    val icon: String? = null,
    /** Epoch milliseconds of the last launch, for "continue playing". */
    val lastPlayedAt: Long? = null,
)

class InstanceStore(val root: File) {
    data class TrashToken(val folder: String)
    data class Deleted(val token: TrashToken, val instance: Instance)
    init { require(!Files.isSymbolicLink(root.toPath())); Files.createDirectories(root.toPath()) }

    fun directory(instance: Instance): File { validate(instance); return FileOps.child(root, instance.gameDirectory) }
    fun list(): List<Instance> = root.listFiles().orEmpty().filter { !it.name.startsWith('.') && it.isDirectory && File(it, "cofre.json").isFile }.map { read(it) }.sortedBy { it.name.lowercase() }
    fun get(id: String): Instance = read(folder(id))

    fun create(name: String, gameVersion: String = Target.GAME_VERSION, loader: Loader = Target.LOADER, loaderVersion: String? = null): Instance {
        require(name.isNotBlank())
        require(list().none { it.name == name }) { "An instance with that name already exists" }
        val instance = Instance(newID(name), name, gameVersion = gameVersion, loader = loader, loaderVersion = loaderVersion)
        save(instance)
        return instance
    }

    fun save(instance: Instance): Instance {
        validate(instance)
        val dir = directory(instance)
        if (File(dir, "cofre.json").exists()) read(dir) // Never overwrite an unsupported schema.
        FileOps.atomic(FileOps.child(dir, "cofre.json"), Kit.json.encodeToString(instance).toByteArray())
        return instance
    }

    fun rename(id: String, name: String): Instance {
        require(name.isNotBlank() && list().none { it.id != id && it.name == name })
        return save(get(id).copy(name = name))
    }
    fun favorite(id: String, value: Boolean): Instance = save(get(id).copy(favorite = value))
    fun group(id: String, value: String?): Instance = save(get(id).copy(group = value))
    fun notes(id: String, value: String): Instance = save(get(id).copy(notes = value))
    fun loaderVersion(id: String, value: String): Instance = save(get(id).copy(loaderVersion = value))
    fun groups(): List<String> = list().mapNotNull { it.group }.distinct().sorted()
    fun markPlayed(id: String, at: Instant = Instant.now()): Instance = save(get(id).copy(lastPlayedAt = at.toEpochMilli()))
    fun lastPlayed(): Instance? = list().filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt!! }

    fun runtime(id: String, memoryMB: Int?, jvmArguments: String, autoMemory: Boolean = memoryMB == null): Instance {
        val old = get(id)
        return save(old.copy(memoryMB = memoryMB, autoMemory = autoMemory, jvmArguments = jvmArguments))
    }
    fun markLastGood(id: String): Instance = get(id).let { save(it.copy(lastGoodMemoryMB = it.memoryMB, lastGoodAutoMemory = it.autoMemory, lastGoodJVMArguments = it.jvmArguments)) }
    fun rollbackRuntime(id: String): Instance = get(id).let { save(it.copy(memoryMB = it.lastGoodMemoryMB, autoMemory = it.lastGoodAutoMemory, jvmArguments = it.lastGoodJVMArguments)) }

    fun duplicate(id: String, name: String = "${get(id).name} copy", includeWorlds: Boolean = true, includeOptions: Boolean = true): Instance {
        require(list().none { it.name == name }) { "Duplicate name" }
        val old = get(id)
        val nextID = newID(name)
        val next = old.copy(id = nextID, gameDirectory = nextID, name = name, favorite = false, lastPlayedAt = null)
        val dest = directory(next)
        try {
            FileOps.copy(directory(old), dest)
            FileOps.delete(File(dest, ".cofre-trash")) // Undo history stays with the source.
            if (!includeWorlds) File(dest, "saves").deleteRecursively()
            if (!includeOptions) listOf("options.txt", "optionsof.txt").forEach { File(dest, it).delete() }
            // The copied metadata still names the source; replace only after validating the copy.
            FileOps.atomic(File(dest, "cofre.json"), Kit.json.encodeToString(next).toByteArray())
            return get(next.id)
        } catch (e: Exception) { dest.deleteRecursively(); throw e }
    }

    fun trash(id: String): TrashToken {
        val instance = get(id)
        val token = TrashToken(UUID.randomUUID().toString())
        FileOps.move(directory(instance), trashFolder(token))
        return token
    }
    fun recentlyDeleted(): List<Deleted> {
        val bin = FileOps.child(root, ".cofre-trash/instances")
        return bin.listFiles().orEmpty().sortedByDescending { it.lastModified() }.map { f ->
            val token = TrashToken(f.name)
            Deleted(token, decode(FileOps.child(trashFolder(token), "cofre.json")))
        }
    }
    fun restore(token: TrashToken): Instance {
        val source = trashFolder(token)
        val old = decode(FileOps.child(source, "cofre.json"))
        val id = if (folder(old.id).exists()) newID(old.name) else old.id
        val instance = old.copy(id = id, gameDirectory = id)
        val dest = directory(instance)
        FileOps.move(source, dest)
        try { FileOps.atomic(File(dest, "cofre.json"), Kit.json.encodeToString(instance).toByteArray()) }
        catch (e: Exception) { FileOps.move(dest, source); throw e }
        return instance
    }

    /** Permanently deletes a trashed instance. */
    fun purge(token: TrashToken) { val folder = trashFolder(token); require(folder.isDirectory) { "Not in Recently deleted" }; FileOps.delete(folder) }
    /** Permanently deletes an instance, skipping the trash. Used to roll back failed installs and imports. */
    fun remove(id: String) { FileOps.delete(directory(get(id))) }

    fun export(id: String, destination: File, includeWorlds: Boolean = true): File {
        Zip.write(directory(get(id)), destination) { p -> !p.startsWith(".cofre-trash") && (includeWorlds || (p != "saves" && !p.startsWith("saves/"))) }
        return destination
    }
    fun import(archive: File, name: String? = null): Instance {
        val stage = FileOps.child(root, ".cofre-instance-${UUID.randomUUID()}")
        try {
            Zip.extract(archive, stage)
            val candidates = stage.listFiles().orEmpty().filter { it.isDirectory && File(it, "cofre.json").isFile }
            val source = if (File(stage, "cofre.json").isFile) stage else candidates.singleOrNull() ?: error("Archive contains no instance")
            val old = decode(FileOps.child(source, "cofre.json"))
            val newName = name ?: old.name
            require(list().none { it.name == newName }) { "Duplicate name" }
            val id = newID(newName)
            val next = old.copy(id = id, gameDirectory = id, name = newName, favorite = false, lastPlayedAt = null)
            FileOps.atomic(File(source, "cofre.json"), Kit.json.encodeToString(next).toByteArray())
            FileOps.move(source, directory(next))
            return next
        } finally { stage.deleteRecursively() }
    }

    private fun folder(id: String): File { require('/' !in id && !id.startsWith('.')); return FileOps.child(root, id) }
    private fun trashFolder(token: TrashToken): File { require(token.folder.matches(Regex("[a-f0-9-]{36}"))); return FileOps.child(root, ".cofre-trash/instances/${token.folder}") }
    private fun read(dir: File): Instance {
        FileOps.child(root, dir.relativeTo(root).invariantSeparatorsPath)
        val instance = decode(FileOps.child(dir, "cofre.json"))
        require(instance.id == dir.name && directory(instance).absoluteFile == dir.absoluteFile) { "Instance path does not match folder" }
        return instance
    }
    private fun decode(file: File): Instance = Kit.json.decodeFromString<Instance>(file.readText()).also(::validate)
    private fun validate(instance: Instance) {
        require(instance.schema == 1) { "Unsupported instance schema: ${instance.schema}" }
        require(instance.name.isNotBlank() && instance.id.isNotBlank() && !instance.id.startsWith('.') && '/' !in instance.id)
        require(instance.gameDirectory == instance.id) { "Instance paths must be relative to instances root" }
        FileOps.child(root, instance.gameDirectory)
        require(instance.memoryMB == null || instance.memoryMB > 0)
        require(instance.lastGoodMemoryMB == null || instance.lastGoodMemoryMB > 0)
    }
    private fun newID(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(60).ifEmpty { "instance" }
        return "$base-${UUID.randomUUID().toString().take(8)}"
    }
}
