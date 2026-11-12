package com.ignacio.cofre.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.net.Inet6Address
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Runs the game in the `:game` process: a full-screen SurfaceView whose window goes to Vulkan
 * through the GLFW shim, input forwarded to the shim, and a JVM started from a [LaunchSpec].
 * When the JVM ends, the process writes an [ExitRecord] and exits; the launcher's activity is
 * underneath and reads it.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        private const val EXTRA_SPEC = "com.ignacio.cofre.runtime.SPEC"
        private const val EXTRA_TOKEN = "com.ignacio.cofre.runtime.TOKEN"

        /** Starts the game described by the JSON [spec] file (see [LaunchSpec.write]); [accessToken] replaces [LaunchSpec.TOKEN] in memory only. */
        fun intent(context: Context, spec: File, accessToken: String? = null): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_SPEC, spec.absolutePath).putExtra(EXTRA_TOKEN, accessToken)

        // The game's state, per process: an activity recreated in place (theme or bold-text change,
        // "Don't keep activities") finds the same JVM.
        private var started = false // one JVM per process
        private var wantCapture = false // the game captured the cursor (mouse-look)
        private var activity: GameActivity? = null
    }

    private lateinit var view: SurfaceView
    private lateinit var nativeDir: File
    private val gamepad = GamepadMapper(inMenu = { !wantCapture })
    private var hasSurface = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** A newer instance owns the game's window (this one is stopped behind it, or finishing): leave the window alone. */
    private val replaced get() = activity != null && activity !== this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restored in a new process: Android killed the game while it was away. Starting it again would reuse an old
        // launch and token; the launcher underneath explains the stop instead.
        if (savedInstanceState != null && !started) {
            finish()
            return
        }
        activity = this
        nativeDir = GameBridge.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Notched phones: the game goes under the cutout instead of being letterboxed beside it.
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        // The soft keyboard shows only when the game asks (REQ_IME_SHOW), over the game rather than resizing its surface.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        view = object : SurfaceView(this) {
            override fun onCheckIsTextEditor() = true

            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                // Plain text with no suggestions (there's no text field here to show them against), and not the
                // full-screen editor keyboards use in landscape.
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                return SoftKeyboard(this)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            holder.addCallback(this@GameActivity)
            if (Build.VERSION.SDK_INT >= 34) {
                // Minecraft makes its Vulkan surface once. The default lifecycle destroys the surface on every stop
                // (Home, sleep), which the game can't recover from; this keeps it until the activity goes.
                setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            }
            // Look-drags start at the screen's edges too: keep the back gesture off the middle 200 dp of each side
            // (Android's limit per edge).
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val band = (200 * resources.displayMetrics.density).roundToInt()
                val top = (v.height - band) / 2
                ViewCompat.setSystemGestureExclusionRects(v, listOf(Rect(0, top, v.width, top + band)))
            }
            setOnTouchListener { _, e -> onTouch(e) }
            setOnGenericMotionListener { _, e -> onGenericMotion(e) }
            setOnCapturedPointerListener { _, e -> onCapturedPointer(e) }
        }
        setContentView(view)
        view.requestFocus()
        hideSystemBars()
        if (Build.VERSION.SDK_INT >= 33) {
            // Gesture back (no key event) → Escape, like the Back key below.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { tapEscape() }
        }

        val path = intent.getStringExtra(EXTRA_SPEC)
        if (started || path == null) return
        started = true
        val spec = LaunchSpec.read(File(path)).let { s -> intent.getStringExtra(EXTRA_TOKEN)?.let(s::withToken) ?: s }
        thread(name = "cofre-requests", isDaemon = true) { serveRequests() }
        start(spec)
    }

    override fun onDestroy() {
        if (activity === this) activity = null
        super.onDestroy()
    }

    private fun start(spec: LaunchSpec) {
        val shim = File(filesDir, "runtimes/cofre-glfw.jar")
        shim.parentFile?.mkdirs()
        assets.open("runtime/cofre-glfw.jar").use { input -> shim.outputStream().use { input.copyTo(it) } }
        val extra = listOf("-Dcofre.refreshRate=${refreshRate().roundToInt()}", "-Duser.timezone=${TimeZone.getDefault().id}") + resolvConf()
        GameBridge.start(this, spec, nativeDir, classpathFirst = listOf(shim.path), extraJvmArgs = extra)
    }

    private fun refreshRate(): Float = if (Build.VERSION.SDK_INT >= 30) {
        display.refreshRate
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.refreshRate
    }

    /**
     * The JDK's DNS client, which Minecraft's server (SRV) lookups use, reads resolv.conf, and Android has none: write
     * this network's servers for it (our JDK's `ext.net.resolvPath`). Without them, "play.example.net" falls back to port 25565.
     */
    private fun resolvConf(): List<String> {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val servers = connectivity.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty().sortedBy { it is Inet6Address }
        if (servers.isEmpty()) return emptyList()
        val file = File(cacheDir, "resolv.conf")
        file.writeText(servers.joinToString("") { "nameserver ${it.hostAddress}\n" })
        return listOf("-Dext.net.resolvPath=${file.path}")
    }

    /** Requests from the game's threads, which must not call into ART themselves. */
    private fun serveRequests() {
        while (true) {
            when (GameBridge.nextRequest()) {
                GameBridge.REQ_CAPTURE -> runOnUi { it.setCapture(true) }
                GameBridge.REQ_RELEASE -> runOnUi { it.setCapture(false) }
                GameBridge.REQ_IME_SHOW -> runOnUi { it.showKeyboard(true) }
                GameBridge.REQ_IME_HIDE -> runOnUi { it.showKeyboard(false) }
                GameBridge.REQ_EXIT -> {
                    runOnUi { it.finish() }
                    Thread.sleep(150) // let the finish reach the system before the process goes
                    GameBridge.exitAck()
                    return
                }
            }
        }
    }

    private fun runOnUi(block: (GameActivity) -> Unit) {
        val current = activity ?: return
        current.runOnUiThread { activity?.let(block) }
    }

    private fun setCapture(capture: Boolean) {
        wantCapture = capture
        if (capture && hasWindowFocus()) view.requestPointerCapture() else if (!capture) view.releasePointerCapture()
    }

    private fun showKeyboard(show: Boolean) {
        val input = getSystemService(InputMethodManager::class.java)
        if (show) input.showSoftInput(view, 0) else input.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ---- Surface and lifecycle -----------------------------------------------------------------

    // Home or sleep. The surface, and the game's swapchain on it, stay (FOLLOWS_ATTACHMENT). Iconified alone, Minecraft
    // still draws at 10 fps; a 0×0 window (minimized) makes it stop acquiring images until the activity is back.
    override fun onStop() {
        super.onStop()
        if (replaced) return
        gamepad.releaseAll()
        GameBridge.iconify(true)
        GameBridge.focus(false)
        GameBridge.windowSize(0, 0)
    }

    // Also the first start: the window isn't iconified while it waits for its first surface. Focus follows the
    // window's (onWindowFocusChanged).
    override fun onStart() {
        super.onStart()
        if (replaced) return
        GameBridge.iconify(false)
        if (hasSurface) GameBridge.windowSize(surfaceWidth, surfaceHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (replaced) return
        surfaceWidth = width
        surfaceHeight = height
        GameBridge.setSurface(holder.surface, width, height)
        GameBridge.windowSize(width, height)
        if (!hasSurface) {
            hasSurface = true
            GameBridge.focus(hasWindowFocus())
            // Vote for the display's own rate, not whatever the launcher left it at.
            if (Build.VERSION.SDK_INT >= 30) holder.surface.setFrameRate(refreshRate(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        Log.i(TAG, "[Cofre] surface ${width}x$height")
    }

    // The surface is gone for good: the activity went (or, before Android 14, it stopped). Minecraft can't make a new
    // Vulkan surface, so its picture doesn't come back; Home and sleep are onStop.
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (replaced) return
        hasSurface = false
        GameBridge.setSurface(null, 0, 0)
        Log.i(TAG, "[Cofre] surface destroyed")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (replaced) return
        if (hasFocus) {
            hideSystemBars()
            if (wantCapture) view.requestPointerCapture()
        } else {
            gamepad.releaseAll()
        }
        if (hasSurface) GameBridge.focus(hasFocus)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---- Input ---------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepad.handles(event) && gamepad.onKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) tapEscape()
            return true
        }
        val key = GlfwKeys.fromAndroid(event.keyCode) ?: return super.dispatchKeyEvent(event)
        val mods = GlfwKeys.mods(event.metaState)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                GameBridge.key(key, if (event.repeatCount > 0) GameBridge.REPEAT else GameBridge.PRESS, event.scanCode, mods)
                val codepoint = event.getUnicodeChar(event.metaState)
                if (codepoint >= 0x20 && codepoint != 0x7f) GameBridge.char(codepoint, mods) // < 0: dead key
            }
            KeyEvent.ACTION_UP -> GameBridge.key(key, GameBridge.RELEASE, event.scanCode, mods)
        }
        return true
    }

    private fun tapEscape() {
        GameBridge.key(GlfwKeys.ESCAPE, GameBridge.PRESS)
        GameBridge.key(GlfwKeys.ESCAPE, GameBridge.RELEASE)
    }

    private fun onGenericMotion(event: MotionEvent): Boolean {
        if (gamepad.handles(event)) {
            gamepad.onMotion(event)
            return true
        }
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> GameBridge.cursorTo(event.x.toDouble(), event.y.toDouble())
            MotionEvent.ACTION_SCROLL -> scroll(event)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> mouseButton(event)
            else -> return false
        }
        return true
    }

    private var touchPointer = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Touch: a pointer in menus (tap = left click); a look-around drag while the cursor is captured. One finger drives
    // it, followed by its id: a second finger landing or lifting would otherwise make the view jump.
    private fun onTouch(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> GameBridge.cursorTo(event.x.toDouble(), event.y.toDouble())
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> mouseButton(event)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                follow(event, 0)
                if (!wantCapture) {
                    GameBridge.cursorTo(event.x.toDouble(), event.y.toDouble())
                    GameBridge.mouseButton(0, GameBridge.PRESS)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == touchPointer) follow(event, if (event.actionIndex == 0) 1 else 0)
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(touchPointer)
                if (i < 0) return true
                val x = event.getX(i)
                val y = event.getY(i)
                if (wantCapture) {
                    GameBridge.cursorBy((x - lastTouchX).toDouble(), (y - lastTouchY).toDouble())
                } else {
                    GameBridge.cursorTo(x.toDouble(), y.toDouble())
                }
                lastTouchX = x
                lastTouchY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!wantCapture) GameBridge.mouseButton(0, GameBridge.RELEASE)
        }
        return true
    }

    private fun follow(event: MotionEvent, index: Int) {
        touchPointer = event.getPointerId(index)
        lastTouchX = event.getX(index)
        lastTouchY = event.getY(index)
    }

    // Mouse while captured (mouse-look): relative motion.
    private fun onCapturedPointer(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> for (i in 0..event.historySize) {
                val dx = if (i < event.historySize) event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i) else event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val dy = if (i < event.historySize) event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i) else event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                GameBridge.cursorBy(dx.toDouble(), dy.toDouble())
            }
            MotionEvent.ACTION_SCROLL -> scroll(event)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> mouseButton(event)
        }
        return true
    }

    private fun scroll(event: MotionEvent) =
        GameBridge.scroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(), event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble())

    private fun mouseButton(event: MotionEvent) {
        val button = when (event.actionButton) {
            MotionEvent.BUTTON_PRIMARY -> 0
            MotionEvent.BUTTON_SECONDARY -> 1
            MotionEvent.BUTTON_TERTIARY -> 2
            MotionEvent.BUTTON_BACK -> 3
            MotionEvent.BUTTON_FORWARD -> 4
            else -> return
        }
        val action = if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) GameBridge.PRESS else GameBridge.RELEASE
        GameBridge.mouseButton(button, action, GlfwKeys.mods(event.metaState))
    }
}
