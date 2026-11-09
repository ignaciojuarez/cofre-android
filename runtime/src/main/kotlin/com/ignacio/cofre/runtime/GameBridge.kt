package com.ignacio.cofre.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import android.view.Surface
import com.ignacio.cofre.kit.modpacks.FileOps
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
            FileOps.delete(dir)
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

    /**
     * Starts [spec]'s JVM on its own thread in this process (:game or :installer), with the
     * device-specific pieces added: java.home/tmpdir, our native folder as the library path for
     * LWJGL, JNA and System.loadLibrary, and [classpathFirst] ahead of the spec's classpath.
     */
    fun start(context: Context, spec: LaunchSpec, nativeDir: File, classpathFirst: List<String> = emptyList(), extraJvmArgs: List<String> = emptyList()) {
        File(spec.gameDir).mkdirs()
        File(spec.logFile).parentFile?.mkdirs()
        val env = mapOf(
            "JAVA_HOME" to spec.javaHome,
            "HOME" to spec.gameDir,
            "TMPDIR" to context.cacheDir.path,
            "LD_LIBRARY_PATH" to "${spec.javaHome}/lib/server:${spec.javaHome}/lib:$nativeDir",
        ) + spec.env
        env.forEach { (key, value) -> Os.setenv(key, value, true) }
        val args = buildList {
            add("java")
            addAll(spec.jvmArgs)
            addAll(extraJvmArgs)
            add("-Djava.home=${spec.javaHome}")
            add("-Djava.io.tmpdir=${context.cacheDir.path}")
            add("-Duser.home=${spec.gameDir}")
            add("-Djava.library.path=$nativeDir")
            add("-Djna.boot.library.path=$nativeDir")
            add("-Djdk.lang.Process.launchMechanism=FORK") // posix_spawn needs jspawnhelper, which can't run here
            add("-Dorg.lwjgl.librarypath=$nativeDir")
            add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
            add("-Dorg.lwjgl.system.allocator=system")
            // LWJGL compares each loaded .so's SHA-1 with Mojang's glibc Linux build (META-INF/linux/arm64/…sha1).
            // Our natives are built from the same 3.4.1 source for Android, so their bytes can never match.
            // fetch.sh checks them against the release's SHA256SUMS instead.
            add("-Dorg.lwjgl.util.NoHashChecks=true")
            // Minecraft 26.2 loads LWJGL's OpenGL bindings at startup whatever the backend. Android has no GLX or
            // libGL, so point LWJGL at the system EGL loader: symbols only, no context or surface is ever created.
            add("-Dorg.lwjgl.opengl.contextAPI=EGL")
            add("-Dorg.lwjgl.egl.libname=libEGL.so")
            add("-cp")
            add((classpathFirst + spec.classpath).joinToString(":"))
            add(spec.mainClass)
            addAll(spec.gameArgs)
        }
        Log.i(TAG, "[Cofre] launching ${spec.mainClass} java.home=${spec.javaHome}")
        val result = launch(args.toTypedArray(), spec.javaHome, spec.gameDir, spec.logFile, spec.exitFile)
        check(result == 0) { "JVM thread didn't start ($result)" }
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
