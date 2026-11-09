package com.ignacio.cofre.kit.pages

import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class ProjectPage(
    val id: String, val source: Source, val title: String, val summary: String = "",
    val iconUrl: String? = null, val body: Body? = null, val gallery: List<Image> = emptyList(),
    val links: List<Link> = emptyList(), val license: String? = null,
    val versions: List<Version> = emptyList(),
) {
    @Serializable enum class Source { Modrinth, CurseForge }
    @Serializable sealed interface Body {
        @Serializable data class Markdown(val text: String) : Body
        @Serializable data class Html(val text: String) : Body
    }
    @Serializable data class Image(val id: String, val thumb: String, val full: String = thumb, val title: String? = null, val caption: String? = null, val featured: Boolean = false)
    @Serializable data class Link(val kind: String, val url: String)
    @Serializable data class Version(val id: String, val name: String, val gameVersions: List<String>, val loaders: List<String>, val channel: String, val changelog: Body? = null)
    data class Video(val id: String) {
        init { require(id.matches(Regex("[A-Za-z0-9_-]{6,32}"))) }
        val watch: String get() = "https://www.youtube.com/watch?v=$id"
        val thumb: String get() = "https://img.youtube.com/vi/$id/hqdefault.jpg"
    }
    /** Extract only URLs; rendering/sanitizing markup belongs to the UI. Markdown is never rewritten. */
    val videos: List<Video> get() {
        val text = when (val content = body) { is Body.Markdown -> content.text; is Body.Html -> content.text; null -> "" }
        val pattern = Regex("https?://(?:www\\.)?(?:youtube(?:-nocookie)?\\.com/(?:embed/|shorts/|watch\\?(?:[^\\s\"'<>]*?&(?:amp;)?)?v=)|youtu\\.be/)([A-Za-z0-9_-]{6,32})(?=[^A-Za-z0-9_-]|$)", RegexOption.IGNORE_CASE)
        return pattern.findAll(text).map { Video(it.groupValues[1]) }.distinctBy { it.id }.toList()
    }
    companion object {
        fun from(project: Modrinth.Project, versions: List<Modrinth.Version> = emptyList()): ProjectPage {
            val fixed = listOf("source" to project.sourceUrl, "issues" to project.issuesUrl, "wiki" to project.wikiUrl, "discord" to project.discordUrl)
            val links = (fixed + project.donations.map { "donate:${it.platform.orEmpty()}" to it.url }).mapNotNull { (kind, url) -> web(url)?.let { Link(kind, it) } }
            val gallery = project.gallery.sortedWith(compareByDescending<Modrinth.Shot> { it.featured }.thenBy { it.ordering }).mapNotNull { shot ->
                web(shot.url)?.let { Image(shot.url, it, web(shot.rawUrl) ?: it, shot.title.nonempty(), shot.description.nonempty(), shot.featured) }
            }
            return ProjectPage(project.id, Source.Modrinth, project.title, project.description, web(project.icon), project.body.nonempty()?.let(Body::Markdown), gallery, links,
                project.license?.let { it.name.nonempty() ?: it.id.removePrefix("LicenseRef-").replace('-', ' ') },
                versions.map { Version(it.id, it.name, it.gameVersions, it.loaders, it.versionType, it.changelog?.let(Body::Markdown)) })
        }
        fun from(mod: CurseForge.Mod, description: String, files: List<CurseForge.File> = emptyList()): ProjectPage = ProjectPage(
            "${mod.id}", Source.CurseForge, mod.name, mod.summary, web(mod.logo?.thumbnailUrl ?: mod.logo?.url), description.nonempty()?.let(Body::Html),
            mod.screenshots.mapNotNull { image -> web(image.url)?.let { Image("${image.id}", web(image.thumbnailUrl) ?: it, it, image.title.nonempty(), image.description.nonempty()) } },
            listOf("website" to mod.links.websiteUrl, "source" to mod.links.sourceUrl, "issues" to mod.links.issuesUrl, "wiki" to mod.links.wikiUrl).mapNotNull { (kind, url) -> web(url)?.let { Link(kind, it) } },
            versions = files.map { Version("${it.id}", it.displayName, it.gameVersions.filter { v -> v.firstOrNull()?.isDigit() == true }, it.gameVersions.filter { v -> v.lowercase() in setOf("neoforge", "forge", "fabric", "quilt") }.map(String::lowercase), when (it.releaseType) { 2 -> "beta"; 3 -> "alpha"; else -> "release" }) },
        )
        suspend fun load(client: Modrinth, id: String): ProjectPage = from(client.project(id), client.matchingFirst(id, listOf(Target.LOADER.modrinthId), listOf(Target.GAME_VERSION)))
        suspend fun load(client: CurseForge, id: Int): ProjectPage = from(client.mod(id), client.description(id), client.files(id, Target.GAME_VERSION))
        suspend fun changelog(client: Modrinth, id: String): Body? = client.version(id).changelog?.let(Body::Markdown)
        suspend fun changelog(client: CurseForge, modId: Int, fileId: Int): Body = Body.Html(client.changelog(modId, fileId))
        private fun String?.nonempty(): String? = this?.takeIf { it.isNotBlank() }
        private fun web(value: String?): String? = value?.takeIf { it.toHttpUrlOrNull() != null }
    }
}

class Gallery(images: List<ProjectPage.Image>, videos: List<ProjectPage.Video> = emptyList()) {
    constructor(page: ProjectPage) : this(page.gallery, page.videos)
    val images: List<ProjectPage.Image> = images.distinctBy { it.id }
    val videos: List<ProjectPage.Video> = videos.distinctBy { it.id }
    sealed interface Item {
        data class Image(val image: ProjectPage.Image, val index: Int) : Item
        data class Video(val video: ProjectPage.Video) : Item
    }
    val items: List<Item> = this.videos.map(Item::Video) + this.images.mapIndexed { index, image -> Item.Image(image, index) }
    val count: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
}
