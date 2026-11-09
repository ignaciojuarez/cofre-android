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
import com.ignacio.cofre.kit.compat.CompatibilityIssue
import com.ignacio.cofre.kit.compat.ContentCompatibility
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.kit.logs.LogStore
import com.ignacio.cofre.kit.logs.ReportBundle
import com.ignacio.cofre.kit.logs.ReportContext
import com.ignacio.cofre.kit.logs.ReportInstance
import com.ignacio.cofre.kit.mods.ModInstaller
import com.ignacio.cofre.kit.mods.ModJarMetadata
import com.ignacio.cofre.kit.mods.ModRequirement
import com.ignacio.cofre.kit.mods.ModRequirements
import com.ignacio.cofre.kit.options.JavaHeap
import com.ignacio.cofre.onIo
import com.ignacio.cofre.runtime.GameActivity
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.scratch
import com.ignacio.cofre.share
import com.ignacio.cofre.ui.setup.plainVulkan
import com.ignacio.cofre.work
import com.ignacio.cofre.runtime.launch.GameAccount
import com.ignacio.cofre.runtime.launch.GameLaunch
import com.ignacio.cofre.runtime.launch.NeoForgeInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Play: the launch stages with their log, then the game in the `:game` process, then — when it stops — the
 * "game stopped" report. The game can outlive Cofre, so the run in progress is a small file, read on resume.
 */
class Launcher(private val graph: AppGraph) {
    enum class Stage(@param:StringRes val title: Int) {
        Mods(R.string.stage_mods),
        Device(R.string.stage_device),
        Account(R.string.stage_account),
        Java(R.string.stage_java),
        Loader(R.string.stage_loader),
        Files(R.string.stage_files),
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
        /** "Get missing mods": what the crash said is missing, then progress and outcome. */
        val missing: List<ModRequirement> = emptyList(),
        val gettingMods: Boolean = false,
        val modsGot: Boolean = false,
        val fixError: String? = null,
    )

    /** "Before you play": installed mods require mods that aren't installed, or are known or likely to crash on Vulkan. */
    data class Preflight(
        val instance: Instance,
        val quickPlay: QuickPlay?,
        val missing: List<ModRequirement>,
        /** "Controlify needs yet_another_config_lib_v3", one per requirement. */
        val lines: List<String>,
        /** Enabled mods (or the active shader pack) a rule calls broken, or whose jar calls OpenGL directly. */
        val issues: List<CompatibilityIssue> = emptyList(),
        val gettingMods: Boolean = false,
        val turningOff: Boolean = false,
        val error: String? = null,
    ) {
        val busy: Boolean get() = gettingMods || turningOff
    }

    @Serializable
    private data class Pending(val instance: String, val spec: String, val quickPlay: QuickPlay? = null)

    var run by mutableStateOf<Run?>(null)
        private set

    /** Debug builds (`--ez cofre.skipVulkanCheck true`): run the pipeline on a device that fails the check. */
    var skipVulkanCheck = false
        set(value) {
            check(!value || BuildConfig.DEBUG)
            field = value
        }
    var stopped by mutableStateOf<Stopped?>(null)
        private set
    var preflight by mutableStateOf<Preflight?>(null)
        private set
    private var job: Job? = null
    private var watching: Job? = null
    private val pendingFile get() = File(graph.home, "launch/pending.json")

    /** True while a game started by Cofre is still running: worlds and servers are read-only then. */
    val gameRunning: Boolean get() = pendingFile.isFile && gameProcessAlive()

    /**
     * Checks the instance's mods first: a missing requirement, or a mod that will or likely will crash on Vulkan, opens
     * "Before you play". "May not work" doesn't. Jars the Installed tab hasn't graded yet are scanned here, which takes
     * a while on a big new pack, so the launch screen shows this as its first stage.
     */
    fun play(instance: Instance, quickPlay: QuickPlay? = null) {
        if (job?.isActive == true || preflight != null || refusedWhileRunning(instance, quickPlay)) return
        graph.library.select(instance.id)
        run = Run(instance, quickPlay, stage = Stage.Mods)
        job = graph.work({ start(instance, quickPlay) }) {
            val dir = graph.instances.directory(instance)
            val mods = File(dir, "mods")
            val missing = graph.onIo { ModRequirements.missing(dir) }
            val issues = graph.onIo { ContentCompatibility.playIssues(dir, graph.glScans) }.filter { it.severity == "broken" || it.severity == "likely" }
            if (missing.isEmpty() && issues.isEmpty()) return@work start(instance, quickPlay)
            val lines = graph.onIo {
                missing.map { req ->
                    val by = req.neededBy?.let { ModJarMetadata.read(File(mods, it))?.name ?: it } ?: return@map req.name
                    graph.context.getString(R.string.mods_needs, by, req.name)
                }
            }
            run = null
            preflight = Preflight(instance, quickPlay, missing, lines, issues)
        }
    }

