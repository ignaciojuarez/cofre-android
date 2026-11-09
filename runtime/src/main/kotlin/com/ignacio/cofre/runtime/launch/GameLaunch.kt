package com.ignacio.cofre.runtime.launch

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.kit.modpacks.FileOps
import com.ignacio.cofre.kit.options.FMLConfig
import com.ignacio.cofre.kit.options.GameOptions
import com.ignacio.cofre.kit.options.JavaHeap
import com.ignacio.cofre.kit.versions.VersionJson
import com.ignacio.cofre.runtime.ExitRecord
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.runtime.LaunchSpec
import java.io.File
import java.io.RandomAccessFile

/** Who plays. Comes from Microsoft sign-in; debug builds also have `DevAccount`. The token goes to `GameActivity.intent`, never to disk. */
data class GameAccount(val username: String, val uuid: String)

/**
 * Prepares and explains game runs. [prepare] downloads what's missing, writes the Vulkan
 * settings and returns the [LaunchSpec] file for `GameActivity.intent`; [exit] reads what the
 * run left behind once the launcher is back. Specs and exit records live in `noBackupFilesDir/launch`,
 * outside the instance, so exports, copies and the file manager never see them.
 */
class GameLaunch(private val context: Context, val files: GameFiles = GameFiles(File(context.filesDir, "minecraft"))) {

    /**
     * Blocking work and downloads: call off the main thread. [versionId] is `26.2` or an installed `neoforge-…`.
     * [heapMB] is `-Xmx` (the device's RAM tier unless the launcher picks one), [extraJvmArgs] the instance's own,
     * and [extraGameArgs] go after the game's (Quick Play: `--quickPlaySingleplayer <world>`).
     */
    suspend fun prepare(
        versionId: String,
        gameDir: File,
        account: GameAccount,
        heapMB: Int = autoHeapMB(),
        extraJvmArgs: List<String> = emptyList(),
        extraGameArgs: List<String> = emptyList(),
    ): File {
        val version = files.install(versionId)
        val javaHome = JreManager(context).install()
        val runs = File(context.noBackupFilesDir, "launch")
        val exitFile = File(runs, "${gameDir.name}.exit.json")
        val spec = LaunchArgs.spec(version, files, gameDir, account, javaHome.path, heapMB, exitFile, extraJvmArgs, extraGameArgs)
        prepareGameDir(gameDir, LaunchArgs.isNeoForge(version))
        FileOps.delete(File(gameDir, ".cofre")) // where older builds kept the spec, token included
        // Native crash reports hold the command line, token included: older builds left them in the instance.
        listOf(gameDir, runs).flatMap { it.listFiles { f -> f.name.startsWith("hs_err_pid") }.orEmpty().toList() }.forEach { it.delete() }
        val file = File(runs, "${gameDir.name}.json")
        exitFile.delete()
        spec.write(file)
        Log.i(TAG, "[Cofre] launch prepared ${version.id} main=${spec.mainClass} classpath=${spec.classpath.size} jars heap=${spec.jvmArgs.first { it.startsWith("-Xmx") }}")
        return file
    }

    private fun autoHeapMB(): Int {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return JavaHeap.autoMB((memory.totalMem shr 20).toInt())
    }

    /** How the run described by [specFile] ended, with kit's diagnosis. */
    fun exit(specFile: File): GameExit {
        val spec = LaunchSpec.read(specFile)
        val record = ExitRecord.read(File(spec.exitFile))
        val since = specFile.lastModified()
        val gameDir = File(spec.gameDir)
        val log = listOf(File(spec.logFile), File(gameDir, "logs/latest.log")).firstOrNull { it.isFile }?.let(::tail)
        // hs_err files sit next to the exit record (GameBridge's -XX:ErrorFile).
        val crash = (File(gameDir, "crash-reports").listFiles().orEmpty().toList() + File(spec.exitFile).parentFile?.listFiles { f -> f.name.startsWith("hs_err_pid") }.orEmpty())
            .filter { it.isFile && it.lastModified() >= since }.maxByOrNull { it.lastModified() }
        val report = GameExitReport.diagnose(record?.code ?: KILLED, crash?.readText(), log, gameDir.name)
        return GameExit(record, report).also {
            Log.i(TAG, "[Cofre] diagnosis code=${record?.code} reason=${it.report.reason} ${it.summary}")
        }
    }

