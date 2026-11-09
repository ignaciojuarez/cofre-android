package com.ignacio.cofre.runtime.debug

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.runtime.ExitRecord
import com.ignacio.cofre.runtime.GameActivity
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.runtime.LaunchSpec
import com.ignacio.cofre.runtime.TAG
import com.ignacio.cofre.runtime.VulkanSupport
import com.ignacio.cofre.runtime.launch.GameLaunch
import com.ignacio.cofre.runtime.launch.NeoForgeInstaller
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Debug builds only: runs the runtime end to end without the launcher UI.
 *
 *     adb shell am start -n com.ignacio.cofre/com.ignacio.cofre.runtime.debug.RuntimeDebugActivity \
 *         [--es action install|vanilla|neoforge]
 *         [--es main hello|vulkan] [--es args "exit=3"] [--ez reinstall true] [--ez checkOnly true]
 *
 * `action` installs NeoForge 26.2 or launches vanilla/NeoForge 26.2 with [DevAccount]; `main`
 * runs a probe. Logs (tag Cofre): progress, the game's output, its exit record and the diagnosis.
 */
class RuntimeDebugActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var status: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val launcher by lazy { GameLaunch(this) }
    private var exitFile: File? = null
    private var gameSpec: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)
        text = TextView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(button("Install NeoForge 26.2") { action("install") })
            addView(button("Launch vanilla 26.2") { action("vanilla") })
            addView(button("Launch NeoForge 26.2") { action("neoforge") })
            addView(status)
            addView(text)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        scope.launch {
            launcher.files.progress.collectLatest { p ->
                status.text = if (p.totalFiles > 0) "${p.step}: ${p.files}/${p.totalFiles} files, ${p.bytes shr 20}/${p.totalBytes shr 20} MB" else p.step
            }
        }
        if (savedInstanceState != null) return
        intent.getStringExtra("action")?.let { return action(it) }
        val main = when (intent.getStringExtra("main") ?: return) {
            "vulkan" -> "com.ignacio.cofre.probe.VulkanProbe"
            else -> "com.ignacio.cofre.probe.Hello"
        }
        val args = intent.getStringExtra("args")?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
        val reinstall = intent.getBooleanExtra("reinstall", false)
        val checkOnly = intent.getBooleanExtra("checkOnly", false)
        thread(name = "cofre-debug") { probe(main, args, reinstall, checkOnly) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        gameSpec?.let { spec ->
            gameSpec = null
            thread(name = "cofre-exit") {
                val exit = launcher.exit(spec)
                show("Game exited: code ${exit.record?.code}\n${exit.summary}")
            }
        }
        val record = exitFile?.let(ExitRecord::read) ?: return
        Log.i(TAG, "[Cofre] launcher read exit record code=${record.code} timeMillis=${record.timeMillis}")
        show("Game exited: code ${record.code}")
        exitFile = null
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun action(name: String) {
        scope.launch(Dispatchers.IO) {
            try {
                when (name) {
                    "install" -> {
                        val started = SystemClock.elapsedRealtime()
                        val id = NeoForgeInstaller(this@RuntimeDebugActivity, launcher.files).install()
                        show("Installed $id in ${(SystemClock.elapsedRealtime() - started) / 1000} s")
                    }
                    "vanilla" -> play(Target.GAME_VERSION, "debug-vanilla")
                    "neoforge" -> play(NeoForgeInstaller(this@RuntimeDebugActivity, launcher.files).install(), "debug-neoforge")
                    else -> show("Unknown action $name")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Cofre] debug $name failed", e)
                show("$name failed: $e")
            }
        }
    }

    private suspend fun play(versionId: String, instance: String) {
        val spec = launcher.prepare(versionId, File(filesDir, "instances/$instance"), DevAccount.account)
        show("Launching $versionId")
        runOnUiThread {
            gameSpec = spec
            startActivity(GameActivity.intent(this, spec, DevAccount.TOKEN))
        }
    }

    private fun probe(main: String, args: List<String>, reinstall: Boolean, checkOnly: Boolean) {
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
}
