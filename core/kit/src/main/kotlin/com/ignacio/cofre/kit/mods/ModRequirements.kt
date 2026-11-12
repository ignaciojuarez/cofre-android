package com.ignacio.cofre.kit.mods

import kotlinx.serialization.Serializable
import java.io.File

@Serializable data class ModRequirement(val modId: String, val name: String = modId, val projectId: String? = null, val reason: String, val neededBy: String? = null) {
    val slugs: List<String> get() = if (projectId != null) emptyList() else listOfNotNull(ModRequirements.aliases[modId], modId, modId.replace('_', '-')).distinct()
}
object ModRequirements {
    val builtIn: Set<String> = setOf("minecraft", "forge", "neoforge", "fml", "javafml", "lowcodefml", "mcp", "java", "fabricloader", "fabric-loader")
    val aliases: Map<String, String> = mapOf("kotlinforforge" to "kotlin-for-forge", "architectury" to "architectury-api", "cloth_config" to "cloth-config", "forgeconfigapiport" to "forge-config-api-port", "puzzleslib" to "puzzles-lib", "resourcefullib" to "resourceful-lib", "bookshelf" to "bookshelf-lib", "owo" to "owo-lib", "yet_another_config_lib_v3" to "yacl", "fabric" to "fabric-api", "fabric_language_kotlin" to "fabric-language-kotlin", "sophisticatedcore" to "sophisticated-core")
    fun requirement(id: String, reason: String, neededBy: String? = null): ModRequirement {
        val key = id.lowercase()
        return ModRequirement(key, when (key) { "kotlinforforge" -> "Kotlin for Forge"; "terrablender" -> "TerraBlender"; "glitchcore" -> "GlitchCore"; else -> key }, if (key == "kotlinforforge") "ordsPcFz" else null, reason, neededBy)
    }
    /** Exact mod ids from each jar's metadata and bundled jars; a file name counts only for [otherFiles], jars without metadata. */
    fun missing(jars: Map<String, ModJarMetadata>, otherFiles: List<String> = emptyList()): List<ModRequirement> {
        val ids = jars.values.flatMap { it.modIds + it.bundled }.map(::modKey).toSet()
        val files = otherFiles.map(::modKey)
        fun present(id: String): Boolean = id.lowercase() in builtIn || modKey(id) in ids || files.any { it.startsWith(modKey(id)) }
        return buildList {
            for ((file, jar) in jars) {
                if (!jar.neoForge) continue // NeoForge won't load it ("Before you play" says so), so its needs don't count.
                if (jar.modLoader?.lowercase() == "kotlinforforge" && !present("kotlinforforge")) add(requirement("kotlinforforge", "modLoader:kotlinforforge", file))
                jar.dependencies.filter { it.required && !present(it.id) }.forEach { add(requirement(it.id, "dependency:${it.id}", file)) }
            }
        }.distinctBy { it.modId }
    }
    /** Preflight: what [folder]'s (a mod folder's) enabled jars require that nothing installed provides. Jar metadata is cached per file. */
    fun missing(folder: File): List<ModRequirement> {
        val jars = File(folder, "mods").listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }.orEmpty()
        val metadata = jars.mapNotNull { jar -> ModJarMetadata.read(jar)?.let { jar.name to it } }.toMap()
        return missing(metadata, jars.filter { it.name !in metadata }.map { it.name })
    }
}

object ModRemoval {
    fun dependents(removing: String, nodes: List<InstalledMod>): List<InstalledMod> {
        val root = nodes.firstOrNull { it.file == removing || it.projectId == removing } ?: return emptyList()
        val affected = linkedSetOf(root.file)
        var changed: Boolean
        do {
            changed = false
            nodes.filter { it.file !in affected }.forEach { node ->
                if (node.dependencies.any { dependency -> dependency.required && nodes.any { it.file in affected && modKey(dependency.id) in it.keys } }) { affected += node.file; changed = true }
            }
        } while (changed)
        return nodes.filter { it.file != root.file && it.file in affected }
    }
    /** Enabled mods left without a required mod once [off] (files) are turned off, through chains; a mod another enabled jar still provides isn't missed. */
    fun stranded(off: Set<String>, nodes: List<InstalledMod>): List<InstalledMod> {
        val enabled = nodes.filter { it.enabled }; val gone = off.toMutableSet()
        fun lost(id: String) = enabled.any { it.file in gone && modKey(id) in it.keys } && enabled.none { it.file !in gone && modKey(id) in it.keys }
        do {
            val more = enabled.filter { node -> node.file !in gone && node.dependencies.any { it.required && lost(it.id) } }
            more.forEach { gone += it.file }
        } while (more.isNotEmpty())
        return enabled.filter { it.file in gone && it.file !in off }
    }
    fun options(removing: String, nodes: List<InstalledMod>): List<InstalledMod> {
        val root = nodes.firstOrNull { it.file == removing || it.projectId == removing } ?: return emptyList()
        val reachable = linkedSetOf(root.file)
        var changed: Boolean
        do {
            changed = false
            nodes.filter { it.file in reachable }.flatMap { it.dependencies }.filter { it.required }.forEach { dependency ->
                nodes.firstOrNull { modKey(dependency.id) in it.keys && !it.installedDirectly && !it.protectedFromRemoval }?.let { if (reachable.add(it.file)) changed = true }
            }
        } while (changed)
        return keepOnlyUnneeded(root.file, reachable, nodes).sortedBy { it.name.lowercase() }
    }
    fun selected(removing: String, candidates: Set<String>, nodes: List<InstalledMod>): List<InstalledMod> {
        val root = nodes.firstOrNull { it.file == removing || it.projectId == removing } ?: return emptyList()
        val offered = options(root.file, nodes).map { it.file }.toSet()
        return keepOnlyUnneeded(root.file, setOf(root.file) + candidates.intersect(offered), nodes)
    }
    private fun keepOnlyUnneeded(root: String, candidates: Set<String>, nodes: List<InstalledMod>): List<InstalledMod> {
        val removed = candidates.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            nodes.filter { it.file !in removed }.forEach { node ->
                node.dependencies.filter { it.required }.forEach { dependency ->
                    nodes.filter { modKey(dependency.id) in it.keys && it.file != root }.forEach { if (removed.remove(it.file)) changed = true }
                }
            }
        } while (changed)
        return nodes.filter { it.file != root && it.file in removed }
    }
}
