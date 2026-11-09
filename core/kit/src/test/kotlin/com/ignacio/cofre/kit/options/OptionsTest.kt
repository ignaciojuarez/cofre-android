package com.ignacio.cofre.kit.options

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class OptionsTest {
    @TempDir lateinit var root: File
    @Test fun newInstancesReceivePlayableDistances() {
        val o = GameOptions(root); assertTrue(o.seedDefaults(12000, 120))
        assertEquals("maxFps:120\nmenuBackgroundBlurriness:0\nrenderDistance:10\nsimulationDistance:8", o.file.readText()); assertFalse(o.seedDefaults(12000))
    }
    @Test fun smallerDevicesGetShorterDistances() {
        val o = GameOptions(root); o.file.writeText("lang:en_us\n"); o.seedDefaults(3862)
        assertEquals("6", o.get("renderDistance")); assertEquals("5", o.get("simulationDistance")); assertTrue(o.file.readText().endsWith('\n'))
    }
    @Test fun playerChoicesAndUnknownLinesArePreserved() {
        val o = GameOptions(root); val text = "unknown:value:with:colons\r\nrenderDistance:16\r\nmaxFps:30\r\n"
        o.file.writeText(text); o.seedDefaults(12000); assertTrue(o.file.readText().startsWith(text)); assertEquals("16", o.get("renderDistance")); assertEquals("30", o.get("maxFps"))
    }
    @Test fun vulkanReplacesForcedOpenGLAndIsIdempotent() {
        val o = GameOptions(root); o.file.writeText("lang:en_us\npreferredGraphicsBackend:\"opengl\"\ngraphicsPreset:\"fancy\"\n")
        assertTrue(o.useVulkan()); assertEquals("lang:en_us\npreferredGraphicsBackend:vulkan\ngraphicsPreset:\"fancy\"\n", o.file.readText()); assertFalse(o.useVulkan())
    }
    @Test fun duplicateOptionsAreUpdatedAndLineInjectionFails() {
        val o = GameOptions(root); o.file.writeText("maxFps:30\nmaxFps:60"); o.set("maxFps", "120")
        assertEquals("maxFps:120\nmaxFps:120", o.file.readText()); assertFails { o.set("lang", "a\nb") }; assertFails { o.set("a:b", "x") }
    }
    @Test fun fmlPreservesCommentsSpacingOtherSettingsAndTables() {
        val f = File(root, "config/fml.toml").apply { parentFile.mkdirs(); writeText("# hello\r\n  earlyWindowControl = true # keep\r\nfoo = 3\r\n[section]\r\nbar = true\r\n") }
        assertTrue(FMLConfig.disableEarlyWindow(root)); assertEquals("# hello\r\n  earlyWindowControl = false # keep\r\nfoo = 3\r\n[section]\r\nbar = true\r\n", f.readText()); assertFalse(FMLConfig.disableEarlyWindow(root))
    }
    @Test fun fmlAddsRootKeyBeforeTables() {
        val f = File(root, "config/fml.toml").apply { parentFile.mkdirs(); writeText("[section]\nearlyWindowControl = true\n") }
        FMLConfig.disableEarlyWindow(root); assertEquals("earlyWindowControl = false\n[section]\nearlyWindowControl = true\n", f.readText())
    }
    @Test fun fmlCreatesMissingFile() { FMLConfig.disableEarlyWindow(root); assertEquals("earlyWindowControl = false\n", File(root, "config/fml.toml").readText()) }
    @Test fun heapFollowsAndroidRAMTiers() {
        for ((ram, expected) in listOf(8192 to 4096, 12288 to 6144, 16384 to 8192, 32768 to 8192, 4096 to 2048, 2048 to 2048,
            // What Android reports for 16/12/8/6 GB phones (OnePlus 5, 6 GB: 5,714 MiB).
            15_200 to 8192, 11_400 to 6144, 7_600 to 4096, 5_714 to 2857)) assertEquals(expected, JavaHeap.autoMB(ram))
        assertFails { JavaHeap.autoMB(0) }
    }
    @Test fun bundledPresetPinsEveryModAndTheLibrariesTheyRequire() {
        val p = PresetCatalog.presets.single(); p.validate(); assertEquals("Handheld (NeoForge 26.2, Vulkan)", p.name)
        assertEquals(listOf("mc26.2-0.9.2-neoforge", "3.5.3+mc26.2", "3.9.7+26.2-neoforge", "1.16.5+26.2-neoforge", "1.11.3", "9.0.0-neoforge", "5.27.21+mc26.2"), p.pinnedMods.map { it.version })
        assertTrue(p.pinnedMods.all { it.url.startsWith("https://cdn.modrinth.com/") && it.sha512.length == 128 })
        assertEquals("6", p.options(4096)["renderDistance"]); assertEquals("10", p.options(8192)["renderDistance"])
    }
    @Test fun presetSeedsOnlyMissingSettings() {
        val o = GameOptions(root); o.file.writeText("renderDistance:16\nmaxFps:30\n"); assertTrue(o.seedPreset(PresetCatalog.presets.single(), 12000))
        assertEquals("16", o.get("renderDistance")); assertEquals("30", o.get("maxFps")); assertEquals("vulkan", o.get("preferredGraphicsBackend")); assertFalse(o.seedPreset(PresetCatalog.presets.single(), 12000))
    }
    @Test fun presetValidationRejectsBadHashesURLsDuplicatesAndSchemas() {
        val p = PresetCatalog.presets.single(); val m = p.pinnedMods.first()
        for (bad in listOf(m.copy(sha512 = "bad"), m.copy(url = "http://bad.test/a"), m.copy(file = "../bad.jar"))) assertFails { p.copy(pinnedMods = listOf(bad)).validate() }
        assertFails { p.copy(pinnedMods = listOf(m, m)).validate() }; assertFails { p.copy(firstCreateOptions = emptyMap()).validate() }
        assertFails { PresetCatalog.validate(PresetCatalog.Manifest(2, listOf(p))) }; assertFails { PresetCatalog.validate(PresetCatalog.Manifest(1, listOf(p, p))) }
    }
    @Test fun storageCountsFilesAndSkipsSymlinkTrees() {
        File(root, "mods").mkdir(); File(root, "mods/a").writeText("1234"); val outside = File(root, "outside").apply { mkdir() }; File(outside, "x").writeText("12")
        Files.createSymbolicLink(File(root, "mods/link").toPath(), outside.toPath())
        assertEquals(4, Storage.bytes(File(root, "mods"))); assertEquals(6, Storage.bytes(root))
    }
    @Test fun cleanupKeepsContentActiveLogsRecentDownloadsAndSymlinks() {
        val home = File(root, "home"); val game = File(root, "game"); val temp = File(root, "tmp")
        fun write(p: String): File = File(root, p).apply { parentFile.mkdirs(); writeText("test") }
        val disposable = listOf(write("game/logs/old.log"), write("game/crash-reports/a.txt"), write("tmp/old").apply { setLastModified(1) })
        val keep = listOf(write("game/saves/a/level.dat"), write("game/mods/a.jar"), write("game/logs/latest.log"), write("home/logs/launcher.log"), write("tmp/recent"), write("outside/important"))
        Files.createSymbolicLink(File(game, "logs/link").toPath(), File(root, "outside").toPath())
        val c = StorageCleanup(home, listOf(game), temp); assertEquals(12, c.reclaimableBytes); assertEquals(12, c.clear()); assertEquals(0, c.reclaimableBytes)
        assertTrue(disposable.none { it.exists() }); assertTrue(keep.all { it.exists() })
    }
    @Test fun presetInstanceGetsOptionsEarlyWindowOffAndPinnedMods() {
        val preset = PresetCatalog.presets.single()
        val downloads = PresetCatalog.apply(preset, root, 12000)
        val o = GameOptions(root)
        assertEquals("vulkan", o.get("preferredGraphicsBackend")); preset.options(12000).forEach { (k, v) -> assertEquals(v, o.get(k), k) }
        assertTrue("earlyWindowControl = false" in File(root, "config/fml.toml").readText())
        assertEquals(preset.pinnedMods.map { File(root, "mods/${it.file}").path }, downloads.map { it.destination })
        assertEquals(preset.pinnedMods.map { it.sha512 }, downloads.map { it.sha512 })
    }
    @Test fun plainInstanceGetsDefaultsAndNoMods() {
        assertEquals(emptyList(), PresetCatalog.apply(null, root, 4000))
        val o = GameOptions(root)
        assertEquals("vulkan", o.get("preferredGraphicsBackend")); assertEquals("6", o.get("renderDistance"))
        assertTrue(File(root, "config/fml.toml").isFile)
    }
}
