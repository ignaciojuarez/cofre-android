package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.mods.ModIndex
import com.ignacio.cofre.kit.mods.ModInstaller
import com.ignacio.cofre.kit.mods.Snapshots
import com.ignacio.cofre.kit.pages.ProjectPage
import com.ignacio.cofre.message
import com.ignacio.cofre.onIo
import com.ignacio.cofre.ui.folders.FoldersModel
import com.ignacio.cofre.ui.folders.SnapshotRow
import com.ignacio.cofre.ui.folders.snapshotRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Detail routes. Plain strings, so the list-detail layout can save them in a Bundle. */
object ModsRoutes {
    const val INSTALLED = "installed"
    const val IMPORT = "import"
}

/** What Mods shows: browse with the folder beside it, or every folder. */
enum class ModsPage { Browse, Folders }

/** The folder Mods works on, with who uses it and how much is in it. */
@Immutable
data class FolderInfo(
    val folder: ModFolder,
    val usedBy: List<Instance>,
    val mods: Int = 0,
    /** The instance whose worlds the Data tab shows (the active one when it uses the folder). */
    val worlds: Instance? = usedBy.firstOrNull(),
) {
    val shared: Boolean get() = usedBy.size > 1
}

/** A change to a shared folder, waiting for "change both" or "copy for this instance only". */
@Immutable
data class SharedPrompt(
    val kind: SharedChange,
    val item: String,
    val folder: String,
    /** The instance a copy would be for (the active one when it uses the folder). */
    val instance: Instance,
    val others: List<String>,
    internal val run: (InstalledModel) -> Unit,
)

@Immutable
data class SnapshotSheet(val rows: List<SnapshotRow>?)

enum class SharedChange { Update, UpdateAll, Remove, TurnOff, TurnOn }

/**
 * The Mods section: browse, the open mod page, the Get in progress, and the mod folder it works on ([AppNav.modFolder],
 * else the active instance's). A ViewModel because it outlives section switches (the shell drops the section's composition).
 */
