package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.downloads.DownloadProgress
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files

/** A jar in `mods/` ([file], `.disabled` included) with a newer fitting build on Modrinth. */
@Serializable data class ModUpdate(
    val file: String, val name: String, val projectId: String,
    val currentVersionId: String?, val currentVersion: String?, val target: ModCandidate,
)

data class ModUpdateResult(val updated: List<ModUpdate>, val failed: List<Failed>, val snapshot: Snapshots.Snapshot?) {
    data class Failed(val update: ModUpdate, val reason: String)
}

/**
 * The jars in [dir] with a newer build for [gameVersion] and [loader], in one Modrinth request. Skips pinned jars, jars
 * Modrinth doesn't know, jars already on that build and builds without a download. A jar only moves to a less stable
 * channel than its own with [includeBeta] (beta, never alpha). Modrinth answers with the newest fitting build only, so
 * when that build is on a skipped channel the jar shows no update. Turned-off jars are included.
 */
suspend fun ModUpdates.check(dir: File, client: Modrinth, gameVersion: String = Target.GAME_VERSION, loader: Loader = Target.LOADER, includeBeta: Boolean = false): List<ModUpdate> {
    val index = ModIndex(dir)
    val (mods, entries, hashes) = withContext(Dispatchers.IO) { Triple(index.installed(), index.read(), index.jars().associate { it.name to Hashes.sha1(it) }) }
    val open = mods.filter { entries[ModIndex.key(it.file)]?.pinned != true && it.file in hashes }
    val latest = open.mapNotNull { hashes[it.file] }.distinct().chunked(500)
        .flatMap { client.latestVersions(it, listOf(loader.modrinthId), listOf(gameVersion)).entries }.associate { it.key to it.value }
    return open.mapNotNull { mod ->
        val hash = hashes.getValue(mod.file)
        val version = latest[hash] ?: return@mapNotNull null
        val entry = entries[ModIndex.key(mod.file)]?.takeIf { it.sha1 == null || it.sha1 == hash }
        if (version.id == entry?.versionId || version.files.any { it.hashes["sha1"] == hash }) return@mapNotNull null
        if (entry != null && entry.projectId != version.projectId) return@mapNotNull null
        val target = ModCandidate.from(version, mod.name)
        if (!target.downloadable || target.filename == null) return@mapNotNull null
        val current = entry?.channel ?: VersionChannel.Release
        if (!current.allowsUpdate(target.channel) && !(includeBeta && target.channel == VersionChannel.Beta)) return@mapNotNull null
        ModUpdate(mod.file, mod.name, version.projectId, entry?.versionId, mod.version, target)
    }
}

/**
 * Installs [updates] in [dir] through [installer]'s staged path, under the dir lock, after a [Snapshots.Reason.BeforeUpdate]
 * snapshot. Turned-off jars stay off. A mod whose download or plan fails is reported and left as it was; any failure
 * that could leave the folder half-changed restores the snapshot and reports every update as failed. The result's
 * snapshot is null when nothing changed.
 */
suspend fun ModUpdates.apply(dir: File, updates: List<ModUpdate>, installer: ModInstaller = ModInstaller(dir), metered: Boolean = false, onProgress: (DownloadProgress) -> Unit = {}): ModUpdateResult {
    if (updates.isEmpty()) return ModUpdateResult(emptyList(), emptyList(), null)
    return withContext(Dispatchers.IO) { InstanceStore.lock(dir).withLock { applyLocked(dir, updates, installer, metered, onProgress) } }
}

private suspend fun applyLocked(dir: File, updates: List<ModUpdate>, installer: ModInstaller, metered: Boolean, onProgress: (DownloadProgress) -> Unit): ModUpdateResult {
    val snapshot = Snapshots.takeLocked(dir, Snapshots.Reason.BeforeUpdate)
    val index = ModIndex(dir); val mods = File(dir, "mods")
    val updated = mutableListOf<ModUpdate>(); val failed = mutableListOf<ModUpdateResult.Failed>()
    try {
        for (update in updates) {
            val key = ModIndex.key(update.file)
            val jar = listOf(File(mods, key), File(mods, "$key.disabled")).firstOrNull { it.isFile }
            if (jar == null) { failed += ModUpdateResult.Failed(update, "The mod is no longer in the folder"); continue }
            // The installer replaces the old jar by project, so a jar known only by hash gets its identity first.
            val entries = index.read(); val identified = entries[key]?.projectId != update.projectId
            if (identified) index.write(entries + (key to ModIndex.Entry(update.projectId, update.currentVersionId.orEmpty(), sha1 = Hashes.sha1(jar))))
            try { installer.installLocked(ModInstallPlan(update.target), metered, onProgress = onProgress) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (rollback: ModInstallFailure.Rollback) { throw rollback }
            catch (failure: Exception) {
                if (identified) index.write(entries)
                failed += ModUpdateResult.Failed(update, failure.message ?: "Update failed"); continue
            }
            if (jar.name.endsWith(".disabled")) {
                val name = requireNotNull(update.target.filename)
                Files.move(File(mods, name).toPath(), File(mods, "$name.disabled").toPath())
            }
            updated += update
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        Snapshots.restoreLocked(dir, snapshot)
        return ModUpdateResult(emptyList(), updates.map { ModUpdateResult.Failed(it, failure.message ?: "Update failed") }, snapshot)
    }
    if (updated.isEmpty()) Snapshots.deleteLocked(dir, snapshot)
    return ModUpdateResult(updated, failed, snapshot.takeIf { updated.isNotEmpty() })
}
