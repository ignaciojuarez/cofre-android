package com.ignacio.cofre.ui.folders

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ignacio.cofre.design.AdaptiveShell
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHost
import com.ignacio.cofre.design.SheetPanel
import com.ignacio.cofre.design.ShellSection
import com.ignacio.cofre.design.pageBackground
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.mods.Snapshots
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Odin 3: 1920×1080 at 270 dpi, about 1138×640 dp in landscape. */
private const val ODIN = "w1138dp-h640dp-land-270dpi"

/** A short landscape phone with the rail. */
private const val PHONE = "w731dp-h411dp-land-420dpi"

/** Sample folders like the mockup's: shared, auto, unused, can't play yet. */
internal object FolderSamples {
    private const val NOW = 1_762_700_000_000L
    private const val MB = 1L shl 20
    val survival = Instance("survival", "Survival+", loaderVersion = "26.2.0.93", modFolder = "survival-mods", lastPlayedAt = NOW - 3_600_000)
    val creative = Instance("creative", "Creative test", loaderVersion = "26.2.0.93", modFolder = "survival-mods")
    val handheld = Instance("handheld", "Handheld", loaderVersion = "26.2.0.93", modFolder = "handheld-lite")
    val instances = listOf(survival, creative, handheld)

    val cards = listOf(
        FolderCard(ModFolder("survival-mods", "Survival mods", loaderVersion = "26.2.0.93", createdFrom = "survival", createdAt = NOW - 86_400_000 * 7), 42, 6, 412 * MB, listOf(survival, creative)),
        FolderCard(ModFolder("handheld-lite", "Handheld lite", autoUpdate = AutoUpdate.Auto), 7, 0, 64 * MB, listOf(handheld)),
        FolderCard(ModFolder("create-test", "Create test"), 88, 2, 1100 * MB, emptyList()),
        FolderCard(ModFolder("survival-fabric", "Survival mods · Fabric", loader = Loader.Fabric), 35, 6, 398 * MB, emptyList()),
        FolderCard(ModFolder("prep-263", "26.3 prep", gameVersion = "26.3"), 34, 0, 380 * MB, emptyList()),
    )

    val snapshots = listOf(
        SnapshotRow(Snapshots.Snapshot("1-before_update", Snapshots.Reason.BeforeUpdate, NOW - 86_400_000), 3),
        SnapshotRow(Snapshots.Snapshot("2-last_good", Snapshots.Reason.LastGood, NOW - 7_200_000), 0),
    )

    val state = FoldersState(cards = cards, loading = false, selected = "survival-mods", snapshots = snapshots)
}

/** Mod folders: the overview and its sheets on sample data, in the shell, as a controller user sees them. */
abstract class FoldersScreenshots(private val device: String) {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    private fun shoot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            CofreTheme(highContrast = false, reduceMotion = true) {
                ControllerHost {
                    AdaptiveShell(ShellSection.Mods, {}) { content() }
                }
            }
        }
        // Wake controller mode the way a device does: the first press only takes focus.
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/$device/folders_$name.png")
    }

    private fun sheet(name: String, content: @Composable () -> Unit) = shoot(name) {
        Box(Modifier.fillMaxSize().pageBackground()) {
            SheetPanel(Modifier.align(Alignment.Center)) { content() }
        }
    }

    @Test fun overview() = shoot("overview") {
        FoldersContent(FolderSamples.state, {}, {}, {}, {}, { FolderDetailActions() })
    }

    @Test fun newFolder() = sheet("new") {
        NewFolderContent(
            FolderSamples.instances, FolderSamples.cards.map { it.folder }, listOf("26.2", "26.1", "1.21.11"), listOf("26.2.0.93", "26.2.0.88"),
            suggestName = { "$it 2" }, onCreate = {}, onConvert = { _, _ -> }, onPickPack = {}, onDismiss = {},
        )
    }

    @Test fun newFolderEmpty() = sheet("new_empty") {
        NewFolderContent(
            FolderSamples.instances, FolderSamples.cards.map { it.folder }, listOf("26.2"), listOf("26.2.0.93"),
            suggestName = { it }, onCreate = {}, onConvert = { _, _ -> }, onPickPack = {}, onDismiss = {},
            initialStart = FolderStart.Empty,
        )
    }

    @Test fun delete() = shoot("delete") {
        Box(Modifier.fillMaxSize().pageBackground()) {
            SheetPanel(Modifier.align(Alignment.Center), hugsContent = true) {
                DeleteContent(FolderSamples.cards[0], listOf(FolderSamples.cards[1].folder), {}, {})
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = ODIN, application = Application::class)
class OdinFoldersScreenshots : FoldersScreenshots("odin")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE, application = Application::class)
class PhoneFoldersScreenshots : FoldersScreenshots("phone")