    private fun start(instance: Instance, quickPlay: QuickPlay?) {
        if (refusedWhileRunning(instance, quickPlay)) return
        run = Run(instance, quickPlay)
        job = graph.work(::fail) { launch(instance, quickPlay) }
    }

    /**
     * Every Play path asks here first: while the game Cofre started still runs (say, Cofre reopened for a pack), a
     * second launch would rewrite its live launch files and start another game in the same process.
     */
    private fun refusedWhileRunning(instance: Instance, quickPlay: QuickPlay?): Boolean {
        if (!gameRunning) return false
        run = Run(instance, quickPlay, error = graph.context.getString(R.string.error_game_running))
        return true
    }

    fun retry() {
        val last = run ?: return
        start(last.instance, last.quickPlay)
    }

    fun playAnyway() {
        val current = preflight ?: return
        preflight = null
        start(current.instance, current.quickPlay)
    }

    fun dismissPreflight() {
        if (preflight?.busy != true) preflight = null
    }

    /** "Before you play" → "Turn off these mods", then check again: turning one off can leave another without its library. */
    fun turnOffPreflightMods() {
        val current = preflight?.takeIf { !it.busy && it.issues.isNotEmpty() } ?: return
        preflight = current.copy(turningOff = true, error = null)
        graph.work({ preflight = current.copy(error = it) }) {
            val dir = graph.instances.directory(current.instance)
            InstanceStore.lock(dir).withLock { graph.onIo { ContentCompatibility.turnOff(current.issues, dir) } }
            preflight = null
            play(current.instance, current.quickPlay)
        }
    }

    /** "Before you play" → "Get missing mods", then check again (the new mods are checked for Vulkan too). */
    fun getPreflightMods() {
        val current = preflight?.takeIf { !it.busy && it.missing.isNotEmpty() } ?: return
        preflight = current.copy(gettingMods = true, error = null)
        graph.work({ preflight = current.copy(error = it) }) {
            getMissing(current.instance, current.missing)
            preflight = null
            play(current.instance, current.quickPlay)
        }
    }

    /** Game stopped → "Get missing mods". */
    fun getStoppedMods() {
        val current = stopped?.takeIf { it.missing.isNotEmpty() && !it.gettingMods } ?: return
        stopped = current.copy(gettingMods = true, fixError = null)
        graph.work({ stopped = current.copy(fixError = it) }) {
            getMissing(current.instance, current.missing)
            stopped = current.copy(modsGot = true)
        }
    }

