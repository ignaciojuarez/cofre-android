package com.ignacio.cofre.design

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Odin 3: 1920×1080 at 270 dpi, about 1138×640 dp in landscape. */
private const val ODIN = "w1138dp-h640dp-land-270dpi"

/** A phone in portrait (Pixel 9 class). */
private const val PHONE = "w411dp-h914dp-port-xhdpi"

abstract class ScreenshotTest(private val device: String) {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    protected fun shoot(name: String, controller: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            view = LocalView.current
            CofreTheme(highContrast = false, reduceMotion = true) { ControllerHost { content() } }
        }
        if (controller) {
            // Wake controller mode the way a device does: the first button press only takes focus.
            rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)) }
            rule.runOnUiThread { view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)) }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/$device/$name.png")
    }

    protected fun gallery(section: GallerySection) = shoot("gallery_${section.name.lowercase()}") {
        DesignGalleryPage(section, {})
    }

    @Test fun tokens() = gallery(GallerySection.Tokens)
    @Test fun buttons() = gallery(GallerySection.Buttons)
    @Test fun rows() = gallery(GallerySection.Rows)
    @Test fun inputs() = gallery(GallerySection.Inputs)
    @Test fun feedback() = gallery(GallerySection.Feedback)
    @Test fun icons() = gallery(GallerySection.Icons)
    @Test fun overlays() = gallery(GallerySection.Overlays)
    @Test fun controller() = gallery(GallerySection.Controller)

    /** The shell as a controller user first sees it: focus on the first item, hints at the bottom. */
    @Test fun shellController() = shoot("shell_controller", controller = true) {
        AdaptiveShell(ShellSection.Instances, {}, modsBadge = 3) { SampleInstances() }
    }

    @Test fun shellTouch() = shoot("shell_touch") {
        AdaptiveShell(ShellSection.Play, {}, modsBadge = 3) { SamplePlay() }
    }

    @Test fun listDetail() = shoot("list_detail") {
        AdaptiveShell(ShellSection.Mods, {}) {
            AdaptiveListDetail("Sodium", {}, list = { SampleInstances() }, detail = { SampleDetail(it) })
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = ODIN)
class OdinScreenshots : ScreenshotTest("odin") {
    /** The Odin is wide enough for the sidebar; the rail must fit its 640 dp height too. */
    @Test fun shellRail() = shoot("shell_rail", controller = true) {
        AdaptiveShell(ShellSection.Instances, {}, preferRail = true, modsBadge = 3) { SampleInstances() }
    }

    @Test fun centeredCard() = shoot("centered_card") {
        CenteredCard {
            CofreText("Welcome to Cofre", CofreTheme.type.title)
            CofreText("Minecraft 26.2 with NeoForge, on Vulkan.", CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
            StepBar(listOf("Account", "Java", "Version", "Done"), current = 1)
            BlockButton("Continue", {}, role = ButtonRole.Primary)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE)
class PhoneScreenshots : ScreenshotTest("phone")

@Composable
private fun SampleInstances() = Column {
    ColumnHeader("Instances") { IconButton(PixelGlyph.Gear, "Options", {}) }
    PaneScroll {
        ListSection(label = "Pinned") {
            BlockRow("Survival 26.2", subtitle = "NeoForge · 42 mods", onClick = {}, leading = { PixelIcon(PixelGlyph.Grass, size = 26.dp) }, trailing = { Chevron() })
            BlockRow("Creative flat", subtitle = "NeoForge · 3 mods", onClick = {}, leading = { PixelIcon(PixelGlyph.Planks, size = 26.dp) }, trailing = { Chevron() })
            BlockRow("Skyblock", subtitle = "Modpack · 180 mods", onClick = {}, leading = { PixelIcon(PixelGlyph.Ore, size = 26.dp, tint = OreTint.Gold.color) }, trailing = { Chevron() })
        }
        ListSection(label = "Others") {
            BlockRow("Vanilla 26.2", subtitle = "No mods", onClick = {}, leading = { PixelIcon(PixelGlyph.Dirt, size = 26.dp) }, trailing = { Chevron() })
        }
    }
}

@Composable
private fun SamplePlay() {
    Column {
        PixelScene(11, Modifier.fillMaxWidth().height(200.dp), width = 240, height = 110)
        PaneScroll {
            CofreText("Survival 26.2", CofreTheme.type.display)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("NeoForge 26.2.0.93", tone = Tone.NeoForge)
                Badge("Vulkan", tone = Tone.Success)
            }
            BlockButton("Play", {}, role = ButtonRole.Primary, size = ButtonSize.Large, icon = PixelGlyph.Play)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("42", "mods", Modifier.weight(1f))
                Stat("3", "updates", Modifier.weight(1f), tone = Tone.Warn)
                Stat("6 GB", "memory", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SampleDetail(name: String) = Column {
    ColumnHeader(name) { BlockButton("Update", {}, role = ButtonRole.Primary, size = ButtonSize.Small) }
    PaneScroll {
        Banner("Update available: 0.9.2", PixelGlyph.Bolt, tone = Tone.Accent)
        ToggleRow("Enabled", true, {})
        ValueRow("Version", "mc26.2-0.9.1", {})
        ProgressBar(0.4f)
    }
}
