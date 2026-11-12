package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile

@Serializable enum class ModSide { Both, Client, Server;
    companion object { fun from(value: String?): ModSide = when (value?.lowercase()) { "client" -> Client; "server" -> Server; else -> Both } }
}
@Serializable enum class Relation { Required, Optional, Incompatible }
@Serializable data class JarDependency(val id: String, val relation: Relation = Relation.Required, val versionRange: String? = null, val side: ModSide = ModSide.Both, val owner: String? = null, val name: String = id) {
    val required: Boolean get() = relation == Relation.Required && side != ModSide.Server
}

/**
 * The FML TOML subset: scalar root keys, [[mods]], [[dependencies.owner]], multiline descriptions, and the inline form
 * some build tools write (`mods = [{modId = 'x'}]`, `dependencies.x = [{modId = 'y', type = 'required'}]`).
 */
class ModsToml(text: String) {
    data class Table(val name: String, val values: Map<String, String>)
    val tables: List<Table>
    val root: Map<String, String>
    val modLoader: String? get() = root["modLoader"]
    val mods: List<Map<String, String>> get() = tables.filter { it.name == "mods" }.map { it.values }
    val modIds: List<String> get() = mods.mapNotNull { it["modId"] }
    val dependencies: List<JarDependency> get() = tables.filter { it.name.startsWith("dependencies.") }.mapNotNull { table ->
        val row = table.values; val id = row["modId"] ?: return@mapNotNull null
        val relation = when {
            row["type"]?.lowercase() == "incompatible" -> Relation.Incompatible
            row["type"]?.lowercase() == "required" || row["required"] == "true" || row["mandatory"] == "true" -> Relation.Required
            else -> Relation.Optional
        }
        JarDependency(id, relation, row["versionRange"], ModSide.from(row["side"]), table.name.removePrefix("dependencies."))
    }
    init {
        val out = mutableListOf<Table>(); val roots = linkedMapOf<String, String>()
        var table = ""; var entry = linkedMapOf<String, String>(); var closing: String? = null
        var inline: Pair<String, StringBuilder>? = null
        fun flush() { if (table.isNotEmpty()) out += Table(table, entry.toMap()); entry = linkedMapOf() }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val end = closing
            if (end != null) { if (line.contains(end)) closing = null; continue }
            val open = inline
            if (open != null) {
                open.second.append(' ').append(line)
                if (depth(open.second) == 0) { out += inlineTables(open.first, open.second); inline = null }
                continue
            }
            if (line.startsWith("#") || line.isEmpty()) continue
            if (line.startsWith("[")) {
                flush(); table = line.substringBefore('#').trim().trim('[', ']', ' ', '\t').split('.').joinToString(".") { it.trim().trim('"', '\'') }; continue
            }
            val eq = line.indexOf('='); if (eq < 0) continue
            val key = line.substring(0, eq).trim().trim('"', '\'')
            val rawValue = line.substring(eq + 1).trim()
            if (rawValue.startsWith("[") && rawValue.drop(1).trimStart().let { it.isEmpty() || it.startsWith("{") }) {
                val name = (if (table.isEmpty()) key else "$table.$key").split('.').joinToString(".") { it.trim().trim('"', '\'') }
                val value = StringBuilder(rawValue)
                if (depth(value) == 0) out += inlineTables(name, value) else inline = name to value
                continue
            }
            val triple = listOf("\"\"\"", "'''").firstOrNull { rawValue.startsWith(it) }
            if (triple != null) { if (!rawValue.drop(3).contains(triple)) closing = triple; continue }
            val value = scalar(rawValue) ?: continue
            if (value.contains("\${")) continue
            if (table.isEmpty()) roots[key] = value else entry[key] = value
        }
        flush(); tables = out; root = roots
    }
    /** Bracket depth of [text] outside quotes: 0 once an inline array is complete. */
    private fun depth(text: CharSequence): Int {
        var depth = 0; var quote: Char? = null
        for (c in text) when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '[' || c == '{' -> depth++
            c == ']' || c == '}' -> depth--
        }
        return depth
    }
    /** `[{a = 'x', b = 1}, {…}]` → one [Table] per `{…}`, named [name]. Nested arrays and tables are skipped. */
    private fun inlineTables(name: String, text: CharSequence): List<Table> {
        val tables = mutableListOf<Table>(); var depth = 0; var quote: Char? = null
        val pairs = mutableListOf<String>(); val current = StringBuilder()
        for (c in text) {
            if (quote != null) { if (depth >= 2) current.append(c); if (c == quote) quote = null; continue }
            when (c) {
                '"', '\'' -> { quote = c; if (depth >= 2) current.append(c) }
                '[', '{' -> { depth++; if (depth > 2) current.append(c) }
                ']', '}' -> {
                    if (depth > 2) current.append(c)
                    if (depth == 2 && c == '}') {
                        pairs += current.toString(); current.clear()
                        tables += Table(name, pairs.mapNotNull { pair ->
                            val eq = pair.indexOf('='); if (eq < 0) return@mapNotNull null
                            val value = pair.substring(eq + 1).trim()
                            if (value.startsWith("[") || value.startsWith("{")) return@mapNotNull null
                            scalar(value)?.let { pair.substring(0, eq).trim().trim('"', '\'') to it }
                        }.toMap())
                        pairs.clear()
                    }
                    depth--
                }
                ',' -> if (depth == 2) { pairs += current.toString(); current.clear() } else if (depth > 2) current.append(c)
                else -> if (depth >= 2) current.append(c)
            }
        }
        return tables
    }
    private fun scalar(raw: String): String? {
        if (raw.startsWith('\'')) return raw.drop(1).substringBefore('\'')
        if (raw.startsWith('"')) {
            var escaped = false
            for (index in 1 until raw.length) {
                val c = raw[index]
                if (c == '"' && !escaped) return runCatching { Kit.json.decodeFromString<String>(raw.substring(0, index + 1)) }.getOrNull()
                escaped = c == '\\' && !escaped
            }
            return null
        }
        return raw.substringBefore('#').trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * Metadata is bounded before inflation; bundled jars are inspected one level deep. [modIds] lists the jar's own ids,
 * then the ids only its bundled (jar-in-jar) libraries declare ([nested]). [loaders]: whose metadata the jar carries
 * (`neoforge.mods.toml` NeoForge, `mods.toml` Forge, `fabric.mod.json` Fabric, `quilt.mod.json` Quilt).
 */
data class ModJarMetadata(
    val modIds: List<String>, val name: String? = null, val version: String? = null,
    val dependencies: List<JarDependency> = emptyList(), val side: ModSide = ModSide.Both,
    val modLoader: String? = null, val bundled: List<String> = emptyList(), val icon: ByteArray? = null,
    val loaders: Set<Loader> = setOf(Loader.NeoForge), val nested: List<String> = emptyList(),
) {
    /** NeoForge loads it: since 20.5 only `META-INF/neoforge.mods.toml` makes a mod; FML ignores a lone `mods.toml`. */
    val neoForge: Boolean get() = Loader.NeoForge in loaders
    /** The jar's own mod ids, without its bundled libraries'. */
    val own: List<String> get() = modIds.filter { it !in nested }
    companion object {
        // ponytail: 128 jars; a bigger instance re-reads jars on each listing. Drop the unused icon, then raise it, if that shows.
        private val cache = linkedMapOf<String, ModJarMetadata>()
        fun read(jar: File, loader: Loader = Loader.NeoForge): ModJarMetadata? {
            val key = "${jar.canonicalPath}|${jar.length()}|${jar.lastModified()}|$loader"
            synchronized(cache) { cache[key] }?.let { return it }
            val found = runCatching {
                ZipFile(jar).use { zip ->
                    val metadata = parse(loader) { path, limit -> entryBytes(zip, path, limit) } ?: return null
                    val extra = mutableListOf<String>()
                    zip.entries().asSequence().filter { it.name.startsWith("META-INF/jarjar/") && it.name.endsWith(".jar") && it.size in 0..64_000_000 }.take(128).forEach { entry ->
                        val nested = entryBytes(zip, entry.name, 64_000_000) ?: return@forEach
                        // By name through the central directory: a bundled library's metadata often comes after its classes.
                        val temp = File.createTempFile("cofre-jarjar", ".jar")
                        try {
                            temp.writeBytes(nested)
                            runCatching { ZipFile(temp).use { inner -> parse(loader) { path, limit -> entryBytes(inner, path, limit) } } }.getOrNull()?.let { extra += it.modIds }
                        } finally { temp.delete() }
                    }
                    metadata.copy(modIds = (metadata.modIds + extra).distinct(), nested = extra.distinct().filter { it !in metadata.modIds })
                }
            }.getOrNull() ?: return null
            synchronized(cache) { cache[key] = found; if (cache.size > 128) cache.remove(cache.keys.first()) }
            return found
        }
        private fun parse(loader: Loader, bytes: (String, Int) -> ByteArray?): ModJarMetadata? {
            fun obj(path: String): JsonObject? = bytes(path, 1_000_000)?.let { Kit.json.parseToJsonElement(it.decodeToString()).jsonObject }
            var ids = emptyList<String>(); var name: String? = null; var version: String? = null; var side = ModSide.Both
            val dependencies = mutableListOf<JarDependency>(); var icon: ByteArray? = null; var modLoader: String? = null
            val loaders = mutableSetOf<Loader>()
            fun iconData(element: JsonElement?): ByteArray? {
                val path = when (element) { is JsonPrimitive -> element.content; is JsonObject -> element["64"]?.jsonPrimitive?.content ?: element.values.firstOrNull()?.jsonPrimitive?.content; else -> null } ?: return null
                return bytes(path.removePrefix("/"), 2_000_000)
            }
            val fabric = obj("fabric.mod.json")
            val quiltRoot = if (fabric == null) obj("quilt.mod.json") else null
            val quilt = quiltRoot?.get("quilt_loader")?.jsonObject
            if (fabric != null) loaders += Loader.Fabric else if (quilt != null) loaders += Loader.Quilt
            if (fabric != null) {
                ids = listOfNotNull(fabric.text("id")); name = fabric.text("name"); version = fabric.text("version"); side = ModSide.from(fabric.text("environment"))
                for ((key, relation) in listOf("depends" to Relation.Required, "recommends" to Relation.Optional, "suggests" to Relation.Optional, "breaks" to Relation.Incompatible)) {
                    (fabric[key] as? JsonObject)?.toSortedMap()?.forEach { (id, range) -> dependencies += JarDependency(id, relation, if (range is JsonPrimitive) range.content else range.toString()) }
                }
                icon = iconData(fabric["icon"])
            } else if (quilt != null) {
                ids = listOfNotNull(quilt.text("id")); version = quilt.text("version"); val metadata = quilt["metadata"] as? JsonObject
                name = metadata?.text("name"); side = ModSide.from((quiltRoot["minecraft"] as? JsonObject)?.text("environment")); icon = iconData(metadata?.get("icon"))
                for ((key, relation) in listOf("depends" to Relation.Required, "breaks" to Relation.Incompatible)) {
                    (quilt[key] as? JsonArray)?.forEach { row ->
                        val value = row as? JsonObject ?: return@forEach
                        value.text("id")?.let { dependencies += JarDependency(it, if (value["optional"]?.jsonPrimitive?.booleanOrNull == true) Relation.Optional else relation, value["versions"]?.toString()) }
                    }
                }
            }
            val neo = bytes("META-INF/neoforge.mods.toml", 1_000_000)?.also { loaders += Loader.NeoForge }
            val forge = bytes("META-INF/mods.toml", 1_000_000)?.also { loaders += Loader.Forge }
            val toml = (if (loader == Loader.NeoForge) neo ?: forge else forge ?: neo)?.let { ModsToml(it.decodeToString()) }
            if (toml != null) {
                ids = (ids + toml.modIds).distinct(); modLoader = toml.modLoader
                val first = toml.mods.firstOrNull()
                name = name ?: first?.get("displayName") ?: ids.firstOrNull(); version = version ?: first?.get("version")
                if (version == null) version = bytes("META-INF/MANIFEST.MF", 1_000_000)?.let { Manifest(ByteArrayInputStream(it)).mainAttributes.getValue("Implementation-Version") }
                side = ModSide.from(first?.get("side") ?: toml.root["side"])
                // A universal jar (Controlify) also carries fabric.mod.json: its Fabric dependencies don't apply here.
                if (loader == Loader.NeoForge) dependencies.clear()
                dependencies += toml.dependencies
                if (modLoader?.lowercase() == "kotlinforforge") dependencies += JarDependency("kotlinforforge", name = "Kotlin for Forge")
                val logo = first?.get("logoFile") ?: toml.root["logoFile"]
                if (logo != null) icon = bytes(logo.removePrefix("/"), 2_000_000) ?: icon
            }
            if (ids.isEmpty()) return null
            val bundled = (obj("META-INF/jarjar/metadata.json")?.get("jars") as? JsonArray)?.mapNotNull { ((it.jsonObject["identifier"] as? JsonObject)?.text("artifact")) }.orEmpty()
            return ModJarMetadata(ids, name, version, dependencies.distinctBy { listOf(it.id, it.relation, it.side, it.owner) }, side, modLoader, bundled, icon, loaders)
        }
        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun entryBytes(zip: ZipFile, path: String, limit: Int): ByteArray? {
            val entry = zip.getEntry(path) ?: return null
            if (entry.size > limit) return null
            return zip.getInputStream(entry).use { bounded(it, limit) }
        }
        private fun bounded(input: InputStream, limit: Int): ByteArray? {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val read = input.read(buffer); if (read < 0) break; if (out.size() + read > limit) return null; out.write(buffer, 0, read) }
            return out.toByteArray()
        }
    }
}
