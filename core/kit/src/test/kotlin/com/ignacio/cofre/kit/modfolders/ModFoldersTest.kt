package com.ignacio.cofre.kit.modfolders

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Zip
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.instances.InstanceStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class ModFoldersTest {
    @TempDir lateinit var home: File
    private val instances by lazy { InstanceStore(File(home, "instances")) }
    private val folders by lazy { ModFolderStore(File(home, "mod-folders")) }

    private fun write(dir: File, path: String, text: String = path) = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun isLink(dir: File, name: String) = Files.isSymbolicLink(File(dir, name).toPath())

    @Test fun createListRenameAndReload() {
        val a = folders.create("Survival mods", loaderVersion = "26.2.0.93")
        assertTrue(a.playable); assertEquals("26.2", a.gameVersion); assertEquals(Loader.NeoForge, a.loader)
        val dir = folders.directory(a)
        ModFolderStore.CONTENT.forEach { assertTrue(File(dir, it).isDirectory) }
        assertFalse(File(dir, ".cofre-folder.json").readText().contains(home.absolutePath))
        assertEquals("Survival mods 2", folders.uniqueName("Survival mods"))
        assertFails { folders.create("Survival mods") }
        folders.rename(a.id, "Main")
        assertEquals(listOf("Main"), ModFolderStore(folders.root).list().map { it.name })
        assertEquals(a.id, folders.get(a.id).id, "rename keeps the id, so links keep working")
        assertTrue(folders.root.listFiles()!!.none { it.name.startsWith(".cofre-new-") }, "no stage left behind")
    }

    @Test fun pathsAndSchemaAreChecked() {
        for (id in listOf("../out", ".hidden", "a/b", "")) assertFails { folders.save(ModFolder(id, "x")) }
        val f = folders.create("A")
        File(folders.directory(f), ".cofre-folder.json").writeText("""{"id":"${f.id}","name":"A","schema":2}""")
        assertFails { folders.get(f.id) }; assertTrue(folders.list().isEmpty())
        assertFails { folders.save(f) }
    }

    @Test fun fitsAndPlayable() {
        val instance = Instance("a", "A")
        val ok = ModFolder("f", "F")
        assertTrue(ok.playable); assertTrue(ok.fits(instance))
        val fabric = ok.copy(loader = Loader.Fabric)
        assertFalse(fabric.playable); assertFalse(fabric.fits(instance)); assertTrue(fabric.fits(instance.copy(loader = Loader.Fabric)))
        val old = ok.copy(gameVersion = "1.21.1")
        assertFalse(old.playable); assertFalse(old.fits(instance))
    }

    @Test fun copyAndDuplicateTakeContentAndIndexButNotCofreFiles() {
        val a = folders.create("A")
        val dir = folders.directory(a)
        write(dir, "mods/a.jar"); write(dir, "shaderpacks/s.zip"); write(dir, ModFolderStore.INDEX, "{}"); write(dir, ".cofre-trash/content/x", "old")
        folders.save(a.copy(autoUpdate = AutoUpdate.Auto, includeBeta = true))
        val b = folders.duplicate(a.id, "B")
        val copy = folders.directory(b)
        assertEquals("mods/a.jar", File(copy, "mods/a.jar").readText()); assertTrue(File(copy, "shaderpacks/s.zip").isFile); assertTrue(File(copy, ModFolderStore.INDEX).isFile)
        assertFalse(File(copy, ".cofre-trash").exists()); assertTrue(File(copy, "resourcepacks").isDirectory)
        assertEquals(AutoUpdate.Auto, b.autoUpdate); assertTrue(b.includeBeta); assertEquals(a.id, b.createdFrom)
        val fabric = folders.copy(dir, "Fabric", "26.2", Loader.Fabric, null, a.id)
        assertFalse(fabric.playable); assertTrue(File(folders.directory(fabric), "mods/a.jar").isFile)
    }

    @Test fun trashRestorePurgeAndUsedBy() {
        val a = folders.create("A"); write(folders.directory(a), "mods/a.jar")
        val user = Instance("i", "I", modFolder = a.id)
        assertEquals(listOf(user), folders.usedBy(a.id, listOf(user, Instance("j", "J"))))
        assertTrue(folders.size(a.id) > 0)
        val token = folders.trash(a.id)
        assertTrue(folders.list().isEmpty()); assertEquals(a, folders.recentlyDeleted().single().folder)
        assertEquals(a, folders.restore(token), "same id, so links work again")
        assertTrue(File(folders.directory(a), "mods/a.jar").isFile)
        val again = folders.trash(a.id)
        folders.purgeOldTrash(30, Instant.now().plusSeconds(31L * 86_400))
        assertTrue(folders.recentlyDeleted().isEmpty()); assertFails { folders.purge(again) }
    }

    @Test fun linkMakesRelativeLinksAndRefusesAFolderWithFiles() {
        val instance = instances.create("A"); val dir = instances.directory(instance)
        val folder = folders.directory(folders.create("A mods"))
        write(folder, "mods/a.jar")
        ModFolderLink.link(dir, folder)
        ModFolderStore.CONTENT.forEach { assertTrue(isLink(dir, it)) }
        assertFalse(Files.readSymbolicLink(File(dir, "mods").toPath()).isAbsolute)
        assertTrue(File(dir, "mods/a.jar").isFile, "the game sees the folder's jars")
        ModFolderLink.link(dir, folder) // idempotent
        assertTrue(ModFolderLink.linked(dir))

        ModFolderLink.link(dir, null)
        ModFolderStore.CONTENT.forEach { assertFalse(isLink(dir, it)); assertTrue(File(dir, it).isDirectory) }
        assertTrue(File(folder, "mods/a.jar").isFile, "unlinking never touches the folder")

        write(dir, "mods/mine.jar")
        assertFails { ModFolderLink.link(dir, folder) }
        assertTrue(File(dir, "mods/mine.jar").isFile)
    }

    @Test fun migrationMovesContentInLinksAndIsIdempotent() {
        val a = instances.create("A", loaderVersion = "26.2.0.93"); val dir = instances.directory(a)
        write(dir, "mods/a.jar"); write(dir, "resourcepacks/r.zip"); write(dir, ModFolderStore.INDEX, "{}"); write(dir, "config/c.toml")
        val b = instances.create("B")
        assertEquals(2, ModFolderMigration.run(instances, folders))
        val moved = instances.get(a.id); val folder = folders.get(assertNotNull(moved.modFolder))
        assertEquals("A mods", folder.name); assertEquals("26.2.0.93", folder.loaderVersion); assertTrue(folder.fits(moved))
        val fdir = folders.directory(folder)
        assertTrue(File(fdir, "mods/a.jar").isFile); assertTrue(File(fdir, "resourcepacks/r.zip").isFile); assertTrue(File(fdir, ModFolderStore.INDEX).isFile)
        assertTrue(isLink(dir, "mods")); assertFalse(File(dir, ModFolderStore.INDEX).exists()); assertTrue(File(dir, "config/c.toml").isFile)
        assertNotNull(instances.get(b.id).modFolder)
        assertEquals(0, ModFolderMigration.run(instances, folders)); assertEquals(2, folders.list().size)
        assertFalse(File(dir, ".cofre-mod-folder").exists())
    }

    @Test fun migrationResumesAfterACrashAtEachStep() {
        val a = instances.create("A"); val dir = instances.directory(a)
        write(dir, "mods/a.jar"); write(dir, "shaderpacks/s.zip")
        // Crash 1: the journal names a folder that was never made, and mods/ was already moved by hand of a crash.
        val id = ModFolderStore.newID("A")
        File(dir, ".cofre-mod-folder").writeText(id)
        val half = File(folders.root, id).apply { mkdirs() }
        Files.move(File(dir, "mods").toPath(), File(half, "mods").toPath())
        assertEquals(1, ModFolderMigration.run(instances, folders))
        val folder = folders.get(id)
        assertEquals(id, instances.get(a.id).modFolder); assertEquals(1, folders.list().size)
        assertTrue(File(dir, "mods/a.jar").isFile); assertTrue(File(dir, "shaderpacks/s.zip").isFile)

        // Crash 2: saved with its folder but killed before linking.
        val b = instances.create("B"); val bdir = instances.directory(b)
        instances.save(b.copy(modFolder = folder.id))
        assertEquals(0, ModFolderMigration.run(instances, folders))
        assertTrue(isLink(bdir, "mods")); assertTrue(File(bdir, "mods/a.jar").isFile)
    }

    @Test fun instancesAlreadyLinkedAreSkipped() {
        val a = instances.create("A"); val dir = instances.directory(a)
        val other = folders.create("Shared")
        ModFolderLink.link(dir, folders.directory(other))
        assertEquals(0, ModFolderMigration.run(instances, folders))
        assertNull(instances.get(a.id).modFolder); assertEquals(1, folders.list().size)
    }

    @Test fun duplicateSharesTheFolderWithoutCopyingIt() {
        val a = instances.create("A"); write(instances.directory(a), "mods/a.jar"); write(instances.directory(a), "saves/w/level.dat")
        ModFolderMigration.run(instances, folders)
        val copy = instances.duplicate(a.id, "B")
        val dir = instances.directory(copy)
        assertEquals(instances.get(a.id).modFolder, copy.modFolder)
        assertTrue(isLink(dir, "mods")); assertTrue(File(dir, "mods/a.jar").isFile); assertTrue(File(dir, "saves/w/level.dat").isFile)
        assertEquals(1, folders.list().size)
        assertEquals(listOf(copy), folders.usedBy(copy.modFolder!!, instances.list()).filter { it.id == copy.id })
    }

    @Test fun instanceTrashRestoreAndPurgeKeepTheFolder() {
        val a = instances.create("A"); write(instances.directory(a), "mods/a.jar")
        ModFolderMigration.run(instances, folders)
        val folder = folders.get(instances.get(a.id).modFolder!!)
        val token = instances.trash(a.id)
        assertTrue(File(folders.directory(folder), "mods/a.jar").isFile)
        val restored = instances.restore(token)
        assertTrue(File(instances.directory(restored), "mods/a.jar").isFile, "the link still resolves after restore")
        instances.purge(instances.trash(a.id))
        assertTrue(File(folders.directory(folder), "mods/a.jar").isFile, "purging an instance never follows its links")
    }

    @Test fun exportIsSelfContainedAndImportMakesANewFolder() {
        val a = instances.create("A"); val dir = instances.directory(a)
        write(dir, "mods/a.jar"); write(dir, ModFolderStore.INDEX, "{}"); write(dir, "options.txt")
        ModFolderMigration.run(instances, folders)
        val folder = folders.get(instances.get(a.id).modFolder!!)
        val zip = instances.export(a.id, File(home, "a.zip"), folder = folders.directory(folder))
        val paths = Zip.entries(zip).map { it.path }
        assertTrue("mods/a.jar" in paths); assertTrue(ModFolderStore.INDEX in paths); assertTrue("options.txt" in paths)
        assertTrue(paths.none { it.startsWith(".cofre") })

        val imported = instances.import(zip, "Imported")
        assertNull(imported.modFolder); assertFalse(isLink(instances.directory(imported), "mods"))
        val adopted = assertNotNull(ModFolderMigration.adopt(imported, instances, folders))
        assertNotEquals(folder.id, adopted.modFolder); assertEquals("Imported mods", folders.get(adopted.modFolder!!).name)
        assertTrue(File(instances.directory(adopted), "mods/a.jar").isFile)
        assertTrue(isLink(instances.directory(adopted), "mods"))
    }

    @Test fun contentStoreActsOnTheFolderAndKeepsActivePacksFromTheInstance() {
        val a = instances.create("A"); val dir = instances.directory(a)
        ModFolderMigration.run(instances, folders)
        val fdir = folders.directory(folders.get(instances.get(a.id).modFolder!!))
        write(fdir, "resourcepacks/Pack.zip"); write(fdir, "mods/m.jar"); write(dir, "options.txt", "resourcePacks:[\"vanilla\",\"file/Pack.zip\"]")
        assertFails { ContentStore(dir).list(ContentKind.Mods) } // never through the link
        val store = ContentStore(dir, fdir)
        assertTrue(store.list(ContentKind.ResourcePacks).single().inUse)
        val token = store.trash(store.list(ContentKind.Mods).single())
        assertTrue(File(fdir, ".cofre-trash/content/${token.trashID}").isFile)
        store.restore(token); assertTrue(File(fdir, "mods/m.jar").isFile)
    }
}
