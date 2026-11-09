package com.ignacio.cofre

import android.app.ActivityManager
import android.content.Context
import com.ignacio.cofre.accounts.Accounts
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modrinth.Modrinth
import com.ignacio.cofre.kit.servers.MinecraftServerAddress
import com.ignacio.cofre.kit.servers.MinecraftServerPing
import com.ignacio.cofre.kit.servers.MinecraftServerStatus
import com.ignacio.cofre.launch.Launcher
import com.ignacio.cofre.runtime.VulkanReport
import com.ignacio.cofre.runtime.VulkanSupport
import com.ignacio.cofre.ui.downloads.Downloads
import com.ignacio.cofre.ui.instances.Library
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.crypto.SecretKey

/**
 * Everything long-lived, built once in [CofreApp]: the SwiftUI `@Environment` equivalent, passed down explicitly.
 * Add a `val` here when a screen needs a new long-lived object; keep construction cheap (lazy for anything heavy).
 *
 * The parameters after [context] are the device edges, replaced in tests: file root, IO dispatcher, the Vulkan
 * probe, server pings, the Microsoft client ID and the Keystore key.
 */
class AppGraph(
    val context: Context,
    val home: File = context.filesDir,
    val io: CoroutineDispatcher = Dispatchers.IO,
    private val vulkanProbe: () -> VulkanReport = VulkanSupport::check,
    val ping: (MinecraftServerAddress) -> MinecraftServerStatus = { MinecraftServerPing.ping(it) },
    val microsoftClientId: String = BuildConfig.MICROSOFT_CLIENT_ID,
    secretKey: () -> SecretKey = SecretVault::keystoreKey,
) {
    /** App-lifetime work (imports, sign-in, downloads): survives screens, not the process. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(File(home, "settings.json"))
    val vault = SecretVault(File(home, "secrets.bin"), secretKey)
    val instances = InstanceStore(File(home, "instances").apply { mkdirs() })
    val modrinth = Modrinth()
    val downloads by lazy { DownloadQueue(File(home, "downloads/queue.json").apply { parentFile?.mkdirs() }) }
    val launcherLog = File(home, "logs/launcher.log")
    val nav = AppNav()

    /** The device's Vulkan driver against 26.2's needs, probed once per process. */
    val vulkan: VulkanReport by lazy(vulkanProbe)

    /** RAM in MiB, for the heap tier and preset options. */
    val physicalMB: Int by lazy {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt().coerceAtLeast(1024)
    }

    private val curseForge = MutableStateFlow(vault.get(CURSEFORGE_KEY))

    /** The user's own CurseForge API key (never bundled), or null. */
    val curseForgeKey: StateFlow<String?> = curseForge.asStateFlow()

    fun setCurseForgeKey(key: String?) {
        val value = key?.trim()?.takeIf { it.isNotEmpty() }
        vault.set(CURSEFORGE_KEY, value)
        curseForge.value = value
    }

    val library = Library(this)
    val accounts = Accounts(this)
    val downloadsModel by lazy { Downloads(this) }
    val launcher = Launcher(this)

    private companion object {
        const val CURSEFORGE_KEY = "curseforge.key"
    }
}
