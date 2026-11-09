package com.ignacio.cofre.kit.logs

import com.ignacio.cofre.kit.compat.KnownModsCatalog

enum class ExitReason { Memory, SystemKilled, VulkanUnsupported, NativeWindowInUse, OpenGLMod, Mods, MissingDependency, MissingLibrary, JavaVersion, Java, Renderer, Unknown, Quit }

data class GameExitReport(val code: Int, val reason: ExitReason, val clue: String? = null, val instance: String? = null, val logTail: List<String> = emptyList(), val missingVulkanRequirements: List<String> = emptyList(), val mod: String? = null) {
    val title: String get() = if (reason == ExitReason.Quit) "Minecraft closed" else "Minecraft stopped"
    val explanation: String get() = when (reason) {
        ExitReason.Memory -> "Minecraft ran out of memory."
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
        ExitReason.Quit -> "Play again whenever you want."
    }
    val detailsText: String get() = buildList {
        add("$title: $explanation"); instance?.let { add("Instance: $it") }; add("Exit code: $code"); clue?.let { add("Clue: $it") }
        if (missingVulkanRequirements.isNotEmpty()) add("Missing: ${missingVulkanRequirements.joinToString()}")
        if (logTail.isNotEmpty()) { add(""); add("Last log lines:"); addAll(logTail.takeLast(20)) }
    }.joinToString("\n")
    companion object {
        fun diagnose(code: Int, crashReport: String? = null, log: String? = null, instance: String? = null, tail: Int = 60): GameExitReport {
            require(tail >= 0)
            val lines = log.orEmpty().lines().filter { it.isNotEmpty() }
            val report = crashReport.orEmpty().lines().takeWhile { !it.startsWith("-- System Details --") }.filter { it.isNotEmpty() }
            fun result(reason: ExitReason, clue: String? = null, missing: List<String> = emptyList()): GameExitReport =
                GameExitReport(code, reason, clue?.let(::clean), instance, lines.takeLast(tail), missing, if (reason == ExitReason.OpenGLMod) glMod(report + lines) else null)
            val stopped = lines.indexOfLast { "Stopping!" in it }
            if (crashReport == null && stopped >= 0 && lines.drop(stopped + 1).none(::isError)) return result(ExitReason.Quit)
            val errors = lines.filter { isError(it) || listOf("No context is current", "No GLCapabilities", "does not support required", "CodeCache is full", "VK_ERROR_").any { marker -> it.contains(marker, true) } }
            val loader = (report + lines).firstOrNull { "Failure message: " in it || "Error during pre-loading phase: " in it }
            val rules = listOf(
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
            for (source in listOf(report, errors)) {
                for ((reason, signs) in rules) {
                    val line = source.lastOrNull { l -> signs.any { it in l.lowercase() } } ?: continue
                    if (reason == ExitReason.Renderer && loader != null) return result(if (loader.contains("needs language provider")) ExitReason.MissingDependency else ExitReason.Mods, loader.substringAfter("Failure message: ").substringAfter("Error during pre-loading phase: "))
                    val missing = if (reason == ExitReason.VulkanUnsupported) vulkanMissing((report + lines).joinToString("\n")) else emptyList()
                    return result(reason, line, missing)
                }
            }
            if (loader != null) return result(ExitReason.Mods, loader.substringAfter("Failure message: ").substringAfter("Error during pre-loading phase: "))
            val killed = (report + lines).lastOrNull { l -> listOf("low memory killer", "lowmemorykiller", "lmkd", "killed by the system", "killed by android", "sigkill").any { it in l.lowercase() } }
            if (killed != null || code in setOf(137, -9, 9)) return result(ExitReason.SystemKilled, killed)
            val cache = lines.firstOrNull { "CodeCache is full" in it }; if (cache != null) return result(ExitReason.Java, cache)
            val fatal = (report + lines).indexOfFirst { "SIGSEGV" in it || "SIGBUS" in it }
            if (fatal >= 0 && (report + lines).drop(fatal).any { "libvulkan" in it || "vulkan.adreno" in it }) return result(ExitReason.Renderer, "The Vulkan driver crashed.")
            val java = (report + errors).firstOrNull { "Exception" in it || "java.lang." in it } ?: report.firstOrNull { it.startsWith("Description:") }?.substringAfter("Description:")
            return when { java != null || report.isNotEmpty() -> result(ExitReason.Java, java); code == 0 -> result(ExitReason.Quit); else -> result(ExitReason.Unknown, errors.lastOrNull()) }
        }
        private val glMarkers = listOf("no context is current", "no glcapabilities", "no opengl context", "com.mojang.blaze3d.opengl")
        private val platformIDs = setOf("minecraft", "neoforge", "forge", "fml", "fmlloader", "fancymodloader", "mixinextras")
        /** The first mod frame after the OpenGL error: NeoForge's `TRANSFORMER/<id>@<version>/` or a mixin's `from mod <id>`. */
        internal fun glMod(lines: List<String>): String? {
            val start = lines.indexOfFirst { l -> glMarkers.any { it in l.lowercase() } }.takeIf { it >= 0 } ?: return null
            val frame = Regex("^\\s*at [A-Z-]+/([a-z][a-z0-9_]{1,63})@|from mod ([A-Za-z0-9_-]{2,64})")
            for (line in lines.drop(start).take(60)) {
                val id = frame.findAll(line).map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).lowercase() }.firstOrNull { it !in platformIDs } ?: continue
                return KnownModsCatalog.name(id) ?: id.replace('_', ' ').replace('-', ' ').split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
            }
            return null
        }
        fun isError(line: String): Boolean = listOf("error", "exception", "fatal", "caused by", "failed").any { it in line.lowercase() }
        private fun clean(line: String): String {
            val text = line.replace(Regex("^(?:\\s*\\[[^]]*][ :]*\\s*)+"), "").trim()
            return if (text.length <= 200) text else text.take(199) + "…"
        }
    }
}

