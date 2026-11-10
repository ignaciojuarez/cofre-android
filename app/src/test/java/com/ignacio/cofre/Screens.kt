package com.ignacio.cofre

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHost
import com.ignacio.cofre.design.LogLevel
import com.ignacio.cofre.design.LogLine
import com.ignacio.cofre.design.ShellSection
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.compat.ContentCompatibility
import com.ignacio.cofre.kit.compat.GlRisk
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.mods.ModRequirement
import com.ignacio.cofre.launch.Launcher
import com.ignacio.cofre.ui.play.GameStoppedScreen
import com.ignacio.cofre.ui.play.LaunchScreen
import com.ignacio.cofre.ui.play.PreflightSheet
import com.ignacio.cofre.ui.setup.DeviceReadiness
import com.ignacio.cofre.ui.setup.DeviceUnsuitable
import com.ignacio.cofre.ui.setup.SetupFlow
import com.ignacio.cofre.ui.setup.SetupStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Odin 3: 1920×1080 at 270 dpi, about 1138×640 dp in landscape. */
private const val ODIN = "w1138dp-h640dp-land-270dpi"

@OptIn(ExperimentalRoborazziApi::class)
/** Every main screen on sample data, as a controller user sees it (focus ring and hint bar on). */
abstract class ScreenTest(private val device: String) {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    private fun shoot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            content()
        }
        rule.waitForIdle()
        // Wake controller mode the way a device does: the first button press only takes focus.
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
        rule.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$device/$name.png")
    }

    private fun app(name: String, graph: AppGraph = Samples.graph(), setup: AppGraph.() -> Unit = {}) {
        graph.setup()
        shoot(name) { CofreRoot(graph) }
    }

    /** A bare screen in the theme and controller host, for overlays drawn without the shell. */
    private fun screen(name: String, content: @Composable () -> Unit) = shoot(name) {
        CofreTheme(highContrast = false, reduceMotion = true) { ControllerHost { content() } }
    }

    @Test fun setupWelcome() = app("setup_welcome", Samples.graph(setupDone = false, seeded = false))

    @Test fun setupSignIn() = screen("setup_sign_in") {
        SetupFlow(Samples.graph(setupDone = false, seeded = false), start = SetupStep.SignIn)
    }

    @Test fun setupDevice() = screen("setup_device") {
        SetupFlow(
            Samples.graph(setupDone = false, seeded = false),
            start = SetupStep.Device,
            readiness = DeviceReadiness(Samples.suitable, javaBundled = true, freeBytes = 24L shl 30),
        )
    }

    @Test fun deviceUnsuitable() = screen("device_unsuitable") {
        DeviceUnsuitable(DeviceReadiness(Samples.unsuitable, javaBundled = true, freeBytes = 24L shl 30), onContinue = {})
    }

    @Test fun play() = app("play")

    @Test fun playEmpty() = app("play_empty", Samples.graph(seeded = false))

    @Test fun instances() = app("instances") {
        nav.section = ShellSection.Instances
        nav.instance = "survival"
    }

    @Test fun instancesList() = app("instances_list") { nav.section = ShellSection.Instances }

    @Test fun newInstance() = app("new_instance") {
        nav.section = ShellSection.Instances
        nav.newInstance = true
    }

    @Test fun settingsAccounts() = app("settings_accounts") {
        nav.openSettings(SettingsPane.Accounts)
    }

    @Test fun settingsGame() = app("settings_game") { nav.openSettings(SettingsPane.Game) }

    @Test fun settingsController() = app("settings_controller") { nav.openSettings(SettingsPane.Controller) }

    @Test fun downloads() = app("downloads") {
        val dir = instances.directory(instances.get("survival"))
        listOf("sodium-neoforge-0.9.2+mc26.2.jar" to 1_209_565L, "controlify-3.5.3+mc26.2-universal.jar" to 6_411_915L).forEach { (file, size) ->
            downloads.enqueue(com.ignacio.cofre.kit.downloads.DownloadItem("https://cdn.modrinth.com/$file", "$dir/mods/$file", size = size))
        }
        nav.downloads = true
    }

    @Test fun launch() = screen("launch") {
        val instance = Samples.graph().instances.get("survival")
        LaunchScreen(
            Launcher.Run(
                instance, Launcher.QuickPlay(listOf("--quickPlaySingleplayer", "ironwood"), "Ironwood"),
                stage = Launcher.Stage.Files,
                log = listOf(
                    LogLine("12:04:01", "Cofre", "Launching Survival+"),
                    LogLine("12:04:01", "Cofre", "Vulkan 1.3.284 · Adreno (TM) 830", LogLevel.Success),
                    LogLine("12:04:02", "Cofre", "Account ready"),
                    LogLine("12:04:03", "Cofre", "Java 25", LogLevel.Success),
                ),
            ),
            onCancel = {}, onRetry = {},
        )
    }

    @Test fun launchFailed() = screen("launch_failed") {
        val instance = Samples.graph().instances.get("survival")
        LaunchScreen(Launcher.Run(instance, null, stage = Launcher.Stage.Device, error = "This device can't run Minecraft 26.2's Vulkan renderer. Missing: Push descriptors"), onCancel = {}, onRetry = {})
    }

    @Test fun preflight() = screen("preflight") {
        val instance = Samples.graph().instances.get("survival")
        val issues = listOfNotNull(
            ContentCompatibility.issue("iris", "Iris", ContentKind.Mods)?.copy(file = "iris-1.11.4+mc26.2.jar"),
            ContentCompatibility.glIssue("minimap", "Minimap", GlRisk.Likely, "minimap-2.1.jar"),
        )
        val missing = listOf(ModRequirement("yet_another_config_lib_v3", "YetAnotherConfigLib", reason = "dependency", neededBy = "controlify-3.5.3+mc26.2.jar"))
        PreflightSheet(Launcher.Preflight(instance, null, missing, listOf("Controlify needs YetAnotherConfigLib"), issues), {}, {}, {}, {})
    }

    @Test fun gameStopped() = screen("game_stopped") {
        val instance = Samples.graph().instances.get("survival")
        val mod = ContentStore.Item("distanthorizons-3.3.3-26.2.jar", "Distant Horizons", "3.3.3", null, ContentKind.Mods, enabled = true, inUse = false)
        GameStoppedScreen(Launcher.Stopped(Samples.openGlReport(), instance, mod), {}, {}, {}, {})
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = ODIN)
class OdinScreens : ScreenTest("odin")
