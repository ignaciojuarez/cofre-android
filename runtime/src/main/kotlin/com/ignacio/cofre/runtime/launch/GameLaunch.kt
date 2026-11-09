package com.ignacio.cofre.runtime.launch

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.kit.options.FMLConfig
import com.ignacio.cofre.kit.options.GameOptions
import com.ignacio.cofre.kit.options.JavaHeap
import com.ignacio.cofre.kit.versions.VersionJson
import com.ignacio.cofre.runtime.ExitRecord
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.runtime.LaunchSpec
import java.io.File

/** Who plays. Comes from Microsoft sign-in; debug builds also have `DevAccount`. */
data class GameAccount(val username: String, val uuid: String, val accessToken: String)

/**
 * Prepares and explains game runs. [prepare] downloads what's missing, writes the Vulkan
 * settings and returns the [LaunchSpec] file for `GameActivity.intent`; [exit] reads what the
 * run left behind once the launcher is back.
 */
class GameLaunch(private val context: Context, val files: GameFiles = GameFiles(File(context.filesDir, "minecraft"))) {

    /** Blocking work and downloads: call off the main thread. [versionId] is `26.2` or an installed `neoforge-…`. */
    suspend fun prepare(versionId: String, gameDir: File, account: GameAccount, extraJvmArgs: List<String> = emptyList()): File {
        val version = files.install(versionId)
        val javaHome = JreManager(context).install()
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val spec = LaunchArgs.spec(version, files, gameDir, account, javaHome.path, JavaHeap.autoMB((memory.totalMem shr 20).toInt()), extraJvmArgs)
        prepareGameDir(gameDir, LaunchArgs.isNeoForge(version))
        val file = File(gameDir, ".cofre/launch.json")
        File(spec.exitFile).delete()
        spec.write(file)
        Log.i(TAG, "[Cofre] launch prepared ${version.id} main=${spec.mainClass} classpath=${spec.classpath.size} jars heap=${spec.jvmArgs.first { it.startsWith("-Xmx") }}")
        return file
    }

    /** How the run described by [specFile] ended, with kit's diagnosis. */
    fun exit(specFile: File): GameExit {
        val spec = LaunchSpec.read(specFile)
        val record = ExitRecord.read(File(spec.exitFile))
        val since = specFile.lastModified()
        val gameDir = File(spec.gameDir)
        val log = File(spec.logFile).takeIf { it.isFile }?.readText() ?: File(gameDir, "logs/latest.log").takeIf { it.isFile }?.readText()
        val crash = (File(gameDir, "crash-reports").listFiles().orEmpty().toList() + gameDir.listFiles { f -> f.name.startsWith("hs_err_pid") }.orEmpty())
            .filter { it.isFile && it.lastModified() >= since }.maxByOrNull { it.lastModified() }
        val report = GameExitReport.diagnose(record?.code ?: KILLED, crash?.readText(), log, gameDir.name)
        return GameExit(record, report.withVulkanMissing(log.orEmpty())).also {
            Log.i(TAG, "[Cofre] diagnosis code=${record?.code} reason=${it.report.reason} ${it.summary}")
        }
    }

    companion object {
        private const val TAG = "Cofre"
        private const val KILLED = -9

        /** Before every launch: Vulkan in options.txt; for NeoForge, its GL early window off in config/fml.toml. */
        fun prepareGameDir(gameDir: File, neoForge: Boolean) {
            gameDir.mkdirs()
            GameOptions(gameDir).useVulkan()
            if (neoForge) FMLConfig.disableEarlyWindow(gameDir)
        }

        /** Minecraft logs `missing: [A, B]`; kit's diagnosis only reads `{A, B}`. */
        internal fun GameExitReport.withVulkanMissing(log: String): GameExitReport {
            if (reason != ExitReason.VulkanUnsupported || missingVulkanRequirements.isNotEmpty()) return this
            val missing = Regex("missing:\\s*\\[([^\\]]+)\\]").findAll(log).flatMap { it.groupValues[1].split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
            return copy(missingVulkanRequirements = missing)
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

    fun spec(version: VersionJson, files: GameFiles, gameDir: File, account: GameAccount, javaHome: String, heapMB: Int, extraJvmArgs: List<String> = emptyList()): LaunchSpec {
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
            "auth_access_token" to account.accessToken,
            "auth_session" to account.accessToken,
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
            gameArgs = game(version, values),
            gameDir = gameDir.path,
            logFile = File(gameDir, "logs/cofre-output.log").path,
            exitFile = File(gameDir, ".cofre/exit.json").path,
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
