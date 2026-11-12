package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.downloads.DownloadProgress
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.*
import mockwebserver3.*
import okio.Buffer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class InstallerTest {
    @TempDir lateinit var root: File
    private fun candidate(server: MockWebServer, id: String, bytes: ByteArray = modJar(id), version: String = "1", deps: List<ModCandidate.Dependency> = emptyList()): ModCandidate = ModCandidate(id, "$id-$version", id, version, listOf("26.2"), listOf(Loader.NeoForge), dependencies = deps, filename = "$id-$version.jar", url = server.url("/$id.jar").toString(), size = bytes.size.toLong(), sha1 = Hashes.sha1(bytes), sha512 = Hashes.sha512(bytes))
    private fun response(bytes: ByteArray): MockResponse = MockResponse.Builder().body(Buffer().write(bytes)).build()
    private fun mods(): File = File(root, "mods").also { it.mkdirs() }
    @Test fun downloadsAllBeforeCommittingAndWritesInstanceIndex() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val aJar = modJar("a", "[[dependencies.a]]\nmodId='lib'\nrequired=true"); val libJar = modJar("lib")
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = response(if (request.url.encodedPath == "/a.jar") aJar else libJar) }
            val lib = candidate(server, "lib", libJar); val a = candidate(server, "a", aJar)
            val seen = mutableListOf<DownloadProgress>()
            val installed = ModInstaller(root).install(ModInstallPlan(a, required = listOf(lib)), onProgress = { seen += it })
            assertEquals(setOf("a", "lib"), installed.map { it.projectId }.toSet())
            val last = seen.last(); assertEquals(2, last.files); assertEquals(2, last.totalFiles); assertEquals(aJar.size.toLong() + libJar.size, last.bytes); assertEquals(last.bytes, last.totalBytes)
            val index = ModIndex(root).read(); assertTrue(index.getValue("a-1.jar").installedDirectly); assertFalse(index.getValue("lib-1.jar").installedDirectly)
            assertTrue(File(root, "cofre-mods.json").isFile); assertTrue(File(root, ".cofre-mods-staging").listFiles()!!.isEmpty())
        }
    }
    @Test fun packsDownloadIntoTheirFolderWithProgress() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val bytes = "pack".toByteArray(); server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = response(bytes) }
            val pack = candidate(server, "faithful", bytes).copy(filename = "Faithful.zip")
            var last: DownloadProgress? = null
            ModInstaller(root).install(pack, ContentKind.ResourcePacks) { last = it }
            assertContentEquals(bytes, File(root, "resourcepacks/Faithful.zip").readBytes()); assertEquals(1, last?.files); assertEquals(bytes.size.toLong(), last?.bytes)
            ModInstaller(root).install(pack, ContentKind.DataPacks, world = "New World")
            assertTrue(File(root, "saves/New World/datapacks/Faithful.zip").isFile); assertTrue(File(root, ".cofre-mods-staging").listFiles()!!.isEmpty())
            assertFails { ModInstaller(root).install(pack, ContentKind.DataPacks, world = "../x") }; assertFails { ModInstaller(root).install(pack, ContentKind.DataPacks) }
            assertFails { ModInstaller(root).install(pack.copy(filename = "../evil.zip"), ContentKind.ResourcePacks) }
        }
    }
    @Test fun failedDependencyInstallsNothing() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val bytes = modJar("a")
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = if (request.url.encodedPath == "/a.jar") response(bytes) else MockResponse(code = 500) }
            val original = File(mods(), "untouched.jar").also { it.writeBytes(modJar("untouched")) }.readBytes()
            assertFailsWith<ModInstallFailure.Download> { ModInstaller(root).install(ModInstallPlan(candidate(server, "a", bytes), required = listOf(candidate(server, "lib")))) }
            assertEquals(listOf("untouched.jar"), mods().listFiles()!!.map { it.name }); assertContentEquals(original, File(mods(), "untouched.jar").readBytes())
            assertFalse(File(root, "cofre-mods.json").exists())
        }
    }
    @Test fun lateIndexFailureRollsBackEveryMoveAndPreservesIndex() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val old = modJar("a"); File(mods(), "a-1.jar").writeBytes(old)
            val index = ModIndex(root); val originalIndex = mapOf("a-1.jar" to ModIndex.Entry("a", "a-1", sha1 = Hashes.sha1(old))); index.write(originalIndex)
            // The index becomes a link: reads follow it, the late write refuses it.
            val real = File(root, "index-target.json"); assertTrue(index.file.renameTo(real)); java.nio.file.Files.createSymbolicLink(index.file.toPath(), real.toPath())
            val newJar = modJar("a", "# different version"); val libJar = modJar("lib")
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = response(if (request.url.encodedPath == "/a.jar") newJar else libJar) }
            assertFailsWith<IllegalArgumentException> { ModInstaller(root).install(ModInstallPlan(candidate(server, "a", newJar, "2"), required = listOf(candidate(server, "lib", libJar)))) }
            assertEquals(listOf("a-1.jar"), mods().listFiles()!!.map { it.name }); assertContentEquals(old, File(mods(), "a-1.jar").readBytes()); assertEquals(originalIndex, index.read())
        }
    }
    @Test fun disabledDependencyTurnsOnAndOldVersionStepsAside() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val lib = modJar("lib"); File(mods(), "lib.jar.disabled").writeBytes(lib)
            File(mods(), "a-0.jar").writeBytes(modJar("a")); val index = ModIndex(root)
            index.write(mapOf("a-0.jar" to ModIndex.Entry("a", "a-0"), "lib.jar.disabled" to ModIndex.Entry("lib", "lib-1", installedDirectly = false)))
            val bytes = modJar("a", "[[dependencies.a]]\nmodId='lib'\nrequired=true"); server.enqueue(response(bytes))
            val plan = ModInstallPlan(candidate(server, "a", bytes), turnsOn = listOf(index.installed().first { it.projectId == "lib" }))
            ModInstaller(root).install(plan)
            assertTrue(File(mods(), "lib.jar").isFile); assertFalse(File(mods(), "lib.jar.disabled").exists()); assertFalse(File(mods(), "a-0.jar").exists())
            assertTrue("lib.jar" in index.read()); assertFalse("lib.jar.disabled" in index.read())
        }
    }
    @Test fun concurrentInstallsReuseSharedDependencyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = response(modJar(request.url.encodedPath.removePrefix("/").removeSuffix(".jar"))) }
            val lib = candidate(server, "lib")
            coroutineScope { listOf("a", "b").map { id -> async { ModInstaller(root).install(ModInstallPlan(candidate(server, id), required = listOf(lib))) } }.awaitAll() }
            assertEquals(3, server.requestCount); assertEquals(3, ModIndex(root).installed().size)
        }
    }
    @Test fun aGapTheFolderAlreadyHadDoesNotBlockAnUnrelatedInstall() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val bBytes = modJar("b")
            File(mods(), "a-1.jar").writeBytes(modJar("a", "[[dependencies.a]]\nmodId='nope'\nrequired=true"))
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = if (request.url.encodedPath == "/b.jar") response(bBytes) else MockResponse(code = 404) }
            val installed = ModInstaller(root, modrinth = Modrinth(baseUrl = server.url("/v2/"))).install(ModInstallPlan(candidate(server, "b", bBytes)))
            assertTrue(installed.any { it.projectId == "b" }); assertTrue(File(root, "mods/b-1.jar").isFile)
            // A new mod's own unmet requirement still blocks it.
            val cBytes = modJar("c", "[[dependencies.c]]\nmodId='alsonope'\nrequired=true")
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = if (request.url.encodedPath == "/c.jar") response(cBytes) else MockResponse(code = 404) }
            assertFailsWith<ModInstallFailure.MissingRequirement> { ModInstaller(root, modrinth = Modrinth(baseUrl = server.url("/v2/"))).install(ModInstallPlan(candidate(server, "c", cBytes))) }
            assertFalse(File(root, "mods/c-1.jar").exists())
        }
    }
    @Test fun jarDeclaredDependencyMissingFromCatalogIsStagedToo() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val aBytes = modJar("a", "[[dependencies.a]]\nmodId='architectury'\nrequired=true"); val libBytes = modJar("architectury")
            val version = Modrinth.Version("lib-v1", "lib-project", "Architectury", "1", listOf("26.2"), listOf("neoforge"), files = listOf(Modrinth.File(mapOf("sha1" to Hashes.sha1(libBytes)), server.url("/lib.jar").toString(), "architectury.jar", libBytes.size.toLong(), true)))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/a.jar" -> response(aBytes)
                    "/lib.jar" -> response(libBytes)
                    "/v2/project/architectury-api", "/v2/project/lib-project" -> MockResponse(body = """{"id":"lib-project","title":"Architectury","project_type":"mod"}""")
                    "/v2/project/lib-project/version" -> MockResponse(body = Kit.json.encodeToString(listOf(version)))
                    else -> MockResponse(code = 404)
                }
            }
            val installed = ModInstaller(root, modrinth = Modrinth(baseUrl = server.url("/v2/"))).install(ModInstallPlan(candidate(server, "a", aBytes)))
            assertEquals(setOf("a", "lib-project"), installed.map { it.projectId }.toSet()); assertFalse(ModIndex(root).read().getValue("architectury.jar").installedDirectly)
        }
    }
    @Test fun missingModsComeFromTheRequestingModsModrinthDependencies() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // The mod id (yet_another_config_lib_v3) is not a Modrinth slug; Controlify's version names the project.
            val ctrlBytes = modJar("controlify", "[[dependencies.controlify]]\nmodId='yet_another_config_lib_v3'\ntype='required'"); val yaclBytes = modJar("yet_another_config_lib_v3")
            File(mods(), "controlify.jar").writeBytes(ctrlBytes)
            ModIndex(root).write(mapOf("controlify.jar" to ModIndex.Entry("ctrl", "ctrl-1", sha1 = Hashes.sha1(ctrlBytes))))
            fun file(bytes: ByteArray, name: String) = Modrinth.File(mapOf("sha1" to Hashes.sha1(bytes)), server.url("/$name").toString(), name, bytes.size.toLong(), true)
            val ctrl = Modrinth.Version("ctrl-1", "ctrl", "Controlify", "1", listOf("26.2"), listOf("neoforge"), dependencies = listOf(Modrinth.Dependency(projectId = "yacl-id", dependencyType = "required")), files = listOf(file(ctrlBytes, "controlify.jar")))
            val yacl = Modrinth.Version("yacl-1", "yacl-id", "YACL", "1", listOf("26.2"), listOf("neoforge"), files = listOf(file(yaclBytes, "yacl.jar")))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/yacl.jar" -> response(yaclBytes)
                    "/v2/project/ctrl" -> MockResponse(body = """{"id":"ctrl","title":"Controlify","project_type":"mod"}""")
                    "/v2/project/yacl-id" -> MockResponse(body = """{"id":"yacl-id","title":"YACL","project_type":"mod"}""")
                    "/v2/project/ctrl/version" -> MockResponse(body = Kit.json.encodeToString(listOf(ctrl)))
                    "/v2/project/yacl-id/version" -> MockResponse(body = Kit.json.encodeToString(listOf(yacl)))
                    else -> MockResponse(code = 404)
                }
            }
            val missing = ModRequirements.missing(root)
            assertEquals(listOf("yet_another_config_lib_v3"), missing.map { it.modId }); assertEquals("controlify.jar", missing.single().neededBy)
            val installed = ModInstaller(root, modrinth = Modrinth(baseUrl = server.url("/v2/"))).installMissing(missing)
            assertEquals(setOf("ctrl", "yacl-id"), installed.map { it.projectId }.toSet()); assertTrue(ModRequirements.missing(root).isEmpty())
            assertTrue(File(mods(), "controlify.jar").isFile); assertFalse(ModIndex(root).read().getValue("yacl.jar").installedDirectly)
            // The crash names the requester by mod id, not file: nothing left to get.
            assertEquals(installed.toSet(), ModInstaller(root, modrinth = Modrinth(baseUrl = server.url("/v2/"))).installMissing(listOf(ModRequirements.requirement("yet_another_config_lib_v3", "dependency", "controlify"))).toSet())
        }
    }
    @Test fun aJarWithTheNewJarsModIdIsTheOldCopy() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val bytes = modJar("alpha", "# v2")
            // Dropped in by hand under another name and title; a jar that only bundles alpha stays.
            File(mods(), "manual-download.jar.disabled").writeBytes(modJar("alpha"))
            File(mods(), "bundler.jar").writeBytes(jarBytes(mapOf("META-INF/neoforge.mods.toml" to "[[mods]]\nmodId='bundler'".toByteArray(), "META-INF/jarjar/alpha.jar" to modJar("alpha"))))
            server.enqueue(response(bytes))
            ModInstaller(root).install(ModInstallPlan(candidate(server, "P123", bytes).copy(title = "Alpha Mod")))
            assertEquals(setOf("P123-1.jar", "bundler.jar"), mods().list()!!.toSet())
        }
    }
    @Test fun getMissingModsNeverBringsBackAModARuleCallsBroken() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = MockResponse(body = "{}") }
            val addon = modJar("addon", "[[dependencies.addon]]\nmodId='iris'\ntype='required'")
            File(mods(), "iris.jar.disabled").writeBytes(modJar("iris")); File(mods(), "addon.jar").writeBytes(addon)
            val modrinth = Modrinth(baseUrl = server.url("/v2/"))
            ModInstaller(root, modrinth = modrinth).installMissing(ModRequirements.missing(root))
            assertTrue(File(mods(), "iris.jar.disabled").isFile); assertEquals(listOf("iris"), ModRequirements.missing(root).map { it.modId }, "the gap stays reported")
            // Installing another mod that needs it doesn't turn it on either.
            File(mods(), "addon.jar").delete(); server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = response(addon) }
            ModInstaller(root, modrinth = modrinth).install(ModInstallPlan(candidate(server, "addon", addon)))
            assertTrue(File(mods(), "addon-1.jar").isFile); assertTrue(File(mods(), "iris.jar.disabled").isFile)
            assertEquals(2, server.requestCount, "the hash lookup and the addon: Iris is never looked up")
        }
    }
    @Test fun recoverPutsBackWhatAKilledInstallMovedAside() {
        File(mods(), "b-2.jar").writeBytes(modJar("b", "# v2")); File(mods(), "c.jar.disabled").writeBytes(modJar("c"))
        val backup = File(root, ".cofre-mods-staging/one/backup").also { it.mkdirs() }
        File(backup, "0-a-1.jar").writeBytes(modJar("a")) // free: goes back
        File(backup, "1-b-1.jar").writeBytes(modJar("b")) // b-2 replaced it: dropped
        File(backup, "2-c.jar").writeBytes(modJar("c", "# other")) // its name is taken (off): dropped
        File(root, ".cofre-mods-staging/two/downloads").mkdirs(); File(root, ".cofre-mods-staging/two/downloads/x.jar").writeBytes(modJar("x"))
        ModInstaller.recover(root)
        assertEquals(setOf("a-1.jar", "b-2.jar", "c.jar.disabled"), mods().list()!!.toSet()); assertTrue(File(root, ".cofre-mods-staging").list()!!.isEmpty())
        assertContentEquals(modJar("a"), File(mods(), "a-1.jar").readBytes())
    }
    @Test fun unknownEnabledAndDisabledJarsIdentifyBySha1BulkLookup() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val bytes = modJar("a"); File(mods(), "a.jar").writeBytes(bytes); File(mods(), "b.jar.disabled").writeBytes(bytes)
            val hash = Hashes.sha1(bytes); val version = Modrinth.Version("v", "project", versionType = "beta")
            server.enqueue(MockResponse(body = Kit.json.encodeToString(mapOf(hash to version))))
            val index = ModIndex(root); val entries = index.identifyUnknown(Modrinth(baseUrl = server.url("/v2/")))
            assertEquals(2, entries.size); assertTrue(entries.values.all { it.projectId == "project" && it.channel == VersionChannel.Beta })
            val request = server.takeRequest(); assertEquals("/v2/version_files", request.url.encodedPath); assertTrue(request.body!!.utf8().contains(hash))
            index.identifyUnknown(Modrinth(baseUrl = server.url("/v2/"))); assertEquals(1, server.requestCount)
        }
    }
    @Test fun aTurnedOffModKeepsItsIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val bytes = modJar("a"); val hash = Hashes.sha1(bytes)
            File(mods(), "a.jar").writeBytes(bytes); val index = ModIndex(root)
            index.write(mapOf("a.jar" to ModIndex.Entry("123", "456", source = "curseforge", sha1 = hash)))
            File(mods(), "a.jar").renameTo(File(mods(), "a.jar.disabled")) // ContentStore.setEnabled(false)
            server.enqueue(MockResponse(body = "{}")) // Modrinth doesn't know a CurseForge jar
            index.identifyUnknown(Modrinth(baseUrl = server.url("/v2/")))
            assertEquals("123", index.read()["a.jar"]?.projectId); assertEquals("123", index.installed().single().projectId)
        }
    }
    @Test fun filenamesCannotEscapeTheInstance() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (name in listOf("../escape.jar", "bad/path.jar", "bad\\path.jar", "a:drive.jar")) {
                assertFailsWith<IllegalArgumentException> { ModInstaller(root).install(ModInstallPlan(candidate(server, "a").copy(filename = name))) }
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun indexRejectsCorruptOrUnsafeEntries() {
        File(root, "cofre-mods.json").writeText("not-json")
        assertFails { ModIndex(root).read() }
        assertFailsWith<IllegalArgumentException> { ModIndex(root).write(mapOf("../a.jar" to ModIndex.Entry("a", "v"))) }
    }
}
