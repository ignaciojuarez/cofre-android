package com.ignacio.cofre

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerHost
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.modfolders.ConvertGap
import com.ignacio.cofre.kit.modfolders.ConvertPlan
import com.ignacio.cofre.kit.modfolders.ConvertRow
import com.ignacio.cofre.kit.modfolders.ConvertStatus
import com.ignacio.cofre.kit.modfolders.ConvertTarget
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.VersionChannel
import com.ignacio.cofre.ui.convert.ConvertModel
import com.ignacio.cofre.ui.convert.ConvertScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Convert screen on sample plans (no network): Fabric 26.2 and NeoForge 26.3, as in the mockup. */
@OptIn(ExperimentalRoborazziApi::class)
abstract class ConvertScreenTest(private val device: String) {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    private fun shoot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            CofreTheme(highContrast = false, reduceMotion = true) {
                ControllerHost { Box(Modifier.fillMaxSize().background(CofreTheme.colors.page)) { content() } }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
        rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL)) }
        rule.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$device/$name.png")
    }

    private fun model(to: ConvertTarget, rows: List<ConvertRow>, problemsOnly: Boolean = false): ConvertModel {
        val graph = Samples.graph()
        val folder = graph.modFolders.create("Survival mods")
        return ConvertModel(
            graph, folder, to,
            planner = { ConvertPlan(File("."), folder.gameVersion, folder.loader, it, rows) },
            releases = { listOf("26.3", "26.2", "26.1.2", "26.1") },
        ).also { it.problemsOnly = problemsOnly }
    }

    @Test fun convertFabric() {
        val model = model(ConvertTarget("26.2", Loader.Fabric), fabric)
        shoot("convert_fabric") { ConvertScreen(model, onBack = {}) }
    }

    @Test fun convertNeoForge263() {
        val model = model(ConvertTarget("26.3", Loader.NeoForge), v263)
        shoot("convert_neoforge_26_3") { ConvertScreen(model, onBack = {}) }
    }

    @Test fun convertProblems() {
        val model = model(ConvertTarget("26.2", Loader.Fabric), fabric, problemsOnly = true)
        shoot("convert_problems") { ConvertScreen(model, onBack = {}) }
    }

    private companion object {
        fun mod(id: String, title: String, version: String, size: Long = 2_400_000, channel: VersionChannel = VersionChannel.Release) =
            ModCandidate(id, "$id-v", title, version, listOf("26.2", "26.3"), listOf(Loader.Fabric, Loader.NeoForge), channel, filename = "$id-$version.jar", size = size)

        fun ready(name: String, now: String, next: String) = ConvertRow("$name.jar", name, now, name.lowercase(), true, ConvertStatus.Ready, mod(name.lowercase(), name, next))

        val fabric = listOf(
            ready("Sodium", "0.9.2-neoforge", "0.9.2-fabric"),
            ConvertRow("create.jar", "Create", "6.0.6", "create", true, ConvertStatus.Swap, gap = ConvertGap.NoLoaderBuild, suggestion = mod("create-fabric", "Create Fabric", "6.0.6", 18_000_000)),
            ConvertRow("modernfix.jar", "ModernFix (mVUS)", "5.27.21", "modernfix", true, ConvertStatus.Unavailable, gap = ConvertGap.NoLoaderBuild, reason = "No Fabric build"),
            ConvertRow(null, "Fabric API", null, "fabric-api", true, ConvertStatus.Added, mod("fabric-api", "Fabric API", "0.131.2"), reason = "Needed by 4 mods", neededBy = listOf("Sodium", "Controlify", "Waystones", "Jade")),
            ConvertRow("neoloading.jar", "NeoLoading Vulkan Fix", "1.0.0", "neoloading", true, ConvertStatus.NotNeeded, reason = "Not needed on Fabric"),
            ConvertRow("mystuff-1.0.jar", "mystuff-1.0", "1.0", null, true, ConvertStatus.Unknown, reason = "Not on Modrinth"),
            ready("FerriteCore", "9.0.0-neoforge", "9.0.0-fabric"),
            ConvertRow("apotheosis.jar", "Apotheosis", "9.1.2", "apotheosis", true, ConvertStatus.Unavailable, gap = ConvertGap.MissingDependency, reason = "Needs Placebo"),
            ready("Controlify", "3.5.3", "3.5.3"),
            ready("Distant Horizons", "3.3.3", "3.3.3"),
            ready("Waystones", "21.2.4", "21.2.4"),
            ready("Jade", "19.3.1", "19.3.1"),
        )

        val v263 = listOf(
            ready("Sodium", "0.9.2", "0.9.4"),
            ConvertRow("dh.jar", "Distant Horizons", "3.3.3", "dh", true, ConvertStatus.Unavailable, gap = ConvertGap.NotOutYet, reason = "No 26.3 build yet"),
            ConvertRow("apotheosis.jar", "Apotheosis", "9.1.2", "apotheosis", true, ConvertStatus.BetaOnly, mod("apotheosis", "Apotheosis", "9.2.0-beta.3", channel = VersionChannel.Beta), reason = "Beta only"),
            ready("Controlify", "3.5.3", "3.6.1"),
            ready("FerriteCore", "9.0.0", "9.1.0"),
            ConvertRow("create.jar", "Create", "6.0.6", "create", false, ConvertStatus.Unavailable, gap = ConvertGap.NotOutYet, reason = "No 26.3 build yet"),
            ConvertRow("waystones.jar", "Waystones", "21.2.4", "waystones", true, ConvertStatus.BetaOnly, mod("waystones", "Waystones", "22.0.0-beta.2", channel = VersionChannel.Beta), reason = "Beta only"),
            ready("Balm", "7.1.2", "8.0.0"),
            ready("JEI", "26.2.0.12", "26.3.0.3"),
            ConvertRow("mystuff-1.0.jar", "mystuff-1.0", "1.0", null, true, ConvertStatus.Unknown, reason = "Not on Modrinth"),
            ready("Jade", "19.3.1", "20.0.0"),
        )
    }
}

/** Odin 3: 1920×1080 at 270 dpi, about 1138×640 dp in landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1138dp-h640dp-land-270dpi")
class OdinConvertScreens : ConvertScreenTest("odin")

/** A short landscape phone with the rail: everything must scroll, never clip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w731dp-h411dp-land-420dpi")
class PhoneConvertScreens : ConvertScreenTest("landscape-phone")
