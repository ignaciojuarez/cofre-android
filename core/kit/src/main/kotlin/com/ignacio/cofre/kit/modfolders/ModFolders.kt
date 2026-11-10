package com.ignacio.cofre.kit.modfolders

import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.options.Storage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
enum class AutoUpdate { @SerialName("off") Off, @SerialName("notify") Notify, @SerialName("auto") Auto }

/**
 * A mod folder's `.cofre-folder.json`, schema 1. Mods live here, outside instances: an instance links to one folder,
 * several instances can share it, and deleting an instance never deletes its folder.
 */
@Serializable
data class ModFolder(
    val id: String,
    val name: String,
    val schema: Int = 1,
    val gameVersion: String = Target.GAME_VERSION,
    val loader: Loader = Target.LOADER,
    val loaderVersion: String? = null,
    val autoUpdate: AutoUpdate = AutoUpdate.Notify,
    val includeBeta: Boolean = false,
    val icon: String? = null,
    /** The folder or instance id this was made from (convert, duplicate, migration). */
    val createdFrom: String? = null,
    /** Epoch milliseconds. */
    val createdAt: Long? = null,
) {
    /** Cofre can run it today: the one game version and loader it targets. */
    val playable: Boolean get() = gameVersion == Target.GAME_VERSION && loader == Target.LOADER

    /** Same game version and loader as [instance], so it can be linked to it. */
    fun fits(instance: Instance): Boolean = gameVersion == instance.gameVersion && loader == instance.loader
}

/**
 * Mod folders under [root], mirroring [InstanceStore]: slug ids, atomic metadata, a schema check, checked paths and a
 * trash. Like instances, callers hold `InstanceStore.lock(directory(folder))` while they change a folder's content;
 * instances sharing a folder share that lock.
 */
class ModFolderStore(val root: File) {
    companion object {
        /** What lives in a folder, linked from each instance that uses it. Data packs stay in worlds; `config/` stays with the instance. */
        val CONTENT: List<String> = listOf("mods", "resourcepacks", "shaderpacks")
        /** The mods' identities (ModIndex), kept with the jars. */
        const val INDEX = "cofre-mods.json"
        private const val META = ".cofre-folder.json"
        private const val NEW = ".cofre-new-"

        fun newID(name: String): String {
            val base = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(60).ifEmpty { "mods" }
            return "$base-${UUID.randomUUID().toString().take(8)}"
        }
    }

    data class TrashToken(val folder: String)
    data class Deleted(val token: TrashToken, val folder: ModFolder)

    init { require(!Files.isSymbolicLink(root.toPath())); Files.createDirectories(root.toPath()) }

    fun list(): List<ModFolder> = root.listFiles().orEmpty()
        .filter { !it.name.startsWith('.') && it.isDirectory && !Files.isSymbolicLink(it.toPath()) && File(it, META).isFile }
        .mapNotNull { runCatching { read(it) }.getOrNull() }.sortedBy { it.name.lowercase() }
    fun get(id: String): ModFolder = read(folder(id))
    fun directory(folder: ModFolder): File { validate(folder); return FileOps.child(root, folder.id) }

    fun uniqueName(base: String): String {
        val taken = list().map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
    }

    fun create(name: String, gameVersion: String = Target.GAME_VERSION, loader: Loader = Target.LOADER, loaderVersion: String? = null, createdFrom: String? = null): ModFolder {
        val folder = fresh(name, gameVersion, loader, loaderVersion, createdFrom)
        make(folder) { }
        return folder
    }

    /**
     * A new folder with [source]'s content folders and mod index (another folder's directory, or an instance's not yet
     * moved to a folder). Never Cofre's own `.cofre*` files, never through a link. Built aside, then moved in whole.
     */
    fun copy(source: File, name: String, gameVersion: String, loader: Loader, loaderVersion: String?, createdFrom: String?): ModFolder {
        val folder = fresh(name, gameVersion, loader, loaderVersion, createdFrom)
        make(folder) { dir ->
            for (part in CONTENT + INDEX) {
                val from = FileOps.child(source, part)
                if (from.exists()) FileOps.copy(from, File(dir, part))
            }
        }
        return folder
    }

