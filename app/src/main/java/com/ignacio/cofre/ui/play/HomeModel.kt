package com.ignacio.cofre.ui.play

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.compat.CompatibilityIssue
import com.ignacio.cofre.kit.content.ContentStore
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.logs.LogFile
import com.ignacio.cofre.kit.logs.LogStore
import com.ignacio.cofre.kit.logs.ReportBundle
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.mods.ModInstaller
import com.ignacio.cofre.kit.mods.ModRequirement
import com.ignacio.cofre.kit.mods.ModUpdate
import com.ignacio.cofre.kit.mods.ModUpdates
import com.ignacio.cofre.kit.mods.apply
import com.ignacio.cofre.kit.options.GameOptions
import com.ignacio.cofre.kit.servers.MinecraftLaunchArguments
import com.ignacio.cofre.kit.worlds.World
import com.ignacio.cofre.kit.worlds.WorldStore
import com.ignacio.cofre.launch.Launcher
import com.ignacio.cofre.onIo
import com.ignacio.cofre.scratch
import com.ignacio.cofre.share
import com.ignacio.cofre.work
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Home's dashboard for one instance: the ready check, its mod folder (counts, sharing, updates, quick toggles),
 * performance options, servers, the last session and screenshots. Everything loads off the main thread when Home
 * shows the instance, and again after a change; nothing is read during composition.
 */
class HomeModel(private val graph: AppGraph, val instance: Instance) {
    private val dir: File get() = graph.instances.directory(instance)

    // Ready check

    var ready by mutableStateOf<Launcher.ReadyCheck?>(null)
        private set
    /** The fix in progress: an issue's file, a requirement's mod id, or [FIX_ALL]. */
    var fixing by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    /** True while the game Cofre started still runs: no second launch, the folder is read-only. */
    var running by mutableStateOf(false)
        private set

    // Mod folder

    data class Folder(
        val folder: ModFolder?,
        val on: Int,
        val off: Int,
        /** Other instances linked to the same folder. */
        val sharedWith: List<Instance>,
        /** Folders this instance can switch to: they fit it and can play. */
        val choices: List<ModFolder>,
        /** A few mods for quick on/off: turned-off ones first. */
        val quick: List<ContentStore.Item>,
    )

    var folder by mutableStateOf<Folder?>(null)
        private set
    var updates by mutableStateOf<FolderUpdates.Result?>(null)
        private set
    /** Files being updated, or [FIX_ALL]. */
    var updating by mutableStateOf<Set<String>>(emptySet())
        private set

    // Worlds, servers, session, screenshots

    var lastWorld by mutableStateOf<World?>(null)
        private set
    val servers = ServersModel(graph, instance)
    var session by mutableStateOf<Launcher.Session?>(null)
        private set
    var screenshots by mutableStateOf<List<File>>(emptyList())
        private set
    var logLines by mutableStateOf<List<String>?>(null)
        private set

    // Performance

    enum class Preset(val render: Int, val simulation: Int, val fps: Int, val title: Int) {
        Battery(6, 5, 30, R.string.home_perf_battery),
        Balanced(10, 8, 60, R.string.home_perf_balanced),
        Quality(16, 10, 120, R.string.home_perf_quality),
    }

    data class Performance(val render: Int, val simulation: Int, val fps: Int) {
        val preset: Preset? get() = Preset.entries.firstOrNull { it.render == render && it.simulation == simulation && it.fps == fps }
    }

    data class Device(val celsius: Float?, val batteryPercent: Int?, val charging: Boolean, val thermal: Int?)

    var performance by mutableStateOf<Performance?>(null)
        private set
    var device by mutableStateOf<Device?>(null)
        private set

    /** Loads everything; the update check uses its cache. */
    fun load() {
        refreshRunning()
        loadReady()
        loadFolder()
        loadPlaces()
        loadPerformance()
        servers.refresh()
    }

    fun refreshRunning() {
        graph.work({}) { running = graph.onIo { graph.launcher.gameRunning } }
    }

    private fun loadReady() = graph.work({ error = it }) { ready = graph.launcher.check(instance) }

