package com.ignacio.cofre.ui.folders

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.ignacio.cofre.Samples
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.common.Zip
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.modfolders.ModFolderMigration
import com.ignacio.cofre.kit.modpacks.Modpacks
import com.ignacio.cofre.kit.modrinth.Modrinth
import com.ignacio.cofre.kit.mods.ModIndex
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Mod folders overview on real files: create from an instance, delete a folder in use, export. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FoldersModelTest {
    @get:Rule val rule = createComposeRule()

    private fun graph() = Samples.graph().also { graph ->
        ModFolderMigration.run(graph.instances, graph.modFolders)
        graph.library.refresh()
        rule.waitUntil(5_000) { graph.library.instances.any { it.id == "survival" && it.modFolder != null } }
    }

    @Test fun copyAnInstanceThenDeleteItsOldFolderMovingItsUsers() {
        val graph = graph()
        val model = FoldersModel(graph, graph.scope)
        model.reload()
        rule.waitUntil(5_000) { model.state.value.cards.size == 4 }
        val survival = graph.library.instances.first { it.id == "survival" }
        val old = requireNotNull(survival.modFolder)

        model.create(
            NewFolderRequest("Survival copy", FolderStart.Instance, "survival", Target.GAME_VERSION, Target.LOADER, null, AutoUpdate.Auto, essentials = false, linkTo = null),
        )
        rule.waitUntil(5_000) { model.state.value.cards.size == 5 }
        val copy = model.state.value.cards.first { it.folder.name == "Survival copy" }
        assertEquals(3, copy.mods, "the instance's jars came along")
        assertEquals(AutoUpdate.Auto, copy.folder.autoUpdate)
        assertEquals(copy.id, model.state.value.selected)

        model.delete(old, moveTo = copy.id)
        rule.waitUntil(5_000) { model.state.value.deleted.any { it.folder.id == old } }
        assertEquals(copy.id, graph.library.instances.first { it.id == "survival" }.modFolder, "its user moved to the copy")
        assertTrue(model.state.value.cards.none { it.id == old })
        assertEquals(old, model.state.value.undo?.folder?.id)
    }

    @Test fun exportPutsJarsModrinthDoesntKnowInOverrides() = runBlocking {
        val graph = graph()
        val folder = requireNotNull(graph.folderOf(graph.library.instances.first { it.id == "survival" }))
        val dir = graph.modFolders.directory(folder)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "{}"))
            val out = File(graph.context.cacheDir, "out/test.mrpack")
            FolderExport.write(dir, folder, "26.2.0.93", Modrinth(OkHttpClient(), server.url("/v2/")), out)
            val entries = Zip.entries(out).map { it.path }
            assertTrue("modrinth.index.json" in entries)
            assertEquals(ModIndex(dir).jars().size, entries.count { it.startsWith("overrides/mods/") })
            // Cofre's own importer reads it back.
            val pack = Modpacks.inspect(out)
            assertEquals(Loader.NeoForge, pack.loader)
            assertEquals("26.2.0.93", pack.loaderVersion)
            assertEquals(folder.name, pack.name)
        } finally {
            server.close()
        }
    }
}
