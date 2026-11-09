package com.ignacio.cofre

import androidx.test.core.app.ApplicationProvider
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.kit.servers.MinecraftServerStatus
import com.ignacio.cofre.kit.servers.ServerList
import com.ignacio.cofre.kit.servers.ServerRecord
import com.ignacio.cofre.kit.servers.ServersDatCodec
import com.ignacio.cofre.kit.worlds.NBT
import com.ignacio.cofre.kit.worlds.NBTDocument
import com.ignacio.cofre.kit.worlds.NBTTag
import com.ignacio.cofre.runtime.VulkanReport
import com.ignacio.cofre.runtime.VulkanSupport
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

/** Sample data for screens and flows, as real files the kit reads (the iOS `Mocks/` equivalent). */
object Samples {
    val suitable = VulkanReport(
        "1.3.284", "Adreno (TM) 830", "512.800.0",
        VulkanSupport.requiredExtensions.associateWith { true }, VulkanSupport.requiredFeatures.associateWith { true }, emptyList(),
    )

    /** What the Android 17 emulator reports. */
    val unsuitable = VulkanReport(
        "1.3.0", "Goldfish GFXStream (llvmpipe)", "0.0.1",
        VulkanSupport.requiredExtensions.associateWith { it != "VK_KHR_push_descriptor" && it != "VK_EXT_vertex_attribute_divisor" },
        VulkanSupport.requiredFeatures.associateWith { true },
        listOf("VK_KHR_push_descriptor", "VK_EXT_vertex_attribute_divisor"),
    )

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** A graph on a fresh folder: IO inline on the main thread, no network, no Keystore. */
    fun graph(
        vulkan: VulkanReport = suitable,
        setupDone: Boolean = true,
        seeded: Boolean = true,
        clientId: String = "",
    ): AppGraph {
        val home = Files.createTempDirectory("cofre-app").toFile()
        if (seeded) seed(home)
        val graph = AppGraph(
            ApplicationProvider.getApplicationContext(),
            home = home,
            io = Dispatchers.Unconfined,
            vulkanProbe = { vulkan },
            ping = { MinecraftServerStatus("A Minecraft Server", "26.2", 776, 12, 100, null, 38) },
            microsoftClientId = clientId,
            secretKey = { key },
            autoStartDownloads = false,
        )
        graph.settings.update { it.copy(setupDone = setupDone) }
        if (seeded) {
            graph.accounts.addDevAccount()
            graph.library.refresh()
        }
        return graph
    }

    private fun seed(home: File) {
        val root = File(home, "instances")
        val now = System.currentTimeMillis()
        listOf(
            Instance("survival", "Survival+", loaderVersion = "26.2.0.93", favorite = true, lastPlayedAt = now - 3_600_000, notes = "Main world with friends."),
            Instance("skyblock", "Skyblock", loaderVersion = "26.2.0.93", group = "Challenges", lastPlayedAt = now - 86_400_000 * 3),
            Instance("creative", "Creative flat", loaderVersion = "26.2.0.88", group = "Challenges"),
            Instance("vanilla", "Plain NeoForge", loaderVersion = "26.2.0.93"),
        ).forEach { instance ->
            val dir = File(root, instance.id).apply { mkdirs() }
            File(dir, "cofre.json").writeText(com.ignacio.cofre.kit.common.Kit.json.encodeToString(instance))
        }
        val survival = File(root, "survival")
        world(survival, "ironwood", "Ironwood", 0, now - 3_600_000)
        world(survival, "skyhaven", "Skyhaven", 1, now - 86_400_000 * 2)
        world(survival, "river-base", "River Base", 0, now - 86_400_000 * 9)
        File(survival, "mods").mkdirs()
        listOf("sodium-neoforge-0.9.2+mc26.2.jar", "controlify-3.5.3+mc26.2.jar", "distanthorizons-3.3.3-26.2.jar").forEach {
            File(survival, "mods/$it").writeBytes(ByteArray(2048))
        }
        File(survival, "servers.dat").writeBytes(
            ServersDatCodec.encode(ServerList(listOf(ServerRecord("Friends SMP", "smp.example.net"), ServerRecord("Hypixel", "mc.hypixel.net")))),
        )
    }

    private fun world(instance: File, folder: String, name: String, mode: Int, lastPlayed: Long) {
        val dir = File(instance, "saves/$folder").apply { mkdirs() }
        val data = NBTTag.Compound(
            mapOf(
                "LevelName" to NBTTag.StringTag(name),
                "GameType" to NBTTag.IntTag(mode),
                "LastPlayed" to NBTTag.LongTag(lastPlayed),
                "Version" to NBTTag.Compound(mapOf("Name" to NBTTag.StringTag("26.2"))),
            ),
        )
        File(dir, "level.dat").writeBytes(NBT.write(NBTDocument(NBTTag.Compound(mapOf("Data" to data))), gzip = true))
        File(dir, "region").mkdirs()
        File(dir, "region/r.0.0.mca").writeBytes(ByteArray(64 * 1024))
    }

    /** "Distant Horizons called OpenGL": the stopped screen with a mod to turn off. */
    fun openGlReport(instance: String = "Survival+") = GameExitReport(
        1, ExitReason.OpenGLMod,
        "No context is current or a function that is not available in the current context was called.",
        instance,
        listOf(
            "[Render thread/INFO] Loaded 1243 recipes",
            "[Render thread/ERROR] No context is current or a function that is not available in the current context was called.",
            "\tat TRANSFORMER/distanthorizons@3.3.3/com.seibel.distanthorizons.Renderer.render",
        ),
        mod = "Distant Horizons",
    )
}
