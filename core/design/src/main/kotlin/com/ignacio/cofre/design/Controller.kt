package com.ignacio.cofre.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Controller-first input: gamepad buttons → actions, left stick → D-pad focus moves, last-input tracking for hints.

/** Logical gamepad buttons. [Confirm] and [Back] are A and B, or B and A when the confirm/back swap is on. */
public enum class Pad { Confirm, Back, X, Y, L1, R1, L2, R2, Start, Select }

/** What the user touched last. The hint bar shows only for [Controller]. */
public enum class InputKind { Touch, Controller }

/** Button-label family of the active controller. */
public enum class ControllerStyle {
    Xbox,
    PlayStation,
    Nintendo,
    ;

    public companion object {
        /** From `InputDevice.vendorId`: Sony → PlayStation; Nintendo and AYN's built-in pad (0x2020) → Nintendo. */
        public fun of(vendorId: Int): ControllerStyle = when (vendorId) {
            0x054c -> PlayStation
            0x057e, 0x2020 -> Nintendo
            else -> Xbox
        }
    }
}

/** A gamepad action for the focused region: [onPress] runs on the button, [label] shows in the hint bar. */
public class ControllerAction(
    public val pad: Pad,
    public val label: String? = null,
    public val onPress: (() -> Unit)? = null,
)

/** A hint-bar entry. */
public data class ControllerHint(val pad: Pad, val label: String)

/** Controller input state for one window tree. Read it with [LocalController]. */
@Stable
public class ControllerState internal constructor() {
    /** What the user used last. */
    public var lastInput: InputKind by mutableStateOf(InputKind.Touch)
        internal set

    /** Labels of the last controller used. */
    public var style: ControllerStyle by mutableStateOf(ControllerStyle.Xbox)
        internal set

    /** Odin firmware swaps A/B in its Xbox/Nintendo modes; this undoes it. */
    public var swapConfirmBack: Boolean by mutableStateOf(false)
        internal set

    internal var onStart: () -> Unit = {}
    internal var onSelect: () -> Unit = {}
    internal val scopes = mutableStateListOf<ActionScope>()

    /** True while any block component (anything with a focus ring) has focus. */
    internal var hasFocus by mutableStateOf(false)
        private set
    // Every window keeps its own focused item; a sheet's sits on top of the screen's that opened it.
    private val focusedRings = mutableListOf<FocusRingNode>()

    /** The most recently focused block component that still has focus. */
    internal val focusedRing: FocusRingNode? get() = focusedRings.lastOrNull()

    /** Unclipped bounds of the focused block component, in root coordinates. */
    internal val focusedBounds: Rect? get() = focusedRing?.boundsInRoot()

    internal fun onFocusChanged(node: FocusRingNode, isFocused: Boolean) {
        focusedRings.remove(node)
        if (isFocused) focusedRings += node
        hasFocus = focusedRings.isNotEmpty()
    }

    internal fun markController(deviceId: Int, inputModes: InputModeManager?) {
        lastInput = InputKind.Controller
        InputDevice.getDevice(deviceId)?.let { style = ControllerStyle.of(it.vendorId) }
        // Compose only focuses clickables outside touch mode, so leave it on the first button press.
        if (inputModes?.inputMode == InputMode.Touch) inputModes.requestInputMode(InputMode.Keyboard)
    }

    /** What Back, Start and Select do when nothing closer to focus handled them. */
    internal fun fallback(pad: Pad?, view: View, onBack: (() -> Unit)?): (() -> Unit)? = when (pad) {
        Pad.Back -> onBack ?: { view.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher?.onBackPressed(); Unit }
        Pad.Start -> onStart
        Pad.Select -> onSelect
        else -> null
    }

    /** Hints for where focus is now: defaults, overridden by each enclosing [controllerActions], innermost last. */
    public val hints: List<ControllerHint>
        get() {
            val labels = linkedMapOf(Pad.Confirm to "Select", Pad.Back to "Back")
            scopes.sortedBy { it.depth }.forEach { scope ->
                scope.actions.forEach { a -> a.label?.let { labels[a.pad] = it } }
            }
            return Pad.entries.mapNotNull { pad -> labels[pad]?.let { ControllerHint(pad, it) } }
        }
}

/** The controller state of the enclosing [ControllerHost]. Outside one, a detached default. */
@Suppress("ComposeCompositionLocalUsage")
public val LocalController: ProvidableCompositionLocal<ControllerState> =
    staticCompositionLocalOf { ControllerState() }

