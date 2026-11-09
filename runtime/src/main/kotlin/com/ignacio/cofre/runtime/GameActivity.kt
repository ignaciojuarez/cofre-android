package com.ignacio.cofre.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
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

        /** Starts the game described by the JSON [spec] file (see [LaunchSpec.write]). */
        fun intent(context: Context, spec: File): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_SPEC, spec.absolutePath)

        private var started = false // one JVM per process
        private var activity: GameActivity? = null
    }

    private lateinit var view: SurfaceView
    private lateinit var nativeDir: File
    private var gamepad: GamepadMapper? = null
    private var wantCapture = false
    private var hasSurface = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity = this
        nativeDir = GameBridge.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        view = SurfaceView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            holder.addCallback(this@GameActivity)
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
        val spec = LaunchSpec.read(File(path))
        if (spec.builtInController) gamepad = GamepadMapper()
        thread(name = "cofre-requests", isDaemon = true) { serveRequests() }
        start(spec)
    }

    override fun onDestroy() {
        if (activity === this) activity = null
        super.onDestroy()
    }

    private fun start(spec: LaunchSpec) {
        val nativeDir = nativeDir.path
        val shim = File(filesDir, "runtimes/cofre-glfw.jar")
        shim.parentFile?.mkdirs()
        assets.open("runtime/cofre-glfw.jar").use { input -> shim.outputStream().use { input.copyTo(it) } }
        File(spec.gameDir).mkdirs()

        val env = mapOf(
            "JAVA_HOME" to spec.javaHome,
            "HOME" to spec.gameDir,
            "TMPDIR" to cacheDir.path,
            "LD_LIBRARY_PATH" to "${spec.javaHome}/lib/server:${spec.javaHome}/lib:$nativeDir",
        ) + spec.env
        env.forEach { (key, value) -> Os.setenv(key, value, true) }

        val refreshRate = if (Build.VERSION.SDK_INT >= 30) {
            display.refreshRate
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        }
        val args = buildList {
            add("java")
            addAll(spec.jvmArgs)
            add("-Djava.home=${spec.javaHome}")
            add("-Djava.io.tmpdir=${cacheDir.path}")
            add("-Duser.home=${spec.gameDir}")
            add("-Djdk.lang.Process.launchMechanism=FORK") // posix_spawn needs jspawnhelper, which can't run here
            add("-Dorg.lwjgl.librarypath=$nativeDir")
            add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
            add("-Dorg.lwjgl.system.allocator=system")
            add("-Dcofre.refreshRate=${refreshRate.roundToInt()}")
            add("-cp")
            add((listOf(shim.path) + spec.classpath).joinToString(":"))
            add(spec.mainClass)
            addAll(spec.gameArgs)
        }
        Log.i(TAG, "[Cofre] launching ${spec.mainClass} java.home=${spec.javaHome}")
        val result = GameBridge.launch(args.toTypedArray(), spec.javaHome, spec.gameDir, spec.logFile, spec.exitFile)
        check(result == 0) { "JVM thread didn't start ($result)" }
    }

    /** Requests from the game's threads, which must not call into ART themselves. */
    private fun serveRequests() {
        while (true) {
            when (GameBridge.nextRequest()) {
                GameBridge.REQ_CAPTURE -> runOnUi { it.setCapture(true) }
                GameBridge.REQ_RELEASE -> runOnUi { it.setCapture(false) }
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

    // ---- Surface and lifecycle -----------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        GameBridge.setSurface(holder.surface, width, height)
        GameBridge.windowSize(width, height)
        if (!hasSurface) {
            hasSurface = true
            GameBridge.iconify(false)
            GameBridge.focus(true)
        }
        Log.i(TAG, "[Cofre] surface ${width}x$height")
    }

    // Sleep, or the app going to the background. The game sees an iconified, unfocused window and
    // its next glfwCreateWindowSurface waits for the new surface; it has to rebuild its swapchain.
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hasSurface = false
        gamepad?.releaseAll()
        GameBridge.iconify(true)
        GameBridge.focus(false)
        GameBridge.setSurface(null, 0, 0)
        Log.i(TAG, "[Cofre] surface destroyed")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            if (wantCapture) view.requestPointerCapture()
        } else {
            gamepad?.releaseAll()
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
        gamepad?.let { if (it.handles(event) && it.onKey(event)) return true }
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
        gamepad?.let {
            if (it.handles(event)) {
                it.onMotion(event)
                return true
            }
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

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Touch: a pointer in menus (tap = left click); a look-around drag while the cursor is captured.
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
                lastTouchX = event.x
                lastTouchY = event.y
                if (!wantCapture) {
                    GameBridge.cursorTo(event.x.toDouble(), event.y.toDouble())
                    GameBridge.mouseButton(0, GameBridge.PRESS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (wantCapture) {
                    GameBridge.cursorBy((event.x - lastTouchX).toDouble(), (event.y - lastTouchY).toDouble())
                } else {
                    GameBridge.cursorTo(event.x.toDouble(), event.y.toDouble())
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!wantCapture) GameBridge.mouseButton(0, GameBridge.RELEASE)
        }
        return true
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