    companion object {
        private const val TAG = "Cofre"
        private const val KILLED = -9

        /**
         * The end of [file], at most [max] bytes, from a line start: a long session's output can be far bigger than the
         * heap, and the diagnosis only needs the end. UTF-8 safe: a cut character can only be in the dropped first line.
         */
        internal fun tail(file: File, max: Int = 2 shl 20): String = RandomAccessFile(file, "r").use { input ->
            val start = maxOf(0L, input.length() - max)
            input.seek(start)
            val text = ByteArray((input.length() - start).toInt()).also(input::readFully).decodeToString()
            if (start > 0) text.substringAfter('\n', "") else text
        }

        /** Before every launch: Vulkan in options.txt; for NeoForge, its GL early window off in config/fml.toml. */
        fun prepareGameDir(gameDir: File, neoForge: Boolean) {
            gameDir.mkdirs()
            GameOptions(gameDir).useVulkan()
            if (neoForge) FMLConfig.disableEarlyWindow(gameDir)
        }
    }
}

/** A finished run: its [ExitRecord] (null when the process was killed) and why it stopped. */
data class GameExit(val record: ExitRecord?, val report: GameExitReport) {
    /** One line for the launcher. */
    val summary: String get() = when {
        report.reason == ExitReason.VulkanUnsupported -> "Vulkan device not suitable. This device's Vulkan driver is missing: " +
            report.missingVulkanRequirements.ifEmpty { listOfNotNull(report.clue) }.joinToString()
        else -> "${report.title}: ${report.explanation}" + (report.clue?.let { " ($it)" } ?: "")
    }
}

/** The launch command from a merged version JSON. Pure, so it's tested against the real 26.2 and NeoForge JSONs. */
internal object LaunchArgs {
    private const val LAUNCHER = "cofre"
    private const val LAUNCHER_VERSION = "0.1"

    fun isNeoForge(version: VersionJson) = version.mainClass.orEmpty().startsWith("net.neoforged.")

    fun spec(
        version: VersionJson,
        files: GameFiles,
        gameDir: File,
        account: GameAccount,
        javaHome: String,
        heapMB: Int,
        exitFile: File,
        extraJvmArgs: List<String> = emptyList(),
        extraGameArgs: List<String> = emptyList(),
    ): LaunchSpec {
        val classpath = files.classpath(version).map { it.path }
        val values = mapOf(
            "natives_directory" to File(files.root, "natives/${version.id}").path,
            "launcher_name" to LAUNCHER,
            "launcher_version" to LAUNCHER_VERSION,
            "classpath" to classpath.joinToString(":"),
            "classpath_separator" to ":",
            "library_directory" to files.libraries.path,
            "version_name" to version.id,
            "version_type" to (version.type ?: "release"),
            "game_directory" to gameDir.path,
            "assets_root" to files.assets.path,
            "game_assets" to files.assets.path,
            "assets_index_name" to (version.assetIndex?.id ?: version.assets.orEmpty()),
            "auth_player_name" to account.username,
            "auth_uuid" to account.uuid,
            "auth_access_token" to LaunchSpec.TOKEN,
            "auth_session" to LaunchSpec.TOKEN,
            "user_type" to "msa",
            "user_properties" to "{}",
            "clientid" to "",
            "auth_xuid" to "",
        )
        return LaunchSpec(
            javaHome = javaHome,
            classpath = classpath,
            jvmArgs = listOf("-Xms256m", "-Xmx${heapMB}m") + jvm(version, values) + extraJvmArgs,
            mainClass = requireNotNull(version.mainClass) { "${version.id} has no main class" },
            gameArgs = game(version, values) + extraGameArgs,
            gameDir = gameDir.path,
            logFile = File(gameDir, "logs/cofre-output.log").path,
            exitFile = exitFile.path,
        )
    }

    fun substitute(arg: String, values: Map<String, String>): String =
        Regex("\\$\\{([^}]+)\\}").replace(arg) { values[it.groupValues[1]] ?: it.value }

    /**
     * `arguments.jvm` for Linux arm64, substituted. The classpath goes in [LaunchSpec.classpath]
     * and the library path is our native folder, so `-cp ${classpath}` and `-Djava.library.path` drop out.
     */
    fun jvm(version: VersionJson, values: Map<String, String>): List<String> {
        val args = version.arguments?.jvm(GameFiles.rules).orEmpty()
        return buildList {
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    (arg == "-cp" || arg == "-classpath") && args.getOrNull(i + 1) == "\${classpath}" -> i++
                    arg.startsWith("-Djava.library.path=") -> Unit
                    else -> add(substitute(arg, values))
                }
                i++
            }
        }
    }

    /** `arguments.game` (no demo, no quick play, no custom size), `--userType msa` and `--graphicsBackend vulkan`. */
    fun game(version: VersionJson, values: Map<String, String>): List<String> {
        val template = version.arguments?.game(GameFiles.rules) ?: version.minecraftArguments.orEmpty().split(' ').filter { it.isNotEmpty() }
        val args = template.map { substitute(it, values) }.toMutableList()
        if ("--userType" !in args) args += listOf("--userType", "msa")
        if ("--graphicsBackend" !in args) args += listOf("--graphicsBackend", "vulkan")
        return args
    }
}
