package com.ignacio.cofre.kit.content

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ContentTest {
    @TempDir lateinit var root: File
    private fun write(path: String, text: String = ""): File = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun jar(path: String, entries: Map<String, String>) { val file = File(root, path); file.parentFile.mkdirs(); ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v.toByteArray()); z.closeEntry() } } }
    @Test fun listToggleTrashUndoAndResourcePackUse() {
        write("resourcepacks/Pack.zip"); write("resourcepacks/Off.zip.disabled"); write("options.txt", "resourcePacks:[\"vanilla\",\"file/Pack.zip\"]")
        val s = ContentStore(root); val pack = s.list(ContentKind.ResourcePacks).first { it.id == "Pack.zip" }; assertTrue(pack.inUse)
        s.setEnabled(false, pack); assertFalse(s.list(ContentKind.ResourcePacks).any { it.inUse }); s.setEnabled(true, pack)
        val t = s.trash(pack); assertTrue(File(root, ".cofre-trash/content/${t.trashID}").exists()); s.restore(t); assertEquals(2, s.list(ContentKind.ResourcePacks).size)
    }
    @Test fun modpacksAreNotFilesAndFoldersAreMinecraftsOwn() {
        val s = ContentStore(root); assertFails { s.list(ContentKind.Modpacks) }; assertEquals("mods", s.folder(ContentKind.Mods).name); assertEquals("shaderpacks", s.folder(ContentKind.Shaders).name)
    }
    @Test fun sameDatapackInTwoWorldsRemainsDistinct() {
        write("saves/a/datapacks/pack.zip"); write("saves/b/datapacks/pack.zip")
        val s = ContentStore(root); val all = s.list(ContentKind.DataPacks); assertEquals(setOf("a/pack.zip", "b/pack.zip"), all.map { it.id }.toSet())
        s.setEnabled(false, all.first { it.world == "a" }); assertTrue(File(root, "saves/a/datapacks/pack.zip.disabled").exists()); assertTrue(File(root, "saves/b/datapacks/pack.zip").exists())
    }
    @Test fun shaderSelectionReadsIrisOculusAndLegacyProperties() {
        write("shaderpacks/Pack.zip"); val iris = write("config/iris.properties", "enableShaders=true\nshaderPack=Pack.zip")
        val s = ContentStore(root); assertTrue(s.list(ContentKind.Shaders).single().inUse)
        iris.writeText("enableShaders=false\nshaderPack=Pack.zip"); assertNull(s.activeShaderPack())
        iris.delete(); write("config/oculus.properties", "shaderPack=Pack.zip"); assertEquals("Pack.zip", s.activeShaderPack())
        File(root, "config/oculus.properties").delete(); write("optionsshaders.txt", "shaderPack=OFF"); assertNull(s.activeShaderPack())
    }
    @Test fun propertiesSupportEscapesAndContinuations() {
        val p = ContentStore.properties("# comment\nshaderPack:My\\ Pack.zip\nkey=first\\\n second\nunicode=\\u0041")
        assertEquals("My Pack.zip", p["shaderPack"]); assertEquals("firstsecond", p["key"]); assertEquals("A", p["unicode"])
    }
    @Test fun updateLoadersFollowContentKinds() {
        assertEquals(listOf("quilt", "fabric"), ContentKind.Mods.updateLoaders(Loader.Quilt)); assertEquals(listOf("neoforge"), ContentKind.Mods.updateLoaders(Loader.NeoForge))
        assertEquals(listOf("minecraft"), ContentKind.ResourcePacks.updateLoaders(Loader.NeoForge)); assertEquals(listOf("datapack"), ContentKind.DataPacks.updateLoaders(Loader.NeoForge)); assertTrue(ContentKind.Modpacks.updateLoaders(Loader.NeoForge).isEmpty())
    }
    @Test fun jarMetadataReadsFabricQuiltAndNeoForge() {
        jar("mods/f.jar", mapOf("fabric.mod.json" to """{"id":"f","name":"Fabric Name","version":"1"}"""))
        jar("mods/q.jar", mapOf("quilt.mod.json" to """{"quilt_loader":{"id":"q","version":"2","metadata":{"name":"Quilt Name"}}}"""))
        jar("mods/n.jar", mapOf("META-INF/neoforge.mods.toml" to "modLoader=\"javafml\"\n[[mods]]\nmodId=\"n\"\ndisplayName=\"Neo Name\"\nversion=\"3\"\n"))
        val all = ContentStore(root).list(ContentKind.Mods).associateBy { it.modID }
        assertEquals("Fabric Name", all["f"]?.name); assertEquals("2", all["q"]?.version); assertEquals("Neo Name", all["n"]?.name)
    }
    @Test fun manifestVersionAndPackDescriptionAreInspected() {
        jar("mods/n.jar.disabled", mapOf("META-INF/mods.toml" to "[[mods]]\nmodId='n'\ndisplayName='Named'\nversion='\${file.jarVersion}'", "META-INF/MANIFEST.MF" to "Implementation-Version: 4.2\n"))
        jar("resourcepacks/p.zip", mapOf("pack.mcmeta" to """{"pack":{"pack_format":65,"description":{"text":"Hello","extra":[{"text":" pack"}]}}}"""))
        val s = ContentStore(root); assertEquals("4.2", s.list(ContentKind.Mods).single().version); assertFalse(s.list(ContentKind.Mods).single().enabled)
        assertEquals("Hello pack", s.list(ContentKind.ResourcePacks).single().name)
    }
    @Test fun trashRestoreConflictsNeverOverwrite() {
        write("mods/a.jar", "old"); val s = ContentStore(root); val t = s.trash(s.list(ContentKind.Mods).single()); write("mods/a.jar", "new")
        assertFails { s.restore(t) }; assertEquals("new", File(root, "mods/a.jar").readText())
    }
    @Test fun invalidWorldPathsAndForgedIDsFail() {
        val s = ContentStore(root); assertFails { s.folder(ContentKind.DataPacks, "../out") }
        assertFails { s.file(ContentStore.Item("../out", "x", "", null, ContentKind.Mods, true, false)) }
    }
}
