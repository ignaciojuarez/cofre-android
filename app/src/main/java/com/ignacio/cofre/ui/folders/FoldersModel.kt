package com.ignacio.cofre.ui.folders

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.modfolders.ConvertTarget
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.modfolders.ModFolderStore
import com.ignacio.cofre.kit.mods.ModIndex
import com.ignacio.cofre.kit.mods.Snapshots
import com.ignacio.cofre.kit.options.PresetCatalog
import com.ignacio.cofre.kit.versions.Versions
import com.ignacio.cofre.message
import com.ignacio.cofre.moveToDocument
import com.ignacio.cofre.onIo
import com.ignacio.cofre.scratch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One folder in the overview: what's in it, how big, and who uses it. */
@Immutable
data class FolderCard(
    val folder: ModFolder,
    val mods: Int,
    val packs: Int,
    val bytes: Long,
    val usedBy: List<Instance>,
) {
    val id: String get() = folder.id
}

/** A restore point with how many mods changed since. */
@Immutable
data class SnapshotRow(val snapshot: Snapshots.Snapshot, val changed: Int)

@Immutable
data class FoldersState(
    val cards: List<FolderCard> = emptyList(),
    val loading: Boolean = true,
    val selected: String? = null,
    /** The selected folder's snapshots. */
    val snapshots: List<SnapshotRow> = emptyList(),
    val deleted: List<ModFolderStore.Deleted> = emptyList(),
    /** Just moved to Recently deleted, for the Undo banner. */
    val undo: ModFolderStore.Deleted? = null,
    /** The instances that used [undo]'s folder: Undo links them back. */
    val undoUsers: List<String> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
) {
    val current: FolderCard? get() = cards.firstOrNull { it.id == selected }
    val bytes: Long get() = cards.sumOf { it.bytes }
}

/** Where a new folder's content comes from. */
enum class FolderStart { Empty, Instance, Folder, Mrpack }

/** Everything the New mod folder sheet asks. */
@Immutable
data class NewFolderRequest(
    val name: String,
    val start: FolderStart,
    /** The instance or folder id to copy from. */
    val source: String?,
    val gameVersion: String,
    val loader: Loader,
    val loaderVersion: String?,
    val autoUpdate: AutoUpdate,
    val essentials: Boolean,
    /** Link this instance to the new folder once it's made. */
    val linkTo: String?,
)

/**
 * The Mod folders overview: every folder (unused and can't-play-yet ones too), its detail and every change to a
 * folder that isn't its content. Content changes hold `InstanceStore.lock(folderDir)`; links go through Library.
 */
class FoldersModel(private val graph: AppGraph, private val scope: CoroutineScope) {
    private val store: ModFolderStore get() = graph.modFolders
    private val mutable = MutableStateFlow(FoldersState())
    val state: StateFlow<FoldersState> = mutable.asStateFlow()

    /** Release game versions for the new folder sheet: the target first, Mojang's list once it answers. */
    private val mutableVersions = MutableStateFlow(listOf(Target.GAME_VERSION))
    val versions: StateFlow<List<String>> = mutableVersions.asStateFlow()

    fun reload() {
        scope.launch { load() }
    }

    fun select(id: String) {
        mutable.update { it.copy(selected = id, snapshots = emptyList()) }
        scope.launch { loadSnapshots(id) }
    }

    fun dismissMessage() = mutable.update { it.copy(message = null, undo = null, undoUsers = emptyList()) }

    fun autoUpdate(id: String, value: AutoUpdate) = edit { store.save(store.get(id).copy(autoUpdate = value)) }
    fun includeBeta(id: String, value: Boolean) = edit { store.save(store.get(id).copy(includeBeta = value)) }

    fun rename(id: String, name: String) = edit { store.rename(id, name.trim()) }

    fun duplicate(id: String) = edit {
        val source = store.get(id)
        val made = InstanceStore.lockBlocking(store.directory(source)) {
            store.duplicate(id, store.uniqueName(graph.context.getString(R.string.folders_copy_name, source.name)))
        }
        mutable.update { it.copy(selected = made.id) }
    }

