package com.ignacio.cofre.kit.options

import com.ignacio.cofre.kit.modpacks.FileOps
import java.io.File

/** Edits only known keys, keeping unknown lines, comments and line endings. */
class GameOptions(gameDirectory: File) {
    val file: File = FileOps.child(gameDirectory, "options.txt")
    fun get(key: String): String? = text().lineSequence().lastOrNull { it.startsWith("$key:") }?.substringAfter(':')
    fun set(key: String, value: String): Boolean {
        require(key.matches(Regex("[A-Za-z0-9_]+")) && '\n' !in value && '\r' !in value)
        val before = text(); val pattern = Regex("^${Regex.escape(key)}:[^\\r\\n]*", RegexOption.MULTILINE)
        val after = if (pattern.containsMatchIn(before)) pattern.replace(before) { "$key:$value" } else append(before, "$key:$value")
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
        fun distances(physicalMB: Int): Map<String, String> = if (physicalMB >= 6000) mapOf("renderDistance" to "10", "simulationDistance" to "8") else mapOf("renderDistance" to "6", "simulationDistance" to "5")
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
