package com.ignacio.cofre.kit.logs

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.modpacks.FileOps
import com.ignacio.cofre.kit.modpacks.Zip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant

enum class LogLevel { Info, Warning, Error, Success }
enum class LogFile { Launcher, Game, Crash }
data class LogEntry(val id: Int, val text: String, val level: LogLevel, val source: LogFile)

class LogStore(val instance: File, val launcherLog: File) {
    val gameLog: File get() = FileOps.child(instance, "logs/latest.log")
    fun crashReports(since: Instant = Instant.MIN): List<File> = FileOps.child(instance, "crash-reports").listFiles().orEmpty().filter { it.isFile && it.extension == "txt" && !Files.isSymbolicLink(it.toPath()) && Instant.ofEpochMilli(it.lastModified()) >= since }.sortedByDescending { it.lastModified() }
    fun newestCrashReport(since: Instant = Instant.MIN): File? = crashReports(since).firstOrNull()
    fun file(source: LogFile): File? = when (source) { LogFile.Launcher -> launcherLog; LogFile.Game -> gameLog; LogFile.Crash -> newestCrashReport() }
    fun read(source: LogFile): List<LogEntry> = file(source)?.let(::text)?.lineSequence()?.filter { it.isNotEmpty() }?.mapIndexed { i, line -> LogEntry(i, line, level(line), source) }?.toList().orEmpty()
    fun search(query: String = "", levels: Set<LogLevel> = LogLevel.entries.toSet(), sources: Set<LogFile> = LogFile.entries.toSet()): List<LogEntry> = LogFile.entries.filter { it in sources }.flatMap(::read).filter { it.level in levels && it.text.contains(query.trim(), ignoreCase = true) }
    companion object {
        private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
        internal fun text(file: File): String {
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) return ""
            return RandomAccessFile(file, "r").use { input ->
                val start = maxOf(0, input.length() - MAX_TEXT_BYTES); input.seek(start)
                val bytes = ByteArray((input.length() - start).toInt()); input.readFully(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                if (start > 0) text.substringAfter('\n', "") else text
            }
        }
        private fun level(text: String): LogLevel {
            val lower = text.lowercase()
            return when { listOf("error", "exception", "fatal").any { it in lower } -> LogLevel.Error; "warn" in lower -> LogLevel.Warning; else -> LogLevel.Info }
        }
    }
}

@Serializable
data class ReportInstance(val minecraftVersion: String? = null, val loader: String? = null, val mods: Map<String, String> = emptyMap(), val renderer: String = "vulkan", val java: String? = null, val ramMB: Int? = null)
@Serializable
data class ReportContext(val appVersion: String? = null, val build: String? = null, val device: String? = null, val android: String? = null, val instance: ReportInstance? = null, val exitReason: String? = null, val clue: String? = null)

class ReportBundle(val store: LogStore, val context: ReportContext = ReportContext()) {
    fun make(destination: File): File {
        val stage = Files.createTempDirectory("cofre-report-").toFile()
        try {
            val logs = LogFile.entries.flatMap(store::read).joinToString("\n") { it.text }
            val json = Kit.json.encodeToJsonElement(context).jsonObject + ("crashSignature" to JsonPrimitive(signature(logs + context.clue.orEmpty())))
            File(stage, "report.json").writeText(Kit.json.encodeToString(redactJSON(JsonObject(json))))
            File(stage, "logs.txt").writeText(redact(logs))
            store.crashReports().take(50).forEachIndexed { i, crash -> File(stage, "crash-reports/crash-$i.txt").apply { parentFile.mkdirs(); writeText(redact(LogStore.text(crash))) } }
            Zip.write(stage, destination); return destination
        } finally { FileOps.delete(stage) }
    }
    companion object {
        fun signature(text: String): String = Hashes.sha256(redact(text).toByteArray(Charsets.UTF_8))
        fun redact(text: String): String {
            // Minecraft logs "Setting user: <name>"; the name then turns up in chat, joins and paths, so drop it everywhere.
            val names = Regex("(?i)(?:Setting user:|--username)\\s+([A-Za-z0-9_]{3,16})\\b").findAll(text).map { it.groupValues[1] }.toSet()
            var s = names.fold(text) { acc, name -> acc.replace(Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])"), "<redacted-player>") }
            val rules = listOf(
                "(?i)(authorization\\s*[=:]\\s*bearer\\s+)[^\\s,;\"']+" to "$1<redacted>",
                "(?i)((?:--)?(?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|client[_-]?secret|password)[\"']?\\s*(?:[=:]\\s*|\\s+)[\"']?)[^\\s,;\"']+" to "$1<redacted>",
                "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b" to "<redacted-token>",
                "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}" to "<redacted-email>",
                "(?i)(?<![a-z0-9])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![a-z0-9])" to "<redacted-uuid>",
                "(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?" to "<redacted-address>",
                "(?i)(?<![a-z0-9:])\\[?(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]*\\]?(?::\\d+)?" to "<redacted-address>",
                "(?i)(?<![a-z0-9])(?:[a-z]:\\\\(?:Users|Documents and Settings)\\\\|/(?:Users|home|Volumes|tmp|private/var|var/mobile|data/(?:user|user_de|data)|storage/emulated|sdcard)/)[^\\r\\n,;\"']+" to "<redacted-path>",
                "(?i)(?<![a-z0-9-])(?:[a-z0-9-]+\\.)+(?:com|net|org|io|gg|dev|co|me|xyz|sh|app|cloud|pro|eu|us|ca|uk)(?::\\d+)?(?![a-z0-9-])" to "<redacted-host>",
                "(?i)\\b(xuid|player(?:name)?|username)[\"']?\\s*[=:]\\s*[\"']?[^\\s,;\"']+" to "player=<redacted>"
            )
            for ((pattern, replacement) in rules) s = Regex(pattern).replace(s, replacement)
            return s
        }
        private fun redactJSON(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapKeys { redact(it.key) }.mapValues { redactJSON(it.value) })
            is JsonArray -> JsonArray(value.map(::redactJSON))
            is JsonPrimitive -> if (value.isString) JsonPrimitive(redact(value.content)) else value
        }
    }
}
