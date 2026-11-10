package com.ignacio.cofre.kit.modfolders

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.modrinth.Modrinth
import com.ignacio.cofre.kit.mods.ModIndex
import com.ignacio.cofre.kit.mods.ModInstallFailure
import com.ignacio.cofre.kit.mods.VersionChannel
import com.ignacio.cofre.kit.mods.modJar
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import kotlin.test.*
import com.ignacio.cofre.kit.modfolders.ConvertStatus.*

class ConvertTest {
    @TempDir lateinit var root: File
    private val bytes = mutableMapOf<String, ByteArray>()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val failing = mutableSetOf<String>()
    private val source: File get() = File(root, "source")

    /** Jar bytes by name, made once: the fixtures' `@x`/`#x` (sha1), `&x` (sha512) and `%x` (size) point at these. */
    private fun jar(name: String): ByteArray = synchronized(bytes) { bytes.getOrPut(name) { modJar(name, "displayName=\"${name.replaceFirstChar(Char::uppercase)}\"") } }
    private fun fixture(name: String, server: MockWebServer): String? {
        val text = javaClass.getResource("/convert/$name.json")?.readText() ?: return null
        return Regex("\"([@#&%])([a-z0-9-]+)\"").replace(text.replace("{{base}}", server.url("").toString().removeSuffix("/"))) { match ->
            val data = jar(match.groupValues[2])
            when (match.groupValues[1]) { "%" -> "${data.size}"; "&" -> "\"${Hashes.sha512(data)}\""; else -> "\"${Hashes.sha1(data)}\"" }
        }
    }
    private fun serve(server: MockWebServer, scenario: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += path
                val body = when {
                    path == "/v2/version_files" -> fixture("$scenario-current", server)
                    path == "/v2/version_files/update" -> fixture("$scenario-latest", server)
                    path == "/v2/projects" -> fixture("projects", server)
                    path == "/v2/search" -> fixture("search", server)
                    path.startsWith("/v2/project/") && path.endsWith("/version") -> fixture("versions-" + path.removePrefix("/v2/project/").removeSuffix("/version"), server)
                    path.startsWith("/files/") -> {
                        val name = path.removePrefix("/files/").removeSuffix(".jar")
                        return if (name in failing) MockResponse(code = 500) else MockResponse.Builder().body(Buffer().write(jar(name))).build()
                    }
                    else -> null
                }
                return body?.let { MockResponse(body = it) } ?: MockResponse(code = 404)
            }
        }
    }
    private fun folder(vararg jars: String): File {
        val mods = File(source, "mods").apply { mkdirs() }
        for (file in jars) File(mods, file).writeBytes(jar(file.removeSuffix(".disabled").removeSuffix(".jar")))
        File(source, "resourcepacks").mkdirs(); File(source, "resourcepacks/faithful.zip").writeText("pack")
        File(source, "shaderpacks").mkdirs()
        File(source, "cofre-mods.json").writeText("{}")
        return source
    }
    private fun fabricFolder() = folder("sodium.jar", "waystones.jar", "jade.jar.disabled", "create.jar", "apotheosis.jar", "dh.jar",
        "neoloading.jar", "mystuff.jar", "ferrite.jar", "controlify.jar", "jei.jar")
    private suspend fun fabricPlan(server: MockWebServer): ConvertPlan {
        serve(server, "fabric")
        return Convert.plan(fabricFolder(), "26.2", Loader.NeoForge, ConvertTarget("26.2", Loader.Fabric), Modrinth(baseUrl = server.url("/v2/")))
    }
    private fun ConvertPlan.row(jar: String) = rows.single { it.jar == jar }
    private fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to Hashes.sha1(it) }

    @Test fun planSortsEveryModIntoAStatus() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val before = snapshot(fabricFolder())
            val plan = fabricPlan(server)
            assertEquals(Ready, plan.row("sodium.jar").status)
            assertEquals("0.9.2-fabric", plan.row("sodium.jar").target?.version)
            assertEquals("sodium", plan.row("sodium.jar").projectId)
            assertEquals("Sodium", plan.row("sodium.jar").name)
            assertFalse(plan.row("jade.jar.disabled").enabled)
            assertEquals(Ready, plan.row("jade.jar.disabled").status)
            // A newer beta fits, but a release does too: the release wins.
            assertEquals("3.5.3", plan.row("controlify.jar").target?.version)
            plan.row("ferrite.jar").let { assertEquals(BetaOnly, it.status); assertEquals("9.1.0-beta.1", it.target?.version) }
            plan.row("create.jar").let { assertEquals(Swap, it.status); assertEquals("create-fabric", it.suggestion?.projectId); assertNull(it.target) }
            plan.row("apotheosis.jar").let { assertEquals(Unavailable, it.status); assertEquals(ConvertGap.NoLoaderBuild, it.gap); assertEquals("No Fabric build", it.reason) }
            plan.row("dh.jar").let { assertEquals(Unavailable, it.status); assertEquals(ConvertGap.NotOutYet, it.gap); assertEquals("No Fabric 26.2 build yet", it.reason) }
            plan.row("jei.jar").let { assertEquals(Unavailable, it.status); assertEquals(ConvertGap.MissingDependency, it.gap); assertEquals("Needs Missing Lib", it.reason) }
            assertEquals(NotNeeded, plan.row("neoloading.jar").status)
            plan.row("mystuff.jar").let { assertEquals(Unknown, it.status); assertNull(it.projectId) }

            val added = plan.rows.filter { it.status == Added }.associateBy { it.projectId }
            assertEquals(setOf("fabric-api", "cloth-config"), added.keys)
            assertEquals("Fabric API", added.getValue("fabric-api").name)
            assertNull(added.getValue("fabric-api").jar)
            // Jade is off, so only the mods that are on (and the Create Fabric swap) count as needing Fabric API.
            assertEquals(setOf("Sodium", "Create"), added.getValue("fabric-api").neededBy.toSet())
            assertEquals("Needed by Waystones", added.getValue("cloth-config").reason)

            assertEquals(mapOf(Ready to 4, BetaOnly to 1, Swap to 1, Unavailable to 3, NotNeeded to 1, Unknown to 1, Added to 2), plan.counts)
            assertEquals(0.4f, plan.carriesOver)
            val expected = listOf("sodium-fabric", "waystones-fabric", "jade-fabric", "controlify-fabric", "fabric-api", "cloth-config").sumOf { jar(it).size.toLong() }
            assertEquals(expected, plan.downloadBytes())

            assertEquals(1, requests.count { it == "/v2/version_files" }); assertEquals(1, requests.count { it == "/v2/version_files/update" })
            assertEquals(2, requests.count { it == "/v2/projects" }); assertEquals(2, requests.count { it == "/v2/search" })
            // One per beta (is there a release?), the swap's builds, and each new dependency once; never the optional Mod Menu.
            assertEquals(setOf("ferritecore", "controlify", "create-fabric", "fabric-api", "cloth-config", "missing-lib"),
                requests.filter { it.endsWith("/version") }.map { it.removePrefix("/v2/project/").removeSuffix("/version") }.toSet())
            assertEquals(12, requests.size)
            assertEquals(before, snapshot(source))
        }
    }
    @Test fun upgradeSplitsReasonAndTakesBetasWhenAllowed() = runBlocking {
        MockWebServer().use { server ->
            server.start(); serve(server, "upgrade")
            val plan = Convert.plan(folder("sodium.jar", "ferrite.jar", "dh.jar"), "26.2", Loader.NeoForge, ConvertTarget("26.3", Loader.NeoForge, includeBeta = true), Modrinth(baseUrl = server.url("/v2/")))
            assertEquals(Ready, plan.row("ferrite.jar").status)
            plan.row("dh.jar").let { assertEquals(ConvertGap.NotOutYet, it.gap); assertEquals("No 26.3 build yet", it.reason) }
            assertEquals(jar("ferrite-beta").size.toLong(), plan.downloadBytes(), "Sodium's target is the same file")
            assertEquals(3, requests.size)

            val dest = File(root, "dest")
            Convert.apply(plan, dest)
            assertContentEquals(jar("sodium"), File(dest, "mods/sodium.jar").readBytes())
            assertTrue(File(dest, "mods/ferrite-beta.jar").isFile)
            assertEquals(1, requests.count { it.startsWith("/files/") })
        }
    }
    @Test fun applyWritesTheDefaultChoices() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val plan = fabricPlan(server)
            val before = snapshot(source)
            val dest = File(root, "dest")
            Convert.apply(plan, dest)
            assertEquals(setOf("sodium-fabric.jar", "waystones-fabric.jar", "jade-fabric.jar.disabled", "controlify-fabric.jar", "fabric-api.jar", "cloth-config.jar"),
                File(dest, "mods").list()!!.toSet())
            val index = ModIndex(dest).read()
            assertEquals(setOf("sodium-fabric.jar", "waystones-fabric.jar", "jade-fabric.jar", "controlify-fabric.jar", "fabric-api.jar", "cloth-config.jar"), index.keys)
            assertEquals("sodium-f", index.getValue("sodium-fabric.jar").versionId)
            assertFalse(index.getValue("fabric-api.jar").installedDirectly)
            assertEquals(Hashes.sha1(jar("cloth-config")), index.getValue("cloth-config.jar").sha1)
            assertEquals("pack", File(dest, "resourcepacks/faithful.zip").readText())
            assertTrue(File(dest, "shaderpacks").isDirectory)
            assertFalse(File(dest, ".cofre-convert-queue.json").exists())
            assertEquals(before, snapshot(source))
        }
    }
    @Test fun applyFollowsTheUsersChoices() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val plan = fabricPlan(server)
            val dest = File(root, "dest").apply { mkdirs() }
            Convert.apply(plan, dest, ConvertChoices(swaps = setOf("create.jar"), betas = setOf("ferrite.jar"), keepMissingOff = true))
            val files = File(dest, "mods").list()!!.toSet()
            assertTrue(setOf("create-fabric.jar", "ferrite-beta.jar", "fabric-api.jar", "apotheosis.jar.disabled", "dh.jar.disabled", "mystuff.jar.disabled", "jei.jar.disabled").all { it in files }, "$files")
            assertFalse(files.any { it.startsWith("neoloading") })
            assertContentEquals(jar("mystuff"), File(dest, "mods/mystuff.jar.disabled").readBytes())
            assertTrue("jade-fabric.jar.disabled" in files, "A mod that was off stays off")
            assertEquals(VersionChannel.Beta, ModIndex(dest).read().getValue("ferrite-beta.jar").channel)
        }
    }
    @Test fun aSwapLeftOutDropsTheDependencyOnlyItNeeded() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val plan = fabricPlan(server)
            val onlySwap = plan.copy(rows = plan.rows.filter { it.jar != "sodium.jar" })
            assertFalse(onlySwap.picks(ConvertChoices()).any { it.second.projectId == "fabric-api" })
            assertTrue(onlySwap.picks(ConvertChoices(swaps = setOf("create.jar"))).any { it.second.projectId == "fabric-api" })
        }
    }
    @Test fun failedDownloadRemovesTheDestination() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val plan = fabricPlan(server)
            val before = snapshot(source)
            failing += "cloth-config"
            val dest = File(root, "dest")
            assertFailsWith<ModInstallFailure.Download> { Convert.apply(plan, dest) }
            assertFalse(dest.exists())
            assertEquals(before, snapshot(source))
            File(dest, "taken").apply { parentFile.mkdirs(); writeText("x") }
            assertFailsWith<IllegalArgumentException> { Convert.apply(plan, dest) }
            assertTrue(File(dest, "taken").isFile, "A folder that isn't empty is never touched")
        }
    }
}
