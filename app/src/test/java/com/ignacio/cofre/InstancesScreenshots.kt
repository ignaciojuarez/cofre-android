package com.ignacio.cofre

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ignacio.cofre.design.ShellSection
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.modfolders.ModFolderLink
import com.ignacio.cofre.kit.modfolders.ModFolderMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Instances linked to mod folders: Overview's folder block, the picker, delete, and new instance (Odin 3). */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1138dp-h640dp-land-270dpi")
class InstancesScreenshots {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    /** Sample data after migration, with Creative flat sharing Survival+'s folder and a Fabric folder kept for later. */
    private fun graph(): AppGraph = Samples.graph().apply {
        ModFolderMigration.run(instances, modFolders)
        val survival = instances.get("survival")
        val creative = instances.get("creative")
        val shared = modFolders.get(requireNotNull(survival.modFolder))
        modFolders.rename(shared.id, "Survival mods")
        val own = creative.modFolder
        ModFolderLink.link(instances.directory(creative), modFolders.directory(shared))
        instances.save(creative.copy(modFolder = shared.id))
        own?.let { modFolders.remove(it) }
        modFolders.create("Survival mods · Fabric", loader = Loader.Fabric)
        modFolders.create("26.3 prep", gameVersion = "26.3")
        library.refresh()
        nav.section = ShellSection.Instances
        nav.instance = "survival"
    }

    private fun shoot(name: String, graph: AppGraph, act: () -> Unit = {}) {
        rule.setContent {
            view = LocalView.current
            CofreRoot(graph)
        }
        rule.waitForIdle()
        act()
        rule.waitForIdle()
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
        rule.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/odin/$name.png")
    }

    @Test fun overview() = shoot("instance_mod_folder", graph())

    @Test fun picker() = shoot("instance_folder_picker", graph()) {
        rule.onNodeWithTag("instance.folder.change").performClick()
    }

    @Test fun delete() = shoot("instance_delete", graph()) {
        rule.onNodeWithTag("instance.delete").performScrollTo().performClick()
    }

    @Test fun newInstance() = shoot("new_instance_folder", graph().apply { nav.newInstance = true })

    @Test fun pickerLinksAndUnlinks() {
        val graph = graph()
        shoot("instance_own_folder", graph) {
            rule.onNodeWithTag("instance.folder.change").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("folderPicker.new").performScrollTo().performClick()
        }
        val linked = graph.instances.get("survival")
        assertEquals("Survival+ mods", graph.modFolders.get(requireNotNull(linked.modFolder)).name)
        graph.library.linkFolder("survival", null)
        rule.waitForIdle()
        assertNull(graph.instances.get("survival").modFolder)
    }

    @Test fun deleteWithFolderAndUndo() {
        val graph = graph()
        val folder = requireNotNull(graph.instances.get("vanilla").modFolder)
        graph.library.trash("vanilla", alsoFolder = true)
        rule.waitForIdle()
        assert(graph.modFolders.list().none { it.id == folder })
        graph.library.undoTrash()
        rule.waitForIdle()
        assertEquals(folder, graph.instances.get("vanilla").modFolder)
        assertEquals(folder, graph.modFolders.get(folder).id)
        // A shared folder never goes with the instance.
        val shared = requireNotNull(graph.instances.get("creative").modFolder)
        graph.library.trash("creative", alsoFolder = true)
        rule.waitForIdle()
        assertEquals(shared, graph.modFolders.get(shared).id)
    }
}