/**
 * Root of controller input; wrap the app's content once.
 * - A (or B when [swapConfirmBack]) activates the focused item; B goes back through the OnBackPressedDispatcher.
 * - The left stick and hat move focus like the D-pad, past a deadzone and with key repeat.
 * - Start and Select call [onStart] and [onSelect] unless a focused region handles them.
 * Regions add X, Y, shoulder and trigger actions with [controllerActions]; screens restore focus with [ScreenFocus].
 */
@Composable
public fun ControllerHost(
    modifier: Modifier = Modifier,
    swapConfirmBack: Boolean = false,
    onStart: () -> Unit = {},
    onSelect: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state = remember { ControllerState() }
    state.swapConfirmBack = swapConfirmBack
    state.onStart = onStart
    state.onSelect = onSelect
    CompositionLocalProvider(LocalController provides state) {
        WindowInput(state, onBack = null)
        Box(modifier.controllerInput(onBack = null)) { content() }
    }
}

/**
 * Hooks the window this composition draws in, so it works before anything has focus:
 * - A gamepad key marks controller input. With nothing focused, Back/Start/Select still act and any other button
 *   only wakes focus (the screen's [ScreenFocus] takes it); it doesn't also click what just got focus.
 * - Joystick motion becomes focus moves.
 * Sheets and menus in their own window call it again with their own [onBack].
 */
@Composable
internal fun WindowInput(state: ControllerState, onBack: (() -> Unit)?) {
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    val scope = rememberCoroutineScope()
    val currentFocus by rememberUpdatedState(focus)
    DisposableEffect(view) {
        val window = view.findWindow() ?: return@DisposableEffect onDispose {}
        val original = window.callback
        val stick = StickRepeater()
        var repeatJob: Job? = null
        fun move(direction: FocusDirection) {
            state.markController(-1, inputModes)
            currentFocus.moveFocus(direction)
        }
        val wrapped = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
                if (!isGamepadKey(event.keyCode)) return original.dispatchKeyEvent(event)
                state.markController(event.deviceId, inputModes)
                if (state.hasFocus) return original.dispatchKeyEvent(event)
                val action = state.fallback(padFor(event.keyCode, state.swapConfirmBack), view, onBack)
                if (action != null && event.action == AndroidKeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
                return true
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) {
                    return original.dispatchGenericMotionEvent(event)
                }
                val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                val useHat = hatX != 0f || hatY != 0f
                val x = if (useHat) hatX else event.getAxisValue(MotionEvent.AXIS_X)
                val y = if (useHat) hatY else event.getAxisValue(MotionEvent.AXIS_Y)
                val now = event.eventTime
                stick.onAxes(x, y, now)?.let { first ->
                    move(first)
                    repeatJob?.cancel()
                    repeatJob = scope.launchRepeat(stick, ::move)
                }
                if (stick.held == null) repeatJob?.cancel()
                return true
            }
        }
        window.callback = wrapped
        onDispose {
            repeatJob?.cancel()
            if (window.callback === wrapped) window.callback = original
        }
    }
}

private fun CoroutineScope.launchRepeat(stick: StickRepeater, move: (FocusDirection) -> Unit): Job = launch {
    var now = android.os.SystemClock.uptimeMillis()
    while (isActive) {
        delay((stick.nextAt - now).coerceAtLeast(1))
        now = android.os.SystemClock.uptimeMillis()
        val direction = stick.onTick(now)
        if (direction != null) move(direction) else if (stick.held == null) return@launch
    }
}

/**
 * Left-stick (or hat) position → D-pad direction, with a deadzone and key-repeat timing. Pure, so it's tested
 * without a device: [onAxes] returns a direction to move now when the stick enters one; [onTick] returns the held
 * direction again once [nextAt] passes.
 */
internal class StickRepeater(
    private val deadzone: Float = 0.5f,
    private val initialDelayMs: Long = 400,
    private val repeatMs: Long = 120,
) {
    var held: FocusDirection? = null
        private set
    var nextAt: Long = Long.MAX_VALUE
        private set

    fun onAxes(x: Float, y: Float, now: Long): FocusDirection? {
        val direction = direction(x, y, deadzone)
        if (direction == held) return null
        held = direction
        nextAt = if (direction == null) Long.MAX_VALUE else now + initialDelayMs
        return direction
    }

    fun onTick(now: Long): FocusDirection? {
        val direction = held ?: return null
        if (now < nextAt) return null
        nextAt = now + repeatMs
        return direction
    }

    companion object {
        /** The dominant axis past [deadzone]; screen y grows downward, like Android's AXIS_Y. */
        fun direction(x: Float, y: Float, deadzone: Float): FocusDirection? = when {
            maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) < deadzone -> null
            kotlin.math.abs(x) > kotlin.math.abs(y) -> if (x > 0) FocusDirection.Right else FocusDirection.Left
            else -> if (y > 0) FocusDirection.Down else FocusDirection.Up
        }
    }
}

