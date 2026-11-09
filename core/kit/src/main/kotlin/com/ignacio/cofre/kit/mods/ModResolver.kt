package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class VersionChannel {
    @SerialName("release") Release, @SerialName("beta") Beta, @SerialName("alpha") Alpha;
    fun allowsUpdate(next: VersionChannel): Boolean = next.ordinal <= ordinal
    companion object { fun from(raw: String?): VersionChannel = entries.firstOrNull { it.name.equals(raw, true) } ?: Release }
}

@Serializable data class ModCandidate(
    val projectId: String, val versionId: String, val title: String, val version: String,
    val gameVersions: List<String>, val loaders: List<Loader>, val channel: VersionChannel = VersionChannel.Release,
    val dependencies: List<Dependency> = emptyList(), val filename: String? = null, val url: String? = null,
    val size: Long? = null, val sha1: String? = null, val sha512: String? = null,
    val downloadable: Boolean = true, val websiteUrl: String? = null,
    val source: String = "modrinth", val publishedAt: String? = null,
) {
    @Serializable data class Dependency(val projectId: String? = null, val versionId: String? = null, val relation: Relation = Relation.Required)
    fun fits(game: String = Target.GAME_VERSION, loader: Loader = Target.LOADER, kind: ContentKind = ContentKind.Mods): Boolean =
        game in gameVersions && (kind != ContentKind.Mods || loaders.isEmpty() || loader in loaders || loader == Loader.Quilt && Loader.Fabric in loaders)
    companion object {
        fun from(version: Modrinth.Version, title: String = version.name): ModCandidate {
            val file = version.primaryFile
            return ModCandidate(version.projectId, version.id, title, version.versionNumber, version.gameVersions, version.loaders.map(Loader::from).filter { it != Loader.Unknown }, VersionChannel.from(version.versionType),
                version.dependencies.mapNotNull { dependency ->
                    val relation = when (dependency.dependencyType) { "required" -> Relation.Required; "optional" -> Relation.Optional; "incompatible" -> Relation.Incompatible; else -> return@mapNotNull null }
                    Dependency(dependency.projectId, dependency.versionId, relation)
                }, file?.filename, file?.url, file?.size, file?.hashes?.get("sha1"), file?.hashes?.get("sha512"), file?.url != null,
                publishedAt = version.datePublished)
        }
        fun from(file: CurseForge.File, mod: CurseForge.Mod): ModCandidate = ModCandidate("${mod.id}", "${file.id}", mod.name, file.displayName,
            file.gameVersions.filter { it.firstOrNull()?.isDigit() == true }, file.gameVersions.map(Loader::from).filter { it != Loader.Unknown },
            when (file.releaseType) { 2 -> VersionChannel.Beta; 3 -> VersionChannel.Alpha; else -> VersionChannel.Release },
            file.dependencies.mapNotNull { dependency ->
                val relation = when (dependency.relationType) { 3 -> Relation.Required; 2 -> Relation.Optional; 5 -> Relation.Incompatible; else -> return@mapNotNull null }
                Dependency("${dependency.modId}", relation = relation)
            }, file.fileName, file.downloadUrl, file.fileLength, file.hashes.firstOrNull { it.algo == 1 }?.value,
            downloadable = file.downloadUrl != null, websiteUrl = mod.links.websiteUrl, source = "curseforge", publishedAt = file.fileDate)
    }
}

