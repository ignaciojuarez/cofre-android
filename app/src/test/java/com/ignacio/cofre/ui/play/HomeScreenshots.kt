package com.ignacio.cofre.ui.play

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.CofreRoot
import com.ignacio.cofre.Samples
import com.ignacio.cofre.accounts.Accounts
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHost
import com.ignacio.cofre.kit.accounts.AccountStore
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.compat.ContentCompatibility
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.modfolders.ModFolderLink
import com.ignacio.cofre.kit.modfolders.ModFolderMigration
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModRequirement
import com.ignacio.cofre.kit.mods.ModUpdate
import com.ignacio.cofre.kit.mods.Snapshots
import com.ignacio.cofre.launch.Launcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Odin 3: 1920×1080 at 270 dpi, about 1138×640 dp in landscape. */
private const val ODIN = "w1138dp-h640dp-land-270dpi"

/** A short landscape phone (rail navigation). */
private const val PHONE = "w731dp-h411dp-land-280dpi"

/** The shortest landscape phone Home supports: Play must stay above the fold. */
private const val SHORT_PHONE = "w731dp-h360dp-land-420dpi"

/** Home (Play) as a controller user sees it: the dashboard, its tiles opened, and its edge states. */
@OptIn(ExperimentalRoborazziApi::class)
abstract class HomeScreenshots(private val device: String) {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    private fun press(keyCode: Int) {
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)) }
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)) }
        rule.waitForIdle()
    }

    /** Shows Home on [graph] in controller mode; with [tile], focuses that tile and presses A to open it. */
    private fun shoot(name: String, graph: AppGraph, tile: HomeTile? = null) {
        rule.setContent {
            view = LocalView.current
            CofreRoot(graph)
        }
        rule.waitForIdle()
        // Wake controller mode the way a device does: the first button press only takes focus.
        press(KeyEvent.KEYCODE_BUTTON_THUMBL)
        tile?.let {
            rule.onNodeWithTag("home.tile.${it.name.lowercase()}").performSemanticsAction(SemanticsActions.RequestFocus)
            rule.waitForIdle()
            press(KeyEvent.KEYCODE_BUTTON_A)
        }
        captureScreenRoboImage("src/test/screenshots/$device/$name.png")
    }

    @Test fun home() = shoot("home", HomeSamples.graph())

    @Test fun homeReadyOpen() = shoot("home_ready_open", HomeSamples.graph(), HomeTile.Ready)

    @Test fun homeFolderOpen() = shoot("home_folder_open", HomeSamples.graph(), HomeTile.Folder)

    @Test fun homePerformanceOpen() = shoot("home_performance_open", HomeSamples.graph(), HomeTile.Performance)

    @Test fun homeSignedOut() = shoot("home_signed_out", HomeSamples.graph(signedOut = true))

    @Test fun homeFirstRun() = shoot("home_first_run", HomeSamples.graph(installed = false, folder = false, session = false, fixes = false), HomeTile.Ready)

    @Test fun homeCrashed() = shoot("home_crashed", HomeSamples.graph(crashed = true), HomeTile.Session)

    @Test fun homeRunning() {
        val graph = HomeSamples.graph()
        File(graph.home, "launch/pending.json").apply { parentFile?.mkdirs(); writeText("{}") }
        val game = ActivityManager.RunningAppProcessInfo().apply { processName = "${graph.context.packageName}:game" }
        shadowOf(graph.context.getSystemService(ActivityManager::class.java)).setProcesses(listOf(game))
        shoot("home_running", graph)
    }

    @Test fun bClosesATileAndFocusGoesBackToIt() {
        rule.setContent {
            view = LocalView.current
            CofreRoot(HomeSamples.graph())
        }
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_BUTTON_THUMBL)
        rule.onNodeWithTag("home.tile.folder").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("home.panel.folder").assertExists()
        press(KeyEvent.KEYCODE_BUTTON_B)
        rule.onNodeWithTag("home.panel.folder").assertDoesNotExist()
        rule.onNodeWithTag("home.tile.folder").assertIsFocused()
    }

    @Test fun gameStoppedRollback() {
        val graph = HomeSamples.graph()
        val instance = graph.instances.get("survival")
        val stopped = Launcher.Stopped(
            Samples.openGlReport().copy(reason = ExitReason.Mods, mod = null, clue = "Mod ferritecore failed to load correctly"),
            instance,
            rollback = Snapshots.Snapshot("1762560000000-before_update", Snapshots.Reason.BeforeUpdate, 1_762_560_000_000),
            updatedMods = listOf("FerriteCore 9.0.1", "Sodium 0.9.3", "Controlify 3.6.0"),
        )
        rule.setContent {
            view = LocalView.current
            CofreTheme(highContrast = false, reduceMotion = true) { ControllerHost { GameStoppedScreen(stopped, {}, {}, {}, {}) } }
        }
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_BUTTON_THUMBL)
        captureScreenRoboImage("src/test/screenshots/$device/game_stopped_rollback.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = ODIN)
class OdinHome : HomeScreenshots("odin")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE)
class PhoneHome : HomeScreenshots("landscape-phone")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = SHORT_PHONE)
@OptIn(ExperimentalRoborazziApi::class)
class ShortPhoneHome {
    @get:Rule val rule = createComposeRule()

    @Test fun homeShort() {
        rule.setContent { CofreRoot(HomeSamples.graph()) }
        rule.waitForIdle()
        rule.onNodeWithTag("play.play").assertIsDisplayed()
        captureScreenRoboImage("src/test/screenshots/landscape-phone/home_short.png")
    }
}

