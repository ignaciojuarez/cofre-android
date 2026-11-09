package com.ignacio.cofre.ui.mods

import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Installed on real files: toggle, remove with a dependents warning, undo. Modrinth is a fake that knows nothing. */
class InstalledModelTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var instance: File

    @Before fun setUp() {
        server.start()
        repeat(4) { server.enqueue(MockResponse(body = "{}")) }
        instance = temp.newFolder("inst")
        jar("lib.jar", """{"schemaVersion":1,"id":"lib","name":"Lib","version":"1.0"}""")
        jar("app.jar", """{"schemaVersion":1,"id":"app","name":"App","version":"2.0","depends":{"lib":"*"}}""")
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun jar(name: String, fabric: String) {
        val file = File(instance, "mods/$name").apply { parentFile?.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write(fabric.toByteArray())
            zip.closeEntry()
        }
    }

    private fun model() = InstalledModel(
        scope, instance, Modrinth(OkHttpClient(), server.url("/v2/")), GlScanCache(File(temp.root, "gl.json")), Dispatchers.IO,
    )

    private suspend fun InstalledModel.settle(check: (InstalledState) -> Boolean) = withTimeout(5_000) { state.first(check) }

    @Test fun togglesAJarOffAndOn() = runBlocking {
        val installed = model()
        val loaded = installed.settle { !it.loading && it.rows.size == 2 }
        val lib = loaded.rows.first { it.item.name == "Lib" }
        installed.toggle(lib)
        assertFalse(installed.state.value.rows.first { it.item.name == "Lib" }.item.enabled, "the switch answers at once")
        withTimeout(5_000) { while (!File(instance, "mods/lib.jar.disabled").isFile) delay(20) }
        assertFalse(File(instance, "mods/lib.jar").exists())
    }

    @Test fun removingALibraryWarnsAboutWhoNeedsIt() = runBlocking {
        val installed = model()
        val lib = installed.settle { !it.loading && it.rows.size == 2 }.rows.first { it.item.name == "Lib" }
        installed.remove(lib)
        val prompt = assertNotNull(installed.settle { it.removal != null }.removal)
        assertEquals(listOf("App"), prompt.dependents)
        installed.cancelRemoval()
        assertNull(installed.state.value.removal)
        assertTrue(File(instance, "mods/lib.jar").isFile, "nothing removed until confirmed")
    }

    @Test fun folderPacksListInsteadOfCrashing() = runBlocking {
        File(instance, "resourcepacks/Folder").mkdirs()
        File(instance, "resourcepacks/Folder/pack.mcmeta").writeText("""{"pack":{"pack_format":80,"description":"A folder pack"}}""")
        val installed = model()
        installed.settle { !it.loading }
        installed.select(InstalledTab.Packs)
        val packs = installed.settle { !it.loading && it.tab == InstalledTab.Packs }
        assertEquals(listOf("Folder"), packs.rows.map { it.item.id })
        assertNull(packs.message)
    }

    @Test fun removalCanBeUndone() = runBlocking {
        val installed = model()
        val app = installed.settle { !it.loading && it.rows.size == 2 }.rows.first { it.item.name == "App" }
        // Nothing needs App and Lib was installed by hand, so it goes at once, with undo.
        installed.remove(app)
        val removed = installed.settle { it.undo != null && it.rows.size == 1 }
        assertEquals("Removed App", removed.undo?.text)
        assertFalse(File(instance, "mods/app.jar").exists())
        installed.undo()
        installed.settle { it.rows.size == 2 }
        assertTrue(File(instance, "mods/app.jar").isFile)
    }
}
