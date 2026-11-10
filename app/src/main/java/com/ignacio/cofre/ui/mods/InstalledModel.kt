package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Immutable
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.compat.CompatibilityIssue
import com.ignacio.cofre.kit.compat.ContentCompatibility
import com.ignacio.cofre.kit.compat.GlScanCache
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.mods.InstalledMod
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModUpdate
import com.ignacio.cofre.kit.mods.apply
import com.ignacio.cofre.kit.mods.check
import com.ignacio.cofre.kit.mods.ModIndex
import com.ignacio.cofre.kit.mods.ModInstaller
import com.ignacio.cofre.kit.mods.ModRemoval
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

/** The folder's tabs: mods, resource and shader packs, and the instance's data packs grouped by world (they live in worlds). */
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
    /** Pinned in the mod index: updates leave it alone. */
    val pinned: Boolean = false,
) {
    val key: String get() = "${item.kind}:${item.id}"

    /** Only jars the mod index knows (by Modrinth identity) can be pinned. */
    val pinnable: Boolean get() = mod?.projectId != null

    fun matches(filter: InstalledFilter): Boolean = when (filter) {
        InstalledFilter.All -> true
        InstalledFilter.Updates -> update != null
        InstalledFilter.Off -> !item.enabled
        InstalledFilter.Issues -> issue != null
        InstalledFilter.Pinned -> pinned
    }
}

/** The chips over the list. */
enum class InstalledFilter { All, Updates, Off, Issues, Pinned }

/** Removing something others need: who breaks, and which now-unneeded dependencies could go too. */
@Immutable
data class RemovalPrompt(val row: InstalledRow, val dependents: List<String>, val extras: List<InstalledRow>)

@Immutable
data class UndoNote(val text: String, val tokens: List<ContentStore.TrashToken>)

@Immutable
data class InstalledState(
    val tab: InstalledTab = InstalledTab.Mods,
    val rows: List<InstalledRow> = emptyList(),
    val filter: InstalledFilter = InstalledFilter.All,
    val loading: Boolean = true,
    val busy: Set<String> = emptySet(),
    val removal: RemovalPrompt? = null,
    val undo: UndoNote? = null,
    val message: String? = null,
) {
    val updates: Int get() = rows.count { it.update != null }
    val on: Int get() = rows.count { it.item.enabled }
    val shown: List<InstalledRow> get() = rows.filter { it.matches(filter) }
    fun count(filter: InstalledFilter): Int = rows.count { it.matches(filter) }
}

/**
 * One mod folder's content: mods, resource and shader packs live in [folder]; data packs and which packs are active,
 * in [directory] (an instance that uses the folder). The files are the truth: every action re-reads them. Network work
 * (update checks, identifying unknown jars) and the OpenGL scan run in the background and fill rows in as they finish.
 * Updates follow the folder's [gameVersion], [loader] and [includeBeta].
 */
