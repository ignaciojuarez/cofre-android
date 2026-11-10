package com.ignacio.cofre.kit.modfolders

import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.HttpFailure
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.downloads.DownloadProgress
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.modrinth.Modrinth
import com.ignacio.cofre.kit.mods.InstalledMod
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModIndex
import com.ignacio.cofre.kit.mods.ModInstallFailure
import com.ignacio.cofre.kit.mods.ModJarMetadata
import com.ignacio.cofre.kit.mods.ModResolver
import com.ignacio.cofre.kit.mods.PlanBlock
import com.ignacio.cofre.kit.mods.Relation
import com.ignacio.cofre.kit.mods.VersionChannel
import com.ignacio.cofre.kit.mods.modKey
import com.ignacio.cofre.kit.mods.safeFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.io.File

/** Where a folder converts to: another loader, game version or loader build. [includeBeta] lets betas count as ready. */
data class ConvertTarget(val gameVersion: String, val loader: Loader, val includeBeta: Boolean = false)

/** One row of the two-column review. */
enum class ConvertStatus {
    /** A fitting target build was found. */
    Ready,
    /** Only a beta or alpha fits and the target excludes betas; [ConvertChoices.betas] can take it anyway. */
    BetaOnly,
    /** Known on Modrinth, but no fitting build; see [ConvertRow.gap]. */
    Unavailable,
    /** No build for the target loader, but a different project looks like the same mod there ([ConvertRow.suggestion]). */
    Swap,
    /** A helper that only matters on the source loader (`loader-helpers.json`). Left out. */
    NotNeeded,
    /** A new required dependency on the target, e.g. Fabric API; [ConvertRow.neededBy] says for which mods. */
    Added,
    /** Not on Modrinth, so it can't be checked. */
    Unknown,
}

/** Why a known mod has no target build. */
enum class ConvertGap { NoLoaderBuild, NotOutYet, MissingDependency }

/**
 * [jar] is the file in the source `mods/` (null for [ConvertStatus.Added]); [target] is what gets downloaded (for
 * BetaOnly only if chosen); [suggestion] is the Swap project, never applied unless chosen. [reason] is short English
 * for the target column ("No Fabric build", "Needed by Waystones").
 */
data class ConvertRow(
    val jar: String?, val name: String, val version: String?, val projectId: String?, val enabled: Boolean,
    val status: ConvertStatus, val target: ModCandidate? = null, val gap: ConvertGap? = null, val reason: String? = null,
    val suggestion: ModCandidate? = null, val neededBy: List<String> = emptyList(), val sha1: String? = null,
)

/** The user's answers, by [ConvertRow.jar]: Swaps to accept, BetaOnly rows to take the beta of, and what to do with the rest. */
data class ConvertChoices(
    val swaps: Set<String> = emptySet(), val betas: Set<String> = emptySet(),
    /** Carry jars that don't convert (unavailable, unknown, skipped) over turned off instead of leaving them out. */
    val keepMissingOff: Boolean = false,
)

data class ConvertPlan(
    val source: File, val fromGameVersion: String, val fromLoader: Loader, val to: ConvertTarget, val rows: List<ConvertRow>,
    /** Added project id → the project ids that need it. */
    internal val dependents: Map<String, Set<String>> = emptyMap(),
) {
    val counts: Map<ConvertStatus, Int> get() = rows.groupingBy { it.status }.eachCount()
    /** Ready mods over the folder's mods that still matter on the target (NotNeeded and Added rows don't count). */
    val carriesOver: Float get() {
        val mods = rows.count { it.jar != null && it.status != ConvertStatus.NotNeeded }
        return if (mods == 0) 1f else rows.count { it.status == ConvertStatus.Ready } / mods.toFloat()
    }
    /** What [Convert.apply] downloads for [choices]; jars that are already the target build are copied, not counted. */
    fun downloadBytes(choices: ConvertChoices = ConvertChoices()): Long = picks(choices).filter { (row, c) -> c.sha1 == null || c.sha1 != row.sha1 }.sumOf { it.second.size ?: 0 }

    /** Each row that converts, with the build it becomes. Added rows come with the chosen mods that need them. */
    internal fun picks(choices: ConvertChoices): List<Pair<ConvertRow, ModCandidate>> {
        val chosen = rows.mapNotNull { row ->
            when (row.status) {
                ConvertStatus.Ready -> row.target
                ConvertStatus.BetaOnly -> row.target?.takeIf { row.jar in choices.betas }
                ConvertStatus.Swap -> row.suggestion?.takeIf { row.jar in choices.swaps }
                else -> null
            }?.let { row to it }
        }
        val roots = chosen.filter { it.first.enabled }.map { it.second.projectId }.toSet()
        val added = rows.filter { it.status == ConvertStatus.Added && dependents[it.projectId].orEmpty().any(roots::contains) }.mapNotNull { row -> row.target?.let { row to it } }
        return (chosen + added).distinctBy { it.second.projectId }
    }
    /** Source jars that don't convert with [choices] (everything not picked except NotNeeded). */
    internal fun missing(choices: ConvertChoices): List<ConvertRow> {
        val picked = picks(choices).map { it.first }.toSet()
        return rows.filter { it.jar != null && it.status != ConvertStatus.NotNeeded && it !in picked }
    }
}

