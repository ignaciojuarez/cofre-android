package com.ignacio.cofre.ui.instances

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.copyToCache
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.modfolders.ModFolderLink
import com.ignacio.cofre.kit.modfolders.ModFolderStore
import com.ignacio.cofre.kit.modfolders.ModFolderMigration
import com.ignacio.cofre.kit.options.CofrePreset
import com.ignacio.cofre.kit.options.PresetCatalog
import com.ignacio.cofre.kit.options.Storage
import com.ignacio.cofre.kit.versions.Versions
import com.ignacio.cofre.moveToDocument
import com.ignacio.cofre.onIo
import com.ignacio.cofre.scratch
import com.ignacio.cofre.work
import com.ignacio.cofre.kit.downloads.DownloadItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The instance library: list, recently deleted, and every change to an instance (the iOS `CofreStore` slice). */
class Library(private val graph: AppGraph) {
    private val store: InstanceStore get() = graph.instances

    var instances by mutableStateOf<List<Instance>>(emptyList())
        private set
    var deleted by mutableStateOf<List<InstanceStore.Deleted>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** The instance just moved to Recently deleted, for the Undo banner. */
    var undo by mutableStateOf<Undo?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var neoForgeVersions by mutableStateOf(listOf(handheld.loaderVersion))
        private set

    /** [folder]: the mod folder deleted with it, restored with it. */
    data class Undo(val name: String, val token: InstanceStore.TrashToken, val folder: ModFolderStore.TrashToken? = null)
    data class Stats(val mods: Int, val bytes: Long)

    /** A mod folder with its mod count and the instances linked to it. */
    data class FolderInfo(val folder: ModFolder, val mods: Int, val users: List<Instance>)

    /** Play's instance: the one picked last, else the one played last, else the first. */
    val active: Instance?
        get() {
            val id = graph.settings.value.activeInstance
            return instances.firstOrNull { it.id == id }
                ?: instances.filter { it.lastPlayedAt != null }.maxByOrNull { it.lastPlayedAt ?: 0 }
                ?: instances.firstOrNull()
        }

    fun select(id: String) = graph.settings.update { it.copy(activeInstance = id) }

    fun refresh() = edit { }