class ModsViewModel(private val graph: AppGraph) : ViewModel() {
    private val catalog: StateFlow<Catalog> = graph.curseForgeKey
        .map { key -> Catalog(graph.modrinth, key?.let(::CurseForge)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Catalog(graph.modrinth, graph.curseForgeKey.value?.let(::CurseForge)))

    val browse = BrowseModel(
        viewModelScope,
        search = { query, offset, limit -> catalog.value.search(query, offset, limit) },
        sourceReady = { it.source == ProjectPage.Source.Modrinth || catalog.value.hasCurseForge },
    )

    val hasCurseForge: StateFlow<Boolean> = catalog.map { it.hasCurseForge }
        .stateIn(viewModelScope, SharingStarted.Eagerly, catalog.value.hasCurseForge)

    /** The shell's current instance: where data packs go, and who a shared folder's copy is for. */
    private val mutableInstance = MutableStateFlow(graph.library.active)
    val instance: StateFlow<Instance?> = mutableInstance.asStateFlow()

    private val mutableFolder = MutableStateFlow<FolderInfo?>(null)
    val folder: StateFlow<FolderInfo?> = mutableFolder.asStateFlow()

    /** Every folder, for the "Adding to" picker. */
    private val mutableFolders = MutableStateFlow<List<ModFolder>>(emptyList())
    val folders: StateFlow<List<ModFolder>> = mutableFolders.asStateFlow()

    private val mutablePage = MutableStateFlow(ModsPage.Browse)
    val page: StateFlow<ModsPage> = mutablePage.asStateFlow()

    private val mutableRoute = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = mutableRoute.asStateFlow()

    private val mutableInstalled = MutableStateFlow<InstalledModel?>(null)
    val installed: StateFlow<InstalledModel?> = mutableInstalled.asStateFlow()

    /** Modrinth project id → the update's version (null when up to date), for the browse badges. */
    private val mutableInFolder = MutableStateFlow<Map<String, String?>>(emptyMap())
    val inFolder: StateFlow<Map<String, String?>> = mutableInFolder.asStateFlow()

    private val mutableInstall = MutableStateFlow<InstallModel?>(null)
    val install: StateFlow<InstallModel?> = mutableInstall.asStateFlow()

    private val mutableShared = MutableStateFlow<SharedPrompt?>(null)
    val shared: StateFlow<SharedPrompt?> = mutableShared.asStateFlow()

    /** The open Snapshots sheet (null when closed); its rows are null while they load. */
    private val mutableSnapshots = MutableStateFlow<SnapshotSheet?>(null)
    val snapshots: StateFlow<SnapshotSheet?> = mutableSnapshots.asStateFlow()

    val import = ImportModel(viewModelScope, graph.instances, graph.modFolders, graph.modrinth, { curseForge() }, File(graph.context.cacheDir, "mods"))

    val manage = FoldersModel(graph, viewModelScope)

    private val glScans = graph.glScans
    private val projects = mutableMapOf<String, ProjectModel>()
    private val summaries = mutableMapOf<String, ProjectSummary>()
    /** Shared folders the user already said "change both" for, this session. */
    private val sharedOk = mutableSetOf<String>()
    private var watching: Job? = null

    init {
        graph.library.refresh()
        viewModelScope.launch {
            snapshotFlow { Triple(graph.library.active, graph.nav.modFolder, graph.library.instances) }.collect { (active, _, _) ->
                mutableInstance.value = active
                useFolder()
            }
        }
        // A new key searches again, so picking CurseForge before adding one recovers on its own.
        viewModelScope.launch { catalog.drop(1).collect { browse.retry() } }
    }

    fun select(route: String?) {
        mutableRoute.value = route
    }

    fun show(page: ModsPage) {
        if (page == ModsPage.Folders) manage.reload()
        mutablePage.value = page
    }

    fun open(project: ProjectSummary) {
        summaries[project.route] = project
        mutableRoute.value = project.route
    }

    fun project(route: String): ProjectModel? {
        projects[route]?.let { return it }
        val summary = summaries[route] ?: return null
        return ProjectModel(viewModelScope, summary, { catalog.value }, ::installedCopy).also { projects[route] = it }
    }

    /** Get (or update to) [versionId] of [project] into the folder. Modpacks go through Import into a new instance. */
    fun get(project: ProjectSummary, versionId: String? = null) {
        if (project.kind == ContentKind.Modpacks) {
            mutableRoute.value = ModsRoutes.IMPORT
            import.openLink("https://modrinth.com/modpack/${project.slug ?: project.id}")
            return
        }
        val model = installed.value ?: return
        val info = folder.value ?: return
        val dir = model.directory
        val content = model.folder
        // The app's scope: leaving Mods mid-install must not cancel it halfway.
        mutableInstall.value = InstallModel(
            graph.scope, project, project.issue,
            plan = { optional ->
                val mods = withContext(Dispatchers.IO) { ModIndex(content).installed() }
                catalog.value.resolver(project, mods, info.folder.gameVersion, info.folder.loader).plan(project.id, versionId, optional)
            },
            install = { plan, onProgress ->
                val installer = ModInstaller(content, dir, modrinth = graph.modrinth)
                when (project.kind) {
                    ContentKind.Mods -> installer.install(plan, onProgress = onProgress)
                    else -> installer.install(requireNotNull(plan.target), project.kind, newestWorld(dir), onProgress = onProgress)
                }
                installed.value?.reload()
                projects[project.route]?.refreshInstalled()
                refreshCounts()
            },
        )
    }

    fun closeInstall() {
        mutableInstall.value = null
    }

    /** Removes the page's installed copy through the folder pane, so dependents are warned about and undo works. */
    fun remove(project: ProjectSummary) {
        viewModelScope.launch {
            val row = installedCopy(project) ?: return@launch
            remove(row)
            projects[project.route]?.refreshInstalled()
        }
    }

    // The folder

    /** Mods works on folder [id] from now on (the picker). */
    fun selectFolder(id: String) {
        graph.nav.modFolder = id
    }

    fun toggle(row: InstalledRow) =
        guarded(if (row.item.enabled) SharedChange.TurnOff else SharedChange.TurnOn, row.item.name) { it.toggle(row) }

    fun remove(row: InstalledRow) = guarded(SharedChange.Remove, row.item.name) { it.remove(row) }

    fun update(row: InstalledRow) = guarded(SharedChange.Update, row.item.name) { it.update(row) }

    fun updateAll() = guarded(SharedChange.UpdateAll, "${installed.value?.state?.value?.updates ?: 0}") { it.updateAll() }

    fun pin(row: InstalledRow) {
        installed.value?.pin(row)
    }

    fun autoUpdate(value: AutoUpdate) {
        val info = folder.value ?: return
        work { graph.modFolders.save(info.folder.copy(autoUpdate = value)) }
    }

    /** "Change both": this folder doesn't ask again until Mods is recreated. */
    fun sharedBoth() {
        val prompt = mutableShared.value ?: return
        mutableShared.value = null
        folder.value?.let { sharedOk += it.folder.id }
        installed.value?.let(prompt.run)
    }

    /** "Copy folder for <instance> only": the instance gets its own copy, Mods switches to it, and the change happens there. */
    fun sharedCopy() {
        val prompt = mutableShared.value ?: return
        mutableShared.value = null
        graph.library.copyFolderFor(prompt.instance.id) { made ->
            val id = made.modFolder ?: return@copyFolderFor
            graph.nav.modFolder = id
            viewModelScope.launch {
                useFolder()
                if (folder.value?.folder?.id == id) installed.value?.let(prompt.run)
            }
        }
    }

    fun dismissShared() {
        mutableShared.value = null
    }

    fun openSnapshots() {
        val dir = installed.value?.folder ?: return
        mutableSnapshots.value = SnapshotSheet(null)
        viewModelScope.launch { mutableSnapshots.value = SnapshotSheet(runCatching { snapshotRows(dir) }.getOrDefault(emptyList())) }
    }

    fun closeSnapshots() {
        mutableSnapshots.value = null
    }

    /** Puts the mods back as they were; mods it rolls back are pinned so updates leave them alone. */
    fun restore(snapshot: Snapshots.Snapshot) {
        val model = installed.value ?: return
        if (refuseWhileRunning(model)) return
        mutableSnapshots.value = null
        viewModelScope.launch {
            runCatching { Snapshots.restore(model.folder, snapshot, pin = true) }
                .onFailure { e -> model.show(graph.context.message(e)) }
            model.reload()
            refreshCounts()
        }
    }

    /** No instance yet: make the default NeoForge 26.2 one (Library's own create) so Get has somewhere to go. */
    fun createInstance() {
        val library = graph.library
        library.create(graph.instances.uniqueName("${Target.LOADER.displayName} ${Target.GAME_VERSION}"), library.neoForgeVersions.first(), preset = null) { made ->
            library.select(made.id)
        }
    }

    /** Asks first when the folder is shared (once per folder); refuses while the game runs. */
    private fun guarded(kind: SharedChange, item: String, run: (InstalledModel) -> Unit) {
        val model = installed.value ?: return
        val info = folder.value ?: return
        if (refuseWhileRunning(model)) return
        if (!info.shared || info.folder.id in sharedOk) return run(model)
        val mine = info.usedBy.firstOrNull { it.id == instance.value?.id } ?: info.usedBy.first()
        mutableShared.value = SharedPrompt(kind, item, info.folder.name, mine, info.usedBy.filter { it.id != mine.id }.map { it.name }, run)
    }

    private fun refuseWhileRunning(model: InstalledModel): Boolean {
        if (!graph.launcher.gameRunning) return false
        model.show(graph.context.getString(R.string.error_game_running))
        return true
    }

    /** Follows [AppNav.modFolder], else the active instance's folder. A new folder gets a new pane model. */
    private suspend fun useFolder() {
        val folders = graph.onIo { runCatching { graph.modFolders.list() }.getOrDefault(emptyList()) }
        mutableFolders.value = folders
        val active = mutableInstance.value
        val id = graph.nav.modFolder?.takeIf { wanted -> folders.any { it.id == wanted } } ?: active?.modFolder
        val picked = folders.firstOrNull { it.id == id }
        if (picked == null) {
            mutableFolder.value = null
            mutableInstalled.value = null
            mutableInFolder.value = emptyMap()
            return
        }
        val usedBy = graph.modFolders.usedBy(picked.id, graph.library.instances)
        val current = mutableFolder.value
        val worlds = usedBy.firstOrNull { it.id == active?.id } ?: usedBy.firstOrNull()
        mutableFolder.value = FolderInfo(picked, usedBy, current?.takeIf { it.folder.id == picked.id }?.mods ?: 0, worlds)
        val dir = graph.modFolders.directory(picked)
        val game = worlds?.let(graph.instances::directory) ?: dir
        val model = mutableInstalled.value
        val same = model != null && model.folder == dir && model.directory == game && current?.folder?.let { it.includeBeta == picked.includeBeta } == true
        if (!same) {
            mutableInstalled.value = InstalledModel(
                viewModelScope, game, graph.modrinth, glScans, folder = dir,
                gameVersion = picked.gameVersion, loader = picked.loader, includeBeta = picked.includeBeta,
            )
            browse.setQuery { it.copy(game = picked.gameVersion, loader = picked.loader) }
            watchInstalled()
            projects.values.forEach { it.refreshInstalled() }
        }
        refreshCounts()
    }

    /** Keeps the browse badges in step with the folder's Mods tab. */
    private fun watchInstalled() {
        watching?.cancel()
        val model = installed.value ?: return
        val dir = model.folder
        mutableInFolder.value = emptyMap()
        watching = viewModelScope.launch {
            val known = graph.onIo { runCatching { ModIndex(dir).read().values.map { it.projectId } }.getOrDefault(emptyList()) }
            mutableInFolder.value = known.associateWith { null }
            model.state.collect { state ->
                if (state.tab != InstalledTab.Mods || state.loading) return@collect
                mutableInFolder.value = state.rows.mapNotNull { row -> row.mod?.projectId?.let { it to row.update?.version } }.toMap()
            }
        }
    }

    private suspend fun refreshCounts() {
        val info = mutableFolder.value ?: return
        val dir = graph.modFolders.directory(info.folder)
        val mods = graph.onIo { ModIndex(dir).jars().size }
        mutableFolder.value = mutableFolder.value?.takeIf { it.folder.id == info.folder.id }?.copy(mods = mods)
    }

    private fun work(block: () -> Unit) {
        viewModelScope.launch {
            runCatching { graph.onIo(block) }.onFailure { e -> installed.value?.show(graph.context.message(e)) }
            useFolder()
        }
    }

    private fun curseForge(): CurseForge? = graph.curseForgeKey.value?.let(::CurseForge)

    /** The installed copy of [project] in the folder: by Modrinth/CurseForge id for mods, by name otherwise. */
    private suspend fun installedCopy(project: ProjectSummary): InstalledRow? = withContext(Dispatchers.IO) {
        val model = installed.value ?: return@withContext null
        val store = ContentStore(model.directory, model.folder)
        if (project.kind == ContentKind.Mods) {
            val mod = ModIndex(model.folder).installed().firstOrNull { it.matches(project.id, project.title) } ?: return@withContext null
            val item = store.list(ContentKind.Mods).firstOrNull { it.urlName == mod.file } ?: return@withContext null
            InstalledRow(item, mod)
        } else if (project.kind.folderName != null && project.kind != ContentKind.DataPacks) {
            val key = project.title.lowercase().filter(Char::isLetterOrDigit)
            store.list(project.kind)
                .firstOrNull { item -> key.isNotEmpty() && item.file.lowercase().filter(Char::isLetterOrDigit).startsWith(key) }
                ?.let { InstalledRow(it) }
        } else {
            null
        }
    }

    /** Data packs go to the most recently played world. */
    private fun newestWorld(dir: File): String? =
        File(dir, "saves").listFiles()?.filter { it.isDirectory && File(it, "level.dat").isFile }?.maxByOrNull { it.lastModified() }?.name
}
