package com.ignacio.cofre.runtime

import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import kotlin.math.abs

/** GLFW key codes (LWJGL 3.4.1 GLFW_KEY_*) for the Android keys a keyboard sends. */
internal object GlfwKeys {
    const val SPACE = 32
    const val ESCAPE = 256
    const val TAB = 258
    const val BACKSPACE = 259
    const val F = 70
    const val E = 69
    const val Q = 81
    const val W = 87
    const val A = 65
    const val S = 83
    const val D = 68
    const val F5 = 294
    const val LEFT_SHIFT = 340
    const val LEFT_CONTROL = 341

    private val map = HashMap<Int, Int>().apply {
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, 65 + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, 48 + i)
        for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, 290 + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_NUMPAD_0 + i, 320 + i)
        putAll(
            listOf(
                KeyEvent.KEYCODE_SPACE to SPACE, KeyEvent.KEYCODE_APOSTROPHE to 39, KeyEvent.KEYCODE_COMMA to 44,
                KeyEvent.KEYCODE_MINUS to 45, KeyEvent.KEYCODE_PERIOD to 46, KeyEvent.KEYCODE_SLASH to 47,
                KeyEvent.KEYCODE_SEMICOLON to 59, KeyEvent.KEYCODE_EQUALS to 61, KeyEvent.KEYCODE_LEFT_BRACKET to 91,
                KeyEvent.KEYCODE_BACKSLASH to 92, KeyEvent.KEYCODE_RIGHT_BRACKET to 93, KeyEvent.KEYCODE_GRAVE to 96,
                KeyEvent.KEYCODE_ESCAPE to ESCAPE, KeyEvent.KEYCODE_ENTER to 257, KeyEvent.KEYCODE_TAB to TAB,
                KeyEvent.KEYCODE_DEL to 259, KeyEvent.KEYCODE_INSERT to 260, KeyEvent.KEYCODE_FORWARD_DEL to 261,
                KeyEvent.KEYCODE_DPAD_RIGHT to 262, KeyEvent.KEYCODE_DPAD_LEFT to 263, KeyEvent.KEYCODE_DPAD_DOWN to 264,
                KeyEvent.KEYCODE_DPAD_UP to 265, KeyEvent.KEYCODE_PAGE_UP to 266, KeyEvent.KEYCODE_PAGE_DOWN to 267,
                KeyEvent.KEYCODE_MOVE_HOME to 268, KeyEvent.KEYCODE_MOVE_END to 269, KeyEvent.KEYCODE_CAPS_LOCK to 280,
                KeyEvent.KEYCODE_SCROLL_LOCK to 281, KeyEvent.KEYCODE_NUM_LOCK to 282, KeyEvent.KEYCODE_SYSRQ to 283,
                KeyEvent.KEYCODE_BREAK to 284, KeyEvent.KEYCODE_NUMPAD_DOT to 330, KeyEvent.KEYCODE_NUMPAD_DIVIDE to 331,
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY to 332, KeyEvent.KEYCODE_NUMPAD_SUBTRACT to 333,
                KeyEvent.KEYCODE_NUMPAD_ADD to 334, KeyEvent.KEYCODE_NUMPAD_ENTER to 335,
                KeyEvent.KEYCODE_NUMPAD_EQUALS to 336, KeyEvent.KEYCODE_SHIFT_LEFT to LEFT_SHIFT,
                KeyEvent.KEYCODE_CTRL_LEFT to LEFT_CONTROL, KeyEvent.KEYCODE_ALT_LEFT to 342,
                KeyEvent.KEYCODE_META_LEFT to 343, KeyEvent.KEYCODE_SHIFT_RIGHT to 344, KeyEvent.KEYCODE_CTRL_RIGHT to 345,
                KeyEvent.KEYCODE_ALT_RIGHT to 346, KeyEvent.KEYCODE_META_RIGHT to 347, KeyEvent.KEYCODE_MENU to 348,
            ),
        )
    }

    fun fromAndroid(keyCode: Int): Int? = map[keyCode]

    /** GLFW_MOD_* bits from Android meta state. */
    fun mods(metaState: Int): Int =
        (if (metaState and KeyEvent.META_SHIFT_ON != 0) 0x1 else 0) or
            (if (metaState and KeyEvent.META_CTRL_ON != 0) 0x2 else 0) or
            (if (metaState and KeyEvent.META_ALT_ON != 0) 0x4 else 0) or
            (if (metaState and KeyEvent.META_META_ON != 0) 0x8 else 0) or
            (if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) 0x10 else 0) or
            (if (metaState and KeyEvent.META_NUM_LOCK_ON != 0) 0x20 else 0)
}