class InstalledModel(
    private val scope: CoroutineScope,
    val directory: File,
    private val modrinth: Modrinth,
    private val glScans: GlScanCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val folder: File = directory,
    private val gameVersion: String = Target.GAME_VERSION,
    private val loader: Loader = Target.LOADER,
    private val includeBeta: Boolean = false,
) {
    private val mutable = MutableStateFlow(InstalledState())
    val state: StateFlow<InstalledState> = mutable.asStateFlow()
    private val store = ContentStore(directory, folder)
    private val index = ModIndex(folder)
    private var loading: Job? = null
    private var identified = false
    /** The last check's updates, by index key (jar name without `.disabled`). */
    private val updates = mutableMapOf<String, ModUpdate>()

    init {
        reload()
    }

    fun select(tab: InstalledTab) {
        if (tab == mutable.value.tab) return
        mutable.update { it.copy(tab = tab, rows = emptyList(), loading = true, filter = InstalledFilter.All) }
        reload()
    }

    fun filter(filter: InstalledFilter) = mutable.update { it.copy(filter = filter) }

    /** Pins or unpins a jar the index knows; a pinned jar shows no update. */
    fun pin(row: InstalledRow) {
        if (!row.pinnable) return
        val key = ModIndex.key(row.item.urlName)
        scope.launch {
            quietly { index.pin(key, !row.pinned) }
            if (!row.pinned) updates.remove(key)
            refresh()
        }
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
            quietly { InstanceStore.lock(folder).withLock { withContext(io) { note.tokens.asReversed().forEach(store::restore) } } }
            reload()
        }
    }

    fun show(message: String) = mutable.update { it.copy(message = message) }

    fun dismissMessage() = mutable.update { it.copy(message = null, undo = null) }

    fun update(row: InstalledRow) {
        val update = updateFor(row) ?: return
        busy(row.key) { apply(listOf(update)) }
    }

    fun updateAll() {
        val rows = mutable.value.rows.filter { it.update != null }
        val list = rows.mapNotNull(::updateFor)
        if (list.isEmpty()) return
        busy(*rows.map { it.key }.toTypedArray()) { apply(list) }
    }

    /** The checked update for [row], or one built from the row (a copied folder before its first check). */
    private fun updateFor(row: InstalledRow): ModUpdate? {
        val target = row.update ?: return null
        val key = ModIndex.key(row.item.urlName)
        return updates[key] ?: ModUpdate(row.item.urlName, row.item.name, target.projectId, row.mod?.versionId, row.mod?.version, target)
    }

    /** Through [ModUpdates.apply]: a snapshot first, rolled back if the folder could be left half-changed. */
    private suspend fun apply(list: List<ModUpdate>) {
        val result = ModUpdates.apply(folder, list, ModInstaller(folder, directory, modrinth = modrinth))
        result.updated.forEach { updates.remove(ModIndex.key(it.file)) }
        result.failed.firstOrNull()?.let { failed -> mutable.update { it.copy(message = "${failed.update.name}: ${failed.reason}") } }
    }

    // Reading

    private suspend fun read(tab: InstalledTab): List<InstalledRow> = withContext(io) {
        val mods = if (tab == InstalledTab.Mods) index.installed().associateBy { it.file } else emptyMap()
        val entries = if (tab == InstalledTab.Mods) runCatching { index.read() }.getOrDefault(emptyMap()) else emptyMap()
        tab.kinds.flatMap { kind -> runCatching { store.list(kind) }.getOrDefault(emptyList()) }.map { item ->
            val mod = mods[item.urlName]
            val id = item.modID ?: item.id
            val issue = ContentCompatibility.issue(id, item.name, item.kind, item.version)
                ?: item.takeIf(::scannable)?.let { glScans.cached(store.file(it)) }?.let { ContentCompatibility.glIssue(id, item.name, it) }
            val key = ModIndex.key(item.urlName)
            val pinned = entries[key]?.pinned == true
            InstalledRow(item, mod, update = updates[key]?.target?.takeIf { !pinned }, issue = issue, pinned = pinned)
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

    /** One Modrinth request for the whole folder ([ModUpdates.check]); offline means no updates shown. */
    private suspend fun checkUpdates() {
        val found = silently { ModUpdates.check(folder, modrinth, gameVersion, loader, includeBeta) } ?: return
        updates.clear()
        found.forEach { updates[ModIndex.key(it.file)] = it }
        refresh()
    }

    // Acting

    private fun trash(rows: List<InstalledRow>) {
        scope.launch {
            val tokens = quietly { InstanceStore.lock(folder).withLock { withContext(io) { rows.map { store.trash(it.item) } } } } ?: return@launch
            val text = if (rows.size == 1) "Removed ${rows[0].item.name}" else "Removed ${rows.size} items"
            mutable.update { it.copy(undo = UndoNote(text, tokens)) }
            refresh()
        }
    }

    private fun act(row: InstalledRow, block: () -> Unit) {
        scope.launch {
            quietly { InstanceStore.lock(folder).withLock { withContext(io) { block() } } }
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
