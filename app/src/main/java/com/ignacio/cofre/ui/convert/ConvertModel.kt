package com.ignacio.cofre.ui.convert

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modfolders.Convert
import com.ignacio.cofre.kit.modfolders.ConvertChoices
import com.ignacio.cofre.kit.modfolders.ConvertPlan
import com.ignacio.cofre.kit.modfolders.ConvertRow
import com.ignacio.cofre.kit.modfolders.ConvertStatus
import com.ignacio.cofre.kit.modfolders.ConvertTarget
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.modfolders.ModFolderStore
import com.ignacio.cofre.kit.mods.Snapshots
import com.ignacio.cofre.kit.versions.Versions
import com.ignacio.cofre.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Where Convert writes the result. */
enum class SaveAs { NewFolder, Replace }

sealed interface ConvertPhase {
    data object Loading : ConvertPhase
    data class Ready(val plan: ConvertPlan) : ConvertPhase
    data class Offline(val message: String) : ConvertPhase
    data class Failed(val message: String) : ConvertPhase
}

/**
 * One Convert screen: the folder, the target, its plan and the user's answers. Plans once per target (and on retry),
 * never per recomposition. [planner] and [releases] are swappable for screenshots.
 */
class ConvertModel(
    private val graph: AppGraph,
    val folder: ModFolder,
    start: ConvertTarget?,
    private val planner: suspend (ConvertTarget) -> ConvertPlan = { to ->
        Convert.plan(graph.modFolders.directory(folder), folder.gameVersion, folder.loader, to, graph.modrinth)
    },
    private val releases: suspend () -> List<String> = {
        withContext(graph.io) { Versions().manifest().versions.filter { it.type == "release" }.map { it.id } }
    },
) {
    private val scope = CoroutineScope(graph.scope.coroutineContext + SupervisorJob(graph.scope.coroutineContext[Job]))
    private var planJob: Job? = null
    private var applyJob: Job? = null

    var target by mutableStateOf(start ?: ConvertTarget(folder.gameVersion, folder.loader, folder.includeBeta))
        private set
    var phase by mutableStateOf<ConvertPhase>(ConvertPhase.Loading)
        private set
    var versions by mutableStateOf(listOf(target.gameVersion, folder.gameVersion, Target.GAME_VERSION).distinct())
        private set
    var choices by mutableStateOf(ConvertChoices())
        private set
    var problemsOnly by mutableStateOf(false)
    var saveAs by mutableStateOf(SaveAs.NewFolder)
    /** Files done and total while applying; null when not applying. */
    var applying by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var applyError by mutableStateOf<String?>(null)
        private set
    /** Bytes waiting on "Download on mobile data?". */
    var askMetered by mutableStateOf<Long?>(null)
        private set

    /** Instances linked to this folder, read once: Replace is offered only when the target still fits them all. */
    private val users = runCatching { graph.modFolders.usedBy(folder.id, graph.instances.list()) }.getOrDefault(emptyList())
    val canReplace: Boolean get() = users.all { it.gameVersion == target.gameVersion && it.loader == target.loader }
    val playable: Boolean get() = target.gameVersion == Target.GAME_VERSION && target.loader == Target.LOADER
    private var named: Pair<ConvertTarget, String>? = null
    /** "<name> · <Loader> <version>", unique among folders; read from disk once per target. */
    val newName: String get() = named?.takeIf { it.first == target }?.second
        ?: graph.modFolders.uniqueName(convertedName(folder.name, target)).also { named = target to it }

    init {
        replan()
        scope.launch {
            val list = runCatching { releases() }.getOrNull() ?: return@launch
            // Newest releases only: the menu stays short, and the folder's own and the preset target always stay in it.
            val recent = list.sortedWith(VersionOrder.reversed()).take(RECENT)
            versions = (recent + versions).distinct().sortedWith(VersionOrder.reversed())
        }
    }

    fun retarget(next: ConvertTarget) {
        if (next == target || applying != null) return
        target = next
        if (saveAs == SaveAs.Replace && !canReplace) saveAs = SaveAs.NewFolder
        replan()
    }

    fun retry() = replan()

    private fun replan() {
        planJob?.cancel()
        phase = ConvertPhase.Loading
        val to = target
        planJob = scope.launch {
            phase = try {
                ConvertPhase.Ready(planner(to))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ConvertPhase.Offline(graph.context.getString(R.string.convert_offline))
            } catch (e: Exception) {
                ConvertPhase.Failed(graph.context.message(e))
            }
        }
    }

    fun toggleSwap(jar: String, use: Boolean) { choices = choices.copy(swaps = if (use) choices.swaps + jar else choices.swaps - jar) }
    fun toggleBeta(jar: String) { choices = choices.copy(betas = if (jar in choices.betas) choices.betas - jar else choices.betas + jar) }
    fun keepMissingOff(on: Boolean) { choices = choices.copy(keepMissingOff = on) }

    /** How many mods the new folder gets with the current answers. */
    fun modCount(plan: ConvertPlan): Int {
        val picked = plan.rows.filter { picked(it) }
        val roots = picked.filter { it.enabled }.map { it.name }.toSet()
        val added = plan.rows.count { it.status == ConvertStatus.Added && it.neededBy.any(roots::contains) }
        val missing = if (choices.keepMissingOff) plan.rows.count { it.jar != null && it.status != ConvertStatus.NotNeeded && !picked(it) } else 0
        return picked.size + added + missing
    }

    private fun picked(row: ConvertRow): Boolean = when (row.status) {
        ConvertStatus.Ready -> true
        ConvertStatus.BetaOnly -> row.jar in choices.betas
        ConvertStatus.Swap -> row.jar in choices.swaps
        else -> false
    }

    /** Builds the folder; on mobile data it asks first when something downloads. */
    fun create(approvedMetered: Boolean = false) {
        val plan = (phase as? ConvertPhase.Ready)?.plan ?: return
        if (applying != null) return
        val bytes = plan.downloadBytes(choices)
        if (!approvedMetered && bytes > 0 && graph.downloadsModel.metered()) { askMetered = bytes; return }
        askMetered = null
        applyError = null
        applying = 0 to 0
        val mode = if (saveAs == SaveAs.Replace && canReplace) SaveAs.Replace else SaveAs.NewFolder
        val answers = choices
        val name = newName
        applyJob = scope.launch {
            val stage = File(graph.modFolders.root, ".cofre-new-convert-${UUID.randomUUID()}")
            try {
                Convert.apply(plan, stage, answers, onProgress = { p -> applying = p.files to p.totalFiles })
                val id = withContext(graph.io) { if (mode == SaveAs.Replace) replace(stage) else createNew(stage, name) }
                graph.nav.convertFolder = null
                graph.nav.convertTarget = null
                graph.nav.openFolder(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                applyError = graph.context.getString(R.string.convert_apply_failed, graph.context.message(e))
            } finally {
                withContext(NonCancellable + graph.io) { FileOps.delete(stage) }
                applying = null
            }
        }
    }

    fun dismissMetered() { askMetered = null }

    /** A new folder with the converted content moved in. Returns its id. */
    private fun createNew(stage: File, name: String): String {
        val store = graph.modFolders
        val made = store.create(name, target.gameVersion, target.loader, createdFrom = folder.id)
        try {
            moveContent(stage, store.directory(made))
            store.save(made.copy(includeBeta = target.includeBeta, autoUpdate = folder.autoUpdate, icon = folder.icon))
        } catch (e: Exception) {
            runCatching { store.remove(made.id) }
            throw e
        }
        return made.id
    }

    /** Swaps this folder's mods for the converted ones after a BeforeUpdate snapshot; packs stay as they are. */
    private suspend fun replace(stage: File): String {
        val dir = graph.modFolders.directory(folder)
        Snapshots.take(dir, Snapshots.Reason.BeforeUpdate)
        InstanceStore.lock(dir).withLock {
            for (part in listOf("mods", ModFolderStore.INDEX)) {
                val from = File(stage, part)
                val to = File(dir, part)
                FileOps.delete(to)
                if (from.exists()) FileOps.move(from, to)
            }
            File(dir, "mods").mkdirs()
            graph.modFolders.save(graph.modFolders.get(folder.id).copy(gameVersion = target.gameVersion, loader = target.loader, loaderVersion = null, includeBeta = target.includeBeta))
        }
        return folder.id
    }

    private fun moveContent(stage: File, dir: File) {
        for (part in ModFolderStore.CONTENT + ModFolderStore.INDEX) {
            val from = File(stage, part)
            if (!from.exists()) continue
            val to = File(dir, part)
            FileOps.delete(to)
            FileOps.move(from, to)
        }
    }

    fun close() = scope.cancel()

    private companion object { const val RECENT = 16 }
}

/** "<name> · <Loader> <version>", replacing the suffix an earlier convert added (and its " 2" de-dup number). */
internal fun convertedName(name: String, target: ConvertTarget): String {
    val loaders = Loader.entries.joinToString("|") { Regex.escape(it.displayName) }
    val base = name.replace(Regex(" · (?:$loaders) \\S+(?: \\d+)?$"), "")
    return "$base · ${target.loader.displayName} ${target.gameVersion}"
}

/** "26.2" < "26.2.1" < "26.10": numeric parts compared in order. */
private object VersionOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