/**
 * Convert a mod folder (`mods/`, `resourcepacks/`, `shaderpacks/`, `cofre-mods.json`) to another loader or game
 * version. [plan] only reads; [apply] writes a new folder and never touches the source.
 */
object Convert {
    @Serializable private data class Helper(val name: String, val loaders: List<String>, val modIds: List<String> = emptyList(), val slugs: List<String> = emptyList(), val projectIds: List<String> = emptyList())
    private val helpers: List<Helper> by lazy { Kit.json.decodeFromString(requireNotNull(javaClass.getResourceAsStream("/loader-helpers.json")).bufferedReader().use { it.readText() }) }

    /**
     * Requests: one hash lookup for identity, one `version_files/update` for every target, one batch of projects for
     * the ones without a target, then one per BetaOnly candidate (is there a release?), two per Swap (search + its
     * versions), one per new dependency project, and one batch for the new dependencies' names.
     */
    suspend fun plan(dir: File, fromGameVersion: String, fromLoader: Loader, to: ConvertTarget, modrinth: Modrinth): ConvertPlan {
        class Local(val jar: File, val sha1: String, val meta: ModJarMetadata?)
        val local = withContext(Dispatchers.IO) {
            ModIndex(dir).jars().map { Local(it, Hashes.sha1(it), ModJarMetadata.read(it, fromLoader)) }
        }
        val loaders = if (to.loader == Loader.Quilt) listOf("quilt", "fabric") else listOf(to.loader.modrinthId)
        val games = listOf(to.gameVersion)
        val hashes = local.map { it.sha1 }.distinct()
        val current = hashes.chunked(500).flatMap { modrinth.versionFiles(it).entries }.associate { it.key to it.value }
        val latest = current.keys.toList().chunked(500).flatMap { modrinth.latestVersions(it, loaders, games).entries }.associate { it.key to it.value }
        val missingIds = local.mapNotNull { current[it.sha1]?.takeIf { _ -> it.sha1 !in latest }?.projectId }.distinct()
        val projects = modrinth.projects(missingIds).associateBy { it.id }
        val targeted = latest.values.map { it.projectId }.toMutableSet()

        fun helper(meta: ModJarMetadata?, projectId: String?, slug: String?): Helper? = helpers.firstOrNull { h ->
            fromLoader.modrinthId in h.loaders && to.loader.modrinthId !in h.loaders &&
                (meta?.modIds.orEmpty().any { id -> h.modIds.any { modKey(it) == modKey(id) } } || projectId in h.projectIds || slug in h.slugs)
        }
        fun best(versions: List<Modrinth.Version>, title: String): ModCandidate? {
            val fits = versions.map { ModCandidate.from(it, title) }.filter { it.fits(to.gameVersion, to.loader) && it.downloadable }
            return if (to.includeBeta) fits.firstOrNull() else fits.firstOrNull { it.channel == VersionChannel.Release } ?: fits.firstOrNull()
        }

        val rows = local.map { item ->
            val enabled = !item.jar.name.endsWith(".disabled")
            val name = item.meta?.name ?: ModIndex.key(item.jar.name).removeSuffix(".jar")
            val now = current[item.sha1]
            val row = ConvertRow(item.jar.name, name, item.meta?.version ?: now?.versionNumber, now?.projectId, enabled, ConvertStatus.Unknown, sha1 = item.sha1)
            if (now == null) {
                val h = helper(item.meta, null, null)
                return@map if (h != null) row.copy(status = ConvertStatus.NotNeeded, reason = "Not needed on ${to.loader.displayName}")
                else row.copy(reason = "Not on Modrinth")
            }
            val next = latest[item.sha1]
            if (next != null) {
                val candidate = ModCandidate.from(next, name)
                if (candidate.channel == VersionChannel.Release || to.includeBeta) return@map row.copy(status = ConvertStatus.Ready, target = candidate)
                val release = best(modrinth.versions(now.projectId, loaders, games, includeChangelog = false), name)
                return@map if (release != null && release.channel == VersionChannel.Release) row.copy(status = ConvertStatus.Ready, target = release)
                else row.copy(status = ConvertStatus.BetaOnly, target = candidate, reason = "Beta only")
            }
            val project = projects[now.projectId]
            helper(item.meta, now.projectId, project?.slug)?.let { return@map row.copy(status = ConvertStatus.NotNeeded, reason = "Not needed on ${to.loader.displayName}") }
            val hasLoader = project != null && project.loaders.any { it in loaders }
            if (hasLoader) {
                val text = if (to.loader == fromLoader) "No ${to.gameVersion} build yet" else "No ${to.loader.displayName} ${to.gameVersion} build yet"
                return@map row.copy(status = ConvertStatus.Unavailable, gap = ConvertGap.NotOutYet, reason = text)
            }
            val missing = row.copy(status = ConvertStatus.Unavailable, gap = ConvertGap.NoLoaderBuild, reason = "No ${to.loader.displayName} build")
            val title = project?.title ?: name
            val hit = modrinth.search(title, listOf(listOf("categories:${to.loader.modrinthId}"), listOf("versions:${to.gameVersion}"), listOf("project_type:mod")), limit = 10)
                .firstOrNull { it.projectId != now.projectId && it.projectId !in targeted && sameMod(it, title, project?.slug, to.loader) }
                ?: return@map missing
            val swap = best(modrinth.versions(hit.projectId, loaders, games, includeChangelog = false), hit.title) ?: return@map missing
            targeted += swap.projectId
            missing.copy(status = ConvertStatus.Swap, suggestion = swap)
        }
        return withDependencies(ConvertPlan(dir, fromGameVersion, fromLoader, to, rows), modrinth, loaders)
    }

