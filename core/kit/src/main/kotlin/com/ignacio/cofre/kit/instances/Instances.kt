package com.ignacio.cofre.kit.instances

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.common.Zip
import com.ignacio.cofre.kit.modfolders.ModFolderStore
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

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
    /** The mod folder this instance links its mods, resource packs and shader packs to (`ModFolderStore`). */
    val modFolder: String? = null,
)

class InstanceStore(val root: File) {
    companion object {
        /** Cofre's own files in an instance (trash, temp writes, legacy launch state): never exported, copied or shared. */
        fun isPrivate(path: String): Boolean = path.startsWith(".cofre")

        private val locks = ConcurrentHashMap<String, Mutex>()

        /**
         * The one owner of an instance folder: mod installs, toggles, trash, duplicate, export and launch take it, so
         * they never interleave. Not reentrant: never call something that takes it while holding it.
         */
        fun lock(directory: File): Mutex = locks.getOrPut(directory.canonicalPath) { Mutex() }

        /** A fresh folder id: [name] as a slug plus a random suffix. */
        fun newID(name: String): String {
            val base = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(60).ifEmpty { "instance" }
            return "$base-${UUID.randomUUID().toString().take(8)}"
        }
    }

    data class TrashToken(val folder: String)
    data class Deleted(val token: TrashToken, val instance: Instance)
    init { require(!Files.isSymbolicLink(root.toPath())); Files.createDirectories(root.toPath()) }

    fun directory(instance: Instance): File { validate(instance); return FileOps.child(root, instance.gameDirectory) }
    /** Every readable instance. A folder with a broken or copied `cofre.json` is skipped, so one bad folder never hides the rest. */
    fun list(): List<Instance> = root.listFiles().orEmpty().filter { !it.name.startsWith('.') && it.isDirectory && File(it, "cofre.json").isFile }.mapNotNull { runCatching { read(it) }.getOrNull() }.sortedBy { it.name.lowercase() }
    fun get(id: String): Instance = read(folder(id))

    /** [base] if no instance has that name yet, else "base 2", "base 3", … */
    fun uniqueName(base: String): String {
        val taken = list().map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
    }

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
            // Undo history and Cofre's own files stay with the source. Mod folder links are made again, pointing at
            // the same folder: never copied through.
            Files.createDirectories(dest.toPath())
            for (file in directory(old).listFiles().orEmpty().filter { !isPrivate(it.name) }) {
                val path = file.toPath()
                if (Files.isSymbolicLink(path) && file.name in ModFolderStore.CONTENT) Files.createSymbolicLink(dest.toPath().resolve(file.name), Files.readSymbolicLink(path))
                else FileOps.copy(file, File(dest, file.name))
            }
            if (!includeWorlds) FileOps.delete(File(dest, "saves"))
            if (!includeOptions) listOf("options.txt", "optionsof.txt").forEach { File(dest, it).delete() }
            // The copied metadata still names the source; replace only after validating the copy.
            FileOps.atomic(File(dest, "cofre.json"), Kit.json.encodeToString(next).toByteArray())
            return get(next.id)
        } catch (e: Exception) { runCatching { FileOps.delete(dest) }; throw e }
    }

    fun trash(id: String): TrashToken {
        val instance = get(id)
        val token = TrashToken(UUID.randomUUID().toString())
        FileOps.move(directory(instance), trashFolder(token))
        trashFolder(token).setLastModified(System.currentTimeMillis()) // the trash date, for purging
        return token
    }

    /** Empties Recently deleted (instances, and each instance's removed content and worlds) of what was trashed over [maxAgeDays] ago. */
    fun purgeOldTrash(maxAgeDays: Long = 30, now: Instant = Instant.now()) {
        val cutoff = now.minus(java.time.Duration.ofDays(maxAgeDays)).toEpochMilli()
        val bins = listOf(FileOps.child(root, ".cofre-trash/instances")) +
            list().flatMap { i -> listOf("content", "worlds").map { FileOps.child(directory(i), ".cofre-trash/$it") } }
        for (bin in bins) bin.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach(FileOps::delete)
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

    /**
     * A self-contained zip of the instance. With its mod [folder], the folder's real content and mod index take the
     * place of the instance's links, so importing it makes a new folder.
     */
    fun export(id: String, destination: File, includeWorlds: Boolean = true, folder: File? = null): File {
        val dir = directory(get(id))
        val content = ModFolderStore.CONTENT + ModFolderStore.INDEX
        fun tree(root: File, names: List<String>): List<Pair<String, File>> = names.map { FileOps.child(root, it) }.filter { it.exists() }
            .flatMap { listOf(it) + FileOps.tree(it) }.map { it.relativeTo(root).invariantSeparatorsPath to it }
        val own = dir.listFiles().orEmpty().map { it.name }.filter { folder == null || it !in content }.sorted()
        val files = (tree(dir, own) + if (folder != null) tree(folder, content) else emptyList())
            .filter { (p, _) -> !isPrivate(p) && (includeWorlds || (p != "saves" && !p.startsWith("saves/"))) }
        Zip.write(files, destination, listOfNotNull(dir, folder))
        return destination
    }
    fun import(archive: File, name: String? = null): Instance {
        val stage = FileOps.child(root, ".cofre-instance-${UUID.randomUUID()}")
        try {
            Zip.extract(archive, stage)
            val candidates = stage.listFiles().orEmpty().filter { it.isDirectory && File(it, "cofre.json").isFile }
            val source = if (File(stage, "cofre.json").isFile) stage else candidates.singleOrNull() ?: error("Archive contains no instance")
            val old = decode(FileOps.child(source, "cofre.json"))
            val newName = name ?: uniqueName(old.name)
            require(list().none { it.name == newName }) { "Duplicate name" }
            val id = newID(newName)
            // Its mods came in the zip, real files: the caller gives it a new folder (ModFolderMigration.adopt).
            val next = old.copy(id = id, gameDirectory = id, name = newName, favorite = false, lastPlayedAt = null, modFolder = null)
            FileOps.atomic(File(source, "cofre.json"), Kit.json.encodeToString(next).toByteArray())
            FileOps.move(source, directory(next))
            return next
        } finally { FileOps.delete(stage) }
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
    fun validate(instance: Instance) {
        require(instance.schema == 1) { "Unsupported instance schema: ${instance.schema}" }
        require(instance.name.isNotBlank() && instance.id.isNotBlank() && !instance.id.startsWith('.') && '/' !in instance.id)
        require(instance.gameDirectory == instance.id) { "Instance paths must be relative to instances root" }
        FileOps.child(root, instance.gameDirectory)
        require(instance.memoryMB == null || instance.memoryMB > 0)
        require(instance.lastGoodMemoryMB == null || instance.lastGoodMemoryMB > 0)
    }
}
