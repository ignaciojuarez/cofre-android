package com.ignacio.cofre.design

import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ControllerTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View

    /** Presses a key the way a device delivers it: through the window, so it works before anything has focus. */
    private fun press(keyCode: Int, window: View = view) {
        rule.runOnUiThread {
            window.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            window.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = rule.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.TestTag]

    private fun content(body: @Composable () -> Unit) = rule.setContent {
        view = LocalView.current
        CofreTheme { ControllerHost { body() } }
    }

    @Test
    fun stickPastDeadzoneMovesOnceThenRepeats() {
        val stick = StickRepeater(deadzone = 0.5f, initialDelayMs = 400, repeatMs = 120)
        assertNull(stick.onAxes(0.3f, 0.2f, 0))
        assertEquals(FocusDirection.Right, stick.onAxes(0.9f, 0.2f, 0))
        assertNull("same direction doesn't move again", stick.onAxes(0.95f, 0.1f, 50))
        assertNull(stick.onTick(399))
        assertEquals(FocusDirection.Right, stick.onTick(400))
        assertNull(stick.onTick(519))
        assertEquals(FocusDirection.Right, stick.onTick(520))
        assertEquals(FocusDirection.Up, stick.onAxes(0.2f, -0.8f, 600))
        assertNull(stick.onAxes(0.1f, 0.1f, 700))
        assertNull(stick.onTick(10_000))
    }

    @Test
    fun stickPicksTheDominantAxis() {
        assertEquals(FocusDirection.Down, StickRepeater.direction(0.6f, 0.7f, 0.5f))
        assertEquals(FocusDirection.Left, StickRepeater.direction(-0.7f, 0.6f, 0.5f))
        assertNull(StickRepeater.direction(0.49f, -0.49f, 0.5f))
    }

    @Test
    fun confirmAndBackSwap() {
        assertEquals(Pad.Confirm, padFor(android.view.KeyEvent.KEYCODE_BUTTON_A, swapConfirmBack = false))
        assertEquals(Pad.Back, padFor(android.view.KeyEvent.KEYCODE_BUTTON_B, swapConfirmBack = false))
        assertEquals(Pad.Back, padFor(android.view.KeyEvent.KEYCODE_BUTTON_A, swapConfirmBack = true))
        assertEquals(Pad.Confirm, padFor(android.view.KeyEvent.KEYCODE_BUTTON_B, swapConfirmBack = true))
    }

    @Test
    fun controllerStyleFromVendor() {
        assertEquals(ControllerStyle.Nintendo, ControllerStyle.of(0x2020))
        assertEquals(ControllerStyle.PlayStation, ControllerStyle.of(0x054c))
        assertEquals(ControllerStyle.Xbox, ControllerStyle.of(0x045e))
    }

    @Test
    fun sectionsCycleBothWays() {
        val all = ShellSection.entries
        assertEquals(ShellSection.Instances, all.cycle(ShellSection.Play, 1))
        assertEquals(ShellSection.Settings, all.cycle(ShellSection.Play, -1))
        assertEquals(ShellSection.Play, all.cycle(ShellSection.Settings, 1))
    }

    @Test
    fun aClicksTheFocusedButtonAndBGoesBack() {
        var clicks = 0
        var backs = 0
        content {
            BackHandler { backs++ }
            ScreenFocus {
                Column {
                    BlockButton("One", { clicks++ }, Modifier.testTag("one"))
                    BlockButton("Two", {}, Modifier.testTag("two"))
                }
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A) // wakes controller mode; the screen takes focus, nothing is clicked
        rule.onNodeWithTag("one").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, clicks)
        rule.onNodeWithTag("one").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(1, backs)
    }

    @Test
    fun unusedButtonsAreSwallowedSoAndroidDoesNotResendThemAsAClick() {
        var clicks = 0
        content { ScreenFocus { BlockButton("One", { clicks++ }, Modifier.testTag("one")) } }
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("one").assertIsFocused()
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)) {
            var handled = false
            rule.runOnUiThread {
                handled = view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) and
                    view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            assertTrue("${KeyEvent.keyCodeToString(code)} is consumed, so no fallback key is sent", handled)
        }
        assertEquals(0, clicks)
    }

    @Test
    fun shoulderButtonsSwitchSections() {
        var section by mutableStateOf(ShellSection.Play)
        content {
            AdaptiveShell(section, { section = it }) { s ->
                BlockButton(s.title, {}, Modifier.testTag("content-${s.name}"))
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(ShellSection.Instances, section)
        press(KeyEvent.KEYCODE_BUTTON_L1)
        press(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(ShellSection.Settings, section)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun shoulderButtonsMoveFocusIntoTheNewSection() {
        var section by mutableStateOf(ShellSection.Play)
        content {
            AdaptiveShell(section, { section = it }) { s ->
                Column {
                    BlockButton("${s.title} 1", {}, Modifier.testTag("content-${s.name}"))
                    BlockButton("${s.title} 2", {}, Modifier.testTag("content-${s.name}-2"))
                }
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("content-Play").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(ShellSection.Instances, section)
        rule.onNodeWithTag("content-Instances").assertIsFocused()
        // From the sidebar too: R1 leaves it for the next section's content.
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        rule.onNodeWithTag("content-Instances").assertIsNotFocused()
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(ShellSection.Mods, section)
        rule.onNodeWithTag("content-Mods").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_L1)
        rule.onNodeWithTag("content-Instances").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w1138dp-h640dp")
    fun shoulderButtonsReachContentInsideANestedScreen() {
        var section by mutableStateOf(ShellSection.Play)
        content {
            AdaptiveShell(section, { section = it }, preferRail = true) { s ->
                // Like SplitLayout: the section's content is its own screen.
                ScreenFocus {
                    Column {
                        BlockButton("${s.title} 1", {}, Modifier.testTag("content-${s.name}"))
                        BlockButton("${s.title} 2", {}, Modifier.testTag("content-${s.name}-2"))
                    }
                }
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("content-Play").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(ShellSection.Instances, section)
        rule.onNodeWithTag("content-Instances").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_BUTTON_R1)
        rule.onNodeWithTag("content-Mods").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_L1)
        rule.onNodeWithTag("content-Instances-2").assertIsFocused()
    }

    @Test
    fun selectMenuOpensOnTheCurrentChoice() {
        content {
            ScreenFocus {
                SelectButton("Folder", "c", {}, listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma"), Modifier.testTag("select"))
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Folder, Gamma") and isFocused()).assertExists()
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNode(androidx.compose.ui.test.hasText("Gamma") and isFocused() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertExists()
    }

    @Test
    @Config(qualifiers = "w1138dp-h640dp")
    fun sectionActionsWorkFromTheRail() {
        var section by mutableStateOf(ShellSection.Mods)
        var picks = 0
        content {
            AdaptiveShell(section, { section = it }) { s ->
                if (s == ShellSection.Mods) SectionActions(ControllerAction(Pad.L2, "Folder") { picks++ })
                BlockButton(s.title, {}, Modifier.testTag("content-${s.name}"))
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_DPAD_LEFT) // onto the rail
        rule.onNodeWithTag("content-Mods").assertIsNotFocused()
        press(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(1, picks)
        press(KeyEvent.KEYCODE_BUTTON_R1)
        press(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals("only while Mods is on screen", 1, picks)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp")
    fun aShortRailReachesEverySectionAndTheFooter() {
        content {
            AdaptiveShell(ShellSection.Play, {}, navFooter = { BlockButton("Footer", {}, Modifier.testTag("footer")) }) {
                BlockButton("Content", {}, Modifier.testTag("content"))
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        rule.onNodeWithTag("footer").assertIsFocused().assertIsDisplayed()
        repeat(2) { press(KeyEvent.KEYCODE_DPAD_UP) }
        rule.onNode(androidx.compose.ui.test.hasText("Mods") and isFocused()).assertIsDisplayed()
    }

    @Test
    fun focusComesBackToTheSameItemOnReturn() {
        var screen by mutableStateOf("list")
        content {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(screen) {
                ScreenFocus {
                    Column {
                        repeat(3) { i -> BlockButton("$screen $i", {}, Modifier.testTag("$screen-$i")) }
                    }
                }
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("list-0").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("list-2").assertIsFocused()

        screen = "detail"
        rule.waitForIdle()
        rule.onNodeWithTag("detail-0").assertIsFocused()

        screen = "list"
        rule.waitForIdle()
        rule.onNodeWithTag("list-2").assertIsFocused()
    }

    @Test
    fun sheetTakesFocusAndGivesItBack() {
        var open by mutableStateOf(false)
        lateinit var sheetView: View
        content {
            ScreenFocus {
                Column {
                    BlockButton("Other", {}, Modifier.testTag("other"))
                    BlockButton("Open", { open = true }, Modifier.testTag("open"))
                }
            }
            if (open) {
                BlockSheet({ open = false }) {
                    sheetView = LocalView.current
                    BlockButton("Inside", {}, Modifier.testTag("inside"))
                }
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("open").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(true, open)
        rule.onNodeWithTag("inside").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_B, window = sheetView)
        assertEquals(false, open)
        rule.onNodeWithTag("open").assertIsFocused()
    }

    @Test
    fun aClosingSheetWinsOverTheWindowsDefaultPick() {
        var open by mutableStateOf(false)
        lateinit var sheetView: View
        val first = FocusRequester()
        content {
            ScreenFocus {
                Column {
                    BlockButton("Import", {}, Modifier.testTag("import").focusRequester(first))
                    BlockButton("Open", { open = true }, Modifier.testTag("open"))
                }
            }
            if (open) {
                BlockSheet({ open = false }) {
                    sheetView = LocalView.current
                    BlockButton("Inside", {}, Modifier.testTag("inside"))
                }
            } else {
                // What Compose does when the window gets focus back: the first item.
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("inside").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_B, window = sheetView)
        rule.onNodeWithTag("open").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w400dp-h600dp")
    fun triggersPageThroughAList() {
        content {
            ScreenFocus { PaneScroll { repeat(40) { i -> BlockButton("Item $i", {}, Modifier.testTag("item-$i")) } } }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("item-0").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_R2)
        val afterPage = focusedTag().removePrefix("item-").toInt()
        assertTrue("R2 moved focus about a screen down, got $afterPage", afterPage in 8..14)
        press(KeyEvent.KEYCODE_BUTTON_L2)
        assertTrue("L2 came back up, got ${focusedTag()}", focusedTag().removePrefix("item-").toInt() <= 2)
    }

    @Test
    fun touchHidesTheHintBar() {
        lateinit var controller: ControllerState
        content {
            controller = LocalController.current
            ScreenFocus { BlockButton("Play", {}, Modifier.testTag("play")) }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(InputKind.Controller, controller.lastInput)
        rule.onNodeWithTag("play").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(InputKind.Touch, controller.lastInput)
    }

    @Test
    fun hintsFollowTheFocusedRegion() {
        lateinit var controller: ControllerState
        content {
            controller = LocalController.current
            ScreenFocus(Modifier.controllerActions(ControllerAction(Pad.Y, "Search"))) {
                Column {
                    BlockButton("Outer", {}, Modifier.testTag("outer"))
                    Column(Modifier.controllerActions(ControllerAction(Pad.Y, "Filter"), ControllerAction(Pad.Confirm, "Play"))) {
                        BlockButton("Inner", {}, Modifier.testTag("inner"))
                    }
                }
            }
        }
        press(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("outer").assertIsFocused()
        assertEquals(listOf(Pad.Confirm to "Select", Pad.Back to "Back", Pad.Y to "Search"), controller.hints.map { it.pad to it.label })
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf(Pad.Confirm to "Play", Pad.Back to "Back", Pad.Y to "Filter"), controller.hints.map { it.pad to it.label })
    }
}