/** Home's sample data on top of [Samples]: a shared mod folder with updates, options, a session and screenshots. */
object HomeSamples {
    fun graph(
        installed: Boolean = true,
        folder: Boolean = true,
        session: Boolean = true,
        crashed: Boolean = false,
        signedOut: Boolean = false,
        fixes: Boolean = true,
    ): AppGraph {
        val graph = Samples.graph()
        val context = graph.context
        game(context, installed)
        battery(context)
        val survival = graph.instances.get("survival")
        val dir = graph.instances.directory(survival)
        if (fixes) {
            // Two things to fix, as in the mockup: a mod likely to crash on Vulkan and one missing its library.
            File(dir, "mods/iris-neoforge-1.11.4+mc26.2.jar").writeBytes(ByteArray(2048))
            jar(File(dir, "mods/waystones-neoforge-26.2-21.1.0.jar"), "waystones", "Waystones", requires = "balm")
        }
        if (folder) {
            val linked = requireNotNull(ModFolderMigration.adopt(survival, graph.instances, graph.modFolders))
            val made = graph.modFolders.get(requireNotNull(linked.modFolder))
            // Shared with a second instance, as in the mockup.
            val creative = graph.instances.get("creative")
            graph.instances.save(creative.copy(modFolder = made.id))
            ModFolderLink.link(graph.instances.directory(creative), graph.modFolders.directory(made))
            FolderUpdates.save(
                graph, made,
                listOf(
                    update("sodium-neoforge-0.9.2+mc26.2.jar", "Sodium", "0.9.2", "0.9.3"),
                    update("controlify-3.5.3+mc26.2.jar", "Controlify", "3.5.3", "3.6.0"),
                ),
            )
        }
        File(dir, "options.txt").writeText("version:4556\nrenderDistance:10\nsimulationDistance:8\nmaxFps:60\n")
        File(dir, "screenshots").mkdirs()
        listOf("2026-11-08_21.40.12.png", "2026-11-08_21.12.40.png", "2026-11-07_18.03.55.png").forEachIndexed { i, name ->
            File(dir, "screenshots/$name").apply { writeBytes(ByteArray(64)); setLastModified(System.currentTimeMillis() - (i + 1) * 3_600_000L) }
        }
        if (session) {
            val ended = System.currentTimeMillis() - 3_600_000
            val server = Launcher.QuickPlay(listOf("--quickPlayMultiplayer", "smp.example.net"), "Friends SMP")
            val record = if (crashed) {
                Launcher.Session("survival", ended - 3_000, ended, ExitReason.OpenGLMod.name, "No context is current", "Distant Horizons", null, server)
            } else {
                Launcher.Session("survival", ended - 47 * 60_000, ended, ExitReason.Quit.name, quickPlay = Launcher.QuickPlay(listOf("--quickPlaySingleplayer", "ironwood"), "Ironwood"), lastServer = server)
            }
            File(graph.home, "launch/sessions/survival.json").apply { parentFile?.mkdirs(); writeText(Kit.json.encodeToString(record)) }
        }
        if (signedOut) {
            graph.accounts.remove(Accounts.DEV_ID)
            AccountStore(graph.home, { "ms-1" }, {}).save("ms-1", "Ignacio", microsoft = true)
            graph.accounts.refresh()
        }
        graph.library.refresh()
        return graph
    }

    /** A NeoForge mod jar with its metadata and one required dependency. */
    private fun jar(file: File, id: String, name: String, requires: String) {
        val toml = """
            modLoader="javafml"
            loaderVersion="[1,)"
            license="MIT"
            [[mods]]
            modId="$id"
            version="21.1.0"
            displayName="$name"
            [[dependencies.$id]]
            modId="$requires"
            type="required"
            versionRange="[7,)"
            side="BOTH"
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/neoforge.mods.toml"))
            zip.write(toml.toByteArray())
            zip.closeEntry()
        }
    }

    private fun update(file: String, name: String, from: String, to: String) = ModUpdate(
        file, name, name.lowercase(), "v-$from", from,
        ModCandidate(name.lowercase(), "v-$to", name, to, listOf("26.2"), listOf(Loader.NeoForge)),
    )

    /** Minecraft and NeoForge installed (or not: a first run) where the ready check looks. */
    private fun game(context: Context, installed: Boolean) {
        val root = File(context.filesDir, "minecraft")
        root.deleteRecursively()
        if (!installed) return
        listOf("versions/26.2/26.2.json", "versions/neoforge-26.2.0.93/neoforge-26.2.0.93.json", "assets/indexes/30.json").forEach {
            File(root, it).apply { parentFile?.mkdirs(); writeText("{}") }
        }
    }

    @Suppress("DEPRECATION")
    private fun battery(context: Context) {
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 82)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 410)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING),
        )
    }

    /** "Before you play" with a missing library and two mods likely to crash. */
    fun preflight(instance: Instance): Launcher.Preflight {
        val issues = listOfNotNull(
            ContentCompatibility.issue("iris", "Iris", ContentKind.Mods)?.copy(file = "iris-1.11.4+mc26.2.jar"),
            ContentCompatibility.glIssue("minimap", "Minimap", com.ignacio.cofre.kit.compat.GlRisk.Likely, "minimap-2.1.jar"),
        )
        val missing = listOf(ModRequirement("yet_another_config_lib_v3", "YetAnotherConfigLib", reason = "dependency", neededBy = "controlify-3.5.3+mc26.2.jar"))
        val check = Launcher.ReadyCheck(
            instance, Launcher.AccountState.Ready, "Dev", Samples.suitable, vulkanSkipped = false,
            loaderVersion = "26.2.0.93", loaderInstalled = true, gameInstalled = true, folderProblem = null,
            missing = missing, lines = listOf("Controlify needs YetAnotherConfigLib"), issues = issues,
        )
        return Launcher.Preflight(check, null)
    }
}