    private fun loadFolder(forceUpdates: Boolean = false) = graph.work({ error = it }) {
        val current = graph.onIo { runCatching { graph.instances.get(instance.id) }.getOrDefault(instance) }
        val found = graph.onIo { graph.folderOf(current) }
        folder = graph.onIo {
            val all = graph.instances.list()
            val mods = if (found != null || current.modFolder == null) {
                runCatching { ContentStore(dir, graph.contentDirectory(current)).list(ContentKind.Mods) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            Folder(
                found, mods.count { it.enabled }, mods.count { !it.enabled },
                found?.let { f -> graph.modFolders.usedBy(f.id, all).filter { it.id != instance.id } }.orEmpty(),
                graph.modFolders.list().filter { it.fits(current) && it.playable },
                mods.sortedBy { it.enabled }.take(QUICK_TOGGLES),
            )
        }
        updates = if (found == null || found.autoUpdate == AutoUpdate.Off || !found.playable) null else FolderUpdates.get(graph, found, forceUpdates)
    }

    private fun loadPlaces() = graph.work({}) {
        lastWorld = graph.onIo { runCatching { WorldStore(dir) { false }.list().firstOrNull() }.getOrNull() }
        session = graph.onIo { graph.launcher.lastSession(instance) }
        screenshots = graph.onIo {
            File(dir, "screenshots").listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
                .sortedByDescending { it.lastModified() }
        }
    }

    private fun loadPerformance() = graph.work({}) {
        performance = graph.onIo {
            val options = GameOptions(dir)
            fun int(key: String, default: Int) = options.get(key)?.trim()?.toIntOrNull() ?: default
            Performance(int("renderDistance", DEFAULT_RENDER), int("simulationDistance", DEFAULT_SIMULATION), int("maxFps", DEFAULT_FPS))
        }
        device = readDevice()
    }

    /** Battery temperature and charge, and the system's thermal status, from Android's sticky broadcast. */
    fun readDevice(): Device {
        val battery = runCatching { graph.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { graph.context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull()
        } else {
            null
        }
        return Device(
            tenths.takeIf { it != Int.MIN_VALUE && it > 0 }?.let { it / 10f },
            if (level >= 0 && scale > 0) level * 100 / scale else null,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            thermal,
        )
    }

    // Ready fixes

    fun turnOff(issue: CompatibilityIssue) = fix(issue.file ?: issue.id) { graph.launcher.turnOff(instance, listOf(issue)) }

    fun getMod(requirement: ModRequirement) = fix(requirement.modId) { graph.launcher.getMissing(instance, listOf(requirement)) }

    /** Turns off every mod likely to crash, then gets every missing library. Account and device fixes stay with the user. */
    fun fixAll() {
        val check = ready ?: return
        fix(FIX_ALL) {
            if (check.issues.isNotEmpty()) graph.launcher.turnOff(instance, check.issues)
            if (check.missing.isNotEmpty()) graph.launcher.getMissing(instance, check.missing)
        }
    }

    private fun fix(key: String, block: suspend () -> Unit) {
        if (fixing != null) return
        if (refuseWhileRunning()) return
        fixing = key
        error = null
        graph.work({ error = it; fixing = null }) {
            block()
            fixing = null
            loadReady()
            loadFolder()
        }
    }

    // Folder actions

    fun link(folderId: String) {
        graph.library.linkFolder(instance.id, folderId) { load() }
    }

    fun copyFolder() {
        graph.library.copyFolderFor(instance.id) { load() }
    }

    fun setEnabled(item: ContentStore.Item, enabled: Boolean) {
        if (refuseWhileRunning()) return
        val current = folder ?: return
        folder = current.copy(quick = current.quick.map { if (it.id == item.id) it.copy(enabled = enabled) else it })
        graph.work({ error = it; loadFolder() }) {
            val content = graph.onIo { graph.contentDirectory(graph.instances.get(instance.id)) }
            InstanceStore.lock(content).withLock { graph.onIo { ContentStore(dir, content).setEnabled(enabled, item) } }
            loadFolder()
            loadReady()
        }
    }

    fun update(items: List<ModUpdate>) {
        val found = folder?.folder ?: return
        if (items.isEmpty() || updating.isNotEmpty() || refuseWhileRunning()) return
        updating = if (items.size == 1) setOf(items.single().file) else setOf(FIX_ALL)
        error = null
        graph.work({ error = it; updating = emptySet() }) {
            val target = graph.onIo { graph.modFolders.directory(found) }
            val result = ModUpdates.apply(target, items, ModInstaller(target, modrinth = graph.modrinth))
            FolderUpdates.forget(graph, found.id)
            result.failed.firstOrNull()?.let { error = graph.context.getString(R.string.home_update_failed, it.update.name, it.reason) }
            updating = emptySet()
            loadFolder()
            loadReady()
        }
    }

    // Performance actions

    fun setPreset(preset: Preset) = setOptions(Performance(preset.render, preset.simulation, preset.fps))

    fun setOptions(next: Performance) {
        if (refuseWhileRunning()) return
        performance = next
        graph.work({ error = it }) {
            InstanceStore.lock(dir).withLock {
                graph.onIo {
                    val options = GameOptions(dir)
                    options.set("renderDistance", "${next.render}")
                    options.set("simulationDistance", "${next.simulation}")
                    options.set("maxFps", "${next.fps}")
                }
            }
        }
    }

    /** Memory for this instance: auto (the launcher default) or a fixed heap. */
    fun setMemory(autoMemory: Boolean, memoryMB: Int) {
        val current = graph.library.instances.firstOrNull { it.id == instance.id } ?: instance
        graph.library.runtime(instance.id, autoMemory, memoryMB, current.jvmArguments)
    }

    // Last session

    fun loadLog() = graph.work({ error = it }) {
        logLines = graph.onIo { LogStore(dir, graph.launcherLog).read(LogFile.Game).takeLast(LOG_LINES).map { ReportBundle.redact(it.text) } }
    }

    fun closeLog() {
        logLines = null
    }

    /** latest.log with names and tokens redacted, in the share sheet. */
    fun shareLog() = graph.work({ error = it }) {
        val file = graph.onIo {
            val log = LogStore(dir, graph.launcherLog).gameLog
            graph.scratch("latest.log").apply { writeText(ReportBundle.redact(log.readText())) }
        }
        graph.share(file, "text/plain")
    }

    /** After a crash that named a mod: turns that mod off, then plays. */
    fun turnOffAndPlay(modName: String) {
        if (refuseWhileRunning()) return
        graph.work({ error = it }) {
            val content = graph.onIo { graph.contentDirectory(graph.instances.get(instance.id)) }
            InstanceStore.lock(content).withLock {
                graph.onIo {
                    val store = ContentStore(dir, content)
                    val mod = store.list(ContentKind.Mods).firstOrNull {
                        it.enabled && (it.name.equals(modName, ignoreCase = true) || it.modID.equals(modName, ignoreCase = true))
                    }
                    if (mod != null) store.setEnabled(false, mod)
                }
            }
            graph.launcher.play(instance)
        }
    }

    // Playing

    fun play(checked: Boolean = true) = graph.launcher.play(instance, checked = checked)

    fun resume(world: World) = graph.launcher.play(instance, Launcher.QuickPlay(MinecraftLaunchArguments.singleplayer(world.folder), world.name))

    /** The server Home offers: the last one joined from Cofre, else the first in the instance's list (its favorite). */
    val server: ServersModel.Server?
        get() {
            val last = session?.lastServer?.args?.getOrNull(1)
            return servers.servers.firstOrNull { last != null && it.address?.display == last } ?: servers.servers.firstOrNull()
        }

    fun join(server: ServersModel.Server) {
        val address = server.address ?: return
        graph.launcher.play(instance, Launcher.QuickPlay(MinecraftLaunchArguments.server(address), server.record.name))
    }

    private fun refuseWhileRunning(): Boolean {
        if (!graph.launcher.gameRunning) return false
        running = true
        error = graph.context.getString(R.string.error_game_running)
        return true
    }

    companion object {
        const val FIX_ALL = "*"
        private const val QUICK_TOGGLES = 3
        private const val LOG_LINES = 400
        /** Minecraft's own defaults, when options.txt doesn't say. */
        const val DEFAULT_RENDER = 12
        const val DEFAULT_SIMULATION = 12
        const val DEFAULT_FPS = 120
    }
}