    /** Runs [ModResolver] over everything that could convert: required dependencies not in the set become Added rows. */
    private suspend fun withDependencies(plan: ConvertPlan, modrinth: Modrinth, loaders: List<String>): ConvertPlan {
        val to = plan.to
        val possible = plan.rows.mapNotNull { row ->
            when (row.status) { ConvertStatus.Ready, ConvertStatus.BetaOnly -> row.target; ConvertStatus.Swap -> row.suggestion; else -> null }?.let { row to it }
        }
        // Optional dependencies are left out so the resolver doesn't look each one up.
        val seeded = possible.associate { (_, c) -> c.projectId to listOf(c.copy(dependencies = c.dependencies.filter { it.relation != Relation.Optional })) }
        val noTarget = plan.rows.filter { it.status in setOf(ConvertStatus.Unavailable, ConvertStatus.NotNeeded) }.mapNotNull { it.projectId }.toSet()
        val fetched = mutableMapOf<String, List<ModCandidate>>()
        val resolver = ModResolver(
            possible.map { (row, c) -> InstalledMod(c.filename ?: c.projectId, c.title, c.projectId, c.versionId, enabled = row.enabled) },
            to.gameVersion, to.loader,
            versionById = { id -> ModCandidate.from(modrinth.version(id)) },
            versions = { id ->
                seeded[id] ?: if (id in noTarget) emptyList() else fetched.getOrPut(id) {
                    try { modrinth.versions(id, loaders, listOf(to.gameVersion), includeChangelog = false).map { ModCandidate.from(it) } }
                    catch (failure: HttpFailure) { if (failure.status == 404) emptyList() else throw failure }
                }
            },
        )
        val added = linkedMapOf<String, ModCandidate>()
        val dependents = linkedMapOf<String, MutableSet<String>>()
        val blocked = mutableMapOf<String, String>()
        for ((row, c) in possible) {
            if (!row.enabled) continue
            val result = resolver.plan(c.projectId, c.versionId)
            (result.blocked as? PlanBlock.MissingBuild)?.let { blocked[c.projectId] = it.projectId }
            for (dep in result.required) {
                added.putIfAbsent(dep.projectId, dep)
                dependents.getOrPut(dep.projectId) { linkedSetOf() } += c.projectId
            }
        }
        val titles = modrinth.projects((added.keys + blocked.values.filter { it !in seeded }).toList()).associate { it.id to it.title }
        fun title(id: String): String = titles[id] ?: plan.rows.firstOrNull { it.projectId == id || it.target?.projectId == id }?.name ?: id
        val names = possible.associate { (row, c) -> c.projectId to row.name }
        val rows = plan.rows.map { row ->
            val id = (if (row.status == ConvertStatus.Swap) row.suggestion else row.target)?.projectId
            val missing = blocked[id] ?: return@map row
            row.copy(status = ConvertStatus.Unavailable, gap = ConvertGap.MissingDependency, reason = "Needs ${title(missing)}")
        } + added.values.map { dep ->
            val by = dependents[dep.projectId].orEmpty().filter { it !in blocked }.mapNotNull(names::get)
            val reason = if (by.size == 1) "Needed by ${by.single()}" else "Needed by ${by.size} mods"
            ConvertRow(null, title(dep.projectId), null, dep.projectId, true, ConvertStatus.Added, dep.copy(title = title(dep.projectId)), reason = reason, neededBy = by)
        }
        return plan.copy(rows = rows, dependents = dependents.mapValues { (_, by) -> by - blocked.keys }.filterValues { it.isNotEmpty() })
    }

