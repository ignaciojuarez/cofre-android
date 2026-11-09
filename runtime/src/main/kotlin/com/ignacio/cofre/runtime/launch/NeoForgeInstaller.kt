package com.ignacio.cofre.runtime.launch

import android.content.Context
import android.util.Log
import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.modpacks.FileOps
import com.ignacio.cofre.kit.versions.InstallProfile
import com.ignacio.cofre.kit.versions.NeoForgeVersion
import com.ignacio.cofre.kit.versions.VersionJson
import com.ignacio.cofre.runtime.JreManager
import com.ignacio.cofre.runtime.LaunchSpec
import java.io.File
import java.io.IOException
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Installs NeoForge into [files] the way its own installer does for the client side:
 * vanilla files, the installer's libraries, the client processors (in `:installer`), output
 * hashes, and last the version JSON at `versions/neoforge-<version>/`, which marks it installed.
 */
class NeoForgeInstaller(private val context: Context, private val files: GameFiles) {

    /** Installs [version] unless its version JSON is already there; returns the version id. */
    suspend fun install(version: String = VERSION): String {
        val neoforge = NeoForgeVersion(version)
        val profileId = "neoforge-$version"
        if (files.versionJson(profileId).isFile) return profileId

        val vanilla = files.install(MINECRAFT, withAssets = false)
        files.step("Downloading the NeoForge installer")
        val installer = files.library("net.neoforged:neoforge:$version:installer")
        // Pinned for the version we ship; any other one (a modpack's) can only be checked against its server's .sha1.
        val sha1 = if (version == VERSION) VERSION_SHA1 else withContext(Dispatchers.IO) { text("${neoforge.installerUrl}.sha1").trim() }
        files.download("NeoForge installer", listOf(DownloadItem(neoforge.installerUrl, installer.path, sha1 = sha1)))

        val (profile, versionText) = ZipFile(installer).use { zip ->
            fun entry(name: String) = zip.getInputStream(zip.getEntry(name.removePrefix("/")) ?: throw IOException("Installer has no $name")).use { it.readBytes().decodeToString() }
            Kit.json.decodeFromString(InstallProfile.serializer(), entry("install_profile.json")) to entry("version.json")
        }
        val versionJson = VersionJson.decode(versionText)
        check(profile.minecraft == MINECRAFT && versionJson.id == profileId) { "Unexpected installer: ${profile.version} for ${profile.minecraft}" }
        files.download("NeoForge libraries", files.libraryDownloads(profile.libraries + versionJson.libraries))

        val work = File(files.root, ".neoforge/$version").apply { FileOps.delete(this); mkdirs() }
        val base = mapOf(
            "SIDE" to SIDE,
            "MINECRAFT_JAR" to files.clientJar(vanilla).path,
            "MINECRAFT_VERSION" to MINECRAFT,
            "ROOT" to files.root.path,
            "INSTALLER" to installer.path,
            "LIBRARY_DIR" to files.libraries.path,
        )
        val data = ZipFile(installer).use { zip ->
            Processors.data(profile, SIDE, base, files.libraries) { path ->
                File(work, path.removePrefix("/")).also { out ->
                    out.parentFile?.mkdirs()
                    val entry = zip.getEntry(path.removePrefix("/")) ?: throw IOException("Installer has no $path")
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        val steps = Processors.resolve(profile, SIDE, data, files.libraries)
        val javaHome = JreManager(context).install()
        steps.forEachIndexed { i, step ->
            if (step.outputs.isNotEmpty() && step.outputs.all { (file, hash) -> File(file).isFile && Hashes.sha1(File(file)).equals(hash, true) }) {
                Log.i(TAG, "[Cofre] neoforge processor ${i + 1}/${steps.size} outputs already valid, skipped")
                return@forEachIndexed
            }
            val mainClass = JarFile(step.jar).use { it.manifest?.mainAttributes?.getValue("Main-Class") } ?: throw IOException("${step.jar.name} has no Main-Class")
            files.step("Running NeoForge processor ${i + 1} of ${steps.size}")
            Log.i(TAG, "[Cofre] neoforge processor ${i + 1}/${steps.size} $mainClass ${step.args.joinToString(" ")}")
            val log = File(work, "processor-${i + 1}.log")
            val spec = LaunchSpec(
                javaHome = javaHome.path,
                classpath = (listOf(step.jar) + step.classpath).map { it.path },
                jvmArgs = listOf("-Xmx1g"),
                mainClass = mainClass,
                gameArgs = step.args,
                gameDir = work.path,
                logFile = log.path,
                exitFile = File(work, "processor-${i + 1}.exit.json").path,
            )
            val code = InstallerService.run(context, spec, File(work, "processor-${i + 1}.json"), "Installing NeoForge $version", "Step ${i + 1} of ${steps.size}")
            Log.i(TAG, "[Cofre] neoforge processor ${i + 1}/${steps.size} exited code=$code")
            if (code != 0) throw IOException("NeoForge processor $mainClass failed ($code): ${log.takeIf { it.isFile }?.readLines()?.takeLast(5)?.joinToString(" | ")}")
            step.outputs.forEach { (file, hash) ->
                val actual = File(file).takeIf { it.isFile }?.let(Hashes::sha1)
                if (!hash.equals(actual, true)) throw IOException("NeoForge output ${File(file).name} has hash $actual, expected $hash")
                Log.i(TAG, "[Cofre] neoforge output verified ${File(file).name} sha1=$actual")
            }
        }
        // The libraries the game needs at launch were downloaded above; the version JSON goes last.
        FileOps.atomic(files.versionJson(profileId), versionText.toByteArray())
        FileOps.delete(work)
        Log.i(TAG, "[Cofre] neoforge installed ${files.versionJson(profileId)}")
        files.step("Ready")
        return profileId
    }

    private fun text(url: String): String = Kit.http.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
        it.body.string()
    }

    companion object {
        const val VERSION = "26.2.0.93"
        /** [VERSION]'s installer jar (maven.neoforged.net's .sha1 and the downloaded jar agreed, 2026-11-09). */
        private const val VERSION_SHA1 = "97205829a020fc9389f504a77f590b54157c8dde"
        const val MINECRAFT = "26.2"
        const val SIDE = "client"
        private const val TAG = "Cofre"
    }
}

/** The installer's `data` and processor arguments, resolved like NeoForge's own installer does. */
internal object Processors {
    data class Step(val jar: File, val classpath: List<File>, val args: List<String>, val outputs: Map<String, String>)

    /** [base] plus the profile's `data` for [side]: `[coords]` → library path, `'text'` → text, anything else → a file [extract]ed from the installer. */
    fun data(profile: InstallProfile, side: String, base: Map<String, String>, libraries: File, extract: (String) -> File): Map<String, String> =
        base + profile.data.mapValues { (_, value) ->
            val v = if (side == "server") value.server else value.client
            when {
                v.startsWith('[') && v.endsWith(']') -> File(libraries, GameFiles.mavenPath(v.substring(1, v.length - 1))).path
                v.length >= 2 && v.startsWith('\'') && v.endsWith('\'') -> v.substring(1, v.length - 1)
                else -> extract(v).path
            }
        }

    fun resolve(profile: InstallProfile, side: String, data: Map<String, String>, libraries: File): List<Step> =
        profile.processors.filter { it.runsOn(side) }.map { processor ->
            Step(
                jar = File(libraries, GameFiles.mavenPath(processor.jar)),
                classpath = processor.classpath.map { File(libraries, GameFiles.mavenPath(it)) },
                args = processor.args.map { argument(it, data, libraries) },
                outputs = processor.outputs.entries.associate { (key, value) -> argument(key, data, libraries) to tokens(value, data) },
            )
        }

    fun argument(arg: String, data: Map<String, String>, libraries: File): String =
        if (arg.startsWith('[') && arg.endsWith(']')) File(libraries, GameFiles.mavenPath(arg.substring(1, arg.length - 1))).path else tokens(arg, data)

    /** `{KEY}` → data, `'text'` → text as is, `\x` → x. */
    fun tokens(value: String, data: Map<String, String>): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when (c) {
                '\\' -> {
                    require(i + 1 < value.length) { "Trailing escape in $value" }
                    append(value[++i])
                }
                '{', '\'' -> {
                    val close = if (c == '{') '}' else '\''
                    val key = StringBuilder()
                    while (true) {
                        i++
                        require(i < value.length) { "Unclosed $c in $value" }
                        val d = value[i]
                        if (d == '\\') {
                            require(i + 1 < value.length) { "Trailing escape in $value" }
                            key.append(value[++i])
                        } else if (d == close) break else key.append(d)
                    }
                    append(if (c == '\'') key else requireNotNull(data[key.toString()]) { "Unknown installer token {$key} in $value" })
                }
                else -> append(c)
            }
            i++
        }
    }
}
