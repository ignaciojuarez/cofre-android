package com.ignacio.cofre.runtime

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the :game process needs to start the JVM. The launcher writes it to a JSON file and
 * starts [GameActivity] with that file's path ([GameActivity.intent]). The access token is never
 * in the file: arguments carry [TOKEN], and GameActivity puts the real one in from its intent.
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
    /** The JVM's stdout and stderr go here (and to logcat in debuggable builds). */
    val logFile: String,
    /** Where the :game process writes its [ExitRecord] as it ends. */
    val exitFile: String,
) {
    /** This spec with the account's token in place of [TOKEN]. */
    fun withToken(token: String): LaunchSpec = copy(gameArgs = gameArgs.map { it.replace(TOKEN, token) })

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer(), this))
    }

    companion object {
        /** Stands for the access token in [gameArgs] at rest. */
        const val TOKEN = "\${auth_access_token}"

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
