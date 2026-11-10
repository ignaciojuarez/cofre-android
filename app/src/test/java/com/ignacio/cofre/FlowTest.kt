package com.ignacio.cofre

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ignacio.cofre.design.ShellSection
import com.ignacio.cofre.ui.instances.Library
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The main flows, driven through the real app on sample files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1138dp-h640dp-land-270dpi")
class FlowTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    private fun app(graph: AppGraph) = rule.setContent {
        view = LocalView.current
        CofreRoot(graph)
    }

    /** A gamepad press the way a device delivers it: through the window. */
    private fun press(vararg keys: Int) = keys.forEach { key ->
        rule.runOnUiThread {
            view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
        rule.waitForIdle()
    }

    @Test fun createInstanceWithTheHandheldPreset() {
        val graph = Samples.graph(seeded = false)
        graph.library.refresh()
        app(graph)
        rule.onNodeWithTag("play.new").performClick()
        rule.waitForIdle()
        rule.onNode(hasTestTag("newInstance.preset"), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        rule.onNodeWithTag("newInstance.create").performClick()
        rule.waitUntil(5_000) { graph.library.instances.isNotEmpty() }
        val made = graph.library.instances.single()
        assertEquals("Survival", made.name)
        assertEquals("26.2.0.93", made.loaderVersion)
        val dir = graph.instances.directory(made)
        assertTrue(File(dir, "options.txt").readText().contains("preferredGraphicsBackend:vulkan"))
        assertTrue(File(dir, "config/fml.toml").readText().contains("earlyWindowControl = false"))
        assertEquals(Library.handheld.pinnedMods.size, graph.downloads.progress.value.items.size, "the preset's pinned mods are queued")
        assertEquals(made.id, graph.library.active?.id)
        assertEquals(false, graph.nav.newInstance)
    }

    @Test fun signInSaysItIsNotSetUpWithoutAClientId() {
        val graph = Samples.graph(setupDone = false, seeded = false, clientId = "")
        app(graph)
        rule.onNodeWithTag("setup.next").performClick() // Welcome → device check
        rule.onNodeWithTag("setup.next").performClick() // → Sign in
        rule.onNodeWithTag("signin.notConfigured").assertIsDisplayed()
        rule.onNodeWithTag("accounts.signIn").assertIsNotEnabled()
        rule.onNodeWithText("Sign-in isn't set up in this build.").assertIsDisplayed()
    }

    @Test fun anUnsuitableDeviceExplainsWhatIsMissingAndCarriesOn() {
        val graph = Samples.graph(Samples.unsuitable, setupDone = false, seeded = false)
        app(graph)
        rule.onNodeWithTag("setup.next").performClick()
        rule.onNodeWithTag("setup.unsuitable").assertIsDisplayed()
        rule.onNodeWithText("This device can't run Minecraft 26.2's Vulkan renderer").assertIsDisplayed()
        rule.onNodeWithText("Push descriptors").assertIsDisplayed()
        rule.onNodeWithText("Instanced vertex attributes").assertIsDisplayed()
        // The list runs past a 640 dp screen: scroll to the button like a user would.
        rule.onNodeWithTag("setup.next").performScrollTo().performClick()
        rule.onNodeWithTag("signin.notConfigured").assertIsDisplayed()
    }

    @Test fun theControllerReachesEverySectionAndComesBack() {
        val graph = Samples.graph()
        app(graph)
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_BUTTON_THUMBL) // wakes controller focus
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(ShellSection.Instances, graph.nav.section)
        press(KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(ShellSection.Settings, graph.nav.section)
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(ShellSection.Play, graph.nav.section, "R1 wraps around")
        press(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(ShellSection.Settings, graph.nav.section)
        press(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(ShellSection.Instances, graph.nav.section)

        // L1/R1 move focus into the section's content: the first instance rather than the search field. A opens it,
        // B goes back, B again to Play.
        rule.onNodeWithTag("instance.Survival+").assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("survival", graph.nav.instance)
        press(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(ShellSection.Play, graph.nav.section, "B on a section's root goes to Play")

        // Start plays the current instance from Play; on this test device the launch stops at the Vulkan check.
        press(KeyEvent.KEYCODE_BUTTON_START)
        rule.onNodeWithTag("launch").assertIsDisplayed()
        press(KeyEvent.KEYCODE_BUTTON_B)
        assertNull(graph.launcher.run, "B cancels the launch")
    }

    @Test fun deletingAnInstanceCanBeUndone() {
        val graph = Samples.graph()
        graph.nav.section = ShellSection.Instances
        graph.nav.instance = "skyblock"
        app(graph)
        rule.onNodeWithTag("instance.delete").performClick()
        rule.onNodeWithTag("deleteInstance.confirm").performClick()
        rule.waitUntil(5_000) { graph.library.undo != null }
        assertTrue(graph.library.instances.none { it.id == "skyblock" })
        assertEquals("Skyblock", graph.library.deleted.single().instance.name)
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { graph.library.instances.any { it.name == "Skyblock" } }
        assertTrue(graph.library.deleted.isEmpty())
    }
}
