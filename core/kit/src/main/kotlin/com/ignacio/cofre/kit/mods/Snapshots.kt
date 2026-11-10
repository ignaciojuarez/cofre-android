package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.instances.InstanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/**
 * Restore points of a content dir's `mods/` (its top-level files) and `cofre-mods.json`, kept in
 * `dir/.cofre/snapshots/<id>/`. Jars are hard links where the file system allows (Cofre never edits a jar in place, it
 * only moves new files in), else copies. `.cofre*` is private to Cofre: never export or copy it with the dir.
 */
object Snapshots {
    @Serializable enum class Reason { @SerialName("before_update") BeforeUpdate, @SerialName("last_good") LastGood, @SerialName("manual") Manual }
    @Serializable data class Snapshot(val id: String, val reason: Reason, val createdAt: Long)
    /** Jar names in `mods/` since a snapshot: [changed] means the same mod (by mod id, else project, else file) with other bytes, name or on/off state. */
    data class Changes(val added: List<String>, val removed: List<String>, val changed: List<String>) {
        val count: Int get() = added.size + removed.size + changed.size
    }
    /** BeforeUpdate snapshots kept; a new LastGood replaces the previous one; Manual ones stay until deleted. */
    const val KEEP_BEFORE_UPDATE = 5
    private const val META = "snapshot.json"
    private const val INDEX = "cofre-mods.json"

    suspend fun take(dir: File, reason: Reason = Reason.Manual): Snapshot = locked(dir) { takeLocked(dir, reason) }
    /** Newest first. */
    fun list(dir: File): List<Snapshot> = root(dir).listFiles().orEmpty().filter { !it.name.startsWith('.') && File(it, META).isFile }
        .mapNotNull { runCatching { Kit.json.decodeFromString<Snapshot>(File(it, META).readText()) }.getOrNull()?.takeIf { s -> s.id == it.name } }
        .sortedWith(compareByDescending<Snapshot> { it.createdAt }.thenByDescending { it.id })
    suspend fun changedSince(dir: File, snapshot: Snapshot): Changes = withContext(Dispatchers.IO) { changes(dir, folder(dir, snapshot)).public }
    /**
     * Puts `mods/` and `cofre-mods.json` back exactly as in [snapshot] (jars added later go away). With [pin], the mods
     * it rolled back to an older build are pinned so updates leave them alone. All or nothing; returns what it undid.
     */
    suspend fun restore(dir: File, snapshot: Snapshot, pin: Boolean = false): Changes = locked(dir) { restoreLocked(dir, snapshot, pin) }
    suspend fun delete(dir: File, snapshot: Snapshot) = locked(dir) { FileOps.delete(folder(dir, snapshot)) }

    internal fun takeLocked(dir: File, reason: Reason): Snapshot {
        val root = root(dir); Files.createDirectories(root.toPath())
        val stage = File(root, ".tmp-${UUID.randomUUID()}")
        try {
            val mods = File(stage, "mods"); Files.createDirectories(mods.toPath())
            for (file in modFiles(dir)) link(file, File(mods, file.name))
            File(dir, INDEX).takeIf { it.isFile }?.copyTo(File(stage, INDEX))
            val created = maxOf(System.currentTimeMillis(), (list(dir).maxOfOrNull { it.createdAt } ?: 0) + 1)
            val snapshot = Snapshot("$created-${reason.name.lowercase()}", reason, created)
            File(stage, META).writeText(Kit.json.encodeToString(snapshot))
            Files.move(stage.toPath(), File(root, snapshot.id).toPath())
            val drop = when (reason) {
                Reason.BeforeUpdate -> list(dir).filter { it.reason == reason }.drop(KEEP_BEFORE_UPDATE)
                Reason.LastGood -> list(dir).filter { it.reason == reason && it.id != snapshot.id }
                Reason.Manual -> emptyList()
            }
            drop.forEach { FileOps.delete(File(root, it.id)) }
            return snapshot
        } finally { FileOps.delete(stage) }
    }

