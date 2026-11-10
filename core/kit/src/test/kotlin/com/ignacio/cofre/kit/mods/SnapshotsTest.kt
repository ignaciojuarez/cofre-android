package com.ignacio.cofre.kit.mods

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class SnapshotsTest {
    @TempDir lateinit var root: File
    private val mods: File get() = File(root, "mods").also { it.mkdirs() }
    private val index get() = ModIndex(root)

    @Test fun restoreBringsModsAndIndexBackExactly() = runBlocking {
        File(mods, "a-1.jar").writeBytes(modJar("a")); File(mods, "b.jar").writeBytes(modJar("b")); File(mods, "c.jar.disabled").writeBytes(modJar("c"))
        File(mods, "config").mkdirs()
        index.write(mapOf("a-1.jar" to ModIndex.Entry("a", "a-1"), "b.jar" to ModIndex.Entry("b", "b-1")))
        val before = index.read()
        val snapshot = Snapshots.take(root, Snapshots.Reason.LastGood)
        assertTrue(Files.isSameFile(File(mods, "b.jar").toPath(), File(root, ".cofre/snapshots/${snapshot.id}/mods/b.jar").toPath()))

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
        assertEquals(listOf("snapshots"), File(root, ".cofre").list()!!.toList()); assertEquals(1, File(root, ".cofre/snapshots").list()!!.size)
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
}
