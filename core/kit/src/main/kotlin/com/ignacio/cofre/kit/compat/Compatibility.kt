package com.ignacio.cofre.kit.compat

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.content.ContentStore
import kotlinx.serialization.Serializable
import java.io.File

/**
 * One known-mods rule. [severity] "broken" or "slow" warns; "works" vouches for a mod whose OpenGL code only runs on
 * the GL backend, so the jar scan is skipped. [minVersion] (leading numbers, e.g. "0.9.0") must be met by the jar's own
 * version; a rule with one never matches a mod whose version is unknown.
 */
@Serializable
data class KnownMod(val ids: List<String> = emptyList(), val slugs: List<String> = emptyList(), val fileNamePrefixes: List<String> = emptyList(), val namePatterns: List<String> = emptyList(), val name: String, val scope: String, val loaders: Set<Loader>? = null, val gameVersions: Set<String>? = null, val minVersion: String? = null, val severity: String, val reason: String, val evidence: String, val evidenceRef: String? = null) {
    val works: Boolean get() = severity == "works"
    fun matches(id: String, name: String, fileName: String? = null, gameVersion: String = Target.GAME_VERSION, renderer: String = "vulkan", loader: Loader = Target.LOADER, version: String? = null): Boolean = specificity(id, name, fileName) > 0 &&
        (minVersion == null || atLeast(version, minVersion)) &&
        (loaders.isNullOrEmpty() || loader in loaders) &&
        (gameVersions.isNullOrEmpty() || gameVersion in gameVersions || ("26.x" in gameVersions && gameVersion.startsWith("26."))) &&
        (scope == "anyRenderer" || (scope == "vulkan" && renderer.equals("vulkan", true)))
    internal fun specificity(id: String, name: String, fileName: String?): Int {
        val exact = (ids + slugs + namePatterns).map(::normalize)
        if (works) return if (normalize(id) in ids.map(::normalize)) 10_000 else 0 // Vouching never goes by a display name.
        if (normalize(id.removeSuffix(".jar")) in exact || normalize(name) in exact) return 10_000
        return fileNamePrefixes.map(::normalize).filter { it.isNotEmpty() && (normalize(id).startsWith(it) || normalize(fileName ?: id).startsWith(it)) }.maxOfOrNull { it.length } ?: 0
    }
    fun renderedReason(renderer: String, gameVersion: String): String = reason.replace("{renderer}", renderer).replace("{gameVersion}", gameVersion)
    companion object {
        fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
        /** Compares the leading dotted numbers only: "0.9.2+mc26.2" ≥ "0.9.0". Null or unparseable is never enough. */
        internal fun atLeast(version: String?, min: String): Boolean {
            fun parts(v: String) = Regex("^v?(\\d+(?:\\.\\d+)*)").find(v.trim())?.groupValues?.get(1)?.split('.')?.map { it.toLongOrNull() ?: 0 }
            val have = version?.let(::parts) ?: return false
            val need = parts(min) ?: return false
            for (i in 0 until maxOf(have.size, need.size)) {
                val c = have.getOrElse(i) { 0 }.compareTo(need.getOrElse(i) { 0 })
                if (c != 0) return c > 0
            }
            return true
        }
    }
}

object KnownModsCatalog {
    @Serializable data class Manifest(val schema: Int, val mods: List<KnownMod>)
    fun decode(text: String): Manifest = Kit.json.decodeFromString<Manifest>(text).also(::validate)
    fun validate(manifest: Manifest) {
        require(manifest.schema == 1)
        require(manifest.mods.map { "${it.name}:${it.scope}:${it.reason}" }.distinct().size == manifest.mods.size)
        for (m in manifest.mods) {
            require(m.name.isNotBlank() && m.reason.isNotBlank() && m.scope in setOf("vulkan", "anyRenderer"))
            require(m.severity in setOf("broken", "slow", "works") && m.evidence in setOf("device", "expected"))
            require(m.minVersion == null || KnownMod.atLeast(m.minVersion, m.minVersion))
            require(!m.works || (m.ids.isNotEmpty() && m.fileNamePrefixes.isEmpty() && m.namePatterns.isEmpty())) { "Only a mod id can vouch" }
            require(m.evidence != "device" || !m.evidenceRef.isNullOrBlank())
            require((m.ids + m.slugs + m.fileNamePrefixes + m.namePatterns).any { it.isNotBlank() })
        }
    }
    val entries: List<KnownMod> by lazy { decode(requireNotNull(javaClass.getResourceAsStream("/known-mods.json")).bufferedReader().use { it.readText() }).mods }
    fun match(id: String, name: String, fileName: String? = null, gameVersion: String = Target.GAME_VERSION, renderer: String = "vulkan", loader: Loader = Target.LOADER, version: String? = null): KnownMod? = entries.filter { it.matches(id, name, fileName, gameVersion, renderer, loader, version) }.maxByOrNull { it.specificity(id, name, fileName) }
    fun name(id: String, fileName: String? = null): String? = entries.filter { it.specificity(id, "", fileName) > 0 }.maxByOrNull { it.specificity(id, "", fileName) }?.name
}

