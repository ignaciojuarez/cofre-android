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
import java.io.IOException
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
            File(root, "cofre-mods.json.tmp").mkdir()
            val newJar = modJar("a", "# different version"); val libJar = modJar("lib")
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = response(if (request.url.encodedPath == "/a.jar") newJar else libJar) }
            assertFailsWith<IOException> { ModInstaller(root).install(ModInstallPlan(candidate(server, "a", newJar, "2"), required = listOf(candidate(server, "lib", libJar)))) }
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
