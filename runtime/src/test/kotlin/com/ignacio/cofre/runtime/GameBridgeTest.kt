package com.ignacio.cofre.runtime

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameBridgeTest {
    private val spec = LaunchSpec(
        javaHome = "/jre",
        classpath = listOf("/m/client.jar"),
        jvmArgs = listOf("-Xmx2048m"),
        mainClass = "net.minecraft.client.main.Main",
        gameArgs = listOf("--quickPlaySingleplayer", "Base 🏰"),
        gameDir = "/g",
        logFile = "/l/latest.log",
        exitFile = "/r/game.exit.json",
    )
    private val command = GameBridge.command(spec, File("/n"), File("/t"), listOf("/s/cofre-glfw.jar"), emptyList())

    @Test fun vulkanGoesThroughOurRotationLayerFromTheNativeFolder() {
        assertTrue("-Dorg.lwjgl.vulkan.libname=libcofrevk.so" in command)
        assertTrue("-Dorg.lwjgl.librarypath=/n" in command)
    }

    @Test fun hotSpotWritesNoPerfData() = assertTrue("-XX:-UsePerfData" in command)

    @Test fun shimComesFirstAndGameArgsLast() {
        assertEquals("/s/cofre-glfw.jar:/m/client.jar", command[command.indexOf("-cp") + 1])
        assertEquals(listOf("net.minecraft.client.main.Main", "--quickPlaySingleplayer", "Base 🏰"), command.takeLast(3))
    }
}
