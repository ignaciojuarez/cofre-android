package com.ignacio.cofre.kit.instances

import com.ignacio.cofre.kit.common.Loader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class InstancesTest {
    @TempDir lateinit var root: File
    @Test fun createAndReloadVersionedRelativeMetadata() {
        val store = InstanceStore(root); val i = store.create("Main", loaderVersion = "26.2.0.93")
        assertEquals("26.2", i.gameVersion); assertEquals(Loader.NeoForge, i.loader)
        val text = File(store.directory(i), "cofre.json").readText()
        assertTrue(text.contains("\"schema\": 1")); assertFalse(text.contains(root.absolutePath))
        assertEquals(i, InstanceStore(root).list().single())
    }
    @Test fun metadataEditsPersistAndGroupsAreDistinct() {
        val s = InstanceStore(root); val a = s.create("A"); val b = s.create("B")
        s.favorite(a.id, true); s.group(a.id, "Friends"); s.group(b.id, "Friends"); s.notes(a.id, "hello"); s.loaderVersion(a.id, "26.2.0.93"); s.rename(a.id, "Z")
        assertEquals(listOf("Friends"), s.groups()); assertEquals(listOf("B", "Z"), s.list().map { it.name })
        assertEquals("hello", s.get(a.id).notes); assertTrue(s.get(a.id).favorite)
    }
    @Test fun runtimeRollbackUsesSuccessfulSettings() {
        val s = InstanceStore(root); val i = s.create("A")
        s.runtime(i.id, 4096, "-Dsafe=true"); s.markLastGood(i.id); s.runtime(i.id, 8192, "-Dbad=true")
        val restored = InstanceStore(root).rollbackRuntime(i.id)
        assertEquals(4096, restored.memoryMB); assertFalse(restored.autoMemory); assertEquals("-Dsafe=true", restored.jvmArguments)
    }
    @Test fun duplicateCopiesSettingsAndCanExcludeWorldsAndOptions() {
        val s = InstanceStore(root); val i = s.create("A", loaderVersion = "v")
        val dir = s.directory(i); File(dir, "mods").mkdir(); File(dir, "mods/a.jar").writeText("a"); File(dir, "saves").mkdir(); File(dir, "saves/world").mkdir(); File(dir, "options.txt").writeText("lang:x")
        s.favorite(i.id, true); s.notes(i.id, "note")
        val a = s.duplicate(i.id); assertTrue(File(s.directory(a), "saves/world").exists()); assertFalse(a.favorite)
        val b = s.duplicate(i.id, "B", includeWorlds = false, includeOptions = false)
        assertFalse(File(s.directory(b), "saves").exists()); assertFalse(File(s.directory(b), "options.txt").exists())
        assertEquals("note", b.notes); assertEquals("v", b.loaderVersion); assertTrue(File(s.directory(b), "mods/a.jar").isFile)
    }
    @Test fun trashRestoreWorksAcrossStoreReload() {
        val s = InstanceStore(root); val i = s.create("A"); val token = s.trash(i.id)
        assertTrue(s.list().isEmpty()); val reloaded = InstanceStore(root)
        assertEquals(i, reloaded.recentlyDeleted().single().instance); assertEquals(i, reloaded.restore(token)); assertEquals(i, reloaded.list().single())
    }
    @Test fun restoreDoesNotOverwriteReusedFolder() {
        val s = InstanceStore(root); val i = s.create("A"); val token = s.trash(i.id)
        s.save(i.copy(name = "Other")); val restored = s.restore(token)
        assertNotEquals(i.id, restored.id); assertEquals(2, s.list().size)
    }
    @Test fun exportImportRewritesIdentityAndKeepsFiles() {
        val s = InstanceStore(File(root, "instances")); val i = s.create("A")
        File(s.directory(i), "options.txt").writeText("lang:en_us")
        val archive = s.export(i.id, File(root, "instance.zip")); val imported = s.import(archive, "Imported")
        assertNotEquals(i.id, imported.id); assertEquals(imported.id, imported.gameDirectory)
        assertEquals("lang:en_us", File(s.directory(imported), "options.txt").readText()); assertEquals(imported, s.get(imported.id))
    }
    @Test fun absoluteTraversalAndSymlinkPathsAreRejected() {
        val s = InstanceStore(root)
        for (p in listOf("/tmp/out", "../out", "nested/out", ".")) assertFails { s.save(Instance("x", "x", gameDirectory = p)) }
        val outside = File(root, "outside").apply { mkdir() }; Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertFails { s.save(Instance("link", "Link")) }; assertFalse(File(outside, "cofre.json").exists())
    }
    @Test fun unknownSchemaIsNotOverwritten() {
        val s = InstanceStore(root); val i = s.create("A"); val file = File(s.directory(i), "cofre.json")
        val text = file.readText().replace("\"schema\": 1", "\"schema\": 2"); file.writeText(text)
        assertTrue(s.list().isEmpty()); assertFails { s.save(i) }; assertEquals(text, file.readText())
    }
    @Test fun aCopiedOrBrokenFolderDoesNotHideTheOthers() {
        val s = InstanceStore(root); val i = s.create("A")
        s.directory(i).copyRecursively(File(root, "copied-by-hand")) // its cofre.json names another folder
        File(root, "broken").mkdir(); File(root, "broken/cofre.json").writeText("{")
        assertEquals(listOf(i), s.list()); assertEquals("B", s.create("B").name)
    }
    @Test fun importingAnExportNextToItsOriginalPicksAFreeName() {
        val s = InstanceStore(File(root, "instances")); val i = s.create("A")
        val archive = s.export(i.id, File(root, "a.zip"))
        assertEquals("A 2", s.import(archive).name); assertEquals("A 3", s.import(archive).name)
        assertEquals("A copy", s.uniqueName("A copy"))
    }
    @Test fun duplicateNamesAndMissingProfilesFail() {
        val s = InstanceStore(root); val i = s.create("A")
        assertFails { s.create("A") }; assertFails { s.duplicate(i.id, "A") }; assertFails { s.get("absent") }
    }
    @Test fun exportWithoutWorldsDoesNotChangeOriginal() {
        val s = InstanceStore(File(root, "instances")); val i = s.create("A"); File(s.directory(i), "saves").mkdir(); File(s.directory(i), "saves/x").writeText("a")
        val archive = s.export(i.id, File(root, "a.zip"), includeWorlds = false)
        val b = s.import(archive, "B"); assertFalse(File(s.directory(b), "saves").exists()); assertTrue(File(s.directory(i), "saves/x").exists())
    }
    @Test fun lastPlayedPicksNewestAndCopiesStartFresh() {
        val s = InstanceStore(root); val a = s.create("A"); val b = s.create("B"); assertNull(s.lastPlayed())
        s.markPlayed(a.id, Instant.ofEpochMilli(2000)); s.markPlayed(b.id, Instant.ofEpochMilli(1000))
        assertEquals(a.id, InstanceStore(root).lastPlayed()?.id); assertNull(s.duplicate(a.id, "C").lastPlayedAt)
    }
    @Test fun duplicateLeavesContentTrashWithTheSource() {
        val s = InstanceStore(root); val i = s.create("A"); File(s.directory(i), ".cofre-trash/content/x").apply { parentFile.mkdirs(); writeText("old") }
        val copy = s.duplicate(i.id); assertFalse(File(s.directory(copy), ".cofre-trash").exists()); assertTrue(File(s.directory(i), ".cofre-trash/content/x").exists())
    }
    @Test fun purgeAndRemoveDeletePermanentlyWithoutFollowingLinks() {
        val s = InstanceStore(File(root, "instances")); val outside = File(root, "outside").apply { mkdir(); File(this, "keep").writeText("keep") }
        val a = s.create("A"); Files.createSymbolicLink(File(s.directory(a), "link").toPath(), outside.toPath())
        s.remove(a.id); assertTrue(s.list().isEmpty()); assertTrue(File(outside, "keep").isFile); assertFails { s.get(a.id) }
        val b = s.create("B"); val token = s.trash(b.id); s.purge(token)
        assertTrue(s.recentlyDeleted().isEmpty()); assertFails { s.restore(token) }; assertFails { s.purge(token) }
    }
    @Test fun exportAndDuplicateLeaveCofreFilesAndTokensBehind() {
        val s = InstanceStore(File(root, "instances")); val i = s.create("A")
        File(s.directory(i), ".cofre/launch.json").apply { parentFile.mkdirs(); writeText("""{"gameArgs":["--accessToken","eyJsecret"]}""") }
        File(s.directory(i), ".cofre-write-x").writeText("partial")
        val archive = s.export(i.id, File(root, "a.zip"))
        val names = java.util.zip.ZipFile(archive).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(names.none { it.startsWith(".cofre") }, names.toString())
        assertFalse(java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.any { zip.readBytes().decodeToString().contains("accessToken") }
        })
        val copy = s.duplicate(i.id); assertTrue(s.directory(copy).list().orEmpty().none { it.startsWith(".cofre") })
    }
    @Test fun oneLockPerInstanceFolder() {
        val s = InstanceStore(root); val a = s.create("A"); val b = s.create("B")
        assertSame(InstanceStore.lock(s.directory(a)), InstanceStore.lock(File(root, "./${a.id}")))
        assertNotSame(InstanceStore.lock(s.directory(a)), InstanceStore.lock(s.directory(b)))
    }
    @Test fun oldTrashIsPurgedAndRecentTrashKept() {
        val s = InstanceStore(root); val a = s.create("A"); val b = s.create("B"); val dir = s.directory(a)
        val mod = File(dir, "mods/old.jar").apply { parentFile.mkdirs(); writeText("x"); setLastModified(0) }
        val content = com.ignacio.cofre.kit.content.ContentStore(dir)
        val recent = content.trash(content.list(com.ignacio.cofre.kit.common.ContentKind.Mods).single { it.file == mod.name })
        val old = File(dir, ".cofre-trash/content/${java.util.UUID.randomUUID()}").apply { writeText("y"); setLastModified(0) }
        val world = File(dir, ".cofre-trash/worlds/${java.util.UUID.randomUUID()}").apply { mkdirs(); File(this, "level.dat").writeText("z"); setLastModified(0) }
        s.trash(b.id); val trashedLongAgo = File(root, ".cofre-trash/instances").listFiles()!!.single().apply { setLastModified(0) }
        s.purgeOldTrash()
        assertFalse(old.exists()); assertFalse(world.exists()); assertFalse(trashedLongAgo.exists())
        content.restore(recent); assertTrue(mod.isFile, "a file trashed just now stays, however old the file itself")
    }
}
