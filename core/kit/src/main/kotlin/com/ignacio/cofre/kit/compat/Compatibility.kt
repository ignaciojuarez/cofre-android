package com.ignacio.cofre.kit.compat

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.content.ContentStore
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class KnownMod(val ids: List<String> = emptyList(), val slugs: List<String> = emptyList(), val fileNamePrefixes: List<String> = emptyList(), val namePatterns: List<String> = emptyList(), val name: String, val scope: String, val loaders: Set<Loader>? = null, val gameVersions: Set<String>? = null, val severity: String, val reason: String, val evidence: String, val evidenceRef: String? = null) {
    fun matches(id: String, name: String, fileName: String? = null, gameVersion: String = Target.GAME_VERSION, renderer: String = "vulkan", loader: Loader = Target.LOADER): Boolean = specificity(id, name, fileName) > 0 &&
        (loaders.isNullOrEmpty() || loader in loaders) &&
        (gameVersions.isNullOrEmpty() || gameVersion in gameVersions || ("26.x" in gameVersions && gameVersion.startsWith("26."))) &&
        (scope == "anyRenderer" || (scope == "vulkan" && renderer.equals("vulkan", true)))
    internal fun specificity(id: String, name: String, fileName: String?): Int {
        val exact = (ids + slugs + namePatterns).map(::normalize)
        if (normalize(id.removeSuffix(".jar")) in exact || normalize(name) in exact) return 10_000
        return fileNamePrefixes.map(::normalize).filter { it.isNotEmpty() && (normalize(id).startsWith(it) || normalize(fileName ?: id).startsWith(it)) }.maxOfOrNull { it.length } ?: 0
    }
    fun renderedReason(renderer: String, gameVersion: String): String = reason.replace("{renderer}", renderer).replace("{gameVersion}", gameVersion)
    companion object { fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() } }
}

object KnownModsCatalog {
    @Serializable data class Manifest(val schema: Int, val mods: List<KnownMod>)
    fun decode(text: String): Manifest = Kit.json.decodeFromString<Manifest>(text).also(::validate)
    fun validate(manifest: Manifest) {
        require(manifest.schema == 1)
        require(manifest.mods.map { "${it.name}:${it.scope}:${it.reason}" }.distinct().size == manifest.mods.size)
        for (m in manifest.mods) {
            require(m.name.isNotBlank() && m.reason.isNotBlank() && m.scope in setOf("vulkan", "anyRenderer"))
            require(m.severity in setOf("broken", "slow") && m.evidence in setOf("device", "expected"))
            require(m.evidence != "device" || !m.evidenceRef.isNullOrBlank())
            require((m.ids + m.slugs + m.fileNamePrefixes + m.namePatterns).any { it.isNotBlank() })
        }
    }
    val entries: List<KnownMod> by lazy { decode(requireNotNull(javaClass.getResourceAsStream("/known-mods.json")).bufferedReader().use { it.readText() }).mods }
    fun match(id: String, name: String, fileName: String? = null, gameVersion: String = Target.GAME_VERSION, renderer: String = "vulkan", loader: Loader = Target.LOADER): KnownMod? = entries.filter { it.matches(id, name, fileName, gameVersion, renderer, loader) }.maxByOrNull { it.specificity(id, name, fileName) }
    fun name(id: String, fileName: String? = null): String? = entries.filter { it.specificity(id, "", fileName) > 0 }.maxByOrNull { it.specificity(id, "", fileName) }?.name
}

data class CompatibilityIssue(val id: String, val name: String, val kind: ContentKind, val reason: String, val severity: String = "broken", val file: String? = null)

object ContentCompatibility {
    fun issue(id: String, name: String, kind: ContentKind, gameVersion: String = Target.GAME_VERSION, renderer: String = "vulkan", loader: Loader = Target.LOADER): CompatibilityIssue? {
        if (kind == ContentKind.Shaders && renderer.equals("vulkan", true)) return CompatibilityIssue(id, name, kind, "Shader packs require OpenGL and cannot run on Vulkan.")
        if (kind != ContentKind.Mods) return null
        val rule = KnownModsCatalog.match(id, name, name, gameVersion, renderer, loader) ?: return null
        return CompatibilityIssue(id, rule.name, kind, rule.renderedReason(renderer, gameVersion), rule.severity)
    }
    fun playIssues(gameDirectory: File): List<CompatibilityIssue> {
        val store = ContentStore(gameDirectory)
        val mods = store.list(ContentKind.Mods).filter { it.enabled }
        val issues = mods.mapNotNull { m -> issue(m.modID ?: m.id, m.name, ContentKind.Mods)?.copy(file = m.id) }.toMutableList()
        if (issues.none { it.name in setOf("Iris", "Oculus", "OptiFine") }) store.list(ContentKind.Shaders).firstOrNull { it.inUse }?.let { s -> issue(s.id, s.name, ContentKind.Shaders)?.copy(file = s.id)?.let(issues::add) }
        for (m in mods) {
            if (issues.any { it.file == m.id }) continue
            val jar = store.file(m)
            if (jar.isFile && jar.name.endsWith(".jar")) {
                val scan = GlUsageScanner.scan(jar)
                if (scan.usages.isNotEmpty()) issues += CompatibilityIssue(m.modID ?: m.id, m.name, ContentKind.Mods, "Calls OpenGL from ${scan.usages.joinToString { it.className }}. Vulkan has no OpenGL context.", file = m.id)
            }
        }
        return issues
    }
    fun turnOff(issues: List<CompatibilityIssue>, gameDirectory: File) {
        val store = ContentStore(gameDirectory)
        for (issue in issues) store.list(issue.kind).firstOrNull { it.id == issue.file && it.enabled }?.let { store.setEnabled(false, it) }
    }
}