/**
 * [severity]: "broken" and "slow" come from known-mods rules (only "broken" means it will crash); "likely" and "may"
 * grade an unknown jar's OpenGL references (see [GlRisk]) and are warnings, not verdicts.
 */
data class CompatibilityIssue(val id: String, val name: String, val kind: ContentKind, val reason: String, val severity: String = "broken", val file: String? = null)

object ContentCompatibility {
    /** What the rules say; null when nothing is known or a rule vouches for this version. */
    fun issue(id: String, name: String, kind: ContentKind, version: String? = null, gameVersion: String = Target.GAME_VERSION, renderer: String = "vulkan", loader: Loader = Target.LOADER): CompatibilityIssue? {
        if (kind == ContentKind.Shaders && renderer.equals("vulkan", true)) return CompatibilityIssue(id, name, kind, "Shader packs need OpenGL, so they won't run on Vulkan.")
        if (kind != ContentKind.Mods) return null
        val rule = KnownModsCatalog.match(id, name, name, gameVersion, renderer, loader, version)?.takeUnless { it.works } ?: return null
        return CompatibilityIssue(id, rule.name, kind, rule.renderedReason(renderer, gameVersion), rule.severity)
    }
    /** A rule says this mod version works on Vulkan, so its jar isn't scanned. */
    fun vouched(id: String, name: String, version: String?): Boolean = KnownModsCatalog.match(id, name, name, version = version)?.works == true
    /** The graded scan finding for a mod no rule knows. */
    fun glIssue(id: String, name: String, risk: GlRisk, file: String? = null): CompatibilityIssue? = when (risk) {
        GlRisk.None -> null
        GlRisk.May -> CompatibilityIssue(id, name, ContentKind.Mods, "Uses OpenGL code paths, may not work on Vulkan.", "may", file)
        GlRisk.Likely -> CompatibilityIssue(id, name, ContentKind.Mods, "Calls OpenGL directly, likely to crash on Vulkan.", "likely", file)
    }
    /** What "Before you play" checks: enabled mods and the active shader pack. Jars are graded through [scans], shared with the Installed tab. */
    fun playIssues(gameDirectory: File, scans: GlScanCache): List<CompatibilityIssue> {
        val store = ContentStore(gameDirectory)
        val mods = store.list(ContentKind.Mods).filter { it.enabled }
        val issues = mods.mapNotNull { m -> issue(m.modID ?: m.id, m.name, ContentKind.Mods, m.version)?.copy(file = m.id) }.toMutableList()
        if (issues.none { it.name in setOf("Iris", "Oculus", "OptiFine") }) store.list(ContentKind.Shaders).firstOrNull { it.inUse }?.let { s -> issue(s.id, s.name, ContentKind.Shaders)?.copy(file = s.id)?.let(issues::add) }
        for (m in mods) {
            if (issues.any { it.file == m.id } || vouched(m.modID ?: m.id, m.name, m.version)) continue
            val jar = store.file(m)
            if (!jar.isFile || !jar.name.endsWith(".jar")) continue
            // A jar the scanner can't read isn't graded; if it breaks the game, the crash report names it.
            val risk = runCatching { scans.scan(jar) }.getOrNull() ?: continue
            glIssue(m.modID ?: m.id, m.name, risk, m.id)?.let(issues::add)
        }
        return issues
    }
    fun turnOff(issues: List<CompatibilityIssue>, gameDirectory: File) {
        val store = ContentStore(gameDirectory)
        for (issue in issues) store.list(issue.kind).firstOrNull { it.id == issue.file && it.enabled }?.let { store.setEnabled(false, it) }
    }
}
