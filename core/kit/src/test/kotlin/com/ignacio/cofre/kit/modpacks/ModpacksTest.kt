package com.ignacio.cofre.kit.modpacks

import com.ignacio.cofre.kit.common.Loader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ModpacksTest {
    @TempDir lateinit var root: File
    private fun zip(files: Map<String, String>, name: String = "pack.mrpack"): File = File(root, name).also { file -> ZipOutputStream(file.outputStream()).use { z -> files.forEach { (path, text) -> z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry() } } }
    private fun index(version: String = "26.2", loader: String = "neoforge", files: String = "[]"): String = """{"formatVersion":1,"game":"minecraft","name":"Pack","dependencies":{"minecraft":"$version","$loader":"26.2.0.93"},"files":$files}"""
    @Test fun inspectModrinthWithoutExtracting() {
        val archive = zip(mapOf("modrinth.index.json" to index(files = """[{"path":"mods/a.jar","hashes":{"sha512":"${"a".repeat(128)}"},"downloads":["https://cdn.example/a.jar"],"fileSize":4},{"path":"mods/server.jar","env":{"client":"unsupported"}}]"""), "overrides/config/a" to "one", "client-overrides/config/a" to "two"))
        val p = Modpacks.inspect(archive)
        assertEquals("Pack", p.name); assertEquals(Loader.NeoForge, p.loader); assertEquals(1, p.files.size)
        assertEquals("https://cdn.example/a.jar", p.files.single().downloads.single())
        assertEquals("client-overrides/config/a", p.overrides.single().entry)
        assertFalse(File(root, "overrides").exists())
    }
    @Test fun wrappedCurseforgeKeepsManualFileIDs() {
        val p = Modpacks.inspect(zip(mapOf("wrapped/manifest.json" to """{"name":"CF","minecraft":{"version":"26.2","modLoaders":[{"id":"neoforge-26.2.0.93","primary":true}]},"files":[{"projectID":12,"fileID":34,"required":false}],"overrides":"custom"}""", "wrapped/custom/config/a" to "ok")))
        assertEquals(Modpack.Format.CurseForge, p.format); assertEquals(Loader.NeoForge, p.loader)
        assertEquals(12, p.files.single().projectID); assertFalse(p.files.single().required)
        assertEquals("config/a", p.overrides.single().path)
    }
    @Test fun rejectsWrongTargetWithTypedReasons() {
        val old = Modpacks.inspect(zip(mapOf("modrinth.index.json" to index("1.21.1"))))
        assertEquals(PackRejected.Reason.GameVersion("1.21.1"), assertFailsWith<PackRejected> { Modpacks.plan(old) }.reason)
        val fabric = Modpacks.inspect(zip(mapOf("modrinth.index.json" to index(loader = "fabric-loader"))))
        assertEquals(PackRejected.Reason.LoaderType(Loader.Fabric), assertFailsWith<PackRejected> { Modpacks.plan(fabric) }.reason)
    }
    @Test fun blankPackNameBecomesImportedPack() {
        val pack = Modpacks.inspect(zip(mapOf("modrinth.index.json" to index().replace("\"name\":\"Pack\"", "\"name\":\" \""))))
        assertEquals("Imported pack", Modpacks.plan(pack).name)
    }
    @Test fun plainArchiveCanBeInspectedButNeedsTargetEvidence() {
        val p = Modpacks.inspect(zip(mapOf("mods/a.jar" to "a", "config/a" to "b"), "plain.zip"))
        assertEquals(Modpack.Format.Plain, p.format); assertEquals(2, p.overrides.size)
        assertFailsWith<PackRejected> { Modpacks.plan(p) }
    }
    @Test fun rejectsTraversalAndWindowsPathsBeforeExtracting() {
        for (p in listOf("../escape", "/escape", "a/../../escape", "C:/escape", "a\\escape", "a/./b")) assertFails { Zip.entries(zip(mapOf(p to "bad"))) }
        assertFalse(File(root.parentFile, "escape").exists())
    }
    @Test fun rejectsTraversalInManifest() {
        assertFails { Modpacks.inspect(zip(mapOf("modrinth.index.json" to index(files = """[{"path":"../x","downloads":[],"hashes":{}}]""")))) }
    }
    @Test fun rejectsInsecureURLAndMissingHashes() {
        for (f in listOf("""{"path":"mods/a","hashes":{"sha1":"${"a".repeat(40)}"},"downloads":["http://a.test/x"]}""", """{"path":"mods/a","downloads":["https://a.test/x"]}""")) assertFails { Modpacks.inspect(zip(mapOf("modrinth.index.json" to index(files = "[$f]")))) }
    }
    @Test fun rejectsBedrockWorldAndModArchives() {
        for (marker in listOf("level.dat", "fabric.mod.json", "META-INF/neoforge.mods.toml")) assertFails { Modpacks.inspect(zip(mapOf(marker to "{}"))) }
    }
    @Test fun truncatedArchiveFails() {
        val a = zip(mapOf("config/a" to "data")); a.writeBytes(a.readBytes().copyOf(20))
        assertFails { Zip.entries(a) }
    }
    @Test fun forgedSizeFailsBeforeAllocation() {
        val a = zip(mapOf("a" to "data")); val bytes = a.readBytes()
        val pos = bytes.indices.first { it + 4 < bytes.size && bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        for (i in 0..3) bytes[pos + 24 + i] = 0x7f
        a.writeBytes(bytes); assertFails { Zip.read(a, "a") }
    }
    @Test fun zipRoundTripIncludesEmptyFolders() {
        val source = File(root, "source").apply { mkdir() }; File(source, "empty").mkdir(); File(source, "a").writeText("hello")
        val a = File(root, "out.zip"); Zip.write(source, a)
        val dest = File(root, "dest"); Zip.extract(a, dest)
        assertEquals("hello", File(dest, "a").readText()); assertTrue(File(dest, "empty").isDirectory)
    }
    @Test fun extractionDoesNotFollowSymlinksOrOverwrite() {
        val out = File(root, "out").apply { mkdir() }; val outside = File(root, "outside").apply { mkdir() }
        Files.createSymbolicLink(File(out, "config").toPath(), outside.toPath())
        assertFails { Zip.extract(zip(mapOf("config/x" to "bad")), out) }; assertFalse(File(outside, "x").exists())
        val a = zip(mapOf("a" to "new")); File(out, "a").writeText("old"); assertFails { Zip.extract(a, out) }; assertEquals("old", File(out, "a").readText())
    }
    @Test fun stagedInstallCopiesOverridesAndCommitsOnlyWhenReady() {
        val a = zip(mapOf("modrinth.index.json" to index(), "overrides/config/a" to "ok"))
        val p = Modpacks.plan(Modpacks.inspect(a)); val stage = p.stage(root)
        p.copyOverrides(a, stage); assertEquals("ok", File(stage.directory, "config/a").readText())
        assertFails { stage.commit(File(root, "new")) }
        File(stage.directory, "cofre.json").writeText("{}"); stage.commit(File(root, "new"))
        assertFalse(File(root, "new/.cofre-import-in-progress").exists())
    }
    @Test fun abandonedStagingRemovesOnlyOwnedMarkedFolders() {
        val p = Modpacks.plan(Modpacks.inspect(zip(mapOf("modrinth.index.json" to index())))); val stage = p.stage(root)
        val keep = File(root, ".cofre-import-unmarked").apply { mkdir() }
        PackStage.cleanupAbandoned(root); assertFalse(stage.directory.exists()); assertTrue(keep.exists())
    }
    @Test fun rollbackPreservesExistingInstances() {
        val keep = File(root, "existing").apply { mkdir() }; File(keep, "a").writeText("old")
        val stage = Modpacks.plan(Modpacks.inspect(zip(mapOf("modrinth.index.json" to index())))).stage(root)
        stage.rollback(); assertFalse(stage.directory.exists()); assertEquals("old", File(keep, "a").readText())
    }
    @Test fun manyOverridesStreamInOnePass() {
        val files = (0 until 5000).associate { "overrides/config/f$it.txt" to "value $it" } + ("modrinth.index.json" to index())
        val archive = zip(files)
        val plan = Modpacks.plan(Modpacks.inspect(archive)); val stage = plan.stage(File(root, "instances").apply { mkdirs() })
        val started = System.nanoTime()
        plan.copyOverrides(archive, stage)
        assertTrue(System.nanoTime() - started < 10_000_000_000L, "copying 5000 overrides took too long")
        assertEquals("value 4999", File(stage.directory, "config/f4999.txt").readText())
        assertEquals(5000, File(stage.directory, "config").list()!!.size) // no temp files left behind
    }
    @Test fun rollbackAndCleanupNeverFollowLinks() {
        val outside = File(root, "outside").apply { mkdirs(); File(this, "keep").writeText("keep") }
        val instances = File(root, "instances").apply { mkdirs() }
        val plan = Modpacks.plan(Modpacks.inspect(zip(mapOf("modrinth.index.json" to index()))))
        val a = plan.stage(instances); Files.createSymbolicLink(File(a.directory, "link").toPath(), outside.toPath()); a.rollback()
        val b = plan.stage(instances); Files.createSymbolicLink(File(b.directory, "link").toPath(), outside.toPath()); PackStage.cleanupAbandoned(instances)
        assertFalse(a.directory.exists()); assertFalse(b.directory.exists()); assertTrue(File(outside, "keep").isFile)
    }
}
