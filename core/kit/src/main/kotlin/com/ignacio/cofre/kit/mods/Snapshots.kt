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
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Restore points of a content dir's `mods/` (its top-level files) and `cofre-mods.json`, in `dir/.cofre/snapshots/`:
 * - `blobs/<sha1>`: each file's bytes, stored once for every snapshot. A hard link to the live jar where the file
 *   system allows (Cofre never edits a jar in place, it only moves new files in), else a copy. Android denies apps
 *   hard links in their data dir, so there the store holds one copy of each jar a snapshot has seen.
 * - `<id>/snapshot.json` + `<id>/files.json` (names, `.disabled` meaning off, and sha1s) + `<id>/cofre-mods.json`.
 * - `hashes.json`: sha1s of live jars by name, size, mtime and inode, so an unchanged folder isn't hashed again.
 * A snapshot of an unchanged folder adds no blobs; blobs no snapshot names are pruned. Snapshots in the first layout
 * (`<id>/mods/`, full copies) are dropped. `.cofre*` is private to Cofre: never export or copy it with the dir.
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
    /** Puts a source file's bytes at a new target path without copying, or throws (tests pass one that always fails). */
    internal val HARD_LINK: (source: Path, target: Path) -> Unit = { source, target -> Files.createLink(target, source) }

    @Serializable internal data class Entry(val name: String, val sha1: String)
    @Serializable private data class Hashed(val size: Long, val modified: Long, val key: String?, val sha1: String)
    private const val META = "snapshot.json"
    private const val FILES = "files.json"
    private const val INDEX = "cofre-mods.json"
    private const val HASHES = "hashes.json"

    suspend fun take(dir: File, reason: Reason = Reason.Manual): Snapshot = locked(dir) { takeLocked(dir, reason) }
    /** Newest first. */
    fun list(dir: File): List<Snapshot> = root(dir).listFiles().orEmpty().filter { !it.name.startsWith('.') && File(it, META).isFile && File(it, FILES).isFile }
        .mapNotNull { runCatching { Kit.json.decodeFromString<Snapshot>(File(it, META).readText()) }.getOrNull()?.takeIf { s -> s.id == it.name } }
        .sortedWith(compareByDescending<Snapshot> { it.createdAt }.thenByDescending { it.id })
    suspend fun changedSince(dir: File, snapshot: Snapshot): Changes = withContext(Dispatchers.IO) {
        val hashes = HashCache(dir); changes(dir, folder(dir, snapshot), hashes).public.also { hashes.save() }
    }
    /**
     * Puts `mods/` and `cofre-mods.json` back exactly as in [snapshot] (jars added later go away). With [pin], the mods
     * it rolled back to an older build are pinned so updates leave them alone. All or nothing; returns what it undid.
     */
    suspend fun restore(dir: File, snapshot: Snapshot, pin: Boolean = false): Changes = locked(dir) { restoreLocked(dir, snapshot, pin) }
    suspend fun delete(dir: File, snapshot: Snapshot) = locked(dir) { deleteLocked(dir, snapshot) }

    internal fun takeLocked(dir: File, reason: Reason, link: (Path, Path) -> Unit = HARD_LINK): Snapshot {
        val root = root(dir); Files.createDirectories(blobs(dir).toPath())
        val hashes = HashCache(dir)
        val files = modFiles(dir).map { file -> Entry(file.name, hashes.sha1(file)).also { store(file, blob(dir, it.sha1), link) } }
        hashes.save()
        val stage = File(root, ".tmp-${UUID.randomUUID()}")
        try {
            Files.createDirectories(stage.toPath())
            File(stage, FILES).writeText(Kit.json.encodeToString(files))
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
            prune(dir)
            return snapshot
        } finally { FileOps.delete(stage) }
    }

    internal fun restoreLocked(dir: File, snapshot: Snapshot, pin: Boolean = false, link: (Path, Path) -> Unit = HARD_LINK): Changes {
        val source = folder(dir, snapshot)
        val stage = File(root(dir), ".restore-${UUID.randomUUID()}")
        var keepStage = false
        try {
            val hashes = HashCache(dir)
            val changes = changes(dir, source, hashes)
            // Live files already as in the snapshot stay where they are; only the rest is staged from the blob store.
            val wanted = files(source).toSet()
            val live = modFiles(dir)
            val (kept, leaving) = live.partition { Entry(it.name, hashes.sha1(it)) in wanted }
            val keptNames = kept.map { it.name }.toSet()
            val incoming = File(stage, "new"); val outgoing = File(stage, "old")
            Files.createDirectories(File(incoming, "mods").toPath()); Files.createDirectories(File(outgoing, "mods").toPath())
            val arriving = wanted.filter { it.name !in keptNames }.map { entry ->
                val blob = blob(dir, entry.sha1)
                if (!blob.isFile) throw IOException("Snapshot is damaged: ${entry.name} is missing")
                File(incoming, "mods/${entry.name}").also { place(blob, it, link) } to entry.sha1
            }
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
                leaving.forEach { move(it, File(outgoing, "mods/${it.name}")) }
                if (Files.exists(index.toPath(), NOFOLLOW_LINKS)) move(index, File(outgoing, INDEX))
                arriving.forEach { (file, _) -> move(file, File(mods, file.name)) }
                if (restoredIndex != null) move(restoredIndex, index)
            } catch (failure: Exception) {
                var rollbackFailed = false
                for ((from, to) in undo.asReversed()) {
                    try { Files.move(to.toPath(), from.toPath()) } catch (error: Exception) { rollbackFailed = true; failure.addSuppressed(error) }
                }
                if (rollbackFailed) { keepStage = true; throw ModInstallFailure.Rollback(stage.path, failure) }
                throw failure
            }
            arriving.forEach { (file, sha1) -> hashes.remember(File(mods, file.name), sha1) }
            hashes.save()
            return changes.public
        } finally { if (!keepStage) FileOps.delete(stage) }
    }

    internal fun deleteLocked(dir: File, snapshot: Snapshot) { FileOps.delete(folder(dir, snapshot)); prune(dir) }

    /**
     * After a kill: unfinished snapshots (`.tmp-*`) go. A restore (`.restore-*`) that still had files to bring in puts
     * back what it moved out of `mods/` (`old/mods`) by [ModInstaller.putBack]'s rules (a mod the snapshot's copy already
     * replaced keeps that copy), and the old index when there's none. One with nothing left to bring in had finished.
     * The caller holds [InstanceStore.lock] for [dir].
     */
    fun recover(dir: File) {
        for (stage in root(dir).listFiles().orEmpty().filter { it.name.startsWith(".tmp-") || it.name.startsWith(".restore-") }) {
            val incoming = File(stage, "new"); val outgoing = File(stage, "old")
            if (File(incoming, "mods").listFiles().orEmpty().isNotEmpty() || File(incoming, INDEX).exists()) {
                ModInstaller.putBack(File(outgoing, "mods").listFiles().orEmpty().map { it to it.name }, File(dir, "mods"))
                val index = File(dir, INDEX)
                if (File(outgoing, INDEX).isFile && !Files.exists(index.toPath(), NOFOLLOW_LINKS)) Files.move(File(outgoing, INDEX).toPath(), index.toPath())
            }
            FileOps.delete(stage)
        }
    }

    /** Deletes blobs no snapshot names, and snapshots whose file list can't be read: broken ones and the first layout's. */
    private fun prune(dir: File) {
        val manifests = root(dir).listFiles().orEmpty().filter { !it.name.startsWith('.') && it.name != "blobs" && it.isDirectory }
            .associateWith { runCatching { files(it) }.getOrNull() }
        manifests.filterValues { it == null }.keys.forEach(FileOps::delete)
        val named = manifests.values.filterNotNull().flatten().map { it.sha1 }.toSet()
        blobs(dir).listFiles().orEmpty().filter { it.name !in named }.forEach(FileOps::delete)
    }

    /** [Changes] plus the snapshot-side names of the changed mods, which a pinning restore needs. */
    private class Diff(val public: Changes, val changedBefore: List<String>)
    private fun changes(dir: File, snapshotFolder: File, hashes: HashCache): Diff {
        fun isJar(name: String) = name.endsWith(".jar") || name.endsWith(".jar.disabled")
        fun byMod(files: List<Pair<File, Entry>>, index: ModIndex): Map<String, Entry> {
            val entries = runCatching { index.read() }.getOrDefault(emptyMap())
            return files.filter { isJar(it.second.name) }.associate { (file, entry) ->
                (ModJarMetadata.read(file)?.modIds?.firstOrNull() ?: entries[ModIndex.key(entry.name)]?.projectId ?: ModIndex.key(entry.name)) to entry
            }
        }
        val before = byMod(files(snapshotFolder).map { blob(dir, it.sha1) to it }, ModIndex(snapshotFolder))
        val now = byMod(modFiles(dir).filter { isJar(it.name) }.map { it to Entry(it.name, hashes.sha1(it)) }, ModIndex(dir))
        val changed = now.keys.filter { it in before && before.getValue(it) != now.getValue(it) }
        return Diff(Changes((now.keys - before.keys).map { now.getValue(it).name }.sorted(), (before.keys - now.keys).map { before.getValue(it).name }.sorted(),
            changed.map { now.getValue(it).name }.sorted()), changed.map { before.getValue(it).name })
    }

    /** Puts [file] in the store as [blob] unless it's there already; a crash never leaves a partial blob under its name. */
    private fun store(file: File, blob: File, link: (Path, Path) -> Unit) {
        if (blob.isFile) return
        val part = File(blob.parentFile, ".part-${UUID.randomUUID()}")
        try { place(file, part, link); Files.move(part.toPath(), blob.toPath(), ATOMIC_MOVE) } finally { part.delete() }
    }
    private fun place(source: File, target: File, link: (Path, Path) -> Unit) {
        try { link(source.toPath(), target.toPath()) }
        catch (_: UnsupportedOperationException) { Files.copy(source.toPath(), target.toPath(), REPLACE_EXISTING) }
        catch (_: IOException) { Files.copy(source.toPath(), target.toPath(), REPLACE_EXISTING) } // Android: SELinux denies `link`
    }

    /** sha1s of live `mods/` files, reused while a file keeps its size, mtime and inode (Cofre replaces jars, never edits them). */
    private class HashCache(dir: File) {
        private val file = File(root(dir), HASHES)
        private val old = runCatching { Kit.json.decodeFromString<Map<String, Hashed>>(file.readText()) }.getOrDefault(emptyMap())
        private val now = mutableMapOf<String, Hashed>()
        private fun attributes(jar: File) = Files.readAttributes(jar.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            .let { Hashed(it.size(), it.lastModifiedTime().toMillis(), it.fileKey()?.toString(), "") }
        fun sha1(jar: File): String {
            val seen = attributes(jar)
            val known = (now[jar.name] ?: old[jar.name])?.takeIf { it.copy(sha1 = "") == seen }
            return (known ?: seen.copy(sha1 = Hashes.sha1(jar))).also { now[jar.name] = it }.sha1
        }
        fun remember(jar: File, sha1: String) { now[jar.name] = attributes(jar).copy(sha1 = sha1) }
        fun save() { if (now != old && file.parentFile.isDirectory) runCatching { FileOps.atomic(file, Kit.json.encodeToString(now).toByteArray()) } }
    }

    private fun root(dir: File): File = File(dir, ".cofre/snapshots")
    private fun blobs(dir: File): File = File(root(dir), "blobs")
    private fun blob(dir: File, sha1: String): File { require(sha1.matches(Regex("[0-9a-f]{40}"))) { "Not a sha1" }; return File(blobs(dir), sha1) }
    private fun files(folder: File): List<Entry> = Kit.json.decodeFromString<List<Entry>>(File(folder, FILES).readText()).onEach { safeFilename(it.name) }
    private fun folder(dir: File, snapshot: Snapshot): File {
        safeFilename(snapshot.id); require(!snapshot.id.startsWith('.') && snapshot.id != "blobs") { "Not a snapshot" }
        return File(root(dir), snapshot.id).also { require(File(it, META).isFile && File(it, FILES).isFile) { "Snapshot is gone" } }
    }
    /** Top-level files of `mods/`, without downloads still running (`*.part`). */
    private fun modFiles(dir: File): List<File> = File(dir, "mods").listFiles().orEmpty().filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && !it.name.endsWith(".part") }
    private suspend fun <T> locked(dir: File, block: () -> T): T = withContext(Dispatchers.IO) { InstanceStore.lock(dir).withLock { block() } }
}
