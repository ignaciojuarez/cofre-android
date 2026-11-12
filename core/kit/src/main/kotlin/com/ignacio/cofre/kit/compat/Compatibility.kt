package com.ignacio.cofre.kit.compat

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.mods.ModIndex
import com.ignacio.cofre.kit.mods.ModJarMetadata
import com.ignacio.cofre.kit.mods.ModRemoval
import com.ignacio.cofre.kit.mods.ModSide
import com.ignacio.cofre.kit.mods.Relation
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
 * [severity]: "broken" means it won't work (a known-mods rule, or a jar FML refuses to load); "slow" comes from a rule;
 * "likely" and "may" grade an unknown jar's OpenGL references (see [GlRisk]) and are warnings, not verdicts.
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
    /**
     * What "Before you play" checks: enabled mods and the active shader pack. Jars are graded through [scans], shared
     * with the Installed tab. [neoForge] is the build the instance runs (its `cofre.json`); null skips that range check.
     */
    fun playIssues(gameDirectory: File, scans: GlScanCache, contentDirectory: File = gameDirectory, neoForge: String? = neoForgeBuild(gameDirectory)): List<CompatibilityIssue> {
        val store = ContentStore(gameDirectory, contentDirectory)
        val mods = store.list(ContentKind.Mods).filter { it.enabled }
        val issues = mods.mapNotNull { m -> issue(m.modID ?: m.id, m.name, ContentKind.Mods, m.version)?.copy(file = m.id) }.toMutableList()
        loadIssues(mods, store, neoForge).filter { found -> issues.none { it.file == found.file } }.let(issues::addAll)
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
    /** Also turns off the enabled mods that need one going off, or the next check asks for it back. */
    fun turnOff(issues: List<CompatibilityIssue>, gameDirectory: File, contentDirectory: File = gameDirectory) {
        val store = ContentStore(gameDirectory, contentDirectory)
        val mods = issues.filter { it.kind == ContentKind.Mods }.mapNotNull { it.file }.toSet()
        val stranded = runCatching { ModRemoval.stranded(mods, ModIndex(contentDirectory).installed()) }.getOrDefault(emptyList()).map { ContentKind.Mods to it.file }
        for ((kind, file) in issues.map { it.kind to it.file } + stranded) store.list(kind).firstOrNull { it.id == file && it.enabled }?.let { store.setEnabled(false, it) }
    }

    private class Jar(val item: ContentStore.Item, val file: File, val meta: ModJarMetadata)
    /**
     * What FML itself refuses, read from each enabled jar's metadata: a jar that isn't a NeoForge mod, a second copy
     * of a mod (the newer by version, then file date, stays), a Minecraft or NeoForge outside a required range, and a
     * mod it declares incompatible. All "broken", one per jar; a range that can't be read never counts.
     */
    private fun loadIssues(mods: List<ContentStore.Item>, store: ContentStore, neoForge: String?): List<CompatibilityIssue> {
        val jars = mods.mapNotNull { m -> store.file(m).takeIf { it.isFile && it.name.endsWith(".jar") }?.let { f -> ModJarMetadata.read(f)?.let { Jar(m, f, it) } } }
        val issues = linkedMapOf<String, CompatibilityIssue>()
        fun flag(jar: Jar, reason: String) { issues.putIfAbsent(jar.item.id, CompatibilityIssue(jar.item.modID ?: jar.item.id, jar.item.name, ContentKind.Mods, reason, "broken", jar.item.id)) }
        // Sinytra Connector loads Fabric mods on NeoForge.
        val connector = jars.any { it.meta.neoForge && "connector" in it.meta.own }
        for (jar in jars) if (!jar.meta.neoForge && !(connector && jar.meta.loaders.any { it == Loader.Fabric || it == Loader.Quilt })) {
            flag(jar, "Not a NeoForge mod: it's made for ${jar.meta.loaders.joinToString(" or ") { if (it == Loader.Forge) "Forge or an older NeoForge" else it.displayName }}.")
        }
        val loaded = jars.filter { it.meta.neoForge }
        val newer = Comparator<Jar> { a, b -> MavenRange.compare(a.meta.version, b.meta.version).takeIf { it != 0 } ?: a.file.lastModified().compareTo(b.file.lastModified()) }
        for (copies in loaded.flatMap { jar -> jar.meta.own.map { it.lowercase() to jar } }.groupBy({ it.first }, { it.second }).values.filter { it.size > 1 }) {
            val keep = copies.maxWith(newer)
            copies.filter { it !== keep }.forEach { flag(it, "Another copy, ${keep.file.name}, is on too. Two copies stop the game.") }
        }
        for (jar in loaded) for (dependency in jar.meta.dependencies) {
            val range = dependency.versionRange
            if (dependency.relation == Relation.Incompatible && dependency.side != ModSide.Server) {
                val other = loaded.firstOrNull { o -> o !== jar && o.meta.modIds.any { it.equals(dependency.id, true) } && (range == null || MavenRange.contains(range, o.meta.version.orEmpty()) == true) }
                if (other != null) flag(jar, "Can't run with ${other.item.name}.")
                continue
            }
            if (range == null || !dependency.required) continue
            val (name, have) = when (dependency.id.lowercase()) { "minecraft" -> "Minecraft" to Target.GAME_VERSION; "neoforge" -> "NeoForge" to (neoForge ?: continue); else -> continue }
            if (MavenRange.contains(range, have) == false) flag(jar, MavenRange.needs(name, range, have))
        }
        return issues.values.toList()
    }
    /** The NeoForge build the instance in [gameDirectory] runs, from its `cofre.json`. */
    private fun neoForgeBuild(gameDirectory: File): String? = runCatching { Kit.json.decodeFromString<Instance>(File(gameDirectory, "cofre.json").readText()).loaderVersion }.getOrNull()
}