enum class LaunchRecovery { RepairInstance, Retry, Log }
data class LaunchFailure(val reason: Reason, val detail: String = "") {
    enum class Reason { LoaderMissing, ModsNeedLoader, UnknownLoader, ProfileMissing, DownloadFailed, JVMExit, Unknown }
    val recovery: LaunchRecovery get() = when (reason) { Reason.LoaderMissing, Reason.ModsNeedLoader, Reason.UnknownLoader, Reason.ProfileMissing -> LaunchRecovery.RepairInstance; Reason.DownloadFailed -> LaunchRecovery.Retry; Reason.JVMExit, Reason.Unknown -> LaunchRecovery.Log }
    val message: String get() = when (reason) { Reason.LoaderMissing, Reason.ModsNeedLoader -> "This instance needs an installed NeoForge loader. Repair it before playing."; Reason.UnknownLoader -> "Choose a supported NeoForge loader before playing."; Reason.ProfileMissing -> "The instance profile is missing. Repair it before playing."; Reason.DownloadFailed -> "Game files could not be downloaded: $detail"; Reason.JVMExit -> "Minecraft exited before it reached the game: $detail"; Reason.Unknown -> detail.ifEmpty { "Minecraft could not start. Check the launcher log." } }
    companion object { fun modLoaderFailed(log: String): Boolean = listOf("Failed to start FML", "ModLoadingException", "Missing or unsupported mandatory dependencies").any { it in log } }
}

/**
 * What Minecraft's Vulkan check said is missing. 26.2 logs "Device [x] does not support required extensions, missing:
 * [A, B]" (a Java list) and "Device [x] does not have required feature [name]", one line per feature.
 */
internal fun vulkanMissing(text: String): List<String> {
    val lists = Regex("missing:\\s*[\\[{]([^\\]}]+)[\\]}]", RegexOption.IGNORE_CASE).findAll(text).flatMap { it.groupValues[1].split(',') }
    val features = Regex("does not have required feature \\[([^\\]]+)]", RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] }
    return (lists + features).map { it.trim(' ', '\'', '"') }.filter { it.isNotBlank() }.distinct().toList()
}