    /** A copy of folder [id] under [name], with its settings. */
    fun duplicate(id: String, name: String): ModFolder {
        val old = get(id)
        val made = copy(directory(old), name, old.gameVersion, old.loader, old.loaderVersion, createdFrom = old.id)
        return save(made.copy(autoUpdate = old.autoUpdate, includeBeta = old.includeBeta, icon = old.icon))
    }

    fun save(folder: ModFolder): ModFolder {
        validate(folder)
        val dir = directory(folder)
        if (File(dir, META).exists()) read(dir) // Never overwrite an unsupported schema.
        FileOps.atomic(FileOps.child(dir, META), Kit.json.encodeToString(folder).toByteArray())
        return folder
    }

    /** Only the name changes: the id, and so every instance's link, stays. */
    fun rename(id: String, name: String): ModFolder {
        require(name.isNotBlank() && list().none { it.id != id && it.name == name }) { "A mod folder with that name already exists" }
        return save(get(id).copy(name = name))
    }

    fun usedBy(id: String, instances: List<Instance>): List<Instance> = instances.filter { it.modFolder == id }
    fun size(id: String): Long = Storage.bytes(directory(get(id)))

    /** Moves the folder to Recently deleted. Instances linked to it can't play until it's restored. */
    fun trash(id: String): TrashToken {
        val folder = get(id)
        val token = TrashToken(UUID.randomUUID().toString())
        FileOps.move(directory(folder), trashFolder(token))
        trashFolder(token).setLastModified(System.currentTimeMillis()) // the trash date, for purging
        return token
    }

    /** Back under its own id (so links work again), or a new one if that id is taken. */
    fun restore(token: TrashToken): ModFolder {
        val source = trashFolder(token)
        val old = decode(FileOps.child(source, META))
        val folder = if (folder(old.id).exists()) old.copy(id = newID(old.name)) else old
        val dest = directory(folder)
        FileOps.move(source, dest)
        try { FileOps.atomic(File(dest, META), Kit.json.encodeToString(folder).toByteArray()) }
        catch (e: Exception) { FileOps.move(dest, source); throw e }
        return folder
    }

    fun purge(token: TrashToken) { val folder = trashFolder(token); require(folder.isDirectory) { "Not in Recently deleted" }; FileOps.delete(folder) }
    /** Permanently deletes a folder, skipping the trash. Used to roll back a failed create. */
    fun remove(id: String) { FileOps.delete(directory(get(id))) }

