package com.ignacio.cofre.kit.modpacks

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.nio.file.Files
import java.util.UUID

@Serializable
data class PackFile(val path: String, val hashes: Map<String, String> = emptyMap(), val downloads: List<String> = emptyList(), val fileSize: Long = 0, val env: Map<String, String> = emptyMap(), val projectID: Int? = null, val fileID: Int? = null, val required: Boolean = true)

data class Modpack(val format: Format, val name: String?, val gameVersion: String?, val loader: Loader, val loaderVersion: String?, val files: List<PackFile>, val overrides: List<Override>, val unpackedBytes: Long) {
    enum class Format { Modrinth, CurseForge, Plain, Cofre }
    data class Override(val entry: String, val path: String, val size: Long)
}

class PackRejected(val reason: Reason) : IllegalArgumentException(reason.toString()) {
    sealed interface Reason {
        data class GameVersion(val actual: String?) : Reason
        data class LoaderType(val actual: Loader) : Reason
        data object LoaderVersionMissing : Reason
        data class Invalid(val detail: String) : Reason
    }
}

object Modpacks {
    fun inspect(archive: File): Modpack {
        require(archive.extension.lowercase() in setOf("zip", "mrpack")) { "Use a Java .zip or .mrpack" }
        val entries = Zip.entries(archive).filterNot { it.path.startsWith("__MACOSX/") || it.directory }
        require(entries.isNotEmpty())
        val markers = setOf("modrinth.index.json", "manifest.json", "cofre.json")
        val tops = entries.map { it.path.substringBefore('/') }.toSet()
        val gameFolders = setOf("META-INF", "mods", "config", "saves", "resourcepacks", "shaderpacks", "defaultconfigs", "kubejs", "scripts", "overrides", "client-overrides", ".minecraft")
        val prefix = if (tops.size == 1 && tops.first() !in gameFolders && entries.none { '/' !in it.path || it.path in markers }) tops.first() + "/" else ""
        val paths = entries.associateBy { it.path.removePrefix(prefix) }
        fun obj(name: String): JsonObject = Kit.json.parseToJsonElement(Zip.read(archive, prefix + name).decodeToString()).jsonObject
        fun overrides(folder: String): List<Modpack.Override> = paths.filterKeys { it.startsWith("$folder/") }.map { (path, e) -> Modpack.Override(e.path, path.removePrefix("$folder/"), e.size) }
        var name: String? = null
        var game: String? = null
        var loader = Loader.Unknown
        var loaderVersion: String? = null
        var files = emptyList<PackFile>()
        val copies: List<Modpack.Override>
        val format: Modpack.Format
        when {
            "modrinth.index.json" in paths -> {
                val index = obj("modrinth.index.json")
                require(index["formatVersion"]?.jsonPrimitive?.int == 1 && index.text("game") == "minecraft")
                name = index.text("name")
                val deps = index.getValue("dependencies").jsonObject
                game = deps.text("minecraft")
                val key = listOf("neoforge", "forge", "fabric-loader", "quilt-loader").firstOrNull { it in deps }
                loader = Loader.from(key?.removeSuffix("-loader")); loaderVersion = key?.let { deps.text(it) }
                files = Kit.json.decodeFromJsonElement<List<PackFile>>(index.getValue("files")).filter { it.env["client"] != "unsupported" }
                copies = (overrides("overrides") + overrides("client-overrides")).associateBy { it.path }.values.toList()
                format = Modpack.Format.Modrinth
            }
            "manifest.json" in paths -> {
                val manifest = obj("manifest.json")
                val mc = manifest.getValue("minecraft").jsonObject
                name = manifest.text("name"); game = mc.text("version")
                val loaders = mc["modLoaders"]?.jsonArray.orEmpty().map { it.jsonObject }
                val selected = loaders.firstOrNull { it["primary"]?.jsonPrimitive?.booleanOrNull == true } ?: loaders.firstOrNull()
                val id = selected?.text("id")
                val type = listOf("neoforge", "forge", "fabric", "quilt").firstOrNull { id?.startsWith("$it-") == true }
                loader = Loader.from(type); loaderVersion = type?.let { id?.removePrefix("$it-") }
                files = manifest["files"]?.jsonArray.orEmpty().map { e ->
                    val f = e.jsonObject; val project = f.getValue("projectID").jsonPrimitive.int; val file = f.getValue("fileID").jsonPrimitive.int
                    PackFile("mods/curseforge-$project-$file.jar", projectID = project, fileID = file, required = f["required"]?.jsonPrimitive?.booleanOrNull ?: true)
                }
                copies = overrides(manifest.text("overrides") ?: "overrides"); format = Modpack.Format.CurseForge
            }
            else -> {
                require("level.dat" !in paths) { "This is a world; import it from Worlds" }
                require(paths.keys.none { it in setOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml") }) { "This is a mod" }
                format = if ("cofre.json" in paths) Modpack.Format.Cofre else Modpack.Format.Plain
                if (format == Modpack.Format.Cofre) {
                    val meta = obj("cofre.json"); name = meta.text("name"); game = meta.text("gameVersion"); loader = Loader.from(meta.text("loader")); loaderVersion = meta.text("loaderVersion")
                }
                copies = paths.map { (p, e) -> Modpack.Override(e.path, p.removePrefix(".minecraft/"), e.size) }
            }
        }
        val destinations = HashSet<String>()
        for (f in files) {
            FileOps.child(File("/cofre-pack"), f.path)
            require(destinations.add(f.path) && f.fileSize in 0..Zip.MAX_ENTRY_BYTES)
            require(f.downloads.all { it.toHttpUrlOrNull()?.let { u -> u.isHttps && u.username.isEmpty() && u.password.isEmpty() } == true }) { "Insecure download" }
            require(f.hashes.all { (kind, value) -> value.matches(Regex("[a-fA-F0-9]{${when (kind) { "sha512" -> 128; "sha256" -> 64; "sha1" -> 40; else -> throw IllegalArgumentException("Unknown hash: $kind") }}}")) })
            if (format == Modpack.Format.Modrinth) require(f.downloads.isNotEmpty() && ("sha512" in f.hashes || "sha1" in f.hashes)) { "Modrinth file needs a URL and hash" }
        }
        require(files.sumOf { it.fileSize } <= Zip.MAX_TOTAL_BYTES)
        copies.forEach { FileOps.child(File("/cofre-pack"), it.path); require(it.path != "cofre.json" || format == Modpack.Format.Cofre) }
        return Modpack(format, name, game, loader, loaderVersion, files, copies, entries.sumOf { it.size })
    }

    fun plan(pack: Modpack): ModpackInstallPlan {
        if (pack.gameVersion != Target.GAME_VERSION) throw PackRejected(PackRejected.Reason.GameVersion(pack.gameVersion))
        if (pack.loader != Target.LOADER) throw PackRejected(PackRejected.Reason.LoaderType(pack.loader))
        if (pack.loaderVersion.isNullOrBlank()) throw PackRejected(PackRejected.Reason.LoaderVersionMissing)
        return ModpackInstallPlan(pack.name?.takeIf { it.isNotBlank() } ?: "Imported pack", pack.gameVersion, pack.loader, pack.loaderVersion, pack.files, pack.overrides)
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}

data class ModpackInstallPlan(val name: String, val gameVersion: String, val loader: Loader, val loaderVersion: String, val filesToFetch: List<PackFile>, val overridesToCopy: List<Modpack.Override>) {
    /** The caller fetches and verifies files into directory; commit only exposes a complete new instance. */
    fun stage(instancesRoot: File): PackStage = PackStage.create(instancesRoot)
    fun copyOverrides(archive: File, stage: PackStage) {
        val targets = overridesToCopy.filter { it.path != "cofre.json" }.map { o ->
            val target = FileOps.child(stage.directory, o.path)
            require(!target.exists()) { "Override collision: ${o.path}" }
            o.entry to target
        }
        Zip.copyOut(archive, targets)
    }
}

class PackStage private constructor(val directory: File) {
    fun commit(destination: File) {
        require(destination.absoluteFile.parentFile == directory.absoluteFile.parentFile) { "Stage must stay under instances root" }
        FileOps.child(destination.parentFile, destination.name)
        require(File(directory, "cofre.json").isFile) { "Instance metadata is required before commit" }
        FileOps.move(directory, destination)
        Files.delete(File(destination, MARKER).toPath())
    }
    fun rollback() { FileOps.delete(directory) }
    companion object {
        private const val MARKER = ".cofre-import-in-progress"
        internal fun create(root: File): PackStage {
            val dir = FileOps.child(root, ".cofre-import-${UUID.randomUUID()}")
            Files.createDirectories(dir.toPath()); FileOps.atomic(File(dir, MARKER), "staged".toByteArray())
            return PackStage(dir)
        }
        fun cleanupAbandoned(root: File) {
            root.listFiles().orEmpty().filter { it.name.startsWith(".cofre-import-") && !Files.isSymbolicLink(it.toPath()) && File(it, MARKER).isFile }.forEach(FileOps::delete)
        }
    }
}
