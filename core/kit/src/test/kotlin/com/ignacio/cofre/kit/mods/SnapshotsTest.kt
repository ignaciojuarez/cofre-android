package com.ignacio.cofre.kit.mods

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit

class SnapshotsTest {
    @TempDir lateinit var root: File
    private val mods: File get() = File(root, "mods").also { it.mkdirs() }
    private val index get() = ModIndex(root)
    private val blobs get() = File(root, ".cofre/snapshots/blobs")
    private fun blob(bytes: ByteArray) = File(blobs, Hashes.sha1(bytes))
    private fun blobBytes() = blobs.listFiles().orEmpty().sumOf { it.length() }
    private val noLinks: (java.nio.file.Path, java.nio.file.Path) -> Unit = { _, _ -> throw java.nio.file.AccessDeniedException("link") }

    @Test fun restoreBringsModsAndIndexBackExactly() = runBlocking {
        File(mods, "a-1.jar").writeBytes(modJar("a")); File(mods, "b.jar").writeBytes(modJar("b")); File(mods, "c.jar.disabled").writeBytes(modJar("c"))
        File(mods, "config").mkdirs()
        index.write(mapOf("a-1.jar" to ModIndex.Entry("a", "a-1"), "b.jar" to ModIndex.Entry("b", "b-1")))
        val before = index.read()
        val snapshot = Snapshots.take(root, Snapshots.Reason.LastGood)
        assertTrue(Files.isSameFile(File(mods, "b.jar").toPath(), blob(modJar("b")).toPath()))

        // An update (a-1 → a-2), a new mod, a removed mod, a turned-on mod.
        Files.move(File(mods, "a-1.jar").toPath(), File(root, "a-1.jar").toPath()); File(mods, "a-2.jar").writeBytes(modJar("a", "# v2"))
        File(mods, "d.jar").writeBytes(modJar("d")); File(mods, "b.jar").delete(); File(mods, "c.jar.disabled").renameTo(File(mods, "c.jar"))
        index.write(mapOf("a-2.jar" to ModIndex.Entry("a", "a-2"), "d.jar" to ModIndex.Entry("d", "d-1")))

        val changes = Snapshots.changedSince(root, snapshot)
        assertEquals(listOf("d.jar"), changes.added); assertEquals(listOf("b.jar"), changes.removed); assertEquals(listOf("a-2.jar", "c.jar"), changes.changed); assertEquals(4, changes.count)

        assertEquals(changes, Snapshots.restore(root, snapshot, pin = true))
        assertEquals(listOf("a-1.jar", "b.jar", "c.jar.disabled", "config"), mods.list()!!.sorted())
        assertContentEquals(modJar("a"), File(mods, "a-1.jar").readBytes())
        val restored = index.read(); assertTrue(restored.getValue("a-1.jar").pinned); assertFalse(restored.getValue("b.jar").pinned)
        assertEquals(before + ("a-1.jar" to before.getValue("a-1.jar").copy(pinned = true)), restored)
        assertEquals(0, Snapshots.changedSince(root, snapshot).count)
        assertEquals(listOf("snapshots"), File(root, ".cofre").list()!!.toList())
        assertEquals(listOf(snapshot.id, "blobs", "hashes.json").sorted(), File(root, ".cofre/snapshots").list()!!.sorted())
    }

    @Test fun restoreWithoutAnIndexRemovesTheCurrentOne() = runBlocking {
        File(mods, "a.jar").writeBytes(modJar("a")); val snapshot = Snapshots.take(root)
        File(mods, "b.jar").writeBytes(modJar("b")); index.write(mapOf("b.jar" to ModIndex.Entry("b", "b-1")))
        Snapshots.restore(root, snapshot)
        assertEquals(listOf("a.jar"), mods.list()!!.toList()); assertFalse(File(root, "cofre-mods.json").exists())
    }