    fun recentlyDeleted(): List<Deleted> = FileOps.child(root, ".cofre-trash/folders").listFiles().orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f -> runCatching { Deleted(TrashToken(f.name), decode(FileOps.child(trashFolder(TrashToken(f.name)), META))) }.getOrNull() }

    /** Empties Recently deleted (folders, and each folder's removed content) of what was trashed over [maxAgeDays] ago, and day-old unfinished copies. */
    fun purgeOldTrash(maxAgeDays: Long = 30, now: Instant = Instant.now()) {
        val cutoff = now.minus(Duration.ofDays(maxAgeDays)).toEpochMilli()
        val bins = listOf(FileOps.child(root, ".cofre-trash/folders")) + list().map { FileOps.child(directory(it), ".cofre-trash/content") }
        for (bin in bins) bin.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach(FileOps::delete)
        val dayAgo = now.minus(Duration.ofDays(1)).toEpochMilli()
        root.listFiles().orEmpty().filter { it.name.startsWith(NEW) && it.lastModified() < dayAgo }.forEach(FileOps::delete)
    }

    /** The folder for [id] with its metadata, making whatever a crash left missing. Migration's resumable create. */
    internal fun ensure(folder: ModFolder): ModFolder {
        validate(folder)
        val dir = directory(folder)
        Files.createDirectories(dir.toPath())
        return if (File(dir, META).isFile) read(dir) else save(folder)
    }

    private fun fresh(name: String, gameVersion: String, loader: Loader, loaderVersion: String?, createdFrom: String?): ModFolder {
        require(name.isNotBlank()) { "Name the mod folder" }
        require(list().none { it.name == name }) { "A mod folder with that name already exists" }
        return ModFolder(newID(name), name, gameVersion = gameVersion, loader = loader, loaderVersion = loaderVersion, createdFrom = createdFrom, createdAt = System.currentTimeMillis())
            .also(::validate)
    }

    /** Builds [folder] in a hidden stage ([fill], then empty content folders and the metadata), then moves it in. */
    private fun make(folder: ModFolder, fill: (File) -> Unit) {
        val stage = FileOps.child(root, "$NEW${UUID.randomUUID()}")
        try {
            Files.createDirectories(stage.toPath())
            fill(stage)
            CONTENT.forEach { Files.createDirectories(File(stage, it).toPath()) }
            FileOps.atomic(File(stage, META), Kit.json.encodeToString(folder).toByteArray())
            FileOps.move(stage, directory(folder))
        } finally { FileOps.delete(stage) }
    }

    private fun folder(id: String): File { require(id.isNotBlank() && '/' !in id && !id.startsWith('.')); return FileOps.child(root, id) }
    private fun trashFolder(token: TrashToken): File { require(token.folder.matches(Regex("[a-f0-9-]{36}"))); return FileOps.child(root, ".cofre-trash/folders/${token.folder}") }
    private fun read(dir: File): ModFolder {
        FileOps.child(root, dir.relativeTo(root).invariantSeparatorsPath)
        val folder = decode(FileOps.child(dir, META))
        require(folder.id == dir.name) { "Mod folder path does not match its id" }
        return folder
    }
    private fun decode(file: File): ModFolder = Kit.json.decodeFromString<ModFolder>(file.readText()).also(::validate)
    fun validate(folder: ModFolder) {
        require(folder.schema == 1) { "Unsupported mod folder schema: ${folder.schema}" }
        require(folder.name.isNotBlank() && folder.id.isNotBlank() && !folder.id.startsWith('.') && '/' !in folder.id)
        FileOps.child(root, folder.id)
    }
}

/**
 * An instance's content folders as symbolic links into its mod folder. Links are relative, so they survive the
 * app's files moving and instance trash/restore (same depth). Relink before every launch and after any link change.
 */
object ModFolderLink {
    /**
     * Points [instanceDir]'s [ModFolderStore.CONTENT] folders at [folderDir]'s; null makes them empty real folders
     * again. Refuses to replace a real folder that still has files, so nothing is ever lost to a link.
     */
    fun link(instanceDir: File, folderDir: File?) {
        require(!Files.isSymbolicLink(instanceDir.toPath()) && instanceDir.isDirectory) { "No instance folder" }
        for (name in ModFolderStore.CONTENT) {
            val path = instanceDir.toPath().resolve(name)
            val isLink = Files.isSymbolicLink(path)
            if (folderDir == null) {
                if (isLink) Files.delete(path)
                Files.createDirectories(path)
                continue
            }
            val target = File(folderDir, name).also { Files.createDirectories(it.toPath()) }
            val relative = instanceDir.canonicalFile.toPath().relativize(target.canonicalFile.toPath())
            if (isLink && Files.readSymbolicLink(path) == relative) continue
            if (!isLink && Files.exists(path, NOFOLLOW_LINKS)) {
                check(Files.isDirectory(path, NOFOLLOW_LINKS) && path.toFile().list().isNullOrEmpty()) { "The instance's $name folder still has files" }
                Files.delete(path)
            }
            // A new link beside it, renamed over the old one: the folder is never missing.
            val temp = path.resolveSibling(".cofre-link-${UUID.randomUUID()}")
            Files.createSymbolicLink(temp, relative)
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            finally { Files.deleteIfExists(temp) }
        }
    }

