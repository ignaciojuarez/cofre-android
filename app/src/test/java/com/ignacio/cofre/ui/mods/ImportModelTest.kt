package com.ignacio.cofre.ui.mods

import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modpacks.PackFile
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Import pack on real archives: what's inside, the typed rejection, and a staged install into a new instance. */
class ImportModelTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun pack(name: String, game: String, loader: String) = File(temp.root, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("modrinth.index.json"))
            zip.write(
                """{"formatVersion":1,"game":"minecraft","versionId":"1","name":"Handheld","files":[],
                   "dependencies":{"minecraft":"$game","$loader":"26.2.0.93"}}""".toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("overrides/config/sodium-options.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
    }

    private fun model(store: InstanceStore) =
        ImportModel(scope, store, Modrinth(), { null }, File(temp.root, "cache"), Dispatchers.IO)

    private suspend fun ImportModel.settle(check: (ImportPhase) -> Boolean) = withTimeout(5_000) { phase.first(check) }

    @Test fun aFabricPackIsRejectedWithTheReason() = runBlocking {
        val import = model(InstanceStore(temp.newFolder("instances")))
        val archive = pack("fabric.mrpack", "1.21.1", "fabric-loader")
        import.open(archive.name) { archive }
        val ready = assertIs<ImportPhase.Ready>(import.settle { it is ImportPhase.Ready || it is ImportPhase.Failed })
        assertNull(ready.plan)
        assertEquals("It's for Minecraft 1.21.1. Cofre runs 26.2 only.", ready.rejected)
    }

    @Test fun aNeoForgePackBecomesANewInstance() = runBlocking {
        val store = InstanceStore(temp.newFolder("instances"))
        val import = model(store)
        val archive = pack("handheld.mrpack", "26.2", "neoforge")
        import.open(archive.name) { archive }
        val ready = assertIs<ImportPhase.Ready>(import.settle { it is ImportPhase.Ready || it is ImportPhase.Failed })
        assertNotNull(ready.plan)
        assertEquals(1, ready.pack.overrides.size)
        import.install()
        val done = assertIs<ImportPhase.Done>(import.settle { it is ImportPhase.Done || it is ImportPhase.Failed })
        assertEquals("Handheld", done.instance.name)
        assertEquals("26.2.0.93", done.instance.loaderVersion)
        val dir = store.directory(done.instance)
        assertTrue(File(dir, "config/sodium-options.json").isFile)
        assertEquals(listOf(done.instance.id), store.list().map { it.id }, "no stage left behind")
    }

    @Test fun curseForgeFilesGetAPlainNameAndTheirSha1() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            fun file(name: String) = """{"data":{"id":34,"modId":12,"fileName":"$name","fileLength":3,"downloadUrl":"https://edge.forgecdn.net/x.jar","hashes":[{"value":"${"a".repeat(32)}","algo":2},{"value":"${"b".repeat(40)}","algo":1}]}}"""
            server.enqueue(MockResponse(body = file("../evil.jar")))
            server.enqueue(MockResponse(body = file("sodium.jar")))
            val import = ImportModel(scope, InstanceStore(temp.newFolder("instances")), Modrinth(), { CurseForge("key", baseUrl = server.url("/")) }, File(temp.root, "cache"), Dispatchers.IO)
            val stage = temp.newFolder("stage")
            val pack = PackFile("mods/curseforge-12-34.jar", projectID = 12, fileID = 34)
            assertFailsWith<IllegalArgumentException> { import.download(pack, stage) }
            val item = import.download(pack, stage)
            assertEquals(File(stage, "mods/sodium.jar").path, item.destination)
            assertEquals("b".repeat(40), item.sha1)
        }
    }
}