    @Test fun prunesOldBeforeUpdateAndReplacesLastGood() = runBlocking {
        File(mods, "a.jar").writeBytes(modJar("a"))
        val manual = Snapshots.take(root, Snapshots.Reason.Manual)
        val updates = (1..7).map { Snapshots.take(root, Snapshots.Reason.BeforeUpdate) }
        Snapshots.take(root, Snapshots.Reason.LastGood); val good = Snapshots.take(root, Snapshots.Reason.LastGood)
        val list = Snapshots.list(root)
        assertEquals(listOf(good) + updates.takeLast(5).reversed() + manual, list)
        Snapshots.delete(root, manual); assertFalse(manual in Snapshots.list(root))
    }

    @Test fun snapshotsOfAnUnchangedFolderAddNoBlobs() {
        listOf("a", "b", "c").forEach { File(mods, "$it.jar").writeBytes(modJar(it)) }
        Snapshots.takeLocked(root, Snapshots.Reason.LastGood, noLinks)
        val stored = blobBytes(); assertEquals(mods.listFiles()!!.sumOf { it.length() }, stored); assertEquals(3, blobs.list()!!.size)
        repeat(3) { Snapshots.takeLocked(root, Snapshots.Reason.BeforeUpdate, noLinks) }
        assertEquals(stored, blobBytes()); assertEquals(4, Snapshots.list(root).size)
    }

    @Test fun snapshotsShareBlobsAndOnlyNewBytesAreAdded() {
        File(mods, "a-1.jar").writeBytes(modJar("a")); File(mods, "b.jar").writeBytes(modJar("b"))
        Snapshots.takeLocked(root, Snapshots.Reason.Manual, noLinks)
        File(mods, "a-1.jar").delete(); File(mods, "a-2.jar").writeBytes(modJar("a", "# v2"))
        File(mods, "b.jar").renameTo(File(mods, "b.jar.disabled")) // turning a mod off stores nothing new
        Snapshots.takeLocked(root, Snapshots.Reason.Manual, noLinks)
        assertEquals(setOf(modJar("a"), modJar("a", "# v2"), modJar("b")).map(Hashes::sha1).toSet(), blobs.list()!!.toSet())
        assertFalse(Files.isSameFile(File(mods, "a-2.jar").toPath(), blob(modJar("a", "# v2")).toPath())) // copied, not linked
    }

    @Test fun pruneRemovesBlobsNoSnapshotNames() = runBlocking {
        File(mods, "a-1.jar").writeBytes(modJar("a")); File(mods, "b.jar").writeBytes(modJar("b"))
        val old = Snapshots.take(root)
        File(mods, "a-1.jar").delete(); File(mods, "a-2.jar").writeBytes(modJar("a", "# v2"))
        Snapshots.take(root)
        assertTrue(blob(modJar("a")).isFile)
        Snapshots.delete(root, old)
        assertEquals(setOf(modJar("a", "# v2"), modJar("b")).map(Hashes::sha1).toSet(), blobs.list()!!.toSet())
    }

    @Test fun restoreAfterAnUpdateWithoutLinksBringsTheOldJarsBack() {
        File(mods, "a-1.jar").writeBytes(modJar("a")); File(mods, "b.jar").writeBytes(modJar("b")); File(mods, "c.jar.disabled").writeBytes(modJar("c"))
        index.write(mapOf("a-1.jar" to ModIndex.Entry("a", "a-1")))
        val snapshot = Snapshots.takeLocked(root, Snapshots.Reason.BeforeUpdate, noLinks)
        val bInode = Files.getAttribute(File(mods, "b.jar").toPath(), "unix:ino")
        File(mods, "a-1.jar").delete(); File(mods, "a-2.jar").writeBytes(modJar("a", "# v2")); File(mods, "c.jar.disabled").renameTo(File(mods, "c.jar"))
        index.write(mapOf("a-2.jar" to ModIndex.Entry("a", "a-2")))
        Snapshots.restoreLocked(root, snapshot, link = noLinks)
        assertEquals(listOf("a-1.jar", "b.jar", "c.jar.disabled"), mods.list()!!.sorted())
        assertContentEquals(modJar("a"), File(mods, "a-1.jar").readBytes()); assertContentEquals(modJar("c"), File(mods, "c.jar.disabled").readBytes())
        assertEquals(bInode, Files.getAttribute(File(mods, "b.jar").toPath(), "unix:ino")) // an unchanged jar is left in place
        assertEquals(setOf("a-1.jar"), index.read().keys)
        assertEquals(0, runBlocking { Snapshots.changedSince(root, snapshot) }.count)
        assertTrue(File(root, ".cofre/snapshots").list()!!.none { it.startsWith(".") })
    }

