package com.ignacio.cofre.kit.content

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.modpacks.FileOps
import com.ignacio.cofre.kit.modpacks.Zip
import com.ignacio.cofre.kit.mods.ModJarMetadata
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.UUID

class ContentStore(val gameDirectory: File) {
    data class Item(val id: String, val name: String, val version: String, val world: String?, val kind: ContentKind, val enabled: Boolean, val inUse: Boolean, val modID: String? = null) {
        val file: String get() = id.substringAfterLast('/')
        val urlName: String get() = file + if (enabled) "" else ".disabled"
    }
    data class TrashToken(val path: String, val trashID: String)
    fun folder(kind: ContentKind, world: String? = null): File {
        val name = requireNotNull(kind.folderName) { "Modpacks create instances" }
        return if (kind == ContentKind.DataPacks) {
            require(!world.isNullOrBlank() && '/' !in world)
            FileOps.child(gameDirectory, "saves/$world/$name")
        } else FileOps.child(gameDirectory, name)
    }
    fun list(kind: ContentKind): List<Item> {
        require(kind.folderName != null) { "Modpacks create instances" }
        val worlds: List<String?> = if (kind == ContentKind.DataPacks) FileOps.child(gameDirectory, "saves").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }.map { it.name } else listOf(null)
        val active = when (kind) { ContentKind.ResourcePacks -> resourcePacks(); ContentKind.Shaders -> setOfNotNull(activeShaderPack()); else -> emptySet() }
        return worlds.flatMap { world ->
            val dir = folder(kind, world)
            dir.listFiles().orEmpty().filter { !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }.map { file ->
                val enabled = !file.name.endsWith(".disabled"); val logical = file.name.removeSuffix(".disabled")
                val fallback = logical.substringBeforeLast('.', logical)
                val meta = metadata(file)
                Item(if (world == null) logical else "$world/$logical", meta?.name ?: fallback, meta?.version ?: fallback.substringAfterLast('-', ""), world, kind, enabled, enabled && logical in active, meta?.id)
            }
        }.sortedBy { (it.world ?: "") + it.name }
    }
    fun file(item: Item): File {
        require(item.file.isNotBlank() && item.id == (item.world?.let { "$it/" } ?: "") + item.file)
        return FileOps.child(folder(item.kind, item.world), item.urlName)
    }
    fun setEnabled(enabled: Boolean, item: Item) {
        val dir = folder(item.kind, item.world)
        val on = FileOps.child(dir, item.file); val off = FileOps.child(dir, item.file + ".disabled")
        val source = if (enabled) off else on; val target = if (enabled) on else off
        if (source.exists()) FileOps.move(source, target)
    }
    fun trash(item: Item): TrashToken {
        val source = file(item)
        val token = TrashToken(source.relativeTo(gameDirectory.absoluteFile).invariantSeparatorsPath, UUID.randomUUID().toString())
        FileOps.move(source, trashFile(token)); trashFile(token).setLastModified(System.currentTimeMillis()) // the trash date, for purging
        return token
    }
    fun restore(token: TrashToken) { FileOps.move(trashFile(token), FileOps.child(gameDirectory, token.path)) }
    fun delete(item: Item) { trash(item) }
    private fun trashFile(token: TrashToken): File { require(token.trashID.matches(Regex("[a-f0-9-]{36}"))); return FileOps.child(gameDirectory, ".cofre-trash/content/${token.trashID}") }
    fun activeShaderPack(): String? {
        for (name in listOf("iris.properties", "oculus.properties")) {
            val file = FileOps.child(gameDirectory, "config/$name"); if (!file.isFile) continue
            val p = properties(file.readText())
            if (p["enableShaders"] == "false") return null
            p["shaderPack"]?.takeIf { it.isNotBlank() && it !in setOf("OFF", "(internal)") }?.let { return it }
        }
        val legacy = FileOps.child(gameDirectory, "optionsshaders.txt")
        return if (legacy.isFile) properties(legacy.readText())["shaderPack"]?.takeIf { it.isNotBlank() && it !in setOf("OFF", "(internal)") } else null
    }
    private fun resourcePacks(): Set<String> {
        val file = FileOps.child(gameDirectory, "options.txt"); if (!file.isFile) return emptySet()
        val value = file.readLines().lastOrNull { it.startsWith("resourcePacks:") }?.substringAfter(':') ?: return emptySet()
        val names = runCatching { Kit.json.decodeFromString<List<String>>(value) }.getOrElse { value.trim('[', ']').split(',').map { it.trim(' ', '"') } }
        return names.filter { it.startsWith("file/") }.map { it.removePrefix("file/") }.toSet()
    }
    private data class Metadata(val id: String?, val name: String?, val version: String?)
    /** A jar's mod metadata (cached per file), else a pack's `pack.mcmeta`, from a folder or a zip. */
    private fun metadata(file: File): Metadata? = runCatching {
        if (file.name.removeSuffix(".disabled").endsWith(".jar", ignoreCase = true)) return@runCatching ModJarMetadata.read(file)?.let { Metadata(it.modIds.firstOrNull(), it.name, it.version) }
        val text = if (file.isDirectory) FileOps.child(file, "pack.mcmeta").takeIf { it.isFile && it.length() <= 1 shl 20 }?.readText()
            else Zip.read(file, "pack.mcmeta", 1L shl 20).decodeToString()
        val pack = Kit.json.parseToJsonElement(text ?: return@runCatching null).jsonObject.getValue("pack").jsonObject
        Metadata(null, pack["description"]?.let(::flatten), pack["pack_format"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()
    companion object {
        fun properties(text: String): Map<String, String> = Properties().apply { load(text.reader()) }.entries.associate { it.key.toString() to it.value.toString() }
        private fun flatten(e: JsonElement): String = when (e) { is JsonPrimitive -> e.content; is JsonArray -> e.joinToString("") { flatten(it) }; is JsonObject -> (e["text"]?.let(::flatten) ?: "") + (e["extra"]?.let(::flatten) ?: "") }
    }
}

fun ContentKind.updateLoaders(loader: Loader): List<String> = when (this) {
    ContentKind.Mods -> if (loader == Loader.Quilt) listOf("quilt", "fabric") else listOf(loader.modrinthId)
    ContentKind.Shaders -> listOf("iris", "optifine", "canvas", "vanilla")
    ContentKind.ResourcePacks -> listOf("minecraft")
    ContentKind.DataPacks -> listOf("datapack")
    ContentKind.Modpacks -> emptyList()
}
