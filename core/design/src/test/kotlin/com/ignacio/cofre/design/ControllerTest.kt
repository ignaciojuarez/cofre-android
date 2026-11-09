package com.ignacio.cofre.design

import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private fun press(keyCode: Int) {
        rule.runOnUiThread {
            view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            view.rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        rule.waitForIdle()
    }

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
}
