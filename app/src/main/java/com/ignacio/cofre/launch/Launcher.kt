package com.ignacio.cofre.launch

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.BuildConfig
import com.ignacio.cofre.R
import com.ignacio.cofre.accounts.Accounts
import com.ignacio.cofre.design.LogLevel
import com.ignacio.cofre.design.LogLine
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.kit.logs.LogStore
import com.ignacio.cofre.kit.logs.ReportBundle
import com.ignacio.cofre.kit.logs.ReportContext
import com.ignacio.cofre.kit.logs.ReportInstance
import com.ignacio.cofre.kit.options.JavaHeap
import com.ignacio.cofre.onIo
import com.ignacio.cofre.runtime.ExitRecord
import com.ignacio.cofre.runtime.GameActivity
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.runtime.LaunchSpec
import com.ignacio.cofre.scratch
import com.ignacio.cofre.share
import com.ignacio.cofre.ui.setup.plainVulkan
import com.ignacio.cofre.work
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Play: the launch stages with their log, then the game in the `:game` process, then — when it stops — the
 * "game stopped" report. The game can outlive Cofre, so the run in progress is a small file, read on resume.
 */
class Launcher(private val graph: AppGraph) {
    enum class Stage(@param:StringRes val title: Int) {
        Device(R.string.stage_device),
        Account(R.string.stage_account),
        Java(R.string.stage_java),
        Files(R.string.stage_files),
        Loader(R.string.stage_loader),
        Start(R.string.stage_start),
        InGame(R.string.stage_in_game),
    }

    /** Join a world or server straight from the launcher. */
    @Serializable
    data class QuickPlay(val args: List<String>, val label: String)

    data class Run(
        val instance: Instance,
        val quickPlay: QuickPlay?,
        val stage: Stage = Stage.Device,
        val fraction: Float? = null,
        val error: String? = null,
        val log: List<LogLine> = emptyList(),
        val done: Boolean = false,
    )

    data class Stopped(
        val report: GameExitReport,
        val instance: Instance,
        /** The installed mod the report names, when it can be turned off. */
        val mod: ContentStore.Item? = null,
        val modOff: Boolean = false,
        val quickPlay: QuickPlay? = null,
    )

    @Serializable
    private data class Pending(
        val instance: String,
        val exitFile: String,
        val logFile: String,
        val startedAt: Long,
        val quickPlay: QuickPlay? = null,
    )

    var run by mutableStateOf<Run?>(null)
        private set
    var stopped by mutableStateOf<Stopped?>(null)
        private set
    private var job: Job? = null
    private val pendingFile get() = File(graph.home, "launch/pending.json")

    /** True while a game started by Cofre is still running: worlds and servers are read-only then. */
    val gameRunning: Boolean get() = pendingFile.isFile && gameProcessAlive()

    fun play(instance: Instance, quickPlay: QuickPlay? = null) {
        if (job?.isActive == true) return
        graph.library.select(instance.id)
        run = Run(instance, quickPlay)
        job = graph.work(::fail) { launch(instance, quickPlay) }
    }

    fun retry() {
        val last = run ?: return
        play(last.instance, last.quickPlay)
    }

    /** Cancel during the stages, or Close after a failure. */
    fun cancel() {
        job?.cancel()
        job = null
        run = null
    }

