package com.ignacio.cofre.kit.logs

import com.ignacio.cofre.kit.compat.KnownModsCatalog
import com.ignacio.cofre.kit.mods.ModRequirements

enum class ExitReason { Memory, SystemKilled, VulkanUnsupported, NativeWindowInUse, OpenGLMod, Mods, MissingDependency, MissingLibrary, JavaVersion, Java, Renderer, Unknown, Updated, Quit }

data class GameExitReport(val code: Int, val reason: ExitReason, val clue: String? = null, val instance: String? = null, val logTail: List<String> = emptyList(), val missingVulkanRequirements: List<String> = emptyList(), val mod: String? = null, val missingDependencies: List<MissingDependency> = emptyList()) {
    val title: String get() = if (reason == ExitReason.Quit || reason == ExitReason.Updated) "Minecraft closed" else "Minecraft stopped"
    val explanation: String get() = when (reason) {
        ExitReason.Memory -> if (clue == LOW_MEMORY) "Android ran low on memory and closed the game." else "Minecraft ran out of memory."
        ExitReason.SystemKilled -> "Android killed the game process."
        ExitReason.VulkanUnsupported -> "This device is missing Vulkan extensions or features Minecraft requires."
        ExitReason.NativeWindowInUse -> "The game surface is already in use."
        ExitReason.OpenGLMod -> "${mod ?: "A mod"} called OpenGL, which is unavailable with Vulkan."
        ExitReason.Mods -> "A mod failed to load or conflicts with another mod."
        ExitReason.MissingDependency -> "A mod needs a dependency that is missing or incompatible."
        ExitReason.MissingLibrary -> "A game file or native library is missing or could not load."
        ExitReason.JavaVersion -> "Minecraft or a mod needs a different Java version."
        ExitReason.Java -> if (clue?.contains("CodeCache is full") == true) "Java ran out of room for compiled code." else "Minecraft hit a Java error."
        ExitReason.Renderer -> "The Vulkan renderer failed."
        ExitReason.Unknown -> "Minecraft closed unexpectedly."
        ExitReason.Updated -> "Cofre was updated, which closed the game."
        ExitReason.Quit -> "You quit the game."
    }
    val nextStep: String get() = when (reason) {
        ExitReason.Memory, ExitReason.SystemKilled -> "Lower the instance memory limit or remove mods, then try again."
        ExitReason.VulkanUnsupported -> "Use a device or driver that supports the listed Vulkan requirements."
        ExitReason.NativeWindowInUse -> "Restart the game. Check that the early loading window is disabled."
        ExitReason.OpenGLMod -> "Turn off ${mod ?: "the mod that calls OpenGL"} in Mods, then play again."
        ExitReason.Mods, ExitReason.MissingDependency -> "Read Details to update the mod or install its dependency."
        ExitReason.MissingLibrary -> "Repair the instance to recheck game files."
        ExitReason.JavaVersion -> "Use Java 25 for Minecraft 26.2 and compatible mod versions."
        ExitReason.Java, ExitReason.Renderer, ExitReason.Unknown -> "Try again; share a redacted report if it keeps happening."
        ExitReason.Updated, ExitReason.Quit -> "Play again whenever you want."
    }
    companion object {
        /**
         * [killedAs] is what Android says ended a process that left no exit code (its exit info, mapped by the launcher):
         * [ExitReason.Quit] (the player closed it from Recents), [ExitReason.Memory] (low-memory killer) or
         * [ExitReason.Updated]. It replaces "Android killed the game" only where nothing in the reports says more.
         */
        fun diagnose(code: Int, crashReport: String? = null, log: String? = null, instance: String? = null, tail: Int = 60, killedAs: ExitReason? = null): GameExitReport {
            require(tail >= 0)
            val lines = log.orEmpty().lines().filter { it.isNotEmpty() }
            val report = crashReport.orEmpty().lines().takeWhile { !it.startsWith("-- System Details --") }.filter { it.isNotEmpty() }
            val dependencies = dependencies(report + lines)
            // Only absent mods: one there in the wrong version is the user's to update, not "Get missing mods"' to fetch.
            fun result(reason: ExitReason, clue: String? = null, missing: List<String> = emptyList()): GameExitReport =
                GameExitReport(code, reason, clue?.let(::clean), instance, lines.takeLast(tail), missing, if (reason == ExitReason.OpenGLMod) glMod(report + lines) else null, dependencies.filter { it.actual == MISSING })
            // FML's error screen (missing mods, a loader failure) ends like a quit, "Stopping!" and code 0 included: what it says wins.
            val loader = (report + lines).firstOrNull { "Failure message: " in it || "Error during pre-loading phase: " in it }
            val stopped = lines.indexOfLast { "Stopping!" in it }
            if (crashReport == null && loader == null && dependencies.isEmpty() && stopped >= 0 && lines.drop(stopped + 1).none(::isError)) return result(ExitReason.Quit)
            val errors = lines.filter { isError(it) || listOf("No context is current", "No GLCapabilities", "does not support required", "CodeCache is full", "VK_ERROR_").any { marker -> it.contains(marker, true) } }
            // A Vulkan-unsuitable device makes 26.2 fall back to OpenGL, whose "no context" crash is only the aftermath.
            val all = (report + lines).map { it.lowercase() }
            val vulkanSigns = rules().first { it.first == ExitReason.VulkanUnsupported }.second
            val vulkan = (report + lines).lastOrNull { l -> vulkanSigns.any { it in l.lowercase() } }
                ?: (report + lines).lastOrNull { "failed to create backend vulkan" in it.lowercase() }?.takeIf { all.none { "vk_error_native_window_in_use_khr" in it } }
            if (vulkan != null) return result(ExitReason.VulkanUnsupported, vulkan, vulkanMissing((report + lines).joinToString("\n")))
            if (dependencies.isNotEmpty()) return result(ExitReason.MissingDependency, dependencies.joinToString("; ") { "${modName(it.requestedBy.removeSuffix(".jar"))} needs ${it.modId}" })
            // Code 0 is a clean exit whatever the log says after "Stopping!" (a failed chunk save, say).
            if (code == 0 && crashReport == null && loader == null) return result(ExitReason.Quit)
            // A SIGKILL leaves no crash report; whatever the log said last (a mixin warning, say) isn't why it stopped.
            // FML's own error screen (missing mods, loader failure) still names the real problem if the player swiped it away.
            val killed = (report + lines).lastOrNull { l -> listOf("low memory killer", "lowmemorykiller", "lmkd", "killed by the system", "killed by android", "sigkill").any { it in l.lowercase() } }
            if (crashReport == null && loader == null && code in setOf(137, -9, 9)) {
                return result(killedAs ?: ExitReason.SystemKilled, if (killedAs == ExitReason.Memory) LOW_MEMORY else killed)
            }
            // A native crash in the driver: hs_err's "Internal exceptions" would otherwise read as a mod error.
            val fatal = (report + lines).indexOfFirst { "SIGSEGV" in it || "SIGBUS" in it }
            if (fatal >= 0 && (report + lines).drop(fatal).any { "libvulkan" in it || "vulkan.adreno" in it }) return result(ExitReason.Renderer, "The Vulkan driver crashed.")
            val rules = rules()
            for (source in listOf(report, errors)) {
                for ((reason, signs) in rules) {
                    val line = source.lastOrNull { l -> signs.any { it in l.lowercase() } } ?: continue
                    if (reason == ExitReason.Renderer && loader != null) return result(if (loader.contains("needs language provider")) ExitReason.MissingDependency else ExitReason.Mods, loader.substringAfter("Failure message: ").substringAfter("Error during pre-loading phase: "))
                    return result(reason, line)
                }
            }
            if (loader != null) return result(ExitReason.Mods, loader.substringAfter("Failure message: ").substringAfter("Error during pre-loading phase: "))
            if (killed != null || code in setOf(137, -9, 9)) return result(ExitReason.SystemKilled, killed)
            val cache = lines.firstOrNull { "CodeCache is full" in it }; if (cache != null) return result(ExitReason.Java, cache)
            val java = (report + errors).firstOrNull { "Exception" in it || "java.lang." in it } ?: report.firstOrNull { it.startsWith("Description:") }?.substringAfter("Description:")
            return when { java != null || report.isNotEmpty() -> result(ExitReason.Java, java); code == 0 -> result(ExitReason.Quit); else -> result(ExitReason.Unknown, errors.lastOrNull()) }
        }
        private fun rules() = listOf(
                ExitReason.Memory to listOf("outofmemoryerror", "out of memory", "gc overhead limit", "cannot allocate memory"),
                ExitReason.VulkanUnsupported to listOf("does not support required extensions", "does not support required features", "does not have required feature", "no suitable vulkan device", "failed to find a suitable physical device", "vulkan device not suitable", "missing required vulkan"),
                ExitReason.NativeWindowInUse to listOf("vk_error_native_window_in_use_khr"),
                ExitReason.OpenGLMod to listOf("no context is current", "no glcapabilities instance set", "no opengl context", "cannot be cast to class com.mojang.blaze3d.opengl"),
                ExitReason.MissingDependency to listOf("missing or unsupported mandatory dependencies", "requires version", "requires any version", "missing dependency", "needs language provider", "requires mod"),
                ExitReason.JavaVersion to listOf("unsupportedclassversionerror", "class file version", "unsupported major.minor version", "requires java 25"),
                ExitReason.Mods to listOf("failed to start fml", "modloadingexception", "incompatible mods found", "incompatible mod set", "modresolutionexception", "mod resolution", "duplicate mod", "duplicatemods", "mixin apply", "mixinapplyerror", "invalidinjectionexception", "nosuchmethoderror", "nosuchfielderror"),
                ExitReason.MissingLibrary to listOf("unsatisfiedlinkerror", "noclassdeffounderror", "classnotfoundexception", "failed to load library", "library not loaded"),
                ExitReason.Renderer to listOf("vk_error", "glfw error", "failed to create window", "vulkan renderer", "failed to create swapchain")
        )
        private val glMarkers = listOf("no context is current", "no glcapabilities", "no opengl context", "com.mojang.blaze3d.opengl")
        private val platformIDs = setOf("minecraft", "neoforge", "forge", "fml", "fmlloader", "fancymodloader", "mixinextras")
        /** The first mod frame after the OpenGL error: NeoForge's `TRANSFORMER/<id>@<version>/` or a mixin's `from mod <id>`. */
        internal fun glMod(lines: List<String>): String? {
            val start = lines.indexOfFirst { l -> glMarkers.any { it in l.lowercase() } }.takeIf { it >= 0 } ?: return null
            val frame = Regex("^\\s*at [A-Z-]+/([a-z][a-z0-9_]{1,63})@|from mod ([A-Za-z0-9_-]{2,64})")
            for (line in lines.drop(start).take(60)) {
                val id = frame.findAll(line).map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).lowercase() }.firstOrNull { it !in platformIDs } ?: continue
                return modName(id)
            }
            return null
        }
        private fun modName(id: String) = KnownModsCatalog.name(id) ?: id.replace('_', ' ').replace('-', ' ').split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
        private const val MISSING = "[MISSING]"
        /** The clue for a [ExitReason.Memory] stop Android made (its low-memory killer), not the game's heap. */
        private const val LOW_MEMORY = "Android's low-memory killer"
        private val dependencyLine = Regex("Mod ID: '([^']+)', Requested by: '([^']+)', Expected range: '([^']*)', Actual version: '([^']*)'")
        private val languageProvider = Regex("Mod File (?:mods/)?(\\S+?\\.jar) needs language provider ([A-Za-z0-9_-]+)")
        /**
         * FML's "Missing or unsupported mandatory dependencies:" block (one tab-indented line per dependency, wrong
         * versions too) and its "Mod File x.jar needs language provider y" pre-loading error.
         */
        private fun dependencies(lines: List<String>): List<MissingDependency> = lines.flatMap { raw ->
            val line = raw.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "")
            listOfNotNull(
                dependencyLine.find(line)?.destructured?.let { (id, by, range, actual) -> MissingDependency(id, by, range, actual) },
                languageProvider.find(line)?.destructured?.let { (file, id) -> MissingDependency(id, file, "", MISSING) },
            )
        }.filter { it.modId.lowercase() !in ModRequirements.builtIn }.distinctBy { it.modId }
        fun isError(line: String): Boolean = listOf("error", "exception", "fatal", "caused by", "failed").any { it in line.lowercase() }
        private fun clean(line: String): String {
            val text = line.replace(Regex("^(?:\\s*\\[[^]]*][ :]*\\s*)+"), "").trim()
            return if (text.length <= 200) text else text.take(199) + "…"
        }
    }
}

data class MissingDependency(val modId: String, val requestedBy: String, val range: String, val actual: String)

/**
 * What Minecraft's Vulkan check said is missing. 26.2 logs "Device [x] does not support required extensions, missing:
 * [A, B]" (a Java list) and "Device [x] does not have required feature [name]", one line per feature.
 */
internal fun vulkanMissing(text: String): List<String> {
    val lists = Regex("missing:\\s*[\\[{]([^\\]}]+)[\\]}]", RegexOption.IGNORE_CASE).findAll(text).flatMap { it.groupValues[1].split(',') }
    val features = Regex("does not have required feature \\[([^\\]]+)]", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] }
    return (lists + features).map { it.trim(' ', '\'', '"') }.filter { it.isNotBlank() }.distinct().toList()
}