    /**
     * Moves folder [id] to Recently deleted. Instances that use it move to [moveTo] first, or play without mods when
     * it's null. Each link goes through Library, so a running game refuses it and nothing is trashed.
     */
    fun delete(id: String, moveTo: String?) {
        if (refuseWhileRunning()) return
        val users = mutable.value.cards.firstOrNull { it.id == id }?.usedBy.orEmpty()
        fun relink(rest: List<Instance>) {
            val next = rest.firstOrNull() ?: return trash(id, users.map { it.id })
            graph.library.linkFolder(next.id, moveTo) { relink(rest.drop(1)) }
        }
        relink(users)
    }

    fun restoreDeleted(token: ModFolderStore.TrashToken) {
        val users = mutable.value.takeIf { it.undo?.token == token }?.undoUsers.orEmpty()
        edit {
            val folder = store.restore(token)
            mutable.update { it.copy(selected = folder.id, undo = null, undoUsers = emptyList()) }
            // Undo puts the instances back on it too (each link goes through Library, one at a time).
            fun relink(rest: List<String>) {
                val next = rest.firstOrNull() ?: return reload()
                graph.library.linkFolder(next, folder.id) { relink(rest.drop(1)) }
            }
            scope.launch { relink(users) }
        }
    }

    /** Links [instanceId] to [folderId] (it must fit and be playable; Library checks). */
    fun link(folderId: String, instanceId: String) = graph.library.linkFolder(instanceId, folderId) { reload() }