    /**
     * Makes a NeoForge 26.2 instance linked to existing folder [folderId] (it must fit and be playable), or to a new
     * empty mod folder; with [preset], its options and pinned mods (queued as downloads into a new folder only).
     */
    fun create(name: String, neoForge: String, preset: CofrePreset?, folderId: String? = null, done: (Instance) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            error = graph.context.getString(R.string.error_name_empty)
            return
        }
        if (folderId != null) return edit(done) {
            val folder = graph.modFolders.get(folderId)
            val made = store.save(store.create(trimmed, loaderVersion = neoForge).copy(modFolder = folder.id))
            try {
                require(folder.fits(made) && folder.playable) { graph.context.getString(R.string.folder_doesnt_fit) }
                ModFolderLink.link(store.directory(made), graph.modFolders.directory(folder))
                prepare(made, preset = null)
            } catch (e: Exception) {
                store.remove(made.id)
                throw e
            }
            made
        }
        edit(done) {
            val folders = graph.modFolders
            val folder = folders.create(folders.uniqueName(graph.context.getString(R.string.folder_default_name, trimmed)), loaderVersion = neoForge)
            val made = try {
                store.save(store.create(trimmed, loaderVersion = neoForge).copy(modFolder = folder.id))
            } catch (e: Exception) {
                folders.remove(folder.id)
                throw e
            }
            try {
                ModFolderLink.link(store.directory(made), folders.directory(folder))
                prepare(made, preset)
            } catch (e: Exception) {
                store.remove(made.id)
                folders.remove(folder.id)
                throw e
            }
            made
        }
    }

    fun rename(id: String, name: String) = edit { store.rename(id, name.trim()) }
    fun notes(id: String, notes: String) = edit { store.notes(id, notes) }
    fun group(id: String, group: String?) = edit { store.group(id, group?.trim()?.takeIf { it.isNotEmpty() }) }
    fun favorite(id: String, value: Boolean) = edit { store.favorite(id, value) }
    fun loaderVersion(id: String, version: String) = edit { store.loaderVersion(id, version) }

    /** Memory and JVM arguments. The last set that launched cleanly stays as the rollback point. */
    fun runtime(id: String, autoMemory: Boolean, memoryMB: Int, jvmArguments: String) = edit {
        store.runtime(id, if (autoMemory) null else memoryMB, jvmArguments.trim(), autoMemory)
    }

    fun rollbackRuntime(id: String) = edit { store.rollbackRuntime(id) }

    /** The copy links to the same mod folder. */
    fun duplicate(id: String, done: (Instance) -> Unit = {}) = edit(done, owner = id) {
        store.duplicate(id, store.uniqueName(graph.context.getString(R.string.instance_copy_name, store.get(id).name))).also(::relink)
    }

    /**
     * Links instance [id] to mod folder [folderId], or to no mods when null. The folder must fit the instance and be
     * playable. Refused while the game runs; the old folder is kept.
     */
    fun linkFolder(id: String, folderId: String?, done: (Instance) -> Unit = {}) {
        if (graph.launcher.gameRunning) {
            error = graph.context.getString(R.string.error_game_running)
            return
        }
        edit(done, owner = id) {
            val instance = store.get(id)
            val folder = folderId?.let { graph.modFolders.get(it) }
            require(folder == null || (folder.fits(instance) && folder.playable)) { graph.context.getString(R.string.folder_doesnt_fit) }
            ModFolderLink.link(store.directory(instance), folder?.let { graph.modFolders.directory(it) })
            store.save(instance.copy(modFolder = folder?.id))
        }
    }

    /** Gives instance [id] its own copy of its shared mod folder ("<instance> mods") and links it to the copy. */
    fun copyFolderFor(id: String, done: (Instance) -> Unit = {}) {
        if (graph.launcher.gameRunning) {
            error = graph.context.getString(R.string.error_game_running)
            return
        }
        edit(done, owner = id) {
            val instance = store.get(id)
            val source = requireNotNull(graph.folderOf(instance)) { graph.context.getString(R.string.folder_missing) }
            val folders = graph.modFolders
            val sourceDir = folders.directory(source)
            val copy = runBlocking {
                InstanceStore.lock(sourceDir).withLock {
                    folders.copy(sourceDir, folders.uniqueName(graph.context.getString(R.string.folder_default_name, instance.name)), source.gameVersion, source.loader, source.loaderVersion, source.id)
                }
            }
            ModFolderLink.link(store.directory(instance), folders.directory(copy))
            store.save(instance.copy(modFolder = copy.id))
        }
    }

    /**
     * Gives instance [id] a new empty folder ("<instance> mods N", same game version and loader) and links it.
     * Refused while the game runs.
     */
    fun newFolderFor(id: String, done: (Instance) -> Unit = {}) {
        if (graph.launcher.gameRunning) {
            error = graph.context.getString(R.string.error_game_running)
            return
        }
        edit(done, owner = id) {
            val instance = store.get(id)
            val folders = graph.modFolders
            val folder = folders.create(
                folders.uniqueName(graph.context.getString(R.string.folder_default_name, instance.name)),
                instance.gameVersion, instance.loader, instance.loaderVersion, createdFrom = instance.id,
            )
            try {
                ModFolderLink.link(store.directory(instance), folders.directory(folder))
                store.save(instance.copy(modFolder = folder.id))
            } catch (e: Exception) {
                runCatching { ModFolderLink.link(store.directory(instance), graph.folderOf(instance)?.let(folders::directory)) }
                folders.remove(folder.id)
                throw e
            }
        }
    }

    /** Every mod folder with its mod count and users, for pickers. */
    suspend fun folders(): List<FolderInfo> = graph.onIo {
        val all = store.list()
        graph.modFolders.list().map { folder ->
            val mods = runCatching { ContentStore(graph.modFolders.directory(folder)).list(ContentKind.Mods).size }.getOrDefault(0)
            FolderInfo(folder, mods, graph.modFolders.usedBy(folder.id, all))
        }
    }

    /**
     * Moves instance [id] to Recently deleted. Its mod folder stays; with [alsoFolder], and only when no other
     * instance uses it, the folder goes to the folders' Recently deleted too (Undo brings both back).
     */
    fun trash(id: String, alsoFolder: Boolean = false) {
        val name = instances.firstOrNull { it.id == id }?.name ?: return
        edit({ (token, folder): Pair<InstanceStore.TrashToken, ModFolderStore.TrashToken?> -> undo = Undo(name, token, folder) }, owner = id) {
            val instance = store.get(id)
            // Queued preset or pack downloads would land in a folder that's gone.
            val dir = store.directory(instance).canonicalPath + File.separator
            graph.downloads.progress.value.items
                .filter { File(it.destination).canonicalPath.startsWith(dir) && it.state != DownloadItem.State.Finished }
                .forEach { graph.downloads.cancel(it.id) }
            val folder = graph.folderOf(instance)?.takeIf { alsoFolder && graph.modFolders.usedBy(it.id, store.list()).all { u -> u.id == id } }
            val token = store.trash(id)
            token to folder?.let { f ->
                val folderDir = graph.modFolders.directory(f)
                runBlocking { InstanceStore.lock(folderDir).withLock { graph.modFolders.trash(f.id) } }
            }
        }
        if (graph.nav.instance == id) graph.nav.instance = null
    }

    fun undoTrash() {
        val last = undo ?: return
        undo = null
        if (last.folder == null) return restore(last.token)
        edit {
            val folder = graph.modFolders.restore(last.folder)
            val instance = store.restore(last.token)
            // Restore picks a new id when the old one was taken meanwhile: follow it.
            if (instance.modFolder != folder.id) store.save(instance.copy(modFolder = folder.id)).also(::relink) else relink(instance)
        }
    }

    fun restore(token: InstanceStore.TrashToken) = edit { store.restore(token) }
    fun purge(token: InstanceStore.TrashToken) = edit { store.purge(token) }

    /** A self-contained zip: the mod folder's real files take the place of the instance's links. */
    fun export(id: String, uri: Uri, includeWorlds: Boolean = true) = edit(owner = id) {
        val file = graph.scratch("instance.zip")
        val instance = store.get(id)
        val folder = instance.modFolder?.let { graph.contentDirectory(instance) }
        if (folder == null) store.export(id, file, includeWorlds)
        else runBlocking { InstanceStore.lock(folder).withLock { store.export(id, file, includeWorlds, folder) } }
        graph.moveToDocument(file, uri)
    }

    /** The zip's mods become a new mod folder. */
    fun import(uri: Uri, done: (Instance) -> Unit = {}) = edit(done) {
        val file = graph.copyToCache(uri, "zip")
        val made = try { store.import(file) } finally { file.delete() }
        // If this fails, the next start's migration finishes it: the instance still has its own mods.
        runCatching { ModFolderMigration.adopt(made, store, graph.modFolders) }.getOrNull() ?: made
    }

    suspend fun stats(instance: Instance): Stats = graph.onIo {
        val dir = store.directory(instance)
        val mods = runCatching { ContentStore(dir, graph.contentDirectory(instance)).list(ContentKind.Mods).size }.getOrDefault(0)
        Stats(mods, Storage.bytes(dir))
    }

    /** NeoForge 26.2 builds from maven.neoforged.net; the handheld preset's pin until that answers. */
    fun loadNeoForgeVersions() = graph.work({}) {
        val versions = withContext(graph.io) { Versions().neoForgeVersions() }.map { it.version }
        if (versions.isNotEmpty()) neoForgeVersions = versions
    }

    fun clearError() {
        error = null
    }

    /** Points [instance]'s content folders at its mod folder again (after a copy, or any link change). */
    private fun relink(instance: Instance) {
        graph.folderOf(instance)?.let { ModFolderLink.link(store.directory(instance), graph.modFolders.directory(it)) }
    }

    /** The instance's first files; the preset's pinned mods go to the download queue, into its mod folder. */
    private fun prepare(instance: Instance, preset: CofrePreset?) {
        val mods = PresetCatalog.apply(preset, store.directory(instance), graph.physicalMB, File(graph.contentDirectory(instance), "mods"))
        if (mods.isEmpty()) return
        graph.downloads.enqueue(mods, graph.downloadsModel.metered())
        if (graph.autoStartDownloads) graph.downloadsModel.start()
    }

    /** Runs [block] off the main thread; with [owner], while holding that instance's [InstanceStore.lock]. */
    private fun <T> edit(done: (T) -> Unit = {}, owner: String? = null, block: () -> T) {
        busy = true
        error = null
        graph.work({ error = it; busy = false; reloadLater() }) {
            val result = if (owner == null) graph.onIo(block) else {
                InstanceStore.lock(graph.onIo { store.directory(store.get(owner)) }).withLock { graph.onIo(block) }
            }
            reload()
            busy = false
            done(result)
        }
    }

    private fun reloadLater() = graph.work({}) { reload() }

    private suspend fun reload() {
        val (list, bin) = graph.onIo { store.list() to runCatching { store.recentlyDeleted() }.getOrDefault(emptyList()) }
        instances = list
        deleted = bin
        loaded = true
    }

    companion object {
        /** "Handheld (NeoForge 26.2, Vulkan)": checked by default for new instances. */
        val handheld: CofrePreset get() = PresetCatalog.presets.first()
    }
}
