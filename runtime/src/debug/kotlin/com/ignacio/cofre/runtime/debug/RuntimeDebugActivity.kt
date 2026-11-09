package com.ignacio.cofre.runtime.debug

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.ignacio.cofre.runtime.ExitRecord
import com.ignacio.cofre.runtime.GameActivity
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.runtime.LaunchSpec
import com.ignacio.cofre.runtime.VulkanSupport
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: runs the runtime end to end without the launcher UI.
 *
 *     adb shell am start -n com.ignacio.cofre/com.ignacio.cofre.runtime.debug.RuntimeDebugActivity \
 *         [--es main hello|vulkan] [--es args "exit=3"] [--ez reinstall true] [--ez checkOnly true]
 *
 * Logs (tag Cofre): the Vulkan check, the JRE install, the game's own output, and its exit record.
 */
class RuntimeDebugActivity : Activity() {
    private lateinit var text: TextView
    private var exitFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(48, 48, 48, 48) }
        setContentView(text)
        if (savedInstanceState != null) return
        val main = when (intent.getStringExtra("main") ?: "hello") {
            "vulkan" -> "com.ignacio.cofre.probe.VulkanProbe"
            else -> "com.ignacio.cofre.probe.Hello"
        }
        val args = intent.getStringExtra("args")?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
        val reinstall = intent.getBooleanExtra("reinstall", false)
        val checkOnly = intent.getBooleanExtra("checkOnly", false)
        thread(name = "cofre-debug") { run(main, args, reinstall, checkOnly) }
    }

    override fun onResume() {
        super.onResume()
        val record = exitFile?.let(ExitRecord::read) ?: return
        Log.i(TAG, "[Cofre] launcher read exit record code=${record.code} timeMillis=${record.timeMillis}")
        show("Game exited: code ${record.code}")
        exitFile = null
    }

    private fun run(main: String, args: List<String>, reinstall: Boolean, checkOnly: Boolean) {
        val report = VulkanSupport.check()
        show("Vulkan: ${report.deviceName} ${report.apiVersion}\nsuitable=${report.suitable}\nmissing=${report.missing}")
        if (checkOnly) return

        val jre = JreManager(this)
        if (reinstall) File(jre.home, ".cofre-version").delete() // looks like a half-extracted install
        val started = SystemClock.elapsedRealtime()
        val wasInstalled = jre.isInstalled()
        val home = jre.install()
        Log.i(TAG, "[Cofre] jre ready home=$home version=${jre.installedVersion()} " +
            (if (wasInstalled) "already installed" else "installed in ${SystemClock.elapsedRealtime() - started} ms"))

        val runtimeDir = File(filesDir, "runtimes")
        val classpath = (listOf("cofre-probe.jar") + (assets.list("runtime/lwjgl")?.map { "lwjgl/$it" } ?: emptyList())).map { name ->
            File(runtimeDir, name).also { file ->
                file.parentFile?.mkdirs()
                assets.open("runtime/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            }.path
        }
        val dir = File(filesDir, "debug-game").apply { mkdirs() }
        val exit = File(dir, "exit.json").also { it.delete() }
        val spec = LaunchSpec(
            javaHome = home.path,
            classpath = classpath,
            jvmArgs = listOf("-Xms64m", "-Xmx256m"),
            mainClass = main,
            gameArgs = args,
            gameDir = dir.path,
            logFile = File(dir, "latest.log").path,
            exitFile = exit.path,
        )
        val specFile = File(dir, "launch.json").also(spec::write)
        runOnUiThread {
            exitFile = exit
            startActivity(GameActivity.intent(this, specFile))
        }
    }

    private fun show(message: String) = runOnUiThread { text.append(message + "\n\n") }

    private companion object {
        const val TAG = "Cofre"
    }
}