    fun restoreSnapshot(id: String, snapshot: Snapshots.Snapshot) {
        if (refuseWhileRunning()) return
        scope.launch {
            try {
                Snapshots.restore(store.directory(store.get(id)), snapshot, pin = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = graph.context.message(e)) }
            }
            load()
        }
    }

    /** A Modrinth pack of the folder ([FolderExport]) written to the document the user picked. */
    fun export(id: String, uri: Uri) {
        mutable.update { it.copy(busy = true) }
        scope.launch {
            try {
                val folder = store.get(id)
                val file = graph.scratch("folder.mrpack")
                val dir = store.directory(folder)
                val build = folder.loaderVersion ?: graph.library.neoForgeVersions.firstOrNull()?.takeIf { folder.playable }
                InstanceStore.lock(dir).withLock { FolderExport.write(dir, folder, build, graph.modrinth, file) }
                graph.onIo { graph.moveToDocument(file, uri) }
                mutable.update { it.copy(message = graph.context.getString(R.string.folders_exported, folder.name)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = graph.context.message(e)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    /** Mojang's release list, for the version picker. Offline keeps the target alone. */
    fun loadVersions() {
        graph.library.loadNeoForgeVersions()
        if (mutableVersions.value.size > 1) return
        scope.launch {
            val releases = runCatching { Versions().manifest().versions.filter { it.type == "release" }.map { it.id } }.getOrNull() ?: return@launch
            mutableVersions.value = (listOf(Target.GAME_VERSION) + releases.take(24)).distinct()
        }
    }

    /** The folder an instance's mods live in: its own when linked, else null (its content is still in the instance). */
    fun folderOf(instanceId: String): String? = graph.library.instances.firstOrNull { it.id == instanceId }?.modFolder

    /**
     * Makes the folder [request] asks for, then adds the handheld essentials, links the instance and selects it. A copy
     * to another version or loader never comes here: that's Convert.
     */
    fun create(request: NewFolderRequest, done: (ModFolder) -> Unit = {}) = edit {
        val name = request.name.trim()
        require(name.isNotEmpty()) { graph.context.getString(R.string.error_name_empty) }
        val source: File? = when (request.start) {
            FolderStart.Instance -> graph.library.instances.firstOrNull { it.id == request.source }?.let(graph::contentDirectory)
            FolderStart.Folder -> request.source?.let { store.directory(store.get(it)) }
            else -> null
        }
        val made = if (source == null) {
            store.create(name, request.gameVersion, request.loader, request.loaderVersion)
        } else {
            InstanceStore.lockBlocking(source) {
                store.copy(source, name, request.gameVersion, request.loader, request.loaderVersion, createdFrom = request.source)
            }
        }
        val saved = store.save(made.copy(autoUpdate = request.autoUpdate))
        if (request.essentials && saved.playable) essentials(saved)
        mutable.update { it.copy(selected = saved.id) }
        scope.launch {
            request.linkTo?.let { graph.library.linkFolder(it, saved.id) { reload() } }
            done(saved)
        }
    }

    /** The target a copy converts to, when [request] copies from another version or loader; null when it's a plain copy. */
    fun conversion(request: NewFolderRequest): Pair<String, ConvertTarget>? {
        val from = when (request.start) {
            FolderStart.Instance -> folderOf(request.source ?: return null)?.let { runCatching { store.get(it) }.getOrNull() }
            FolderStart.Folder -> request.source?.let { runCatching { store.get(it) }.getOrNull() }
            else -> null
        } ?: return null
        if (from.gameVersion == request.gameVersion && from.loader == request.loader) return null
        return from.id to ConvertTarget(request.gameVersion, request.loader)
    }

    /** The handheld preset's pinned mods, queued into the folder (skipping projects it already has). */
    private fun essentials(folder: ModFolder) {
        val dir = store.directory(folder)
        val have = ModIndex(dir).read().values.map { it.projectId }.toSet()
        val items = PresetCatalog.presets.first().pinnedMods.filter { it.project !in have }
            .map { DownloadItem(it.url, File(dir, "mods/${it.file}").path, title = it.file, sha512 = it.sha512) }
        if (items.isEmpty()) return
        graph.downloads.enqueue(items, graph.downloadsModel.metered())
        if (graph.autoStartDownloads) graph.downloadsModel.start()
    }

    private fun trash(id: String, users: List<String>) {
        if (refuseWhileRunning()) return
        edit {
            val folder = store.get(id)
            val token = InstanceStore.lockBlocking(store.directory(folder)) { store.trash(id) }
            mutable.update { s -> s.copy(undo = ModFolderStore.Deleted(token, folder), undoUsers = users, selected = s.selected.takeIf { it != id }) }
            if (graph.nav.modFolder == id) graph.nav.modFolder = null
        }
    }

    private fun refuseWhileRunning(): Boolean {
        if (!graph.launcher.gameRunning) return false
        mutable.update { it.copy(message = graph.context.getString(R.string.error_game_running)) }
        return true
    }

    /** Runs [block] off the main thread, then reloads; a failure shows as the message. */
    private fun edit(block: () -> Unit) {
        mutable.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                graph.onIo(block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = graph.context.message(e)) }
            }
            mutable.update { it.copy(busy = false) }
            load()
        }
    }

    private suspend fun load() {
        val instances = graph.library.instances
        val (cards, deleted) = graph.onIo {
            val cards = runCatching { store.list() }.getOrDefault(emptyList()).map { folder ->
                val dir = store.directory(folder)
                val mods = ModIndex(dir).jars().size
                val packs = listOf("resourcepacks", "shaderpacks").sumOf { File(dir, it).listFiles()?.count { f -> !f.name.startsWith('.') } ?: 0 }
                FolderCard(folder, mods, packs, runCatching { store.size(folder.id) }.getOrDefault(0), store.usedBy(folder.id, instances))
            }
            cards to runCatching { store.recentlyDeleted() }.getOrDefault(emptyList())
        }
        // Folders in use first, then by name; the selection falls back to the first.
        val sorted = cards.sortedWith(compareBy<FolderCard>({ it.usedBy.isEmpty() }, { !it.folder.playable }, { it.folder.name.lowercase() }))
        mutable.update { s ->
            val selected = s.selected?.takeIf { id -> sorted.any { it.id == id } } ?: graph.nav.modFolder?.takeIf { id -> sorted.any { it.id == id } } ?: sorted.firstOrNull()?.id
            s.copy(cards = sorted, deleted = deleted, loading = false, selected = selected)
        }
        mutable.value.selected?.let { loadSnapshots(it) }
    }

    private suspend fun loadSnapshots(id: String) {
        val rows = runCatching { snapshotRows(store.directory(store.get(id))) }.getOrDefault(emptyList())
        mutable.update { if (it.selected == id) it.copy(snapshots = rows) else it }
    }
}

/** [dir]'s snapshots, newest first, with how many mods changed since each. */
suspend fun snapshotRows(dir: File): List<SnapshotRow> = Snapshots.list(dir).map { snapshot ->
    SnapshotRow(snapshot, runCatching { Snapshots.changedSince(dir, snapshot).count }.getOrDefault(0))
}

/** Runs [block] holding [dir]'s lock, from a thread that may block. */
private fun <T> InstanceStore.Companion.lockBlocking(dir: File, block: () -> T): T =
    kotlinx.coroutines.runBlocking { lock(dir).withLock { block() } }
