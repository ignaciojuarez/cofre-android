package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.downloads.DownloadProgress
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.common.HttpFailure
import com.ignacio.cofre.kit.compat.ContentCompatibility
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed class ModInstallFailure(message: String) : IOException(message) {
    data class Blocked(val reason: PlanBlock?) : ModInstallFailure("Mod install is blocked")
    data class Download(val files: List<String>) : ModInstallFailure("Mod downloads failed")
    data class MeteredApproval(val files: List<String>) : ModInstallFailure("Metered download needs approval")
    data class MissingRequirement(val requirement: ModRequirement) : ModInstallFailure("Missing mod ${requirement.modId}")
    data class Rollback(val recoveryDirectory: String, override val cause: Throwable) : ModInstallFailure("Could not restore the previous mods; backup preserved")
}

/**
 * Serializes installs per mod folder, stages every download, and reverses every move if commit fails. [onProgress]
 * gets the bytes and files of everything downloaded so far, including requirements found along the way. [instance] is
 * the mod folder's directory (or a folder laid out like one); data packs go to [gameDirectory]'s worlds.
 */
class ModInstaller(
    private val instance: File,
    private val gameDirectory: File = instance,
    private val http: OkHttpClient = Kit.http,
    private val modrinth: Modrinth = Modrinth(http),
) {
    /** A resource, shader or data pack: one verified download moved into its folder. Data packs go to [world]. */
    suspend fun install(candidate: ModCandidate, kind: ContentKind, world: String? = null, metered: Boolean = false, allowMetered: Boolean = false, onProgress: (DownloadProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        val folder = when (kind) {
            ContentKind.DataPacks -> { require(!world.isNullOrBlank() && !world.startsWith('.')) { "Pick a world" }; safeFilename(world); File(gameDirectory, "saves/$world/datapacks") }
            ContentKind.Mods, ContentKind.Modpacks -> throw IllegalArgumentException("Not a single-file pack")
            else -> File(instance, requireNotNull(kind.folderName))
        }
        val name = requireNotNull(candidate.filename) { "No file to download" }; safeFilename(name)
        val owner = if (kind == ContentKind.DataPacks) gameDirectory else instance
        InstanceStore.lock(owner).withLock {
            val stage = File(owner, ".cofre-mods-staging/${UUID.randomUUID()}")
            try {
                val download = File(stage, name)
                val queue = DownloadQueue(File(stage, "queue.json"), http)
                queue.enqueue(DownloadItem(requireNotNull(candidate.url) { "No download link" }, download.path, candidate.title, candidate.size, candidate.sha1, candidate.sha512, allowMetered = allowMetered), metered)
                download(queue, metered, emptyList(), onProgress)
                folder.mkdirs()
                Files.move(download.toPath(), File(folder, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { FileOps.delete(stage) }
        }
    }

    suspend fun install(plan: ModInstallPlan, metered: Boolean = false, allowMetered: Boolean = false, onProgress: (DownloadProgress) -> Unit = {}): List<InstalledMod> =
        InstanceStore.lock(instance).withLock { installLocked(plan, metered, allowMetered, onProgress) }

    /** [install] for a caller that already holds [InstanceStore.lock]. Anything but [ModInstallFailure.Rollback] leaves the mods as they were. */
    internal suspend fun installLocked(plan: ModInstallPlan, metered: Boolean = false, allowMetered: Boolean = false, onProgress: (DownloadProgress) -> Unit = {}): List<InstalledMod> = withContext(Dispatchers.IO) {
        if (plan.blocked != null || plan.target == null) throw ModInstallFailure.Blocked(plan.blocked)
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
            // Gaps the folder already had (a jar dropped in by hand, say) are fetched when Modrinth has them, but never
            // block this install: "Before you play" names them.
            val alreadyMissing = before.filter { it.enabled }.map { File(mods, it.file) }.let { jars ->
                val metadata = jars.mapNotNull { jar -> ModJarMetadata.read(jar)?.let { jar.name to it } }.toMap()
                ModRequirements.missing(metadata, jars.filter { it.name !in metadata }.map { it.name }).map { it.modId }.toSet()
            }
            val skipped = mutableSetOf<String>()
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
                val missing = ModRequirements.missing(metadata, jarFiles.filter { it.name !in metadata }.map { it.name }).filter { it.modId !in skipped }
                if (missing.isEmpty()) break
                val knownInstalled = active + all.values.map { candidate ->
                    InstalledMod(candidate.filename.orEmpty(), candidate.title, candidate.projectId, candidate.versionId, aliases = staged[candidate.projectId]?.let { ModJarMetadata.read(it)?.modIds }.orEmpty())
                } + before.filter { !it.enabled && it.file !in turnOn }
                var added = false
                for (requirement in missing) {
                    if (broken(requirement.modId, requirement.name)) { skipped += requirement.modId; continue }
                    val old = requirement.modId in alreadyMissing
                    if (!attemptedRequirements.add(requirement.modId)) {
                        if (old) { skipped += requirement.modId; continue }
                        throw ModInstallFailure.MissingRequirement(requirement)
                    }
                    val extra = try { ModResolver.modrinth(modrinth, knownInstalled).plan(project(requirement).id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (old) { skipped += requirement.modId; continue }; throw failure }
                    if (extra.blocked != null || extra.target == null) {
                        if (old) { skipped += requirement.modId; continue }
                        throw ModInstallFailure.Blocked(extra.blocked)
                    }
                    if (broken(extra.target)) { skipped += requirement.modId; continue }
                    extra.downloads.filterNot(::broken).forEach { candidate -> if (candidate.projectId !in all) { add(candidate); added = true } }
                    extra.turnsOn.filterNot(::broken).forEach { if (turnOn.put(it.file, it) == null) added = true }
                }
                if (!added) missing.firstOrNull { it.modId !in skipped }?.let { throw ModInstallFailure.MissingRequirement(it) } ?: break
            }
            currentCoroutineContext().ensureActive()
            try { commit(plan.target.projectId, all, staged, turnOn.values.toList(), index, stage) }
            catch (failure: ModInstallFailure.Rollback) { preserveBackup = true; throw failure }
            index.installed()
        } finally {
            if (ownsStage && !preserveBackup) FileOps.delete(stage)
        }
    }
    /**
     * "Get missing mods": each requirement comes from the requesting mod's own Modrinth version (its dependency list
     * names the project), else from Modrinth by the mod id's slugs. Requesters Cofre didn't install are identified by hash.
     * A mod a known-mods rule calls broken is never installed or turned back on: its gap stays reported.
     */
    suspend fun installMissing(requirements: List<ModRequirement>, metered: Boolean = false, onProgress: (DownloadProgress) -> Unit = {}): List<InstalledMod> {
        val index = ModIndex(instance)
        var mods = withContext(Dispatchers.IO) { index.installed() }
        fun requester(requirement: ModRequirement) = requirement.neededBy?.let { by -> mods.firstOrNull { it.file == by || it.aliases.any { id -> id.equals(by, true) } } }
        if (requirements.any { requester(it)?.versionId == null }) { index.identifyUnknown(modrinth); mods = withContext(Dispatchers.IO) { index.installed() } }
        for (requirement in requirements) {
            if (withContext(Dispatchers.IO) { ModRequirements.missing(instance) }.none { it.modId == requirement.modId }) continue
            if (broken(requirement.modId, requirement.name)) continue
            val by = requester(requirement)
            val resolver = ModResolver.modrinth(modrinth, mods)
            val viaRequester = if (by?.projectId != null && by.versionId != null) resolver.plan(by.projectId, by.versionId).takeIf { it.blocked == null && it.target?.versionId == by.versionId && it.required.isNotEmpty() } else null
            val plan = viaRequester ?: resolver.plan(project(requirement).id)
            if (viaRequester == null && plan.target?.let(::broken) == true) continue
            mods = install(plan.copy(required = plan.required.filterNot(::broken), turnsOn = plan.turnsOn.filterNot(::broken)), metered, onProgress = onProgress)
        }
        return mods
    }
    private fun broken(id: String, name: String, version: String? = null): Boolean = ContentCompatibility.issue(id, name, ContentKind.Mods, version)?.severity == "broken"
    private fun broken(mod: InstalledMod): Boolean = (mod.aliases + ModIndex.key(mod.file)).any { broken(it, mod.name, mod.version) }
    private fun broken(candidate: ModCandidate): Boolean = broken(candidate.filename ?: candidate.projectId, candidate.title, candidate.version)
    private suspend fun project(requirement: ModRequirement): Modrinth.Project {
        if (requirement.projectId != null) return modrinth.project(requirement.projectId)
        for (slug in requirement.slugs) {
            try { return modrinth.project(slug) }
            catch (failure: HttpFailure) { if (failure.status != 404) throw failure }
        }
        throw ModInstallFailure.MissingRequirement(requirement)
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
    companion object {
        /**
         * After a kill: drops [dir]'s (a mod folder's or an instance's) unfinished installs. A jar a commit had moved to
         * backup goes back to `mods/` ([putBack]). The caller holds [InstanceStore.lock] for [dir].
         */
        fun recover(dir: File) {
            for (stage in File(dir, ".cofre-mods-staging").listFiles().orEmpty()) {
                putBack(File(stage, "backup").listFiles().orEmpty().map { it to it.name.substringAfter('-') }, File(dir, "mods"))
                FileOps.delete(stage)
            }
        }
        /**
         * Moves each (file, name) to `mods/name` when that name is free, on or off, and no jar there has one of its own
         * mod ids (then the copy that replaced it made it in, and putting it back would make two). Others stay put.
         */
        internal fun putBack(files: List<Pair<File, String>>, mods: File) {
            val present = ModIndex(mods.parentFile).jars().flatMap { ModJarMetadata.read(it)?.own.orEmpty() }.toMutableSet()
            for ((file, name) in files) {
                if (!file.isFile || runCatching { safeFilename(name) }.isFailure) continue
                val key = ModIndex.key(name); val ids = ModJarMetadata.read(file)?.own.orEmpty()
                if (File(mods, key).exists() || File(mods, "$key.disabled").exists() || ids.any(present::contains)) continue
                Files.createDirectories(mods.toPath()); Files.move(file.toPath(), File(mods, name).toPath()); present += ids
            }
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
                // The old copy: same project, file name or title, or a jar (on or off) with one of the new jar's own mod ids,
                // since two jars with one mod id stop the game.
                val ids = ModJarMetadata.read(file)?.own.orEmpty().toSet()
                val old = before.filter { File(folder, it.file).isFile }.filter { it.projectId == project || it.file.removeSuffix(".disabled") == file.name || it.projectId == null && it.matches(project, candidate.title) ||
                    ModJarMetadata.read(File(folder, it.file))?.own.orEmpty().any(ids::contains) }
                for (item in old) {
                    move(File(folder, item.file), File(backup, "${undo.size}-${item.file}")); entries.remove(ModIndex.key(item.file))
                }
                move(file, File(folder, file.name))
                entries[file.name] = ModIndex.Entry(project, candidate.versionId, candidate.source, candidate.channel,
                    project == rootId || old.any { it.installedDirectly }, candidate.sha1 ?: Hashes.sha1(File(folder, file.name)))
            }
            for (mod in turningOn) {
                val from = File(folder, mod.file)
                if (!from.exists() || mod.file.removeSuffix(".disabled") in staged.values.map { it.name }) continue
                move(from, File(folder, ModIndex.key(mod.file)))
            }
            entries.keys.retainAll(index.jars().map { ModIndex.key(it.name) }.toSet())
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
}
