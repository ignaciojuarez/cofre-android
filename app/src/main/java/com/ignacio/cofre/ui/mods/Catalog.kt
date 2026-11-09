package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Immutable
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.compat.CompatibilityIssue
import com.ignacio.cofre.kit.compat.ContentCompatibility
import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.mods.InstalledMod
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModResolver
import com.ignacio.cofre.kit.modrinth.Modrinth
import com.ignacio.cofre.kit.modrinth.ProjectFilters
import com.ignacio.cofre.kit.pages.ProjectPage

/** A search hit from either source: what a browse row and the top of a mod page show. */
@Immutable
data class ProjectSummary(
    val id: String,
    val source: ProjectPage.Source,
    val kind: ContentKind,
    val title: String,
    val summary: String,
    val iconUrl: String? = null,
    val downloads: Long? = null,
    val slug: String? = null,
    val pageUrl: String? = null,
) {
    /** What the known-mods rules say about it on Vulkan + NeoForge 26.2, if anything. */
    val issue: CompatibilityIssue? get() = ContentCompatibility.issue(slug ?: id, title, kind)

    /** The detail route; a plain string so the list-detail layout can save it. */
    val route: String get() = "project:${source.name}:$id"
}

/** What the browser searches: text, kind, source and filters. Equal queries never search twice. */
@Immutable
data class BrowseQuery(
    val text: String = "",
    val kind: ContentKind = ContentKind.Mods,
    val source: ProjectPage.Source = ProjectPage.Source.Modrinth,
    val filters: ProjectFilters = ProjectFilters(),
) {
    /** The filters that differ from "fits this instance, by relevance". */
    val activeFilters: Int
        get() = filters.categories.size + (if (filters.sort != "relevance") 1 else 0) + (if (!fitsInstance) 1 else 0)

    val fitsInstance: Boolean get() = filters.followGame && filters.followLoader
}

/** Short badge text for a compatibility issue: what a row can say in two words. */
val CompatibilityIssue.badge: String
    get() = when {
        kind == ContentKind.Shaders -> "Not on Vulkan"
        reason.contains("OpenGL") -> "Needs OpenGL"
        severity == "slow" -> "Slow on Vulkan"
        else -> "Won't run"
    }

/**
 * The two project sources behind one search and resolve surface. CurseForge is there only when the user gave a key.
 * Networking stays in kit; this only maps both into [ProjectSummary] and [ModCandidate].
 */
class Catalog(private val modrinth: Modrinth, private val curseForge: CurseForge?) {
    val hasCurseForge: Boolean get() = curseForge != null

    suspend fun search(query: BrowseQuery, offset: Int, limit: Int): List<ProjectSummary> = when (query.source) {
        ProjectPage.Source.Modrinth ->
            modrinth.search(query.text, query.filters.facets(query.kind), query.filters.sort, offset, limit).map { hit ->
                ProjectSummary(
                    hit.projectId, ProjectPage.Source.Modrinth, kindOf(hit.projectType, query.kind), hit.title, hit.description,
                    hit.iconUrl, hit.downloads, hit.slug, hit.pageUrl,
                )
            }
        ProjectPage.Source.CurseForge -> {
            val client = requireNotNull(curseForge) { "CurseForge needs a key" }
            // CurseForge's search has no class filter in kit yet, so the NeoForge loader filter is what keeps it to mods.
            client.search(
                query.text,
                gameVersion = if (query.filters.followGame) Target.GAME_VERSION else null,
                neoForge = query.filters.followLoader,
                index = offset,
                pageSize = limit.coerceAtMost(50),
            ).map { mod ->
                ProjectSummary(
                    "${mod.id}", ProjectPage.Source.CurseForge, query.kind, mod.name, mod.summary,
                    mod.logo?.thumbnailUrl ?: mod.logo?.url, null, mod.slug, mod.links.websiteUrl.ifBlank { null },
                )
            }
        }
    }

    suspend fun page(project: ProjectSummary): ProjectPage = when (project.source) {
        ProjectPage.Source.Modrinth -> ProjectPage.load(modrinth, project.id)
        ProjectPage.Source.CurseForge -> ProjectPage.load(requireNotNull(curseForge), project.id.toInt())
    }

    suspend fun changelog(project: ProjectSummary, versionId: String): ProjectPage.Body? = when (project.source) {
        ProjectPage.Source.Modrinth -> ProjectPage.changelog(modrinth, versionId)
        ProjectPage.Source.CurseForge -> ProjectPage.changelog(requireNotNull(curseForge), project.id.toInt(), versionId.toInt())
    }

    /** Every build of a project, newest first, as install candidates. */
    suspend fun candidates(source: ProjectPage.Source, id: String): List<ModCandidate> = when (source) {
        ProjectPage.Source.Modrinth -> {
            val title = modrinth.project(id).title
            modrinth.versions(id, includeChangelog = false).map { ModCandidate.from(it, title) }
        }
        ProjectPage.Source.CurseForge -> {
            val client = requireNotNull(curseForge)
            val mod = client.mod(id.toInt())
            client.files(mod.id, Target.GAME_VERSION).sortedByDescending { it.fileDate }.map { ModCandidate.from(it, mod) }
        }
    }

    fun resolver(project: ProjectSummary, installed: List<InstalledMod>): ModResolver = ModResolver(
        installed,
        kind = project.kind,
        versionById = { id -> if (project.source == ProjectPage.Source.Modrinth) ModCandidate.from(modrinth.version(id)) else null },
        versions = { id -> candidates(project.source, id) },
    )

    private fun kindOf(type: String, fallback: ContentKind): ContentKind = when (type) {
        "mod" -> ContentKind.Mods
        "resourcepack" -> ContentKind.ResourcePacks
        "shader" -> ContentKind.Shaders
        "datapack" -> ContentKind.DataPacks
        "modpack" -> ContentKind.Modpacks
        else -> fallback
    }
}
