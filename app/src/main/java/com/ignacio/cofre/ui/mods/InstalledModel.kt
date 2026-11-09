package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Immutable
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.compat.CompatibilityIssue
import com.ignacio.cofre.kit.compat.ContentCompatibility
import com.ignacio.cofre.kit.compat.GlScanCache
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.mods.InstalledMod
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModIndex
import com.ignacio.cofre.kit.mods.ModInstaller
import com.ignacio.cofre.kit.mods.ModRemoval
import com.ignacio.cofre.kit.mods.ModResolver
import com.ignacio.cofre.kit.mods.ModUpdates
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.ignacio.cofre.kit.instances.InstanceStore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The Installed tabs: mods, resource and shader packs, and data packs grouped by world. */
enum class InstalledTab(val kinds: List<ContentKind>) {
    Mods(listOf(ContentKind.Mods)),
    Packs(listOf(ContentKind.ResourcePacks, ContentKind.Shaders)),
    Data(listOf(ContentKind.DataPacks)),
}

/** One installed file, with what Cofre knows about it. */
@Immutable
data class InstalledRow(
    val item: ContentStore.Item,
    /** The jar's metadata and index entry (mods only). */
    val mod: InstalledMod? = null,
    val update: ModCandidate? = null,
    /** A known-mods rule, or the graded OpenGL scan of a jar no rule vouches for. */
    val issue: CompatibilityIssue? = null,
) {
    val key: String get() = "${item.kind}:${item.id}"
}

/** Removing something others need: who breaks, and which now-unneeded dependencies could go too. */
@Immutable
data class RemovalPrompt(val row: InstalledRow, val dependents: List<String>, val extras: List<InstalledRow>)

@Immutable
data class UndoNote(val text: String, val tokens: List<ContentStore.TrashToken>)

@Immutable
data class InstalledState(
    val tab: InstalledTab = InstalledTab.Mods,
    val rows: List<InstalledRow> = emptyList(),
    val loading: Boolean = true,
    val busy: Set<String> = emptySet(),
    val removal: RemovalPrompt? = null,
    val undo: UndoNote? = null,
    val message: String? = null,
) {
    val updates: Int get() = rows.count { it.update != null }
    val on: Int get() = rows.count { it.item.enabled }
}

/**
 * The Installed pane for one instance. The files are the truth: every action re-reads them. Network work (update
 * checks, identifying unknown jars) and the OpenGL scan run in the background and fill rows in as they finish.
 */
