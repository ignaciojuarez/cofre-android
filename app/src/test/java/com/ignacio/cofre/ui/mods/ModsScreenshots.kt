package com.ignacio.cofre.ui.mods

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
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.design.AdaptiveListDetail
import com.ignacio.cofre.design.AdaptiveShell
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHost
import com.ignacio.cofre.design.SheetPanel
import com.ignacio.cofre.design.ShellSection
import com.ignacio.cofre.design.pageBackground
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Odin 3: 1920×1080 at 270 dpi, about 1138×640 dp in landscape. */
private const val ODIN = "w1138dp-h640dp-land-270dpi"

/** The Mods section's screens on sample data, in the shell, as a controller user sees them. */
abstract class ModsScreenshots(private val device: String) {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    private fun shoot(name: String, controller: Boolean = true, content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            CofreTheme(highContrast = false, reduceMotion = true) {
                ControllerHost {
                    AdaptiveShell(ShellSection.Mods, {}, modsBadge = 1) {
                        Box(Modifier.modsActions("Search", "Filters", {}, {})) { content() }
                    }
                }
            }
        }
        if (controller) {
            // Wake controller mode the way a device does: the first press only takes focus.
            rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }
            rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/$device/mods_$name.png")
    }

    @Composable
    private fun Browse() = BrowsePane(
        ModsSamples.browse, selected = ModsSamples.sodium.route, updates = 1, hasCurseForge = true,
        onQueryChange = {}, onOpen = {}, onLoadMore = {}, onRetry = {}, onFilters = {}, onInstalled = {}, onImport = {}, onAddKey = {},
        folder = ModsSamples.folderInfo, inFolder = ModsSamples.inFolder,
    )

    @Composable
    private fun Installed() = FolderPane(ModsSamples.installed, ModsSamples.folderInfo, FolderActions())

    @Test fun browse() = shoot("browse") {
        AdaptiveListDetail<String>(null, list = { Browse() }, detail = {}, placeholder = { Installed() })
    }

    @Test fun page() = shoot("page") {
        AdaptiveListDetail(ModsSamples.sodium.route, list = { Browse() }, detail = {
            ModPage(ModsSamples.project, PageTab.About, {}, {}, {}, {}, {}, { _, _ -> }, {})
        })
    }

    @Test fun pageVersions() = shoot("page_versions", controller = false) {
        AdaptiveListDetail(ModsSamples.sodium.route, list = { Browse() }, detail = {
            ModPage(ModsSamples.project, PageTab.Versions, {}, {}, {}, {}, {}, { _, _ -> }, {})
        })
    }

    @Test fun installSheet() = shoot("install") {
        Box(Modifier.fillMaxSize().pageBackground()) {
            SheetPanel(Modifier.align(Alignment.Center), hugsContent = true) {
                InstallContent(ModsSamples.confirm, ModsSamples.create, null, "Handheld", {}, {}, {}, {}, {})
            }
        }
    }

    @Test fun installed() = shoot("installed") {
        AdaptiveListDetail(ModsRoutes.INSTALLED, list = { Browse() }, detail = { Installed() })
    }

    @Test fun import() = shoot("import") {
        AdaptiveListDetail(ModsRoutes.IMPORT, list = { Browse() }, detail = {
            ImportPane(ModsSamples.ready, {}, {}, {}, {}, {})
        })
    }

    @Test fun shared() = shoot("shared") {
        Box(Modifier.fillMaxSize().pageBackground()) {
            SheetPanel(Modifier.align(Alignment.Center), hugsContent = true) {
                SharedContent(ModsSamples.shared, {}, {}, {})
            }
        }
    }

    @Test fun folderPicker() = shoot("folder_picker") {
        Box(Modifier.fillMaxSize().pageBackground()) {
            SheetPanel(Modifier.align(Alignment.Center), hugsContent = true) {
                FolderPickerContent(
                    listOf(ModsSamples.folder, ModsSamples.folder.copy(id = "fabric", name = "Survival mods · Fabric", loader = Loader.Fabric)),
                    ModsSamples.folder.id, {}, {},
                )
            }
        }
    }

    @Test fun filters() = shoot("filters") {
        Box(Modifier.fillMaxSize().pageBackground()) {
            SheetPanel(Modifier.align(Alignment.Center), hugsContent = true) {
                FiltersContent(ModsSamples.browse.query, {}, {})
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = ODIN, application = Application::class)
class OdinModsScreenshots : ModsScreenshots("odin")