    private suspend fun getMissing(instance: Instance, missing: List<ModRequirement>) {
        val dir = graph.instances.directory(instance)
        ModInstaller(dir, modrinth = graph.modrinth).installMissing(missing)
        val left = graph.onIo { ModRequirements.missing(dir) }.filter { req -> missing.any { it.modId == req.modId } }
        check(left.isEmpty()) { graph.context.getString(R.string.mods_still_missing, left.joinToString { it.name }) }
        graph.library.refresh()
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
        if (!vulkan.suitable && !skipVulkanCheck) error(graph.context.getString(R.string.launch_vulkan_unsuitable, vulkan.missing.joinToString { plainVulkan(graph.context.resources, it) }))
        log("Vulkan ${vulkan.apiVersion} · ${vulkan.deviceName}", LogLevel.Success)
        stage(Stage.Account)
        val session = graph.accounts.session()
        log(graph.context.getString(R.string.log_account_ready))
        stage(Stage.Java)
        // Unpacked by GameLaunch.prepare (or the NeoForge install) when it's missing.
        if (JreManager(graph.context).bundledVersion() == null) error(graph.context.getString(R.string.launch_no_java))
        log("Java ${Target.JAVA_MAJOR}", LogLevel.Success)
        val spec = prepareGame(instance, session, quickPlay)
        stage(Stage.Start)
        graph.onIo {
            pendingFile.parentFile?.mkdirs()
            pendingFile.writeText(Kit.json.encodeToString(Pending(instance.id, spec.path, quickPlay)))
            graph.instances.markPlayed(instance.id)
        }
        graph.context.startActivity(GameActivity.intent(graph.context, spec, session.accessToken).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        stage(Stage.InGame)
        run = run?.copy(done = true)
        graph.library.refresh()
    }

    /** NeoForge, then the runtime's launch spec (game files and Java installed on the way) with this instance's heap, JVM arguments and quick play. */
    private suspend fun prepareGame(instance: Instance, session: Accounts.Session, quickPlay: QuickPlay?): File = coroutineScope {
        val game = GameLaunch(graph.context)
        val progress = launch(graph.io) {
            game.files.progress.collect { p ->
                if (p.totalBytes > 0) run = run?.copy(fraction = (p.bytes.toFloat() / p.totalBytes).coerceIn(0f, 1f))
            }
        }
        try {
            stage(Stage.Loader)
            val loader = instance.loaderVersion ?: NeoForgeInstaller.VERSION
            val version = withContext(graph.io) { NeoForgeInstaller(graph.context, game.files).install(loader) }
            log("NeoForge $loader", LogLevel.Success)
            stage(Stage.Files)
            val dir = graph.instances.directory(instance)
            val account = GameAccount(session.name, session.uuid)
            val heap = heapMB(instance)
            val jvm = instance.jvmArguments.split(Regex("\\s+")).filter { it.isNotBlank() }
            val file = InstanceStore.lock(dir).withLock { withContext(graph.io) { game.prepare(version, dir, account, heap, jvm, quickPlay?.args.orEmpty()) } }
            log(graph.context.getString(R.string.log_files_ready), LogLevel.Success)
            log(graph.context.getString(R.string.log_heap, heap))
            file
        } finally {
            progress.cancel()
        }
    }

    /** The instance's own heap, else the launcher default, else the RAM tier. */
    fun heapMB(instance: Instance): Int = when {
        !instance.autoMemory && instance.memoryMB != null -> instance.memoryMB!!
        !graph.settings.value.autoMemory -> graph.settings.value.memoryMB
        else -> JavaHeap.autoMB(graph.physicalMB)
    }

    /**
     * Back from the game (or Cofre restarted while it ran): once the `:game` process is gone, show why it stopped,
     * unless the player quit. Cofre often resumes a moment before that process dies, so this waits for it.
     */
    fun onResume() {
        if (!pendingFile.isFile || watching?.isActive == true) return
        watching = graph.work({}) {
            while (gameProcessAlive()) delay(500)
            val pending = graph.onIo { runCatching { Kit.json.decodeFromString<Pending>(pendingFile.readText()) }.getOrNull().also { pendingFile.delete() } }
                ?: return@work
            run = null
            stopped = graph.onIo { diagnose(pending) }
            graph.library.refresh()
        }
    }

    private fun diagnose(pending: Pending): Stopped? {
        val instance = runCatching { graph.instances.get(pending.instance) }.getOrNull() ?: return null
        val exit = GameLaunch(graph.context).exit(File(pending.spec))
        val report = exit.report.copy(instance = instance.name)
        if (report.reason == ExitReason.Quit) {
            // A clean run: these memory and JVM settings become the ones "Restore last working" goes back to.
            graph.instances.markLastGood(instance.id)
            return null
        }
        val missing = report.missingDependencies.map { ModRequirements.requirement(it.modId, "dependency:${it.modId}", it.requestedBy) }
        return Stopped(report, instance, modFor(report, graph.instances.directory(instance)), quickPlay = pending.quickPlay, missing = missing)
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
            val dir = graph.instances.directory(current.instance)
            InstanceStore.lock(dir).withLock { graph.onIo { ContentStore(dir).setEnabled(false, mod) } }
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
            graph.launcherLog.appendText("${line.time} [${level.name}] $text\n")
        }
    }

    private fun gameProcessAlive(): Boolean =
        graph.context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
            .any { it.processName == "${graph.context.packageName}:game" }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