/**
 * Built-in controller support: turns gamepad input into the keyboard and mouse events Minecraft
 * already understands, so the game is playable with no mods.
 *
 *   left stick → WASD          right stick → mouse look     R2 → attack (left click)   L2 → use (right click)
 *   A → jump (Space)           B / Start → Escape           X → drop (Q)               Y → inventory (E)
 *   L1 / R1 → hotbar left/right (scroll)                    L3 → sprint (Ctrl)         R3 → sneak (Shift)
 *   D-pad up → perspective (F5), down → swap hands (F), left/right → hotbar            Select → player list (Tab)
 *   In menus ([inMenu]): D-pad → arrow keys, which move between buttons (A presses the focused one)
 */
internal class GamepadMapper(private val inMenu: () -> Boolean) {
    private val held = HashSet<Int>() // GLFW keys / mouse buttons (as -1 - button) we've pressed
    private var hatX = 0 // the hat's last direction per axis: -1, 0, 1
    private var hatY = 0
    private var lookX = 0f
    private var lookY = 0f
    private var lastFrameNanos = 0L
    private var looking = false

    private val lookFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNanos == 0L) 1f / 60 else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.1f)
            lastFrameNanos = frameTimeNanos
            if (lookX == 0f && lookY == 0f) {
                looking = false
                lastFrameNanos = 0L
                return
            }
            GameBridge.cursorBy((curve(lookX) * LOOK_SPEED * dt).toDouble(), (curve(lookY) * LOOK_SPEED * dt).toDouble())
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun handles(event: KeyEvent): Boolean =
        KeyEvent.isGamepadButton(event.keyCode) ||
            (event.isFromSource(InputDevice.SOURCE_GAMEPAD) && event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT)

    fun handles(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE

    /** Returns true when the event was a controller button we map. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount > 0) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> key(GlfwKeys.SPACE, down)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START -> key(GlfwKeys.ESCAPE, down)
            KeyEvent.KEYCODE_BUTTON_X -> key(GlfwKeys.Q, down)
            KeyEvent.KEYCODE_BUTTON_Y -> key(GlfwKeys.E, down)
            KeyEvent.KEYCODE_BUTTON_L1 -> if (down) GameBridge.scroll(0.0, 1.0)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (down) GameBridge.scroll(0.0, -1.0)
            KeyEvent.KEYCODE_BUTTON_L2 -> button(RIGHT_BUTTON, down)
            KeyEvent.KEYCODE_BUTTON_R2 -> button(LEFT_BUTTON, down)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> key(GlfwKeys.LEFT_CONTROL, down)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> key(GlfwKeys.LEFT_SHIFT, down)
            KeyEvent.KEYCODE_BUTTON_SELECT -> key(GlfwKeys.TAB, down)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> dpad(event.keyCode, down)
            else -> return false
        }
        return true
    }

    fun onMotion(event: MotionEvent) {
        val x = deadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val y = deadzone(event.getAxisValue(MotionEvent.AXIS_Y))
        stickKey(GlfwKeys.D, x, positive = true)
        stickKey(GlfwKeys.A, x, positive = false)
        stickKey(GlfwKeys.S, y, positive = true)
        stickKey(GlfwKeys.W, y, positive = false)

        lookX = deadzone(event.getAxisValue(MotionEvent.AXIS_Z))
        lookY = deadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
        if ((lookX != 0f || lookY != 0f) && !looking) {
            looking = true
            Choreographer.getInstance().postFrameCallback(lookFrame)
        }

        val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        trigger(LEFT_BUTTON, right)
        trigger(RIGHT_BUTTON, left)

        // Some pads report the d-pad as a hat instead of key codes.
        hatX = hat(event.getAxisValue(MotionEvent.AXIS_HAT_X), hatX, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        hatY = hat(event.getAxisValue(MotionEvent.AXIS_HAT_Y), hatY, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    }

    /** Releases everything held (focus lost): no stuck keys when the game comes back. */
    fun releaseAll() {
        for (code in held.toList()) if (code >= 0) key(code, false) else button(-1 - code, false)
        lookX = 0f
        lookY = 0f
        hatX = 0
        hatY = 0
    }

    private fun key(glfwKey: Int, down: Boolean) {
        if (down == (glfwKey in held)) return
        if (down) held += glfwKey else held -= glfwKey
        GameBridge.key(glfwKey, if (down) GameBridge.PRESS else GameBridge.RELEASE)
    }

    private fun button(button: Int, down: Boolean) {
        val id = -1 - button
        if (down == (id in held)) return
        if (down) held += id else held -= id
        GameBridge.mouseButton(button, if (down) GameBridge.PRESS else GameBridge.RELEASE)
    }

    // Hysteresis so a stick resting near the threshold doesn't chatter.
    private fun stickKey(glfwKey: Int, value: Float, positive: Boolean) {
        val v = if (positive) value else -value
        if (v > PRESS_AT) key(glfwKey, true) else if (v < RELEASE_AT) key(glfwKey, false)
    }

    private fun trigger(button: Int, value: Float) {
        if (value > PRESS_AT) button(button, true) else if (value < RELEASE_AT) button(button, false)
    }

    // A release lets go of both meanings: a menu may have opened or closed while it was held.
    private fun dpad(keyCode: Int, down: Boolean) {
        val arrow = GlfwKeys.fromAndroid(keyCode) ?: return
        val inGame = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> GlfwKeys.F5
            KeyEvent.KEYCODE_DPAD_DOWN -> GlfwKeys.F
            else -> null // left/right: the hotbar
        }
        when {
            !down -> {
                key(arrow, false)
                inGame?.let { key(it, false) }
            }
            inMenu() -> key(arrow, true)
            inGame != null -> key(inGame, true)
            else -> GameBridge.scroll(0.0, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) 1.0 else -1.0)
        }
    }

    /** A hat axis as d-pad presses: one press and one release per direction it moves to. Returns the new direction. */
    private fun hat(value: Float, was: Int, negativeKey: Int, positiveKey: Int): Int {
        val now = if (value < -0.5f) -1 else if (value > 0.5f) 1 else 0
        if (now != was) {
            if (was != 0) dpad(if (was < 0) negativeKey else positiveKey, false)
            if (now != 0) dpad(if (now < 0) negativeKey else positiveKey, true)
        }
        return now
    }

    private fun deadzone(v: Float) = if (abs(v) < DEADZONE) 0f else v

    private fun curve(v: Float) = v * abs(v)

    private companion object {
        const val LEFT_BUTTON = 0
        const val RIGHT_BUTTON = 1
        const val DEADZONE = 0.15f
        const val PRESS_AT = 0.5f
        const val RELEASE_AT = 0.35f
        const val LOOK_SPEED = 1400f // pixels per second at full tilt
    }
}

/**
 * Android's soft keyboard typing into the game (shown when the game turns GLFW_IME on): committed text becomes
 * characters, deletes become Backspace. Enter and other keys come as key events through dispatchKeyEvent, like a
 * hardware keyboard's, so nothing arrives twice.
 */
internal class SoftKeyboard(view: View) : BaseInputConnection(view, false) {
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        editable?.clear() // any composing text this replaces
        text.toString().codePoints().forEach { GameBridge.char(it) }
        return true
    }

    // A keyboard that composes anyway: what it composed counts as typed.
    override fun finishComposingText(): Boolean = commitText(editable?.toString().orEmpty(), 1)

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) {
            GameBridge.key(GlfwKeys.BACKSPACE, GameBridge.PRESS)
            GameBridge.key(GlfwKeys.BACKSPACE, GameBridge.RELEASE)
        }
        return true
    }
}
