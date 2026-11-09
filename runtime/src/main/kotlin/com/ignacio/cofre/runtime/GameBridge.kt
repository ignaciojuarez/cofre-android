package com.ignacio.cofre.runtime

import android.content.Context
import android.view.Surface
import java.io.File
import java.util.zip.ZipFile

/**
 * ART's side of libcofre.so in the :game process. Events pushed here are what the GLFW shim's
 * glfwPollEvents delivers to the game. Types and fields match GLFW.java (EV_*) and cofre.c.
 */
internal object GameBridge {
    /**
     * Our .so files as plain files, and libcofre loaded from there. HotSpot and LWJGL load
     * libraries by path, but the APK keeps them stored inside itself (no extractNativeLibs), so
     * they're copied out once per APK version. ART and HotSpot must load the same file.
     */
    fun load(context: Context): File {
        val info = context.applicationInfo
        val installed = File(info.nativeLibraryDir)
        val dir = if (File(installed, "libcofre.so").isFile) installed else File(context.filesDir, "runtimes/natives").also { dir ->
            val apk = File(info.sourceDir)
            val stamp = File(dir, ".apk")
            val id = "${apk.length()}-${apk.lastModified()}"
            if (stamp.isFile && stamp.readText() == id) return@also
            dir.listFiles()?.forEach { it.setWritable(true) }
            dir.deleteRecursively()
            dir.mkdirs()
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.startsWith("lib/arm64-v8a/") || !entry.name.endsWith(".so")) continue
                    val so = File(dir, entry.name.substringAfterLast('/'))
                    so.outputStream().use { zip.getInputStream(entry).copyTo(it) }
                    so.setReadOnly() // ART refuses to load writable code (Android 14+)
                }
            }
            stamp.writeText(id)
        }
        System.load(File(dir, "libcofre.so").path)
        return dir
    }

    const val EV_KEY = 1
    const val EV_CHAR = 2
    const val EV_MOUSE_BUTTON = 3
    const val EV_CURSOR_POS = 4
    const val EV_CURSOR_MOVE = 5
    const val EV_SCROLL = 6
    const val EV_WINDOW_SIZE = 7
    const val EV_FOCUS = 8
    const val EV_ICONIFY = 9

    const val REQ_CAPTURE = 1
    const val REQ_RELEASE = 2
    const val REQ_EXIT = 3

    // GLFW values used on this side.
    const val RELEASE = 0
    const val PRESS = 1
    const val REPEAT = 2

    fun key(key: Int, action: Int, scancode: Int = 0, mods: Int = 0) = pushEvent(EV_KEY, key, scancode, action, mods, 0.0, 0.0)
    fun char(codepoint: Int, mods: Int = 0) = pushEvent(EV_CHAR, codepoint, mods, 0, 0, 0.0, 0.0)
    fun mouseButton(button: Int, action: Int, mods: Int = 0) = pushEvent(EV_MOUSE_BUTTON, button, action, mods, 0, 0.0, 0.0)
    fun cursorTo(x: Double, y: Double) = pushEvent(EV_CURSOR_POS, 0, 0, 0, 0, x, y)
    fun cursorBy(dx: Double, dy: Double) = pushEvent(EV_CURSOR_MOVE, 0, 0, 0, 0, dx, dy)
    fun scroll(dx: Double, dy: Double) = pushEvent(EV_SCROLL, 0, 0, 0, 0, dx, dy)
    fun windowSize(width: Int, height: Int) = pushEvent(EV_WINDOW_SIZE, width, height, 0, 0, 0.0, 0.0)
    fun focus(focused: Boolean) = pushEvent(EV_FOCUS, if (focused) 1 else 0, 0, 0, 0, 0.0, 0.0)
    fun iconify(iconified: Boolean) = pushEvent(EV_ICONIFY, if (iconified) 1 else 0, 0, 0, 0, 0.0, 0.0)

    @JvmStatic external fun pushEvent(type: Int, i0: Int, i1: Int, i2: Int, i3: Int, d0: Double, d1: Double)

    /** The SurfaceView's surface, or null once it's destroyed. Never used with EGL. */
    @JvmStatic external fun setSurface(surface: Surface?, width: Int, height: Int)

    /** Starts the JVM on its own thread: `args` is a full java command line starting with "java". */
    @JvmStatic external fun launch(args: Array<String>, javaHome: String, gameDir: String, logFile: String, exitFile: String): Int

    /** Blocks until the game asks the activity for something (REQ_*). */
    @JvmStatic external fun nextRequest(): Int

    /** Tells the exiting JVM the activity has finished, so the process can end. */
    @JvmStatic external fun exitAck()
}
