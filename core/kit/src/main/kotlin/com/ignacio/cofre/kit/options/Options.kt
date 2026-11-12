package com.ignacio.cofre.kit.options

import com.ignacio.cofre.kit.common.FileOps
import java.io.File

/** Edits only known keys, keeping unknown lines, comments and line endings. */
class GameOptions(gameDirectory: File) {
    val file: File = FileOps.child(gameDirectory, "options.txt")
    fun get(key: String): String? = text().lineSequence().lastOrNull { it.startsWith("$key:") }?.substringAfter(':')
    fun set(key: String, value: String): Boolean {
        require(key.matches(Regex("[A-Za-z0-9_]+")) && '\n' !in value && '\r' !in value)
        // A file Cofre creates starts with the game's data version, or 26.2 runs every options data fix from version 0.
        val before = text(); val pattern = Regex("^${Regex.escape(key)}:[^\\r\\n]*", RegexOption.MULTILINE)
        val base = if (file.exists()) before else "version:$DATA_VERSION"
        val after = if (pattern.containsMatchIn(base)) pattern.replace(base) { "$key:$value" } else append(base, "$key:$value")
        if (before == after) return false
        FileOps.atomic(file, after.toByteArray()); return true
    }
    fun useVulkan(): Boolean = set("preferredGraphicsBackend", "vulkan")
    fun seed(values: Map<String, String>): Boolean {
        var changed = false
        for ((key, value) in values.toSortedMap()) if (get(key) == null) changed = set(key, value) || changed
        return changed
    }
    fun seedDefaults(physicalMB: Int, displayHz: Int = 60): Boolean = seed(distances(physicalMB) + mapOf("maxFps" to maxOf(30, displayHz).toString(), "menuBackgroundBlurriness" to "0"))
    fun seedPreset(preset: CofrePreset, physicalMB: Int): Boolean = seed(preset.options(physicalMB))
    private fun text(): String = if (file.exists()) file.readText() else ""
    companion object {
        /** Minecraft 26.2's data version (`world_version` in its client jar's version.json), which options.txt records as `version`. */
        const val DATA_VERSION = 4903
        fun distances(physicalMB: Int): Map<String, String> = if (physicalMB >= 6 * 1024 * 9 / 10) mapOf("renderDistance" to "10", "simulationDistance" to "8") else mapOf("renderDistance" to "6", "simulationDistance" to "5")
        internal fun append(text: String, line: String): String {
            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            return if (text.isEmpty()) line else if (text.endsWith('\n')) text + line + newline else text + newline + line
        }
    }
}

object FMLConfig {
    fun disableEarlyWindow(gameDirectory: File): Boolean {
        val file = FileOps.child(gameDirectory, "config/fml.toml")
        val before = if (file.exists()) file.readText() else ""
        val table = Regex("^\\s*\\[", RegexOption.MULTILINE).find(before)?.range?.first ?: before.length
        val root = before.take(table)
        val setting = Regex("^(\\s*earlyWindowControl\\s*=\\s*)(true|false)([ \\t]*(?:#[^\\r\\n]*)?)", RegexOption.MULTILINE)
        val after = if (setting.containsMatchIn(root)) setting.replace(root) { it.groupValues[1] + "false" + it.groupValues[3] } + before.drop(table)
        else "earlyWindowControl = false" + (if (before.contains("\r\n")) "\r\n" else "\n") + before
        if (before == after) return false
        FileOps.atomic(file, after.toByteArray()); return true
    }
}

object JvmArguments {
    /** An instance's JVM arguments as typed: split on whitespace, except inside double or single quotes, which are dropped. */
    fun split(text: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        for (c in text) {
            when {
                c == quote -> quote = null
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> { quote = c; started = true }
                c.isWhitespace() -> { if (started) args += current.toString(); current.clear(); started = false }
                else -> { current.append(c); started = true }
            }
        }
        if (started) args += current.toString()
        return args
    }
}

object JavaHeap {
    /**
     * RAM and result in MiB: 8/12/16 GB devices get a 4/6/8 GB heap (PLAN.md). Android's `totalMem` is ~93% of the
     * marketed size (the kernel and carve-outs take the rest), so each tier starts at 90% of it.
     */
    fun autoMB(physicalMB: Int): Int {
        require(physicalMB > 0)
        return when {
            physicalMB >= 16 * 1024 * 9 / 10 -> 8192
            physicalMB >= 12 * 1024 * 9 / 10 -> 6144
            physicalMB >= 8 * 1024 * 9 / 10 -> 4096
            else -> maxOf(2048, physicalMB / 2)
        }
    }
}