    private suspend fun launch(instance: Instance, quickPlay: QuickPlay?) {
        log(graph.context.getString(R.string.log_launch_start, instance.name))
        stage(Stage.Device)
        val vulkan = graph.onIo { graph.vulkan }
        if (!vulkan.suitable) error(graph.context.getString(R.string.launch_vulkan_unsuitable, vulkan.missing.joinToString { plainVulkan(graph.context.resources, it) }))
        log("Vulkan ${vulkan.apiVersion} · ${vulkan.deviceName}", LogLevel.Success)
        stage(Stage.Account)
        val session = graph.accounts.session()
        log(graph.context.getString(R.string.log_account_ready))
        stage(Stage.Java)
        val jre = JreManager(graph.context)
        if (jre.bundledVersion() == null) error(graph.context.getString(R.string.launch_no_java))
        val javaHome = graph.onIo { jre.install() }
        log("Java ${Target.JAVA_MAJOR}", LogLevel.Success)
        stage(Stage.Files)
        val spec = prepareGame(instance, session, javaHome, quickPlay)
        stage(Stage.Start)
        val specData = graph.onIo { LaunchSpec.read(spec) }
        graph.onIo {
            File(specData.exitFile).delete()
            pendingFile.parentFile?.mkdirs()
            pendingFile.writeText(Kit.json.encodeToString(Pending(instance.id, specData.exitFile, specData.logFile, System.currentTimeMillis(), quickPlay)))
            graph.instances.markPlayed(instance.id)
        }
        graph.context.startActivity(GameActivity.intent(graph.context, spec).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        stage(Stage.InGame)
        run = run?.copy(done = true)
        graph.library.refresh()
    }

    /**
     * Game files, NeoForge and the launch arguments → a [LaunchSpec] file. This is the one place Play reaches the
     * runtime's launch builder (`com.ignacio.cofre.runtime.launch`); until it lands, Play stops here and says so.
     */
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    private suspend fun prepareGame(instance: Instance, session: Accounts.Session, javaHome: File, quickPlay: QuickPlay?): File {
        val heap = heapMB(instance)
        log(graph.context.getString(R.string.log_heap, heap))
        error(graph.context.getString(R.string.launch_not_wired))
    }

    /** The instance's own heap, else the launcher default, else the RAM tier. */
    fun heapMB(instance: Instance): Int = when {
        !instance.autoMemory && instance.memoryMB != null -> instance.memoryMB!!
        !graph.settings.value.autoMemory -> graph.settings.value.memoryMB
        else -> JavaHeap.autoMB(graph.physicalMB)
    }

    /** Back from the game (or Cofre restarted while it ran): show why it stopped, unless the player quit. */
    fun onResume() {
        if (!pendingFile.isFile || gameProcessAlive()) return
        graph.work({}) {
            val pending = graph.onIo { runCatching { Kit.json.decodeFromString<Pending>(pendingFile.readText()) }.getOrNull().also { pendingFile.delete() } }
                ?: return@work
            run = null
            stopped = graph.onIo { diagnose(pending) }
            graph.library.refresh()
        }
    }

    private fun diagnose(pending: Pending): Stopped? {
        val instance = runCatching { graph.instances.get(pending.instance) }.getOrNull() ?: return null
        val dir = graph.instances.directory(instance)
        // No exit record: the process was killed (low memory, swiped away) before it could write one.
        val code = ExitRecord.read(File(pending.exitFile))?.code ?: 137
        val log = File(pending.logFile).takeIf { it.isFile }?.readText()?.takeLast(2_000_000)
        val crash = LogStore(dir, graph.launcherLog).newestCrashReport(Instant.ofEpochMilli(pending.startedAt))?.readText()
        val report = GameExitReport.diagnose(code, crash, log, instance.name)
        if (report.reason == ExitReason.Quit) {
            graph.instances.markLastGood(instance.id)
            return null
        }
        return Stopped(report, instance, modFor(report, dir), quickPlay = pending.quickPlay)
    }

    private fun modFor(report: GameExitReport, dir: File): ContentStore.Item? {
        val name = report.mod ?: return null
        return runCatching { ContentStore(dir).list(ContentKind.Mods) }.getOrDefault(emptyList())
            .firstOrNull { it.enabled && (it.name.equals(name, ignoreCase = true) || it.modID.equals(name, ignoreCase = true)) }
    }

    /** "Turn off <mod>": disables the jar the crash named. */
    fun turnOffMod() {
        val current = stopped ?: return
        val mod = current.mod ?: return
        graph.work({}) {
            graph.onIo { ContentStore(graph.instances.directory(current.instance)).setEnabled(false, mod) }
            stopped = current.copy(modOff = true)
        }
    }

    fun playAgain() {
        val current = stopped ?: return
        stopped = null
        play(current.instance, current.quickPlay)
    }

    fun dismissStopped() {
        stopped = null
    }

    /** A redacted report zip (logs, crash reports, device) in the share sheet. */
    fun shareReport() {
        val current = stopped ?: return
        graph.work({}) {
            val file = graph.onIo {
                val dir = graph.instances.directory(current.instance)
                val context = ReportContext(
                    appVersion = BuildConfig.VERSION_NAME,
                    build = BuildConfig.VERSION_CODE.toString(),
                    device = Build.MODEL,
                    android = Build.VERSION.RELEASE,
                    instance = ReportInstance(current.instance.gameVersion, "NeoForge ${current.instance.loaderVersion.orEmpty()}".trim(), ramMB = graph.physicalMB),
                    exitReason = current.report.reason.name,
                    clue = current.report.clue,
                )
                ReportBundle(LogStore(dir, graph.launcherLog), context).make(graph.scratch("cofre-report.zip"))
            }
            graph.share(file, "application/zip")
        }
    }

    /** Debug builds: show the stopped screen for a sample report, to check it without a crash. */
    fun showSample(report: GameExitReport, instance: Instance) {
        check(BuildConfig.DEBUG)
        stopped = Stopped(report, instance)
    }

    private fun stage(stage: Stage) {
        run = run?.copy(stage = stage, fraction = null)
    }

    private fun fail(message: String) {
        log(message, LogLevel.Error)
        run = run?.copy(error = message)
    }

    private fun log(text: String, level: LogLevel = LogLevel.Info) {
        val line = LogLine(LocalTime.now().format(TIME), "Cofre", text, level)
        run = run?.copy(log = run?.log.orEmpty() + line)
        runCatching {
            graph.launcherLog.parentFile?.mkdirs()
            graph.launcherLog.appendText("${line.time} [${level.name}] ${ReportBundle.redact(text)}\n")
        }
    }

    private fun gameProcessAlive(): Boolean =
        graph.context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
            .any { it.processName == "${graph.context.packageName}:game" }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