    /** Same mod on another loader, by name: "Create" → "Create Fabric" (`create-fabric`). */
    private fun sameMod(hit: Modrinth.SearchHit, title: String, slug: String?, loader: Loader): Boolean {
        val base = listOfNotNull(modKey(title), slug?.let(::modKey)).filter { it.isNotEmpty() }
        val loaderKey = modKey(loader.displayName)
        return listOfNotNull(modKey(hit.title), hit.slug?.let(::modKey)).any { key -> loaderKey in key && base.any { it in key } }
    }

    /**
     * Writes the converted folder into [dest], which must be missing or empty: downloads (hash-checked), jars that
     * were off stay off, `cofre-mods.json` lists every downloaded mod, and `resourcepacks/` and `shaderpacks/` are
     * copied as they are. On any failure or cancellation [dest] is removed.
     */
    suspend fun apply(plan: ConvertPlan, dest: File, choices: ConvertChoices = ConvertChoices(), http: OkHttpClient = Kit.http, onProgress: (DownloadProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        require(!dest.exists() || dest.isDirectory && dest.list().isNullOrEmpty()) { "Convert needs an empty folder" }
        val source = plan.source.canonicalFile
        require(dest.canonicalFile != source && !dest.canonicalPath.startsWith(source.path + File.separator)) { "Convert can't write inside its source" }
        try {
            val mods = File(dest, "mods").apply { mkdirs() }
            val journal = File(dest, ".cofre-convert-queue.json")
            val queue = DownloadQueue(journal, http)
            val entries = linkedMapOf<String, ModIndex.Entry>()
            val names = mutableSetOf<String>()
            val downloads = mutableListOf<DownloadItem>()
            for ((row, c) in plan.picks(choices)) {
                val name = requireNotNull(c.filename) { "Mod has no filename" }; safeFilename(name)
                require(name.endsWith(".jar", true)) { "Mod is not a jar" }
                require(names.add(name)) { "Two mods have the same filename" }
                val file = File(mods, if (row.enabled) name else "$name.disabled")
                if (c.sha1 != null && c.sha1 == row.sha1 && row.jar != null) FileOps.copy(File(source, "mods/${row.jar}"), file)
                else downloads += DownloadItem(requireNotNull(c.url) { "Mod has no download URL" }, file.path, c.title, c.size, c.sha1, c.sha512, allowMetered = true)
                entries[name] = ModIndex.Entry(c.projectId, c.versionId, c.source, c.channel, installedDirectly = row.status != ConvertStatus.Added, sha1 = c.sha1)
            }
            if (choices.keepMissingOff) for (row in plan.missing(choices)) {
                val jar = requireNotNull(row.jar); safeFilename(jar)
                require(names.add(ModIndex.key(jar))) { "Two mods have the same filename" }
                FileOps.copy(File(source, "mods/$jar"), File(mods, ModIndex.key(jar) + ".disabled"))
            }
            if (downloads.isNotEmpty()) download(queue.also { it.enqueue(downloads) }, onProgress)
            journal.delete()
            for (folder in listOf("resourcepacks", "shaderpacks")) File(source, folder).takeIf { it.isDirectory }?.let { FileOps.copy(it, File(dest, folder)) }
            ModIndex(dest).write(entries)
        } catch (failure: Throwable) {
            FileOps.delete(dest)
            throw failure
        }
    }

    private suspend fun download(queue: DownloadQueue, onProgress: (DownloadProgress) -> Unit) = coroutineScope {
        val watch = launch { queue.progress.collect(onProgress) }
        val result = try { queue.run() } finally { watch.cancel() }
        onProgress(queue.progress.value)
        when (result) {
            DownloadQueue.Result.Completed -> Unit
            is DownloadQueue.Result.Failed -> throw ModInstallFailure.Download(result.ids)
            is DownloadQueue.Result.NeedsMeteredApproval -> throw ModInstallFailure.MeteredApproval(result.ids)
        }
    }
}