    /** True when every content folder of [instanceDir] is a link (to anywhere). */
    fun linked(instanceDir: File): Boolean = ModFolderStore.CONTENT.all { Files.isSymbolicLink(File(instanceDir, it).toPath()) }
}

/**
 * Once per app start, off the main thread: every instance without a folder gets "<name> mods", with its content
 * folders and mod index moved in, then is linked. A small journal in the instance names the folder before anything
 * moves, so a crash at any step resumes into the same folder and no file is lost.
 */
object ModFolderMigration {
    private const val JOURNAL = ".cofre-mod-folder"

    /** Returns how many instances moved to a folder. Instances already linked are relinked if a crash left a link out. */
    fun run(instances: InstanceStore, folders: ModFolderStore): Int {
        var moved = 0
        for (instance in instances.list()) {
            val dir = instances.directory(instance)
            runCatching {
                runBlocking {
                    InstanceStore.lock(dir).withLock {
                        val current = instances.get(instance.id)
                        if (current.modFolder == null) {
                            if (adoptLocked(current, instances, folders) != null) moved++
                        } else {
                            val folder = runCatching { folders.get(current.modFolder) }.getOrNull()
                            if (folder != null && !ModFolderLink.linked(dir)) ModFolderLink.link(dir, folders.directory(folder))
                        }
                    }
                }
            }
        }
        return moved
    }

    /**
     * Moves [instance]'s content into a new folder and links it (imports, packs, migration). The caller must not hold
     * the instance's lock; blocks, so call it off the main thread. Returns the saved instance, or null when its content
     * folders are links already.
     */
    fun adopt(instance: Instance, instances: InstanceStore, folders: ModFolderStore): Instance? = runBlocking {
        InstanceStore.lock(instances.directory(instance)).withLock { adoptLocked(instances.get(instance.id), instances, folders) }
    }

    private fun adoptLocked(instance: Instance, instances: InstanceStore, folders: ModFolderStore): Instance? {
        val dir = instances.directory(instance)
        val journal = FileOps.child(dir, JOURNAL)
        val planned = journal.takeIf { it.isFile }?.readText()?.trim()
        if (planned == null && ModFolderStore.CONTENT.any { Files.isSymbolicLink(File(dir, it).toPath()) }) return null
        val folder = folders.ensure(
            ModFolder(
                planned ?: ModFolderStore.newID(instance.name).also { FileOps.atomic(journal, it.toByteArray()) },
                folders.uniqueName("${instance.name} mods"),
                gameVersion = instance.gameVersion, loader = instance.loader, loaderVersion = instance.loaderVersion,
                createdFrom = instance.id, createdAt = System.currentTimeMillis(),
            ),
        )
        val target = folders.directory(folder)
        for (name in ModFolderStore.CONTENT + ModFolderStore.INDEX) {
            val from = File(dir, name)
            if (Files.isSymbolicLink(from.toPath()) || !from.exists()) continue
            val to = File(target, name)
            if (!Files.exists(to.toPath(), NOFOLLOW_LINKS)) { FileOps.move(from, to); continue }
            // Only reachable if something wrote both sides between crashes: merge, never overwrite.
            if (from.isDirectory && to.isDirectory) {
                from.listFiles().orEmpty().filter { !File(to, it.name).exists() }.forEach { FileOps.move(it, File(to, it.name)) }
                check(from.list().isNullOrEmpty()) { "Files with the same name in the instance and its new mod folder" }
                Files.delete(from.toPath())
            }
        }
        ModFolderStore.CONTENT.forEach { Files.createDirectories(File(target, it).toPath()) }
        val saved = instances.save(instance.copy(modFolder = folder.id))
        ModFolderLink.link(dir, target)
        journal.delete()
        return saved
    }
}
