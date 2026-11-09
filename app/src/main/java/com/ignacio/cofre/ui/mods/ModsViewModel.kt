package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.mods.ModIndex
import com.ignacio.cofre.kit.mods.ModInstaller
import com.ignacio.cofre.kit.pages.ProjectPage
import kotlinx.coroutines.Dispatchers
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

/**
 * The Mods section: browse, the open mod page, the Get in progress, Installed and Import for the current
 * instance. A ViewModel because it outlives section switches (the shell drops the section's composition).
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

    /** The instance Get installs into: the shell's current one. */
    private val mutableInstance = MutableStateFlow(graph.library.active)
    val instance: StateFlow<Instance?> = mutableInstance.asStateFlow()

    /** Every instance, for the switcher. */
    val instances: StateFlow<List<Instance>> = snapshotFlow { graph.library.instances }
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.library.instances)

    private val mutableRoute = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = mutableRoute.asStateFlow()

    private val mutableInstalled = MutableStateFlow<InstalledModel?>(null)
    val installed: StateFlow<InstalledModel?> = mutableInstalled.asStateFlow()

    private val mutableInstall = MutableStateFlow<InstallModel?>(null)
    val install: StateFlow<InstallModel?> = mutableInstall.asStateFlow()

    val import = ImportModel(viewModelScope, graph.instances, graph.modrinth, { curseForge() }, File(graph.context.cacheDir, "mods"))

    private val glScans = GlScanCache(File(graph.context.cacheDir, "mods/gl-risk.json"))
    private val projects = mutableMapOf<String, ProjectModel>()
    private val summaries = mutableMapOf<String, ProjectSummary>()

    init {
        graph.library.refresh()
        useInstance(graph.library.active)
        viewModelScope.launch { snapshotFlow { graph.library.active }.collect(::useInstance) }
        // A new key searches again, so picking CurseForge before adding one recovers on its own.
        viewModelScope.launch { catalog.drop(1).collect { browse.retry() } }
    }

    fun select(route: String?) {
        mutableRoute.value = route
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

    /** Get (or update to) [versionId] of [project]. Modpacks go through Import into a new instance. */
    fun get(project: ProjectSummary, versionId: String? = null) {
        if (project.kind == ContentKind.Modpacks) {
            mutableRoute.value = ModsRoutes.IMPORT
            import.openLink("https://modrinth.com/modpack/${project.slug ?: project.id}")
            return
        }
        val dir = directory() ?: return
        // The app's scope: leaving Mods mid-install must not cancel it halfway.
        mutableInstall.value = InstallModel(
            graph.scope, project, project.issue,
            plan = { optional ->
                val mods = withContext(Dispatchers.IO) { ModIndex(dir).installed() }
                catalog.value.resolver(project, mods).plan(project.id, versionId, optional)
            },
            install = { plan, onProgress ->
                val installer = ModInstaller(dir, modrinth = graph.modrinth)
                when (project.kind) {
                    ContentKind.Mods -> installer.install(plan, onProgress = onProgress)
                    else -> installer.install(requireNotNull(plan.target), project.kind, newestWorld(dir), onProgress = onProgress)
                }
                installed.value?.reload()
                projects[project.route]?.refreshInstalled()
            },
        )
    }

    fun closeInstall() {
        mutableInstall.value = null
    }

    /** Removes the page's installed copy through Installed, so dependents are warned about and undo works. */
    fun remove(project: ProjectSummary) {
        val model = installed.value ?: return
        viewModelScope.launch {
            val row = installedCopy(project) ?: return@launch
            model.remove(row)
            projects[project.route]?.refreshInstalled()
        }
    }

    /** Get mods for another instance; it becomes the shell's current one too. */
    fun selectInstance(id: String) {
        val picked = graph.library.instances.firstOrNull { it.id == id } ?: return
        graph.library.select(id)
        useInstance(picked)
    }

    /** No instance yet: make the default NeoForge 26.2 one (Library's own create) so Get has somewhere to go. */
    fun createInstance() {
        val library = graph.library
        library.create(uniqueName("NeoForge 26.2"), library.neoForgeVersions.first(), preset = null) { made ->
            library.select(made.id)
            useInstance(made)
        }
    }

    private fun useInstance(instance: Instance?) {
        if (instance?.id == mutableInstance.value?.id && mutableInstalled.value != null) return
        mutableInstance.value = instance
        mutableInstalled.value = instance?.let { InstalledModel(viewModelScope, graph.instances.directory(it), graph.modrinth, glScans) }
        projects.values.forEach { it.refreshInstalled() }
    }

    private fun directory(): File? = instance.value?.let(graph.instances::directory)

    private fun curseForge(): CurseForge? = graph.curseForgeKey.value?.let(::CurseForge)

    private fun uniqueName(name: String): String {
        val taken = graph.instances.list().map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name ($it)" }.first { it !in taken }
    }

    /** The installed copy of [project] in the current instance: by Modrinth/CurseForge id for mods, by name otherwise. */
    private suspend fun installedCopy(project: ProjectSummary): InstalledRow? = withContext(Dispatchers.IO) {
        val dir = directory() ?: return@withContext null
        if (project.kind == ContentKind.Mods) {
            val mod = ModIndex(dir).installed().firstOrNull { it.matches(project.id, project.title) } ?: return@withContext null
            val item = ContentStore(dir).list(ContentKind.Mods).firstOrNull { it.urlName == mod.file } ?: return@withContext null
            InstalledRow(item, mod)
        } else if (project.kind.folderName != null && project.kind != ContentKind.DataPacks) {
            val key = project.title.lowercase().filter(Char::isLetterOrDigit)
            ContentStore(dir).list(project.kind)
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