@Serializable data class InstalledMod(
    val file: String, val name: String, val projectId: String? = null, val versionId: String? = null,
    val version: String? = null, val aliases: List<String> = emptyList(), val enabled: Boolean = true,
    val dependencies: List<JarDependency> = emptyList(), val installedDirectly: Boolean = true,
    val protectedFromRemoval: Boolean = false, val channel: VersionChannel = VersionChannel.Release,
) {
    val keys: Set<String> get() = (listOfNotNull(projectId, name, file.removeSuffix(".disabled").removeSuffix(".jar")) + aliases).map(::modKey).toSet()
    fun matches(id: String, title: String? = null): Boolean = projectId == id || modKey(id) in keys || title?.let { modKey(it) in keys } == true
}
internal fun modKey(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

@Serializable sealed interface PlanBlock {
    @Serializable data class MissingBuild(val projectId: String, val reason: String) : PlanBlock
    @Serializable data class Incompatible(val first: String, val second: String) : PlanBlock
    @Serializable data class VersionConflict(val projectId: String) : PlanBlock
    @Serializable data class DownloadDisabled(val projectId: String, val websiteUrl: String?) : PlanBlock
}
@Serializable data class ModInstallPlan(
    val target: ModCandidate?, val required: List<ModCandidate> = emptyList(),
    val optional: List<ModCandidate> = emptyList(), val present: List<String> = emptyList(),
    val turnsOn: List<InstalledMod> = emptyList(), val blocked: PlanBlock? = null,
) {
    val downloads: List<ModCandidate> get() = listOfNotNull(target) + required
    val downloadBytes: Long get() = downloads.sumOf { it.size ?: 0 }
    val needsConfirmation: Boolean get() = required.isNotEmpty() || optional.isNotEmpty() || turnsOn.isNotEmpty() || downloads.any { it.channel != VersionChannel.Release } || blocked != null
}

/** Catalogs are newest first. The resolver is pure apart from the two supplied suspend lookups. */
class ModResolver(
    private val installed: List<InstalledMod> = emptyList(),
    private val game: String = Target.GAME_VERSION, private val loader: Loader = Target.LOADER,
    private val kind: ContentKind = ContentKind.Mods,
    private val versionById: suspend (String) -> ModCandidate? = { null },
    private val versions: suspend (String) -> List<ModCandidate>,
) {
    fun pick(all: List<ModCandidate>, pinned: String? = null): ModCandidate? {
        var fits = all.filter { it.fits(game, loader, kind) }
        val named = fits.filter { it.loaders.isNotEmpty() }
        if (kind == ContentKind.Mods && named.isNotEmpty()) fits = named
        return fits.firstOrNull { it.versionId == pinned } ?: fits.minByOrNull { it.channel.ordinal }
    }
    /** Re-plan with chosen optional IDs so their required dependencies are resolved too. */
    suspend fun plan(projectId: String, versionId: String? = null, selectedOptional: Set<String> = emptySet()): ModInstallPlan {
        val catalog = mutableMapOf<String, List<ModCandidate>>()
        suspend fun options(id: String): List<ModCandidate> = catalog.getOrPutSuspend(id) { versions(id) }
        val rootOptions = options(projectId)
        val root = pick(rootOptions, versionId) ?: return ModInstallPlan(null, blocked = missing(projectId, rootOptions))
        var plan = ModInstallPlan(root)
        if (!root.downloadable) return plan.copy(blocked = PlanBlock.DownloadDisabled(projectId, root.websiteUrl))
        if (kind != ContentKind.Mods) return plan
        val chosen = linkedMapOf(root.projectId to root)
        val queue = ArrayDeque<ModCandidate>(); queue.add(root)
        val optional = linkedMapOf<String, ModCandidate>()
        val present = linkedSetOf<String>(); val turnsOn = linkedMapOf<String, InstalledMod>()
        while (queue.isNotEmpty()) {
            val mod = queue.removeFirst()
            for (dep in mod.dependencies) {
                val pinnedCandidate = if (dep.projectId == null && dep.versionId != null) versionById(dep.versionId) else null
                val id = dep.projectId ?: pinnedCandidate?.projectId
                if (id == null) {
                    if (dep.relation == Relation.Required) return plan.copy(blocked = PlanBlock.MissingBuild(dep.versionId.orEmpty(), "Dependency has no project identity"))
                    continue
                }
                if (dep.relation == Relation.Incompatible) continue // Check after all required and selected optional builds are known.
                if (dep.relation == Relation.Optional && (mod != root || id !in selectedOptional)) {
                    if (mod == root && installed.none { it.matches(id) } && id !in chosen) {
                        try { pick(options(id), dep.versionId)?.takeIf { it.downloadable }?.let { optional[id] = it } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* An unavailable optional mod never blocks the required install. */ }
                    }
                    continue
                }
                val existingChoice = chosen[id]
                if (existingChoice != null) {
                    if (dep.versionId != null && existingChoice.versionId != dep.versionId) return plan.copy(blocked = PlanBlock.VersionConflict(id))
                    continue
                }
                var have = installed.firstOrNull { it.matches(id) }
                if (have != null && (dep.versionId == null || have.versionId == dep.versionId)) {
                    if (have.enabled) present += have.name else turnsOn[have.file] = have
                    continue
                }
                val builds = if (pinnedCandidate != null) listOf(pinnedCandidate) else options(id)
                if (have == null) have = installed.firstOrNull { it.matches(id, builds.firstOrNull()?.title) }
                if (have != null && (dep.versionId == null || have.versionId == dep.versionId)) {
                    if (have.enabled) present += have.name else turnsOn[have.file] = have
                    continue
                }
                val found = if (dep.versionId == null) pick(builds) else builds.firstOrNull { it.versionId == dep.versionId && it.fits(game, loader, kind) }
                if (found == null) {
                    if (mod.loaders.distinct().size > 1 && builds.isNotEmpty() && builds.none { loader in it.loaders } && builds.any { it.loaders.any(mod.loaders::contains) }) continue
                    return plan.copy(blocked = missing(id, builds))
                }
                if (!found.downloadable) return plan.copy(blocked = PlanBlock.DownloadDisabled(id, found.websiteUrl))
                chosen[id] = found; queue.add(found); optional.remove(id)
            }
        }
        for (mod in chosen.values) for (dep in mod.dependencies.filter { it.relation == Relation.Incompatible }) {
            val id = dep.projectId ?: dep.versionId?.let { versionById(it)?.projectId } ?: continue
            val clash = chosen[id]?.takeIf { dep.versionId == null || it.versionId == dep.versionId }
            val have = installed.firstOrNull { it.enabled && it.matches(id) && (dep.versionId == null || it.versionId == dep.versionId) }
            if (clash != null || have != null) return plan.copy(blocked = PlanBlock.Incompatible(mod.title, clash?.title ?: have!!.name))
        }
        for (have in installed.filter { it.enabled }) for (dep in have.dependencies.filter { it.relation == Relation.Incompatible }) {
            val clash = chosen.values.firstOrNull { it.projectId == dep.id || modKey(it.title) == modKey(dep.id) }
            if (clash != null) return plan.copy(blocked = PlanBlock.Incompatible(have.name, clash.title))
        }
        plan = plan.copy(required = chosen.values.drop(1), optional = optional.values.filter { it.projectId !in chosen }, present = present.toList(), turnsOn = turnsOn.values.toList())
        return plan
    }
    private fun missing(id: String, builds: List<ModCandidate>): PlanBlock.MissingBuild {
        val sameGame = builds.filter { game in it.gameVersions }
        val reason = if (sameGame.isEmpty()) "No ${loader.displayName} $game version yet" else "Only available for ${sameGame.flatMap { it.loaders }.distinct().joinToString { it.displayName }}"
        return PlanBlock.MissingBuild(id, reason)
    }
    companion object {
        fun modrinth(client: Modrinth, installed: List<InstalledMod> = emptyList()): ModResolver = ModResolver(installed,
            versionById = { id -> ModCandidate.from(client.version(id)) }, versions = { id ->
                val project = client.project(id)
                client.versions(id).map { ModCandidate.from(it, project.title) }
            })
    }
}
private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspend(key: K, load: suspend () -> V): V = this[key] ?: load().also { this[key] = it }

object ModUpdates {
    /** The current version must be in the catalog, or carry a publication date; never guess an upgrade. */
    fun candidate(current: ModCandidate, newestFirst: List<ModCandidate>): ModCandidate? {
        val currentIndex = newestFirst.indexOfFirst { it.versionId == current.versionId }
        val newer = if (currentIndex >= 0) newestFirst.take(currentIndex) else newestFirst.filter { candidate -> current.publishedAt != null && candidate.publishedAt != null && runCatching { java.time.Instant.parse(candidate.publishedAt).isAfter(java.time.Instant.parse(current.publishedAt)) }.getOrDefault(false) }
        return newer.firstOrNull { it.projectId == current.projectId && it.fits() && it.downloadable && current.channel.allowsUpdate(it.channel) }
    }
}

object VersionLabel {
    fun clean(number: String): String {
        var value = number.trim()
        for (pattern in listOf("\\s*\\([^)]*\\)$", "\\.(jar|zip)$", "[-+_]?\\b(fabric|neoforge|forge|quilt)\\b(?=$|[-+_])", "^[-+_]+", "^mc[0-9.]+[-+_]", "\\+(mc)?[0-9][0-9.]*([-+_][0-9a-z.]*)?$", "[-_]mc[0-9][0-9.]*$", "^[a-z][a-z0-9 ]*?[-_ ](?=v?[0-9])", "^v(?=[0-9])")) value = value.replace(Regex(pattern, RegexOption.IGNORE_CASE), "")
        value = value.replace(Regex("[-.+_ ]?(beta|alpha|pre|rc)[-._ ]?([0-9]+)?$", RegexOption.IGNORE_CASE), " $1 $2").trim()
        return value.ifEmpty { number }
    }
}
