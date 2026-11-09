package com.ignacio.cofre.kit.modrinth

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target

/** Facets are AND groups containing OR values. Empty fields mean any. */
data class ProjectFilters(
    val followGame: Boolean = true, val followLoader: Boolean = true,
    val clientSide: Boolean = false, val categories: Set<String> = emptySet(),
    val resolution: Int? = null, val sort: String = "relevance",
    val shaderLoader: String? = null,
) {
    fun facets(kind: ContentKind = ContentKind.Mods, game: String = Target.GAME_VERSION, loader: Loader = Target.LOADER): List<List<String>> = buildList {
        val type = when (kind) {
            ContentKind.Mods -> "mod"
            ContentKind.Shaders -> "shader"
            ContentKind.ResourcePacks -> "resourcepack"
            ContentKind.DataPacks -> "datapack"
            ContentKind.Modpacks -> "modpack"
        }
        add(listOf("project_type:$type"))
        if (followGame && game.isNotBlank()) add(listOf("versions:$game"))
        if (followLoader && kind in listOf(ContentKind.Mods, ContentKind.Modpacks) && loader !in listOf(Loader.Vanilla, Loader.Unknown)) add(listOf("categories:${loader.modrinthId}"))
        if (clientSide && kind in listOf(ContentKind.Mods, ContentKind.Modpacks)) add(listOf("client_side:required", "client_side:optional"))
        if (kind == ContentKind.ResourcePacks && resolution != null) add((if (resolution >= 128) listOf("128x", "256x", "512x+") else listOf("${resolution}x")).map { "categories:$it" })
        val shaders = shaderLoader ?: if (loader in listOf(Loader.Fabric, Loader.Quilt, Loader.NeoForge)) "iris" else "any"
        if (kind == ContentKind.Shaders && shaders != "any") add(listOf("categories:$shaders"))
        categories.filter { it in categoryIds(kind) }.sorted().forEach { add(listOf("categories:$it")) }
    }
    fun followInstance(): ProjectFilters = copy(followGame = true, followLoader = true, shaderLoader = null)
    fun activeCount(kind: ContentKind, curseForge: Boolean = false, game: String = Target.GAME_VERSION, loader: Loader = Target.LOADER): Int {
        val groups = facets(kind, game, loader).drop(1)
        return if (!curseForge) groups.size else groups.count { group -> group.any { it.startsWith("versions:") || it == "categories:${loader.modrinthId}" } }
    }
    companion object {
        val cleared: ProjectFilters = ProjectFilters(followGame = false, followLoader = false, shaderLoader = "any")
        fun categoryIds(kind: ContentKind): List<String> = when (kind) {
            ContentKind.Mods -> listOf("adventure", "decoration", "library", "magic", "optimization", "storage", "technology", "utility", "worldgen")
            ContentKind.Modpacks -> listOf("adventure", "kitchen-sink", "lightweight", "magic", "multiplayer", "optimization", "quests", "technology")
            ContentKind.Shaders -> listOf("realistic", "vanilla-like", "fantasy", "cartoon", "potato", "low", "medium", "high", "screenshot")
            ContentKind.ResourcePacks -> listOf("vanilla-like", "realistic", "simplistic", "themed", "gui", "audio", "models", "tweaks")
            ContentKind.DataPacks -> emptyList()
        }
        fun categoryLabel(id: String): String = if (id == "gui") "GUI" else id.replaceFirstChar { it.uppercase() }
    }
}
