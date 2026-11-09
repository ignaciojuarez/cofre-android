package com.ignacio.cofre.kit.servercards

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.modpacks.PackFile
import com.ignacio.cofre.kit.servercards.ServerJoinError.Reason
import kotlinx.serialization.Serializable
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable data class MrpackIndex(val dependencies: Map<String, String>, val files: List<PackFile>)
data class InstallEntry(val path: String, val sizeBytes: Long, val required: Boolean, val manual: Boolean, val downloads: List<String>, val hashes: Map<String, String>)
data class InstallPlan(val loader: LoaderRequirement, val gameVersion: String, val javaVersion: Int, val entries: List<InstallEntry>, val totalDownloadSize: Long, val excluded: List<String>, val replacements: List<Replacement>)

object InstallPlanner {
    fun plan(card: ServerCard, index: MrpackIndex, environment: String = "client", platform: String = "android"): InstallPlan {
        val overrides = when (platform) { "android" -> card.platformOverrides?.android; "ios" -> card.platformOverrides?.ios; else -> null }
        val entries = index.files.mapNotNull { file ->
            if (file.path in overrides?.exclude.orEmpty() || file.env[environment] == "unsupported") return@mapNotNull null
            if (file.path.startsWith("mod:")) fail(Reason.InvalidCard, "Unresolved content identifier: ${file.path}")
            try { FileOps.segments(file.path) } catch (_: IllegalArgumentException) { fail(Reason.PathTraversal, file.path) }
            if (file.fileSize !in 0..(250L shl 20)) fail(Reason.SizeLimit)
            file.downloads.forEach { URLPolicy.validate(it, card.security.allowedHosts) }
            val manual = file.downloads.any { url -> val host = url.toHttpUrlOrNull()?.host; host == "curseforge.com" || host?.endsWith(".curseforge.com") == true } || "curseforge" in file.path.lowercase()
            InstallEntry(file.path, file.fileSize, file.env[environment] != "optional" && file.required, manual, file.downloads, file.hashes)
        }
        if (entries.size > 2500 || entries.map { it.path }.distinct().size != entries.size || entries.sumOf { it.sizeBytes } > card.security.maxBytes) fail(Reason.SizeLimit)
        return InstallPlan(card.loader, card.game.version, card.game.javaVersion, entries, entries.sumOf { it.sizeBytes }, overrides?.exclude.orEmpty(), overrides?.replace.orEmpty())
    }
}

data class UpdateDiff(val added: List<String>, val updated: List<String>, val removed: List<String>, val downloadSize: Long, val preservedUserOptional: List<String>)
object UpdateDiffer {
    fun diff(old: MrpackIndex, new: MrpackIndex, userOptional: Set<String> = emptySet()): UpdateDiff {
        require(old.files.map { it.path }.distinct().size == old.files.size && new.files.map { it.path }.distinct().size == new.files.size)
        val a = old.files.associateBy { it.path }; val b = new.files.associateBy { it.path }
        val added = b.keys.filter { it !in a }.sorted(); val removed = a.keys.filter { it !in b && it !in userOptional }.sorted()
        val updated = b.keys.filter { it in a && a[it]?.hashes != b[it]?.hashes }.sorted()
        return UpdateDiff(added, updated, removed, (added + updated).sumOf { b.getValue(it).fileSize }, userOptional.filter { it in a }.sorted())
    }
}

object InstallEngine {
    fun verify(file: File, card: ServerCard) {
        val expected = card.pack.sizeBytes
        if (file.length() > card.security.maxBytes) fail(Reason.SizeLimit)
        if (expected != null && file.length() != expected) fail(Reason.HashMismatch)
        if (!Hashes.sha512(file).equals(card.pack.sha512, true)) fail(Reason.HashMismatch)
    }
}

class FileHashCache(private val root: File) {
    private fun file(sha512: String): File { require(sha512.matches(Regex("[a-f0-9]{128}"))); return FileOps.child(root, sha512) }
    fun get(sha512: String): File? = file(sha512).takeIf { it.isFile }?.also { if (Hashes.sha512(it) != sha512) fail(Reason.HashMismatch) }
    fun put(source: File, sha512: String): File {
        require(source.length() <= 250L shl 20); if (Hashes.sha512(source) != sha512) fail(Reason.HashMismatch)
        val destination = file(sha512); if (!destination.exists()) { destination.parentFile.mkdirs(); FileOps.atomic(destination, source.readBytes()) }; return requireNotNull(get(sha512))
    }
}
