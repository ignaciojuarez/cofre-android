package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Immutable
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModUpdates
import com.ignacio.cofre.kit.pages.ProjectPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The mod page's tabs. Gallery shows only when there's something in it. */
enum class PageTab(val title: String) { About("About"), Gallery("Gallery"), Versions("Versions"), Changelog("Changelog"), Links("Links") }

/** What the page's main button does. */
enum class PageAction { Get, Update, Remove, Unavailable }

@Immutable
data class ProjectState(
    val summary: ProjectSummary,
    val page: ProjectPage? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** The installed copy in the current instance, if any. */
    val installed: InstalledRow? = null,
    val update: ModCandidate? = null,
    val changelogs: Map<String, ProjectPage.Body?> = emptyMap(),
) {
    val tabs: List<PageTab>
        get() = PageTab.entries.filter { tab ->
            when (tab) {
                PageTab.Gallery -> page?.gallery.orEmpty().isNotEmpty() || page?.videos.orEmpty().isNotEmpty()
                PageTab.Links -> page?.links.orEmpty().isNotEmpty() || summary.pageUrl != null
                else -> true
            }
        }

    /** Builds for this instance (26.2, and NeoForge for mods), newest first. */
    val fitting: List<ProjectPage.Version> get() = page?.versions.orEmpty().filter { it.fits(summary.kind) }
    val others: List<ProjectPage.Version> get() = page?.versions.orEmpty().filterNot { it.fits(summary.kind) }

    val action: PageAction
        get() = when {
            update != null -> PageAction.Update
            installed != null -> PageAction.Remove
            page != null && fitting.isEmpty() && summary.kind != ContentKind.Modpacks -> PageAction.Unavailable
            else -> PageAction.Get
        }
}

fun ProjectPage.Version.fits(kind: ContentKind): Boolean =
    Target.GAME_VERSION in gameVersions && (kind != ContentKind.Mods || loaders.isEmpty() || Target.LOADER.modrinthId in loaders)

/** One mod page: the project, its versions and changelogs, and whether the current instance has it. */
class ProjectModel(
    private val scope: CoroutineScope,
    summary: ProjectSummary,
    private val catalog: () -> Catalog,
    private val installedCopy: suspend (ProjectSummary) -> InstalledRow?,
) {
    private val mutable = MutableStateFlow(ProjectState(summary))
    val state: StateFlow<ProjectState> = mutable.asStateFlow()

    init {
        load()
    }

    fun load() {
        val summary = mutable.value.summary
        mutable.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val page = catalog().page(summary)
                mutable.update { it.copy(page = page, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
            refreshInstalled()
        }
    }

    /** After a Get, an update or a removal. */
    fun refreshInstalled() {
        val summary = mutable.value.summary
        scope.launch {
            val installed = try {
                installedCopy(summary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutable.update { it.copy(installed = installed, update = null) }
            val mod = installed?.mod ?: return@launch
            val versionId = mod.versionId ?: return@launch
            val update = try {
                val all = catalog().candidates(summary.source, summary.id)
                all.firstOrNull { it.versionId == versionId }?.let { current -> ModUpdates.candidate(current.copy(channel = mod.channel), all) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutable.update { it.copy(update = update) }
        }
    }

    /** Loads a version's changelog once; Modrinth often sends it with the version list already. */
    fun changelog(versionId: String) {
        val now = mutable.value
        if (versionId in now.changelogs) return
        now.page?.versions?.firstOrNull { it.id == versionId }?.changelog?.let { body ->
            mutable.update { it.copy(changelogs = it.changelogs + (versionId to body)) }
            return
        }
        scope.launch {
            val body = try {
                catalog().changelog(now.summary, versionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutable.update { it.copy(changelogs = it.changelogs + (versionId to body)) }
        }
    }
}
