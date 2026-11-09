package com.ignacio.cofre.kit.modrinth

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target

/** Facets are AND groups containing OR values. Empty fields mean any. */
data class ProjectFilters(
    val followGame: Boolean = true, val followLoader: Boolean = true,
    val categories: Set<String> = emptySet(), val sort: String = "relevance",
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
        if (kind == ContentKind.Shaders && loader in listOf(Loader.Fabric, Loader.Quilt, Loader.NeoForge)) add(listOf("categories:iris"))
        categories.filter { it in categoryIds(kind) }.sorted().forEach { add(listOf("categories:$it")) }
    }
    companion object {
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