    @Test fun aMissingBlobFailsTheRestoreBeforeTouchingTheFolder() {
        File(mods, "a.jar").writeBytes(modJar("a")); val snapshot = Snapshots.takeLocked(root, Snapshots.Reason.Manual)
        File(mods, "a.jar").delete(); File(mods, "b.jar").writeBytes(modJar("b")); blob(modJar("a")).delete()
        assertFailsWith<java.io.IOException> { Snapshots.restoreLocked(root, snapshot) }
        assertEquals(listOf("b.jar"), mods.list()!!.toList())
    }

    @Test fun snapshotsInTheFirstLayoutAreDropped() {
        File(mods, "a.jar").writeBytes(modJar("a"))
        val legacy = File(root, ".cofre/snapshots/1-manual").also { File(it, "mods").mkdirs() }
        File(legacy, "mods/a.jar").writeBytes(modJar("a")); File(legacy, "snapshot.json").writeText("""{"id":"1-manual","reason":"manual","createdAt":1}""")
        assertTrue(Snapshots.list(root).isEmpty())
        val snapshot = Snapshots.takeLocked(root, Snapshots.Reason.Manual)
        assertFalse(legacy.exists()); assertEquals(listOf(snapshot), Snapshots.list(root))
    }

    @Test fun recoverUndoesAnUnfinishedRestoreAndDropsUnfinishedSnapshots() {
        val snapshots = File(root, ".cofre/snapshots"); File(mods, "kept.jar").writeBytes(modJar("kept"))
        File(snapshots, ".tmp-1").mkdirs(); File(snapshots, ".tmp-1/files.json").writeText("[]")
        // Killed while bringing files in: what it moved out goes back, with the index.
        val cut = File(snapshots, ".restore-2").also { File(it, "new/mods").mkdirs(); File(it, "old/mods").mkdirs() }
        File(cut, "new/mods/z.jar").writeBytes(modJar("z")); File(cut, "old/mods/a.jar.disabled").writeBytes(modJar("a")); File(cut, "old/cofre-mods.json").writeText("{}")
        // Killed after the last file came in: it had finished, so what left stays gone.
        val done = File(snapshots, ".restore-3").also { File(it, "new/mods").mkdirs(); File(it, "old/mods").mkdirs() }
        File(done, "old/mods/d.jar").writeBytes(modJar("d"))
        Snapshots.recover(root)
        assertEquals(setOf("kept.jar", "a.jar.disabled"), mods.list()!!.toSet()); assertEquals("{}", File(root, "cofre-mods.json").readText())
        assertTrue(snapshots.list()!!.isEmpty())
    }

    @Test fun downloadsStillRunningAreNotPartOfASnapshot() = runBlocking {
        File(mods, "a.jar").writeBytes(modJar("a")); File(mods, "b.jar.part").writeText("half")
        val snapshot = Snapshots.take(root)
        assertEquals(listOf("a.jar"), Kit.json.decodeFromString<List<Snapshots.Entry>>(File(root, ".cofre/snapshots/${snapshot.id}/files.json").readText()).map { it.name })
        Snapshots.restore(root, snapshot); assertTrue(File(mods, "b.jar.part").isFile, "a restore leaves a running download alone")
    }
}