/**
 * FML's `versionRange`, in Maven's syntax: "[26.2,26.3)", "[26.2.0.80,)", "(,)", "[1.0]", "*", a bare "1.0" (any
 * version), or ranges joined by commas. Versions compare by their dotted numbers, then a release beats its "-beta"
 * style qualifier ("+build" is ignored). Null means unreadable, which never blocks anything.
 */
internal object MavenRange {
    private class Bound(val version: String, val inclusive: Boolean)
    private class Span(val lower: Bound?, val upper: Bound?) {
        fun aboveLower(v: String) = lower == null || compare(v, lower.version).let { if (lower.inclusive) it >= 0 else it > 0 }
        fun belowUpper(v: String) = upper == null || compare(v, upper.version).let { if (upper.inclusive) it <= 0 else it < 0 }
    }
    private val ranges = Regex("[\\[(][^\\])]*[\\])](,[\\[(][^\\])]*[\\])])*")
    private val range = Regex("([\\[(])([^\\])]*)([\\])])")
    private fun spans(spec: String): List<Span>? {
        val text = spec.filterNot(Char::isWhitespace)
        if (text.isEmpty() || text == "*") return listOf(Span(null, null))
        if (!ranges.matches(text)) return if (parts(text) != null) listOf(Span(null, null)) else null
        return range.findAll(text).toList().map { m ->
            val (open, body, close) = m.destructured
            val ends = body.split(',').map { it.ifEmpty { null } }
            if (ends.any { it != null && parts(it) == null }) return null
            when (ends.size) {
                1 -> if (open == "[" && close == "]" && ends[0] != null) Span(Bound(ends[0]!!, true), Bound(ends[0]!!, true)) else return null
                2 -> Span(ends[0]?.let { Bound(it, open == "[") }, ends[1]?.let { Bound(it, close == "]") })
                else -> return null
            }
        }
    }
    fun contains(spec: String, version: String): Boolean? {
        val spans = spans(spec) ?: return null
        if (parts(version) == null) return null
        return spans.any { it.aboveLower(version) && it.belowUpper(version) }
    }
    /** The plain clue for [have] falling outside [spec]: "Needs NeoForge 26.2.0.100 or newer." */
    fun needs(name: String, spec: String, have: String): String {
        val span = spans(spec)?.singleOrNull() ?: return "Needs $name $spec."
        val low = span.lower; val high = span.upper
        return when {
            low != null && high != null && low.version == high.version -> "Needs $name ${low.version}."
            low != null && !span.aboveLower(have) -> if (low.inclusive) "Needs $name ${low.version} or newer." else "Needs $name newer than ${low.version}."
            high != null -> if (high.inclusive) "Needs $name ${high.version} or older." else "Needs $name older than ${high.version}."
            else -> "Needs $name $spec."
        }
    }
    /** 0 when either can't be read. */
    fun compare(a: String?, b: String?): Int {
        val x = a?.let(::parts) ?: return 0; val y = b?.let(::parts) ?: return 0
        for (i in 0 until maxOf(x.first.size, y.first.size)) x.first.getOrElse(i) { 0 }.compareTo(y.first.getOrElse(i) { 0 }).let { if (it != 0) return it }
        return when { x.second == y.second -> 0; x.second.isEmpty() -> 1; y.second.isEmpty() -> -1; else -> x.second.compareTo(y.second) }
    }
    private fun parts(v: String): Pair<List<Long>, String>? {
        val m = Regex("(\\d+(?:\\.\\d+)*)(.*)").matchEntire(v.trim()) ?: return null
        val numbers = m.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
        return numbers to m.groupValues[2].let { if (it.startsWith('+')) "" else it.trimStart('-', '.').lowercase() }
    }
}
