package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.runBlocking
import mockwebserver3.*
import okio.Buffer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class UpdatesTest {
    @TempDir lateinit var root: File
    private val mods: File get() = File(root, "mods").also { it.mkdirs() }
    private val newJars = mutableMapOf<String, ByteArray>()

    /** Writes [file] as the old build of [id] and returns its sha1. */
    private fun jar(file: String, id: String, entry: ModIndex.Entry? = ModIndex.Entry(id, "$id-1")): String {
        val bytes = modJar(id); File(mods, file).writeBytes(bytes); val hash = Hashes.sha1(bytes)
        entry?.let { ModIndex(root).write(ModIndex(root).read() + (ModIndex.key(file) to it.copy(sha1 = hash))) }
        return hash
    }
    private fun version(server: MockWebServer, id: String, type: String = "release", sameAs: String? = null): Modrinth.Version {
        val bytes = modJar(id, "# v2"); newJars["/$id-2.jar"] = bytes
        val hash = sameAs ?: Hashes.sha1(bytes)
        return Modrinth.Version("$id-2", id, id, "2", listOf("26.2"), listOf("neoforge"), type, files = listOf(Modrinth.File(mapOf("sha1" to hash), server.url("/$id-2.jar").toString(), "$id-2.jar", bytes.size.toLong(), true)))
    }
    private fun serve(server: MockWebServer, latest: Map<String, Modrinth.Version>, failing: Set<String> = emptySet()) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/v2/version_files/update") return MockResponse(body = Kit.json.encodeToString(latest))
                if (path in failing) return MockResponse(code = 500)
                return newJars[path]?.let { MockResponse.Builder().body(Buffer().write(it)).build() } ?: MockResponse(code = 404)
            }
        }
    }

    @Test fun pinsRoundTripAndOldIndexesDecode() = runBlocking {
        File(root, "cofre-mods.json").writeText("""{"a.jar":{"projectId":"a","versionId":"v"}}""")
        val index = ModIndex(root); assertFalse(index.read().getValue("a.jar").pinned)
        assertTrue(index.pin("a.jar.disabled")); assertTrue(index.read().getValue("a.jar").pinned)
        assertTrue(index.pin("a.jar", false)); assertFalse(index.read().getValue("a.jar").pinned)
        assertFalse(index.pin("unknown.jar"))
    }

    @Test fun checkSkipsPinnedUnknownSameAndBeta() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val a = jar("a-1.jar", "a"); val b = jar("b-1.jar.disabled", "b"); val c = jar("c-1.jar", "c", ModIndex.Entry("c", "c-1", pinned = true))
            jar("d.jar", "d", entry = null); val e = jar("e-1.jar", "e"); val f = jar("f-1.jar", "f"); val g = jar("g.jar", "g", entry = null)
            serve(server, mapOf(a to version(server, "a"), b to version(server, "b"), c to version(server, "c"), e to version(server, "e", sameAs = e),
                f to version(server, "f", "beta"), g to version(server, "g")))
            val client = Modrinth(baseUrl = server.url("/v2/"))
            val updates = ModUpdates.check(root, client)
            assertEquals(listOf("a-1.jar", "b-1.jar.disabled", "g.jar"), updates.map { it.file })
            assertEquals("a-1", updates.first().currentVersionId); assertEquals("a-2", updates.first().target.versionId); assertNull(updates.last().currentVersionId)
            assertFalse(server.takeRequest().body!!.utf8().contains(c))
            assertEquals(listOf("a-1.jar", "b-1.jar.disabled", "f-1.jar", "g.jar"), ModUpdates.check(root, client, includeBeta = true).map { it.file })
        }
    }

    @Test fun applyKeepsTurnedOffJarsOffAndSnapshotsFirst() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val a = jar("a-1.jar", "a"); val b = jar("b-1.jar.disabled", "b"); val g = jar("g.jar", "g", entry = null)
            serve(server, mapOf(a to version(server, "a"), b to version(server, "b"), g to version(server, "g")))
            val updates = ModUpdates.check(root, Modrinth(baseUrl = server.url("/v2/")))
            val result = ModUpdates.apply(root, updates)
            assertEquals(3, result.updated.size); assertTrue(result.failed.isEmpty())
            assertEquals(listOf("a-2.jar", "b-2.jar.disabled", "g-2.jar"), mods.list()!!.sorted())
            val index = ModIndex(root).read(); assertEquals("b-2", index.getValue("b-2.jar").versionId); assertEquals("g", index.getValue("g-2.jar").projectId)
            val snapshot = Snapshots.list(root).single(); assertEquals(Snapshots.Reason.BeforeUpdate, snapshot.reason); assertEquals(snapshot, result.snapshot)
            assertEquals(3, Snapshots.changedSince(root, snapshot).changed.size)
        }
    }

    @Test fun aFailedDownloadLeavesThatModAndKeepsTheRest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val a = jar("a-1.jar", "a"); val b = jar("b-1.jar.disabled", "b")
            serve(server, mapOf(a to version(server, "a"), b to version(server, "b")), failing = setOf("/b-2.jar"))
            val result = ModUpdates.apply(root, ModUpdates.check(root, Modrinth(baseUrl = server.url("/v2/"))))
            assertEquals(listOf("a-1.jar"), result.updated.map { it.file }); assertEquals("b-1.jar.disabled", result.failed.single().update.file)
            assertEquals(listOf("a-2.jar", "b-1.jar.disabled"), mods.list()!!.sorted()); assertEquals("b-1", ModIndex(root).read().getValue("b-1.jar").versionId)
        }
    }

    @Test fun aLateFailureRestoresTheSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val a = jar("a-1.jar", "a"); val b = jar("b-1.jar.disabled", "b"); val before = File(root, "cofre-mods.json").readText()
            File(mods, "b-2.jar.disabled").mkdirs() // turning the new b off collides after the installer committed it
            serve(server, mapOf(a to version(server, "a"), b to version(server, "b")))
            val result = ModUpdates.apply(root, ModUpdates.check(root, Modrinth(baseUrl = server.url("/v2/"))))
            assertTrue(result.updated.isEmpty()); assertEquals(2, result.failed.size)
            assertEquals(listOf("a-1.jar", "b-1.jar.disabled", "b-2.jar.disabled"), mods.list()!!.sorted()); assertTrue(File(mods, "b-2.jar.disabled").isDirectory)
            assertEquals(before, File(root, "cofre-mods.json").readText()); assertContentEquals(modJar("a"), File(mods, "a-1.jar").readBytes())
        }
    }

    @Test fun nothingUpdatedLeavesNoSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val a = jar("a-1.jar", "a")
            serve(server, mapOf(a to version(server, "a")), failing = setOf("/a-2.jar"))
            val result = ModUpdates.apply(root, ModUpdates.check(root, Modrinth(baseUrl = server.url("/v2/"))))
            assertNull(result.snapshot); assertTrue(Snapshots.list(root).isEmpty()); assertEquals(listOf("a-1.jar"), mods.list()!!.toList())
        }
    }
}
