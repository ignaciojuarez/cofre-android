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
import com.ignacio.cofre.kit.options.CofrePreset
import com.ignacio.cofre.kit.options.PresetCatalog
import com.ignacio.cofre.kit.options.Storage
import com.ignacio.cofre.kit.versions.Versions
import com.ignacio.cofre.moveToDocument
import com.ignacio.cofre.onIo
import com.ignacio.cofre.scratch
import com.ignacio.cofre.work
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

    fun directory(instance: Instance): File = store.directory(instance)

    fun refresh() = edit { }

    /** Makes a NeoForge 26.2 instance; with [preset], its options and pinned mods (queued as downloads). */
    fun create(name: String, neoForge: String, preset: CofrePreset?, done: (Instance) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            error = graph.context.getString(R.string.error_name_empty)
            return
        }
        edit(done) {
            val made = store.create(trimmed, loaderVersion = neoForge)
            try {
                prepare(made, preset)
            } catch (e: Exception) {
                store.remove(made.id)
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

    fun duplicate(id: String, done: (Instance) -> Unit = {}) = edit(done) {
        val source = store.get(id)
        val names = store.list().map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }
            .map { n -> graph.context.getString(R.string.instance_copy_name, source.name) + if (n == 1) "" else " $n" }
            .first { it !in names }
        store.duplicate(id, name)
    }

    fun trash(id: String) {
        val name = instances.firstOrNull { it.id == id }?.name ?: return
        edit({ token -> undo = Undo(name, token) }) { store.trash(id) }
        if (graph.nav.instance == id) graph.nav.instance = null
    }

    fun undoTrash() {
        val last = undo ?: return
        undo = null
        restore(last.token)
    }

    fun dismissUndo() {
        undo = null
    }

    fun restore(token: InstanceStore.TrashToken) = edit { store.restore(token) }
    fun purge(token: InstanceStore.TrashToken) = edit { store.purge(token) }

    fun export(id: String, uri: Uri, includeWorlds: Boolean = true) = edit {
        val file = graph.scratch("instance.zip")
        store.export(id, file, includeWorlds)
        graph.moveToDocument(file, uri)
    }

    fun import(uri: Uri, done: (Instance) -> Unit = {}) = edit(done) {
        val file = graph.copyToCache(uri, "zip")
        try { store.import(file) } finally { file.delete() }
    }

    suspend fun stats(instance: Instance): Stats = graph.onIo {
        val dir = store.directory(instance)
        Stats(runCatching { ContentStore(dir).list(ContentKind.Mods).size }.getOrDefault(0), Storage.bytes(dir))
    }

    /** NeoForge 26.2 builds from maven.neoforged.net; the handheld preset's pin until that answers. */
    fun loadNeoForgeVersions() = graph.work({}) {
        val versions = withContext(graph.io) { Versions().neoForgeVersions() }.map { it.version }
        if (versions.isNotEmpty()) neoForgeVersions = versions
    }

    fun clearError() {
        error = null
    }

    /** The instance's first files; the preset's pinned mods go to the download queue. */
    private fun prepare(instance: Instance, preset: CofrePreset?) {
        val mods = PresetCatalog.apply(preset, store.directory(instance), graph.physicalMB)
        if (mods.isEmpty()) return
        graph.downloads.enqueue(mods, graph.downloadsModel.metered())
        if (graph.autoStartDownloads) graph.downloadsModel.start()
    }

    private fun <T> edit(done: (T) -> Unit = {}, block: () -> T) {
        busy = true
        error = null
        graph.work({ error = it; busy = false; reloadLater() }) {
            val result = graph.onIo(block)
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
