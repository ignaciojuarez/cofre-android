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
import com.ignacio.cofre.kit.modfolders.ModFolderLink
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

    data class Undo(val name: String, val token: InstanceStore.TrashToken)
    data class Stats(val mods: Int, val bytes: Long)

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

    /** Makes a NeoForge 26.2 instance linked to a new empty mod folder; with [preset], its options and pinned mods (queued as downloads into the folder). */
    fun create(name: String, neoForge: String, preset: CofrePreset?, done: (Instance) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            error = graph.context.getString(R.string.error_name_empty)
            return
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

    fun trash(id: String) {
        val name = instances.firstOrNull { it.id == id }?.name ?: return
        edit({ token -> undo = Undo(name, token) }, owner = id) {
            // Queued preset or pack downloads would land in a folder that's gone.
            val dir = store.directory(store.get(id)).canonicalPath + File.separator
            graph.downloads.progress.value.items
                .filter { File(it.destination).canonicalPath.startsWith(dir) && it.state != DownloadItem.State.Finished }
                .forEach { graph.downloads.cancel(it.id) }
            store.trash(id)
        }
        if (graph.nav.instance == id) graph.nav.instance = null
    }

    fun undoTrash() {
        val last = undo ?: return
        undo = null
        restore(last.token)
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