    internal fun restoreLocked(dir: File, snapshot: Snapshot, pin: Boolean = false): Changes {
        val source = folder(dir, snapshot)
        val stage = File(root(dir), ".restore-${UUID.randomUUID()}")
        var keepStage = false
        try {
            val changes = changes(dir, source)
            val incoming = File(stage, "new"); val outgoing = File(stage, "old")
            Files.createDirectories(File(incoming, "mods").toPath()); Files.createDirectories(File(outgoing, "mods").toPath())
            File(source, "mods").listFiles().orEmpty().forEach { link(it, File(incoming, "mods/${it.name}")) }
            val restoredIndex = File(source, INDEX).takeIf { it.isFile }?.let { it.copyTo(File(incoming, INDEX)) }
            if (pin && restoredIndex != null) {
                val index = ModIndex(incoming); val entries = index.read()
                val rolledBack = changes.changedBefore.map(ModIndex::key).toSet()
                index.write(entries.mapValues { (key, entry) -> if (key in rolledBack) entry.copy(pinned = true) else entry })
            }
            val mods = File(dir, "mods"); Files.createDirectories(mods.toPath())
            val index = File(dir, INDEX)
            val undo = mutableListOf<Pair<File, File>>()
            fun move(from: File, to: File) { Files.move(from.toPath(), to.toPath()); undo += from to to }
            try {
                modFiles(dir).forEach { move(it, File(outgoing, "mods/${it.name}")) }
                if (Files.exists(index.toPath(), NOFOLLOW_LINKS)) move(index, File(outgoing, INDEX))
                File(incoming, "mods").listFiles().orEmpty().forEach { move(it, File(mods, it.name)) }
                if (restoredIndex != null) move(restoredIndex, index)
            } catch (failure: Exception) {
                var rollbackFailed = false
                for ((from, to) in undo.asReversed()) {
                    try { Files.move(to.toPath(), from.toPath()) } catch (error: Exception) { rollbackFailed = true; failure.addSuppressed(error) }
                }
                if (rollbackFailed) { keepStage = true; throw ModInstallFailure.Rollback(stage.path, failure) }
                throw failure
            }
            return changes.public
        } finally { if (!keepStage) FileOps.delete(stage) }
    }

    internal fun deleteLocked(dir: File, snapshot: Snapshot) = FileOps.delete(folder(dir, snapshot))

    /** [Changes] plus the snapshot-side names of the changed mods, which a pinning restore needs. */
    private class Diff(val public: Changes, val changedBefore: List<String>)
    private fun changes(dir: File, snapshotFolder: File): Diff {
        fun jars(mods: File, index: ModIndex): Map<String, File> {
            val entries = runCatching { index.read() }.getOrDefault(emptyMap())
            return mods.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled")) }
                .associateBy { ModJarMetadata.read(it)?.modIds?.firstOrNull() ?: entries[ModIndex.key(it.name)]?.projectId ?: ModIndex.key(it.name) }
        }
        val before = jars(File(snapshotFolder, "mods"), ModIndex(snapshotFolder))
        val now = jars(File(dir, "mods"), ModIndex(dir))
        val changed = now.keys.filter { it in before && !same(before.getValue(it), now.getValue(it)) }
        return Diff(Changes((now.keys - before.keys).map { now.getValue(it).name }.sorted(), (before.keys - now.keys).map { before.getValue(it).name }.sorted(),
            changed.map { now.getValue(it).name }.sorted()), changed.map { before.getValue(it).name })
    }
    private fun same(a: File, b: File): Boolean = a.name == b.name &&
        (runCatching { Files.isSameFile(a.toPath(), b.toPath()) }.getOrDefault(false) || a.length() == b.length() && Hashes.sha1(a) == Hashes.sha1(b))

    private fun root(dir: File): File = File(dir, ".cofre/snapshots")
    private fun folder(dir: File, snapshot: Snapshot): File {
        safeFilename(snapshot.id); require(!snapshot.id.startsWith('.')) { "Not a snapshot" }
        return File(root(dir), snapshot.id).also { require(File(it, META).isFile) { "Snapshot is gone" } }
    }
    private fun modFiles(dir: File): List<File> = File(dir, "mods").listFiles().orEmpty().filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
    private fun link(source: File, target: File) {
        try { Files.createLink(target.toPath(), source.toPath()) }
        catch (_: UnsupportedOperationException) { source.copyTo(target) }
        catch (_: IOException) { if (target.exists()) throw IOException("Already exists: ${target.name}"); source.copyTo(target) }
    }
    private suspend fun <T> locked(dir: File, block: () -> T): T = withContext(Dispatchers.IO) { InstanceStore.lock(dir).withLock { block() } }
}
