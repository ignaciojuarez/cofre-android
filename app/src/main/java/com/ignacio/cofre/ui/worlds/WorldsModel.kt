package com.ignacio.cofre.ui.worlds

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.copyToCache
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.worlds.World
import com.ignacio.cofre.kit.worlds.WorldBackup
import com.ignacio.cofre.kit.worlds.WorldStore
import com.ignacio.cofre.moveToDocument
import com.ignacio.cofre.onIo
import com.ignacio.cofre.scratch
import com.ignacio.cofre.ui.common.displayName
import com.ignacio.cofre.work

/** One instance's worlds: list, backups, export/import, copy to another instance, delete with undo. */
class WorldsModel(private val graph: AppGraph, val instance: Instance) {
    private val store = WorldStore(graph.instances.directory(instance)) { graph.launcher.gameRunning }

    var worlds by mutableStateOf<List<World>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var undo by mutableStateOf<Pair<String, WorldStore.TrashToken>?>(null)
        private set

    fun refresh() = edit { }

    suspend fun backups(world: World): List<WorldBackup> = graph.onIo { runCatching { store.backups(world.folder) }.getOrDefault(emptyList()) }

    fun backup(world: World) = edit({ notice = graph.context.getString(R.string.worlds_backed_up, world.name) }) { store.backup(world.folder) }

    fun restore(world: World, backup: WorldBackup) =
        edit({ notice = graph.context.getString(R.string.worlds_restored, world.name) }) { store.restore(backup.file, world.folder) }

    fun export(world: World, uri: Uri) = edit {
        val file = graph.scratch("${world.folder}.zip")
        store.export(world.folder, file)
        graph.moveToDocument(file, uri)
    }

    /** A world zip from the picker. The copy keeps a `.mcworld` extension, so a Bedrock world is refused, not imported broken. */
    fun import(uri: Uri) = edit {
        val name = graph.context.displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val file = graph.copyToCache(uri, if (name.endsWith(".mcworld", ignoreCase = true)) "mcworld" else "zip")
        try { store.import(file, name.removeSuffix(".zip").ifBlank { "Imported world" }) } finally { file.delete() }
    }

    fun copy(world: World, target: Instance) = edit({ notice = graph.context.getString(R.string.worlds_copied, world.name, target.name) }) {
        store.copy(world.folder, WorldStore(graph.instances.directory(target)))
    }

    fun delete(world: World) = edit({ token -> undo = world.name to token }) { store.trash(world.folder) }

    fun undoDelete() {
        val (_, token) = undo ?: return
        undo = null
        edit { store.undo(token) }
    }

    fun dismiss() {
        undo = null
        notice = null
        error = null
    }

    private fun <T> edit(done: (T) -> Unit = {}, block: () -> T) {
        error = null
        graph.work({ error = it }) {
            val result = graph.onIo(block)
            worlds = graph.onIo { store.list() }
            loaded = true
            done(result)
        }
    }
}
