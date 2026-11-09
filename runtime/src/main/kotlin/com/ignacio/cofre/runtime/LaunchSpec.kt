package com.ignacio.cofre.runtime

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the :game process needs to start the JVM. The launcher writes it to a JSON file and
 * starts [GameActivity] with that file's path ([GameActivity.intent]).
 *
 * GameActivity adds what's device-specific itself: the GLFW shim first on the classpath, the
 * LWJGL properties (library path, libvulkan.so, system allocator) and java.home/tmpdir.
 */
@Serializable
data class LaunchSpec(
    /** An installed JRE, usually [JreManager.home]. */
    val javaHome: String,
    val classpath: List<String>,
    val jvmArgs: List<String>,
    val mainClass: String,
    val gameArgs: List<String>,
    /** Working directory of the game (the instance folder). */
    val gameDir: String,
    val env: Map<String, String> = emptyMap(),
    /** The JVM's stdout and stderr go here (and to logcat). */
    val logFile: String,
    /** Where the :game process writes its [ExitRecord] as it ends. */
    val exitFile: String,
    /**
     * Our gamepad → keyboard/mouse mapping. Set false when Controlify drives the controller
     * itself through SDL3; then GameActivity leaves gamepad events alone.
     */
    val builtInController: Boolean = true,
) {
    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer(), this))
    }

    companion object {
        fun read(file: File): LaunchSpec = json.decodeFromString(serializer(), file.readText())
    }
}

/** How the last game run ended. Absent after a crash or a kill: the process died before writing it. */
@Serializable
data class ExitRecord(val code: Int, val timeMillis: Long) {
    companion object {
        fun read(file: File): ExitRecord? =
            if (file.isFile) runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull() else null
    }
}

private val json = Json { ignoreUnknownKeys = true }
