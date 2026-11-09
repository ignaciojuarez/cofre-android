package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.downloads.DownloadProgress
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.modrinth.HttpFailure
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class ModInstallFailure(message: String) : IOException(message) {
    data class Blocked(val reason: PlanBlock?) : ModInstallFailure("Mod install is blocked")
    data class Download(val files: List<String>) : ModInstallFailure("Mod downloads failed")
    data class MeteredApproval(val files: List<String>) : ModInstallFailure("Metered download needs approval")
    data class MissingRequirement(val requirement: ModRequirement) : ModInstallFailure("Missing mod ${requirement.modId}")
    data class Rollback(val recoveryDirectory: String, override val cause: Throwable) : ModInstallFailure("Could not restore the previous mods; backup preserved")
}

/**
 * Serializes installs per instance, stages every download, and reverses every move if commit fails. [onProgress]
 * gets the bytes and files of everything downloaded so far, including requirements found along the way.
 */
class ModInstaller(
    private val instance: File,
    private val http: OkHttpClient = Kit.http,
    private val modrinth: Modrinth = Modrinth(http),
) {
    /** A resource, shader or data pack: one verified download moved into its folder. Data packs go to [world]. */
    suspend fun install(candidate: ModCandidate, kind: ContentKind, world: String? = null, metered: Boolean = false, allowMetered: Boolean = false, onProgress: (DownloadProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        val folder = when (kind) {
            ContentKind.DataPacks -> { require(!world.isNullOrBlank() && !world.startsWith('.')) { "Pick a world" }; safeFilename(world); File(instance, "saves/$world/datapacks") }
            ContentKind.Mods, ContentKind.Modpacks -> throw IllegalArgumentException("Not a single-file pack")
            else -> File(instance, requireNotNull(kind.folderName))
        }
        val name = requireNotNull(candidate.filename) { "No file to download" }; safeFilename(name)
        lock(instance).withLock {
            val stage = File(instance, ".cofre-mods-staging/${UUID.randomUUID()}")
            try {
                val download = File(stage, name)
                val queue = DownloadQueue(File(stage, "queue.json"), http)
                queue.enqueue(DownloadItem(requireNotNull(candidate.url) { "No download link" }, download.path, candidate.title, candidate.size, candidate.sha1, candidate.sha512, allowMetered = allowMetered), metered)
                download(queue, metered, emptyList(), onProgress)
                folder.mkdirs()
                Files.move(download.toPath(), File(folder, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { stage.deleteRecursively() }
        }
    }

    suspend fun install(plan: ModInstallPlan, metered: Boolean = false, allowMetered: Boolean = false, onProgress: (DownloadProgress) -> Unit = {}): List<InstalledMod> = withContext(Dispatchers.IO) {
        if (plan.blocked != null || plan.target == null) throw ModInstallFailure.Blocked(plan.blocked)
        val mutex = lock(instance); mutex.lock()
        val stage = File(instance, ".cofre-mods-staging/${UUID.randomUUID()}")
        var preserveBackup = false
        var ownsStage = false
        try {
            val mods = File(instance, "mods")
            require(mods.canonicalFile.parentFile == instance.canonicalFile && stage.canonicalPath.startsWith(instance.canonicalPath + File.separator)) { "Mod directories leave the instance" }
            mods.mkdirs(); stage.mkdirs(); ownsStage = true
            val index = ModIndex(instance); val before = index.installed()
            val all = linkedMapOf<String, ModCandidate>(); val staged = linkedMapOf<String, File>()
            val turnOn = plan.turnsOn.associateBy { it.file }.toMutableMap()
            fun add(candidate: ModCandidate) {
                require(candidate.fits()) { "Mod does not fit NeoForge 26.2" }
                val previous = all[candidate.projectId]
                if (previous != null && previous.versionId != candidate.versionId) throw ModInstallFailure.Blocked(PlanBlock.VersionConflict(candidate.projectId))
                all[candidate.projectId] = candidate
            }
            plan.downloads.forEach(::add)
            val attemptedRequirements = mutableSetOf<String>()
            var downloaded = emptyList<DownloadItem>()
            while (true) {
                val queue = DownloadQueue(File(stage, "queue-${all.size}.json"), http)
                for ((id, candidate) in all) {
                    if (id in staged) continue
                    val have = before.firstOrNull { it.projectId == id && it.versionId == candidate.versionId }
                    if (have != null) {
                        if (!have.enabled) turnOn[have.file] = have
                        continue
                    }
                    val name = requireNotNull(candidate.filename) { "Mod has no filename" }; safeFilename(name)
                    require(name.endsWith(".jar", true)) { "Mod is not a jar" }
                    require(staged.values.none { it.name == name }) { "Two mods have the same filename" }
                    val destination = File(stage, "downloads/$name")
                    queue.enqueue(DownloadItem(requireNotNull(candidate.url) { "Mod has no download URL" }, destination.path, candidate.title, candidate.size, candidate.sha1, candidate.sha512, allowMetered = allowMetered), metered)
                    staged[id] = destination
                }
                downloaded = download(queue, metered, downloaded, onProgress)
                val active = before.filter { (it.enabled || it.file in turnOn) && it.projectId !in staged }
                val jarFiles = active.map { File(mods, it.file) } + staged.values
                val metadata = jarFiles.mapNotNull { jar -> ModJarMetadata.read(jar)?.let { jar.name to it } }.toMap()
                val missing = ModRequirements.missing(metadata, jarFiles.filter { it.name !in metadata }.map { it.name })
                if (missing.isEmpty()) break
                val knownInstalled = active + all.values.map { candidate ->
                    InstalledMod(candidate.filename.orEmpty(), candidate.title, candidate.projectId, candidate.versionId, aliases = staged[candidate.projectId]?.let { ModJarMetadata.read(it)?.modIds }.orEmpty())
                } + before.filter { !it.enabled && it.file !in turnOn }
                var added = false
                for (requirement in missing) {
                    if (!attemptedRequirements.add(requirement.modId)) throw ModInstallFailure.MissingRequirement(requirement)
                    val project = if (requirement.projectId != null) modrinth.project(requirement.projectId) else {
                        var found: Modrinth.Project? = null
                        for (slug in requirement.slugs) {
                            try { found = modrinth.project(slug); break }
                            catch (failure: HttpFailure) { if (failure.status != 404) throw failure }
                        }
                        found ?: throw ModInstallFailure.MissingRequirement(requirement)
                    }
                    val extra = ModResolver.modrinth(modrinth, knownInstalled).plan(project.id)
                    if (extra.blocked != null || extra.target == null) throw ModInstallFailure.Blocked(extra.blocked)
                    extra.downloads.forEach { candidate -> if (candidate.projectId !in all) { add(candidate); added = true } }
                    extra.turnsOn.forEach { if (turnOn.put(it.file, it) == null) added = true }
                }
                if (!added) throw ModInstallFailure.MissingRequirement(missing.first())
            }
            currentCoroutineContext().ensureActive()
            try { commit(plan.target.projectId, all, staged, turnOn.values.toList(), index, stage) }
            catch (failure: ModInstallFailure.Rollback) { preserveBackup = true; throw failure }
            index.installed()
        } finally {
            if (ownsStage && !preserveBackup) stage.deleteRecursively()
            mutex.unlock()
        }
    }
    /** Runs [queue], reporting its items after [earlier] ones; returns them all. */
    private suspend fun download(queue: DownloadQueue, metered: Boolean, earlier: List<DownloadItem>, onProgress: (DownloadProgress) -> Unit): List<DownloadItem> = coroutineScope {
        val watch = launch { queue.progress.collect { onProgress(DownloadProgress(earlier + it.items, it.bytesPerSecond)) } }
        val result = try { queue.run(metered) } finally { watch.cancel() }
        val all = earlier + queue.progress.value.items
        onProgress(DownloadProgress(all))
        when (result) {
            DownloadQueue.Result.Completed -> all
            is DownloadQueue.Result.Failed -> throw ModInstallFailure.Download(result.ids)
            is DownloadQueue.Result.NeedsMeteredApproval -> throw ModInstallFailure.MeteredApproval(result.ids)
        }
    }
    private fun commit(rootId: String, all: Map<String, ModCandidate>, staged: Map<String, File>, turningOn: List<InstalledMod>, index: ModIndex, stage: File) {
        val folder = File(instance, "mods"); val backup = File(stage, "backup"); backup.mkdirs()
        val before = index.installed(); val entries = index.read().toMutableMap()
        val undo = mutableListOf<Pair<File, File>>()
        fun move(from: File, to: File) {
            check(from.isFile && !to.exists()) { "Cannot move a mod file" }
            Files.move(from.toPath(), to.toPath()); undo += from to to
        }
        try {
            for ((project, file) in staged) {
                val candidate = all.getValue(project)
                val old = before.filter { it.projectId == project || it.file.removeSuffix(".disabled") == file.name || it.projectId == null && it.matches(project, candidate.title) }
                for (item in old) {
                    move(File(folder, item.file), File(backup, "${undo.size}-${item.file}")); entries.remove(item.file)
                }
                move(file, File(folder, file.name))
                entries[file.name] = ModIndex.Entry(project, candidate.versionId, candidate.source, candidate.channel,
                    project == rootId || old.any { it.installedDirectly }, candidate.sha1 ?: Hashes.sha1(File(folder, file.name)))
            }
            for (mod in turningOn) {
                val from = File(folder, mod.file)
                if (!from.exists() || mod.file.removeSuffix(".disabled") in staged.values.map { it.name }) continue
                val name = mod.file.removeSuffix(".disabled"); move(from, File(folder, name))
                entries.remove(mod.file)?.let { entries[name] = it }
            }
            entries.keys.retainAll(index.jars().map { it.name }.toSet())
            index.write(entries)
        } catch (failure: Exception) {
            var rollbackFailure: Exception? = null
            for ((from, to) in undo.asReversed()) {
                try { Files.move(to.toPath(), from.toPath()) } catch (error: Exception) { rollbackFailure = error; failure.addSuppressed(error) }
            }
            if (rollbackFailure != null) throw ModInstallFailure.Rollback(stage.path, failure)
            throw failure
        }
    }
    companion object {
        // One small mutex per used instance; the app has a finite instance list.
        private val locks = ConcurrentHashMap<String, Mutex>()
        internal fun lock(instance: File): Mutex = locks.getOrPut(instance.canonicalPath) { Mutex() }
    }
}