class InstalledModel(
    private val scope: CoroutineScope,
    val directory: File,
    private val modrinth: Modrinth,
    private val glScans: GlScanCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(InstalledState())
    val state: StateFlow<InstalledState> = mutable.asStateFlow()
    private val store = ContentStore(directory)
    private val index = ModIndex(directory)
    private var loading: Job? = null
    private var identified = false
    /** Latest update candidate per project, from the last check. */
    private val updates = mutableMapOf<String, ModCandidate>()

    init {
        reload()
    }

    fun select(tab: InstalledTab) {
        if (tab == mutable.value.tab) return
        mutable.update { it.copy(tab = tab, rows = emptyList(), loading = true) }
        reload()
    }

    /** Re-reads the files, then fills in Vulkan findings and updates in the background. */
    fun reload() {
        loading?.cancel()
        val tab = mutable.value.tab
        loading = scope.launch {
            val rows = quietly { read(tab) }.orEmpty()
            mutable.update { it.copy(rows = rows, loading = false) }
            if (tab != InstalledTab.Mods) return@launch
            if (!identified) {
                identified = true
                // Unknown jars (copied in by hand) get their Modrinth identity by SHA-1, so updates can find them.
                silently { withContext(io) { index.identifyUnknown(modrinth) } }
                refresh()
            }
            scanForOpenGl()
            checkUpdates()
        }
    }

    fun toggle(row: InstalledRow) = act(row) {
        store.setEnabled(!row.item.enabled, row.item)
    }

    /** Asks first when other mods need this one or it leaves dependencies behind; otherwise removes with undo. */
    fun remove(row: InstalledRow) {
        if (row.item.kind != ContentKind.Mods) return trash(listOf(row))
        scope.launch {
            val nodes = quietly { mods() } ?: return@launch
            val file = row.item.urlName
            val dependents = ModRemoval.dependents(file, nodes).map { it.name }
            val files = ModRemoval.options(file, nodes).map { it.file }.toSet()
            val extras = mutable.value.rows.filter { it.item.urlName in files }
            if (dependents.isEmpty() && extras.isEmpty()) trash(listOf(row)) else mutable.update { it.copy(removal = RemovalPrompt(row, dependents, extras)) }
        }
    }

    fun confirmRemoval(extras: Set<String>) {
        val prompt = mutable.value.removal ?: return
        mutable.update { it.copy(removal = null) }
        trash(listOf(prompt.row) + prompt.extras.filter { it.key in extras })
    }

    fun cancelRemoval() = mutable.update { it.copy(removal = null) }

    fun undo() {
        val note = mutable.value.undo ?: return
        mutable.update { it.copy(undo = null) }
        scope.launch {
            quietly { InstanceStore.lock(directory).withLock { withContext(io) { note.tokens.asReversed().forEach(store::restore) } } }
            reload()
        }
    }

    fun dismissMessage() = mutable.update { it.copy(message = null, undo = null) }

    fun update(row: InstalledRow) {
        val candidate = row.update ?: return
        busy(row.key) {
            val plan = ModResolver.modrinth(modrinth, mods()).plan(candidate.projectId, candidate.versionId)
            ModInstaller(directory, modrinth = modrinth).install(plan)
            updates.remove(candidate.projectId)
        }
    }

    fun updateAll() {
        val rows = mutable.value.rows.filter { it.update != null }
        busy(*rows.map { it.key }.toTypedArray()) {
            for (row in rows) {
                val candidate = row.update ?: continue
                val plan = ModResolver.modrinth(modrinth, mods()).plan(candidate.projectId, candidate.versionId)
                ModInstaller(directory, modrinth = modrinth).install(plan)
                updates.remove(candidate.projectId)
            }
        }
    }

    // Reading

    private suspend fun read(tab: InstalledTab): List<InstalledRow> = withContext(io) {
        val mods = if (tab == InstalledTab.Mods) index.installed().associateBy { it.file } else emptyMap()
        tab.kinds.flatMap { kind -> runCatching { store.list(kind) }.getOrDefault(emptyList()) }.map { item ->
            val mod = mods[item.urlName]
            val id = item.modID ?: item.id
            val issue = ContentCompatibility.issue(id, item.name, item.kind, item.version)
                ?: item.takeIf(::scannable)?.let { glScans.cached(store.file(it)) }?.let { ContentCompatibility.glIssue(id, item.name, it) }
            InstalledRow(item, mod, update = mod?.projectId?.let(updates::get)?.takeIf { it.versionId != mod.versionId }, issue = issue)
        }
    }

    /** Reads every jar's metadata, so never on the main thread. */
    private suspend fun mods(): List<InstalledMod> = withContext(io) { index.installed() }

    /** Mods no rule vouches for get the jar scan. */
    private fun scannable(item: ContentStore.Item): Boolean =
        item.kind == ContentKind.Mods && !ContentCompatibility.vouched(item.modID ?: item.id, item.name, item.version)

    private suspend fun refresh() {
        val tab = mutable.value.tab
        val rows = quietly { read(tab) } ?: return
        mutable.update { if (it.tab == tab) it.copy(rows = rows) else it }
    }

    private suspend fun scanForOpenGl() {
        val rows = mutable.value.rows.filter { it.issue == null && scannable(it.item) }
        for (row in rows) {
            val jar = store.file(row.item)
            // A jar the scanner can't read stays unflagged; the launch-time check still runs.
            val risk = silently { withContext(io) { glScans.scan(jar) } } ?: continue
            val issue = ContentCompatibility.glIssue(row.item.modID ?: row.item.id, row.item.name, risk) ?: continue
            mutable.update { s -> s.copy(rows = s.rows.map { if (it.key == row.key) it.copy(issue = issue) else it }) }
        }
    }

    /** Same-or-more-stable channel only (ModUpdates); a project that fails to load is skipped. */
    private suspend fun checkUpdates() {
        val entries = withContext(io) { index.read() }
        val tracked = mutable.value.rows.mapNotNull { row ->
            val mod = row.mod ?: return@mapNotNull null
            val entry = entries[ModIndex.key(row.item.urlName)]?.takeIf { it.source == "modrinth" } ?: return@mapNotNull null
            mod to entry
        }
        for ((mod, entry) in tracked) {
            val catalog = silently {
                modrinth.versions(entry.projectId, listOf(Target.LOADER.modrinthId), listOf(Target.GAME_VERSION), includeChangelog = false)
                    .map { ModCandidate.from(it, mod.name) }
            } ?: continue
            val current = catalog.firstOrNull { it.versionId == entry.versionId }
                ?: silently { ModCandidate.from(modrinth.version(entry.versionId), mod.name) }
                ?: continue
            val next = ModUpdates.candidate(current.copy(channel = entry.channel), catalog) ?: continue
            updates[entry.projectId] = next
            mutable.update { s -> s.copy(rows = s.rows.map { if (it.mod?.projectId == entry.projectId) it.copy(update = next) else it }) }
        }
    }

    // Acting

    private fun trash(rows: List<InstalledRow>) {
        scope.launch {
            val tokens = quietly { InstanceStore.lock(directory).withLock { withContext(io) { rows.map { store.trash(it.item) } } } } ?: return@launch
            val text = if (rows.size == 1) "Removed ${rows[0].item.name}" else "Removed ${rows.size} items"
            mutable.update { it.copy(undo = UndoNote(text, tokens)) }
            refresh()
        }
    }

    private fun act(row: InstalledRow, block: () -> Unit) {
        scope.launch {
            quietly { InstanceStore.lock(directory).withLock { withContext(io) { block() } } }
            refresh()
        }
        // Flip at once so the switch answers the press; the reload confirms it.
        mutable.update { s -> s.copy(rows = s.rows.map { if (it.key == row.key) it.copy(item = it.item.copy(enabled = !it.item.enabled)) else it }) }
    }

    private fun busy(vararg keys: String, block: suspend () -> Unit) {
        mutable.update { it.copy(busy = it.busy + keys) }
        scope.launch {
            quietly { block() }
            mutable.update { it.copy(busy = it.busy - keys.toSet()) }
            refresh()
        }
    }

    /** Runs [block]; a failure shows as the pane's message instead of escaping. */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        mutable.update { it.copy(message = InstallModel.reason(e)) }
        null
    }
}

/** Background work whose failure only means "no finding" (offline, unreadable jar). */
private suspend fun <T> silently(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
