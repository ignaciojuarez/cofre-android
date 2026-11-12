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
import com.ignacio.cofre.kit.common.FileOps
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
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.modfolders.ModFolderLink
import com.ignacio.cofre.kit.mods.ModInstaller
import com.ignacio.cofre.kit.mods.ModJarMetadata
import com.ignacio.cofre.kit.mods.ModRequirement
import com.ignacio.cofre.kit.mods.ModRequirements
import com.ignacio.cofre.kit.mods.ModUpdates
import com.ignacio.cofre.kit.mods.Snapshots
import com.ignacio.cofre.kit.mods.apply
import com.ignacio.cofre.kit.mods.check
import com.ignacio.cofre.kit.options.JavaHeap
import com.ignacio.cofre.kit.options.JvmArguments
import com.ignacio.cofre.onIo
import com.ignacio.cofre.runtime.GameActivity
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.runtime.VulkanReport
import com.ignacio.cofre.scratch
import com.ignacio.cofre.share
import com.ignacio.cofre.ui.play.FolderUpdates
import com.ignacio.cofre.ui.setup.plainVulkan
import com.ignacio.cofre.work
import com.ignacio.cofre.runtime.launch.GameAccount
import com.ignacio.cofre.runtime.launch.GameLaunch
import com.ignacio.cofre.runtime.launch.NeoForgeInstaller
import kotlinx.coroutines.CancellationException
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
        Updates(R.string.stage_updates),
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
        /** A before-update snapshot newer than the last clean run: the crash may be the update's. */
        val rollback: Snapshots.Snapshot? = null,
        /** The mods that changed since [rollback] ("FerriteCore 9.0.1"). */
        val updatedMods: List<String> = emptyList(),
        val rollingBack: Boolean = false,
        val rolledBack: Boolean = false,
    )

    /** Whether the selected account can start the game. */
    enum class AccountState { Ready, SignedOut, None }

    /**
     * "Will it launch?": one check that Home's Ready tile, "Before you play" and Play all read. Blockers (account,
     * Vulkan, the mod folder) stop the launch in its stages; mod problems open "Before you play" first. Java ships in
     * the app and is unpacked by the launch itself, so it's not a step the player can act on.
     */
    data class ReadyCheck(
        val instance: Instance,
        val account: AccountState,
        val accountName: String?,
        val vulkan: VulkanReport,
        /** True when a debug build was told to run past an unsuitable device. */
        val vulkanSkipped: Boolean,
        val loaderVersion: String,
        val loaderInstalled: Boolean,
        /** Minecraft itself (client, libraries, assets) is installed. */
        val gameInstalled: Boolean,
        /** Why the instance's mod folder can't play, or null. */
        val folderProblem: String?,
        val missing: List<ModRequirement>,
        /** "Controlify needs yet_another_config_lib_v3", one per requirement. */
        val lines: List<String>,
        /** Enabled mods (or the active shader pack) a rule calls broken, or whose jar calls OpenGL directly. */
        val issues: List<CompatibilityIssue>,
    ) {
        val vulkanOk: Boolean get() = vulkan.suitable || vulkanSkipped
        val modProblems: Boolean get() = missing.isNotEmpty() || issues.isNotEmpty()
        /** What stops the launch outright. */
        val blockers: Int get() = listOf(account != AccountState.Ready, !vulkanOk, folderProblem != null).count { it }
        val toFix: Int get() = blockers + missing.size + issues.size
        /** Roughly what the first Play downloads, in MB; 0 when it's all installed. An estimate: the real lists come with the install. */
        val firstPlayMB: Int get() = (if (gameInstalled) 0 else GAME_MB) + (if (loaderInstalled) 0 else LOADER_MB)
    }

    /** "Before you play": installed mods require mods that aren't installed, or are known or likely to crash on Vulkan. */
    data class Preflight(
        val check: ReadyCheck,
        val quickPlay: QuickPlay?,
        val gettingMods: Boolean = false,
        val turningOff: Boolean = false,
        val error: String? = null,
    ) {
        val instance: Instance get() = check.instance
        val missing: List<ModRequirement> get() = check.missing
        val lines: List<String> get() = check.lines
        val issues: List<CompatibilityIssue> get() = check.issues
        val busy: Boolean get() = gettingMods || turningOff
    }

    /** How the last run of an instance went, for Home's Last session tile. */
    @Serializable
    data class Session(
        val instance: String,
        val startedAt: Long,
        val endedAt: Long,
        /** [ExitReason] name. */
        val reason: String,
        val clue: String? = null,
        /** The mod the crash named. */
        val mod: String? = null,
        val quickPlay: QuickPlay? = null,
        /** The last server joined from Cofre, kept across sessions. */
        val lastServer: QuickPlay? = null,
    ) {
        val exit: ExitReason get() = ExitReason.entries.firstOrNull { it.name == reason } ?: ExitReason.Unknown
        val crashed: Boolean get() = exit != ExitReason.Quit && exit != ExitReason.Updated
    }

    @Serializable
    private data class Pending(val instance: String, val spec: String, val quickPlay: QuickPlay? = null, val startedAt: Long = 0)

    /** A launch that got everything ready while Cofre was in the background, where Android won't let it start the game. */
    private class Ready(val instance: Instance, val spec: File, val token: String, val quickPlay: QuickPlay?)

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
    private var ready: Ready? = null
    /** Between MainActivity's onResume and onPause: only then may Cofre start the game's activity. */
    private var resumed = false
    private val pendingFile get() = File(graph.home, "launch/pending.json")

    /** True while a game started by Cofre is still running: worlds and servers are read-only then. */
    val gameRunning: Boolean get() = pendingFile.isFile && gameProcessAlive()

    /**
     * Updates the mod folder first when it's set to update on its own, then checks the mods: a missing requirement, or
     * a mod that will or likely will crash on Vulkan, opens "Before you play" ("May not work" doesn't) unless
     * [checked] is false ("Play anyway"). Jars the Installed tab hasn't graded yet are scanned here, which takes a while
     * on a big new pack, so the launch screen shows these as its first stages.
     */
    fun play(instance: Instance, quickPlay: QuickPlay? = null, checked: Boolean = true) {
        if (job?.isActive == true || preflight != null || refusedWhileRunning(instance, quickPlay)) return
        graph.library.select(instance.id)
        run = Run(instance, quickPlay, stage = Stage.Updates)
        job = track(graph.work({ start(instance, quickPlay) }) {
            autoUpdate(instance)
            stage(Stage.Mods)
            if (!checked) return@work start(instance, quickPlay)
            val ready = check(instance)
            if (!ready.modProblems) return@work start(instance, quickPlay)
            run = null
            preflight = Preflight(ready, quickPlay)
        })
    }

    /** The whole ready check for [instance], off the main thread. Jars not graded yet are scanned (cached per jar). */
    suspend fun check(instance: Instance): ReadyCheck {
        val current = graph.onIo { runCatching { graph.instances.get(instance.id) }.getOrDefault(instance) }
        val folderProblem = graph.onIo { folderProblem(current) }
        val dir = graph.instances.directory(current)
        val content = if (folderProblem == null) graph.onIo { graph.contentDirectory(current) } else null
        val missing = content?.let { graph.onIo { ModRequirements.missing(it) } }.orEmpty()
        val issues = content?.let { graph.onIo { ContentCompatibility.playIssues(dir, graph.glScans, it) } }.orEmpty()
            .filter { it.severity == "broken" || it.severity == "likely" }
        val lines = graph.onIo {
            missing.map { req ->
                val by = req.neededBy?.let { ModJarMetadata.read(File(content, "mods/$it"))?.name ?: it } ?: return@map req.name
                graph.context.getString(R.string.mods_needs, by, req.name)
            }
        }
        val entry = graph.accounts.selected
        val account = when {
            entry == null -> AccountState.None
            entry.signedOut -> AccountState.SignedOut
            else -> AccountState.Ready
        }
        val loader = current.loaderVersion ?: NeoForgeInstaller.VERSION
        return graph.onIo {
            val files = GameLaunch(graph.context).files
            ReadyCheck(
                current, account, entry?.account?.name, graph.vulkan, vulkanSkipped = skipVulkanCheck,
                loaderVersion = loader, loaderInstalled = NeoForgeInstaller.installed(files, loader),
                gameInstalled = files.versionJson(Target.GAME_VERSION).isFile && File(files.assets, "indexes").list().orEmpty().isNotEmpty(),
                folderProblem = folderProblem, missing = missing, lines = lines, issues = issues,
            )
        }
    }

    /** Turns off [issues]' mods in [instance]'s folder (a ready-check fix). */
    suspend fun turnOff(instance: Instance, issues: List<CompatibilityIssue>) {
        val dir = graph.instances.directory(instance)
        val content = graph.onIo { graph.contentDirectory(instance) }
        InstanceStore.lock(content).withLock { graph.onIo { ContentCompatibility.turnOff(issues, dir, content) } }
        graph.library.refresh()
    }

    private fun start(instance: Instance, quickPlay: QuickPlay?) {
        if (refusedWhileRunning(instance, quickPlay)) return
        run = Run(instance, quickPlay)
        job = track(graph.work(::fail) { launch(instance, quickPlay) })
    }

    /** [LaunchService] runs while a run's jobs do (one hands over to the next), so leaving Cofre doesn't freeze the download. */
    private fun track(work: Job): Job {
        LaunchService.start(graph.context)
        work.invokeOnCompletion { if (job?.isActive != true) LaunchService.stop(graph.context) }
        return work
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
            turnOff(current.instance, current.issues)
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

    /** Installs [missing] requirements into [instance]'s folder through the mod installer, then checks they're there. */
    suspend fun getMissing(instance: Instance, missing: List<ModRequirement>) {
        val dir = graph.instances.directory(instance)
        val content = graph.onIo { graph.contentDirectory(instance) }
        ModInstaller(content, dir, modrinth = graph.modrinth).installMissing(missing)
        val left = graph.onIo { ModRequirements.missing(content) }.filter { req -> missing.any { it.modId == req.modId } }
        check(left.isEmpty()) { graph.context.getString(R.string.mods_still_missing, left.joinToString { it.name }) }
        graph.library.refresh()
    }

    /** Cancel during the stages, or Close after a failure. */
    fun cancel() {
        job?.cancel()
        job = null
        ready = null
        run = null
    }

    private suspend fun launch(instance: Instance, quickPlay: QuickPlay?) {
        log(graph.context.getString(R.string.log_launch_start, instance.name))
        graph.onIo { folderProblem(graph.instances.get(instance.id)) }?.let { error(it) }
        stage(Stage.Device)
        val vulkan = graph.onIo { graph.vulkan }
        if (!vulkan.suitable && !skipVulkanCheck) error(graph.context.getString(R.string.launch_vulkan_unsuitable, vulkan.missing.joinToString { plainVulkan(graph.context.resources, it) }))
        log("Vulkan ${vulkan.apiVersion} · ${vulkan.deviceName}", LogLevel.Success)
        // What Android reports, for sizing the heap tiers against real devices.
        log(graph.context.getString(R.string.log_device_memory, graph.physicalMB))
        stage(Stage.Account)
        val session = graph.accounts.session()
        log(graph.context.getString(R.string.log_account_ready))
        stage(Stage.Java)
        // Unpacked by GameLaunch.prepare (or the NeoForge install) when it's missing.
        if (JreManager(graph.context).bundledVersion() == null) error(graph.context.getString(R.string.launch_no_java))
        log("Java ${Target.JAVA_MAJOR}", LogLevel.Success)
        val spec = prepareGame(instance, session, quickPlay)
        stage(Stage.Start)
        ready = Ready(instance, spec, session.accessToken, quickPlay)
        // Android 10+ silently drops an activity start from the background: that one waits for onResume.
        if (resumed) startGame() else log(graph.context.getString(R.string.log_start_on_return))
    }

    /** Starts the [ready] launch's game. Pending is written only now, so a launch that never started isn't diagnosed. */
    private suspend fun startGame() {
        val current = ready ?: return
        ready = null
        graph.onIo {
            pendingFile.parentFile?.mkdirs()
            pendingFile.writeText(Kit.json.encodeToString(Pending(current.instance.id, current.spec.path, current.quickPlay, System.currentTimeMillis())))
            graph.instances.markPlayed(current.instance.id)
        }
        graph.context.startActivity(GameActivity.intent(graph.context, current.spec, current.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        stage(Stage.InGame)
        run = run?.copy(done = true)
        graph.library.refresh()
    }

    /** Why [instance] can't play with its mod folder (gone, not for this instance, or can't play yet); null when it can. Blocking. */
    private fun folderProblem(instance: Instance): String? {
        if (instance.modFolder == null) return null
        val folder = graph.folderOf(instance) ?: return graph.context.getString(R.string.folder_missing)
        if (!folder.playable) return graph.context.getString(R.string.launch_folder_unplayable, folder.name, folder.loader.displayName, folder.gameVersion)
        if (!folder.fits(instance)) {
            return graph.context.getString(R.string.launch_folder_mismatch, folder.name, folder.loader.displayName, folder.gameVersion, instance.loader.displayName, instance.gameVersion)
        }
        return null
    }

    /**
     * The Updates stage: a folder set to update on its own gets its updates (after a before-update snapshot) before the
     * mods are checked. Never stops the launch: offline, on metered data or when an update fails, it plays as it is.
     */
    private suspend fun autoUpdate(instance: Instance) {
        val current = graph.onIo { runCatching { graph.instances.get(instance.id) }.getOrNull() } ?: return
        val folder = graph.onIo { graph.folderOf(current) }?.takeIf { it.autoUpdate == AutoUpdate.Auto && it.playable && it.fits(current) } ?: return
        if (graph.downloadsModel.metered()) return log(graph.context.getString(R.string.log_updates_metered), LogLevel.Warning)
        try {
            val dir = graph.onIo { graph.modFolders.directory(folder) }
            val updates = ModUpdates.check(dir, graph.modrinth, folder.gameVersion, folder.loader, folder.includeBeta)
            if (updates.isEmpty()) return log(graph.context.getString(R.string.log_updates_none))
            val result = ModUpdates.apply(dir, updates, ModInstaller(dir, modrinth = graph.modrinth)) { p ->
                if (p.totalBytes > 0) run = run?.copy(fraction = (p.bytes.toFloat() / p.totalBytes).coerceIn(0f, 1f))
            }
            FolderUpdates.forget(graph, folder.id)
            result.updated.forEach { log(graph.context.getString(R.string.log_updated, it.name, it.target.version), LogLevel.Success) }
            result.failed.forEach { log(graph.context.getString(R.string.log_update_failed, it.update.name, it.reason), LogLevel.Warning) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(graph.context.getString(R.string.log_updates_failed, e.message.orEmpty()), LogLevel.Warning)
        }
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
            val jvm = JvmArguments.split(instance.jvmArguments)
            val file = InstanceStore.lock(dir).withLock {
                withContext(graph.io) {
                    // Right before every launch: the game reads mods through these links.
                    val current = graph.instances.get(instance.id)
                    ModFolderLink.link(dir, current.modFolder?.let { graph.contentDirectory(current) })
                    game.prepare(version, dir, account, heap, jvm, quickPlay?.args.orEmpty())
                }
            }
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
        resumed = true
        if (ready != null) {
            graph.work(::fail) { startGame() }
            return
        }
        if (!pendingFile.isFile || watching?.isActive == true) return
        watching = graph.work({}) {
            while (gameProcessAlive()) delay(500)
            val pending = graph.onIo { runCatching { Kit.json.decodeFromString<Pending>(pendingFile.readText()) }.getOrNull().also { pendingFile.delete() } }
                ?: return@work
            run = null
            stopped = diagnose(pending)
            graph.library.refresh()
        }
    }

    fun onPause() {
        resumed = false
    }

    /**
     * Why the game stopped, recorded as the instance's last session. A clean quit marks the instance's settings and its
     * folder's mods (a last-good snapshot) as working; a crash after an update offers to roll the mods back.
     */
    private suspend fun diagnose(pending: Pending): Stopped? {
        val instance = graph.onIo { runCatching { graph.instances.get(pending.instance) }.getOrNull() } ?: return null
        val dir = graph.instances.directory(instance)
        val report = graph.onIo { GameLaunch(graph.context).exit(File(pending.spec)).report.copy(instance = instance.name) }
        val content = graph.onIo { runCatching { graph.contentDirectory(instance) }.getOrNull() }
        graph.onIo { runCatching { record(instance, pending, report) } }
        if (report.reason == ExitReason.Quit) {
            // A clean run: these memory and JVM settings become the ones "Restore last working" goes back to.
            graph.onIo { graph.instances.markLastGood(instance.id) }
            if (content != null) runCatching { Snapshots.take(content, Snapshots.Reason.LastGood) }
            return null
        }
        val missing = report.missingDependencies.map { ModRequirements.requirement(it.modId, "dependency:${it.modId}", it.requestedBy) }
        val rollback = content?.let { runCatching { rollbackFor(it) }.getOrNull() }
        return Stopped(
            report, instance, content?.let { graph.onIo { modFor(report, dir, it) } }, quickPlay = pending.quickPlay, missing = missing,
            rollback = rollback?.first, updatedMods = rollback?.second.orEmpty(),
        )
    }

    /** The newest before-update snapshot taken after the last clean run, with the mods changed since; null if there's none. */
    private suspend fun rollbackFor(content: File): Pair<Snapshots.Snapshot, List<String>>? {
        val snapshots = graph.onIo { Snapshots.list(content) }
        val good = snapshots.firstOrNull { it.reason == Snapshots.Reason.LastGood }
        val before = snapshots.firstOrNull { it.reason == Snapshots.Reason.BeforeUpdate }?.takeIf { good == null || it.createdAt > good.createdAt } ?: return null
        val changes = Snapshots.changedSince(content, before)
        if (changes.count == 0) return null
        val names = graph.onIo {
            (changes.changed + changes.added).map { jar ->
                ModJarMetadata.read(File(content, "mods/$jar"))?.let { listOfNotNull(it.name, it.version).joinToString(" ") } ?: jar
            }
        }
        return before to names
    }

    /** Game stopped → "Roll back to <date> snapshot": the mods as they were before the update, the rolled-back ones pinned. */
    fun rollBack() {
        val current = stopped?.takeIf { it.rollback != null && !it.rollingBack && !it.rolledBack } ?: return
        val snapshot = current.rollback ?: return
        stopped = current.copy(rollingBack = true, fixError = null)
        graph.work({ stopped = current.copy(fixError = it) }) {
            val content = graph.onIo { graph.contentDirectory(current.instance) }
            Snapshots.restore(content, snapshot, pin = true)
            current.instance.modFolder?.let { FolderUpdates.forget(graph, it) }
            stopped = current.copy(rolledBack = true)
            graph.library.refresh()
        }
    }

    private fun sessionFile(instanceId: String): File = File(graph.home, "launch/sessions/$instanceId.json")

    /** How [instance]'s last run went, or null before its first. Blocking. */
    fun lastSession(instance: Instance): Session? =
        runCatching { Kit.json.decodeFromString<Session>(sessionFile(instance.id).readText()) }.getOrNull()

    private fun record(instance: Instance, pending: Pending, report: GameExitReport) {
        val log = File(graph.instances.directory(instance), "logs/latest.log")
        val ended = log.lastModified().takeIf { log.isFile && it > pending.startedAt } ?: System.currentTimeMillis()
        val server = pending.quickPlay?.takeIf { it.args.firstOrNull() == "--quickPlayMultiplayer" } ?: lastSession(instance)?.lastServer
        val session = Session(instance.id, pending.startedAt, ended, report.reason.name, report.clue, report.mod, pending.quickPlay, server)
        FileOps.atomic(sessionFile(instance.id).apply { parentFile?.mkdirs() }, Kit.json.encodeToString(session).toByteArray())
    }

    private fun modFor(report: GameExitReport, dir: File, content: File): ContentStore.Item? {
        val name = report.mod ?: return null
        return runCatching { ContentStore(dir, content).list(ContentKind.Mods) }.getOrDefault(emptyList())
            .firstOrNull { it.enabled && (it.name.equals(name, ignoreCase = true) || it.modID.equals(name, ignoreCase = true)) }
    }

    /** "Turn off <mod>": disables the jar the crash named. */
    fun turnOffMod() {
        val current = stopped ?: return
        val mod = current.mod ?: return
        graph.work({}) {
            val dir = graph.instances.directory(current.instance)
            val content = graph.onIo { graph.contentDirectory(current.instance) }
            InstanceStore.lock(content).withLock { graph.onIo { ContentStore(dir, content).setEnabled(false, mod) } }
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
        /** Rough first-install sizes for the ready check: Minecraft 26.2 with its assets, then NeoForge's libraries. */
        const val GAME_MB = 560
        const val LOADER_MB = 90
    }
}