/** The logical button for a key code, after the confirm/back swap. D-pad center is always [Pad.Confirm]. */
internal fun padFor(keyCode: Int, swapConfirmBack: Boolean): Pad? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_BUTTON_A -> if (swapConfirmBack) Pad.Back else Pad.Confirm
    AndroidKeyEvent.KEYCODE_BUTTON_B -> if (swapConfirmBack) Pad.Confirm else Pad.Back
    AndroidKeyEvent.KEYCODE_DPAD_CENTER -> Pad.Confirm
    AndroidKeyEvent.KEYCODE_BUTTON_X -> Pad.X
    AndroidKeyEvent.KEYCODE_BUTTON_Y -> Pad.Y
    AndroidKeyEvent.KEYCODE_BUTTON_L1 -> Pad.L1
    AndroidKeyEvent.KEYCODE_BUTTON_R1 -> Pad.R1
    AndroidKeyEvent.KEYCODE_BUTTON_L2 -> Pad.L2
    AndroidKeyEvent.KEYCODE_BUTTON_R2 -> Pad.R2
    AndroidKeyEvent.KEYCODE_BUTTON_START -> Pad.Start
    AndroidKeyEvent.KEYCODE_BUTTON_SELECT -> Pad.Select
    else -> null
}

internal fun isGamepadKey(keyCode: Int): Boolean =
    AndroidKeyEvent.isGamepadButton(keyCode) || keyCode in AndroidKeyEvent.KEYCODE_DPAD_UP..AndroidKeyEvent.KEYCODE_DPAD_CENTER

/** The next or previous entry, wrapping around (L1/R1 section cycling). */
public fun <T> List<T>.cycle(current: T, delta: Int): T {
    val i = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(i + delta, size)]
}

// Root key handling

/** Confirm → D-pad center for the focused item, Back → back, Start/Select → host callbacks, touch → touch mode. */
internal fun Modifier.controllerInput(onBack: (() -> Unit)? = null): Modifier = this then ControllerInputElement(onBack)

private data class ControllerInputElement(val onBack: (() -> Unit)?) : ModifierNodeElement<ControllerInputNode>() {
    override fun create() = ControllerInputNode(onBack)
    override fun update(node: ControllerInputNode) {
        node.onBack = onBack
    }
}

private class ControllerInputNode(var onBack: (() -> Unit)?) :
    Modifier.Node(), KeyInputModifierNode, PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private val state get() = currentValueOf(LocalController)

    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        if (!isGamepadKey(native.keyCode)) return false
        state.markController(native.deviceId, currentValueOf(LocalInputModeManager))
        val pad = padFor(native.keyCode, state.swapConfirmBack)
        if (pad == Pad.Confirm && native.keyCode != AndroidKeyEvent.KEYCODE_DPAD_CENTER) {
            // Every clickable already answers D-pad center (press on down, click on up), so A becomes that.
            val view = currentValueOf(LocalView)
            val center = AndroidKeyEvent(
                native.downTime, native.eventTime, native.action, AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                native.repeatCount, native.metaState, native.deviceId, native.scanCode, native.flags, native.source,
            )
            view.post { view.dispatchKeyEvent(center) }
            return true
        }
        // A swapped-away A must not reach clickables as itself.
        return false
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val pad = padFor(event.nativeKeyEvent.keyCode, state.swapConfirmBack)
        val action = state.fallback(pad, currentValueOf(LocalView), onBack) ?: return false
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) action()
        return true
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && pointerEvent.type == PointerEventType.Press) {
            state.lastInput = InputKind.Touch
        }
    }

    override fun onCancelPointerInput() = Unit
    override fun sharePointerInputWithSiblings(): Boolean = true
}

internal fun View.findWindow(): Window? {
    var p = parent
    while (p != null) {
        if (p is DialogWindowProvider) return p.window
        p = p.parent
    }
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return c.window
        c = c.baseContext
    }
    return null
}

internal fun FocusManager.page(down: Boolean, state: ControllerState, distance: Float): Boolean {
    val start = state.focusedBounds ?: return false
    val target = if (down) start.top + distance else start.top - distance
    var moved = false
    for (i in 0 until 64) {
        if (!moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)) break
        moved = true
        val r = state.focusedBounds ?: break
        if (if (down) r.top >= target else r.top <= target) break
    }
    return moved
}
