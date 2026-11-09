package com.ignacio.cofre.kit.logs

import com.ignacio.cofre.kit.modpacks.Zip
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.*

class LogsTest {
    @TempDir lateinit var root: File
    private fun write(path: String, text: String): File = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun store(): LogStore = LogStore(File(root, "game"), File(root, "launcher.log"))
    @Test fun sourcesAndNewestCrashAreReadFromGameFiles() {
        write("launcher.log", "launch\n"); write("game/logs/latest.log", "one\ntwo\nthree\n")
        write("game/crash-reports/old.txt", "old").setLastModified(1000); val newest = write("game/crash-reports/new.txt", "new").apply { setLastModified(2000) }
        val s = store(); assertEquals("launch", s.read(LogFile.Launcher).single().text); assertEquals(listOf("one", "two", "three"), s.read(LogFile.Game).map { it.text }); assertEquals(newest, s.newestCrashReport()); assertNull(s.newestCrashReport(Instant.ofEpochMilli(3000)))
    }
    @Test fun searchFiltersQueryLevelsAndSources() {
        write("launcher.log", "[WARN] memory low\n[ERROR] launch failed\n"); write("game/logs/latest.log", "[INFO] loaded\n[ERROR] bad mod\n")
        val s = store(); assertEquals(2, s.search(levels = setOf(LogLevel.Error)).size); assertEquals("[ERROR] bad mod", s.search(" MOD ", sources = setOf(LogFile.Game)).single().text)
    }
    @Test fun missingSourcesReturnEmptyEntries() { assertTrue(store().read(LogFile.Game).isEmpty()); assertTrue(store().search().isEmpty()) }
    @Test fun redactionCoversTokensFlagsBearerJWTEmailsAndPlayers() {
        val input = "token=SECRET access_token:ACCESS --accessToken CLI refreshToken=REFRESH Authorization: Bearer BEARER me@example.com username=Alex eyJabc.abc.abc"
        val text = ReportBundle.redact(input)
        for (secret in listOf("SECRET", "ACCESS", "CLI", "REFRESH", "BEARER", "me@example.com", "Alex", "eyJabc.abc.abc")) assertFalse(text.contains(secret), secret)
        val json = ReportBundle.redact("{\"access_token\":\"SECRET\",\"refresh_token\":\"REFRESH\"}")
        assertFalse("SECRET" in json || "REFRESH" in json)
    }
    @Test fun redactionDropsThePlayerNameEverywhere() {
        val input = "[12:00:00] [Render thread/INFO]: Setting user: Steve_99\n[12:00:05] [Server thread/INFO]: Steve_99 joined the game\n<Steve_99> hi\n--username Steve_99 --version 26.2"
        val text = ReportBundle.redact(input)
        assertFalse("Steve_99" in text, text)
        assertTrue("Setting user: <redacted-player>" in text && "joined the game" in text, text)
    }
    @Test fun redactionCoversIPsHostsUUIDsAndUserPaths() {
        val input = "192.168.1.2:25565 [2001:db8::1]:25565 ::1\n/Users/alice/My Games/cofre/log.txt\n/data/user/0/com.ignacio.cofre/files/game\n/storage/emulated/0/Documents/game\nC:\\Users\\Alice\\Game\\latest.log\nplay.example.com 123e4567-e89b-12d3-a456-426614174000"
        val text = ReportBundle.redact(input)
        for (secret in listOf("192.168.1.2", "2001:db8", "::1", "alice", "Alice", "com.ignacio", "emulated/0", "play.example.com", "123e4567")) assertFalse(secret in text, secret)
    }
    @Test fun reportRedactsContextAndEveryArchiveMember() {
        write("game/logs/latest.log", "token=SECRET"); write("game/crash-reports/a.txt", "user@example.com /home/alice/world")
        val context = ReportContext(device = "me@example.com", instance = ReportInstance(mods = mapOf("user@example.com" to "token=SECRET")), clue = "username=Alex")
        val bundle = ReportBundle(store(), context); val zip = bundle.make(File(root, "report.zip")); assertEquals(3, Zip.entries(zip).count { !it.directory })
        for (entry in Zip.entries(zip).filterNot { it.directory }) {
            val text = Zip.read(zip, entry.path).decodeToString(); assertFalse(listOf("SECRET", "me@example.com", "user@example.com", "alice", "Alex").any { it in text }, entry.path)
        }
        assertTrue(Zip.read(zip, "report.json").decodeToString().contains("crashSignature"))
    }
    @Test fun crashSignatureIsRealSHA256AndStableAfterRedaction() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ReportBundle.signature("abc"))
        assertEquals(ReportBundle.signature("token=first"), ReportBundle.signature("token=second"))
    }
    @Test fun androidVulkanReasonsHaveActionableCopy() {
        val cases = mapOf(
            "java.lang.OutOfMemoryError: Java heap space" to ExitReason.Memory,
            "[ERROR] device does not support required extensions, missing: {VK_KHR_push_descriptor, VK_KHR_swapchain}" to ExitReason.VulkanUnsupported,
            "[ERROR] VK_ERROR_NATIVE_WINDOW_IN_USE_KHR" to ExitReason.NativeWindowInUse,
            "java.lang.IllegalStateException: No context is current" to ExitReason.OpenGLMod,
            "[ERROR] net.neoforged.fml.ModLoadingException: bad" to ExitReason.Mods,
            "[ERROR] Missing or unsupported mandatory dependencies" to ExitReason.MissingDependency,
            "java.lang.UnsupportedClassVersionError: class file version 69.0" to ExitReason.JavaVersion,
            "java.lang.UnsatisfiedLinkError: missing lib" to ExitReason.MissingLibrary,
            "[ERROR] VK_ERROR_DEVICE_LOST" to ExitReason.Renderer,
            "java.lang.NullPointerException: boom" to ExitReason.Java,
            "lmkd killed by the system" to ExitReason.SystemKilled
        )
        for ((text, expected) in cases) { val r = GameExitReport.diagnose(1, log = text); assertEquals(expected, r.reason, text); assertTrue(r.explanation.isNotBlank() && r.nextStep.isNotBlank()) }
    }
    @Test fun vulkanMissingExtensionAndFeatureListsAreParsed() {
        val r = GameExitReport.diagnose(1, log = "Device does not support required extensions, missing: {VK_KHR_push_descriptor, VK_KHR_swapchain}\nDevice does not support required features, missing: {timelineSemaphore}")
        assertEquals(ExitReason.VulkanUnsupported, r.reason); assertEquals(listOf("VK_KHR_push_descriptor", "VK_KHR_swapchain", "timelineSemaphore"), r.missingVulkanRequirements)
    }
    @Test fun vulkanMissingReadsMinecraft262Lines() {
        val log = "[Render thread/WARN]: Device [llvmpipe] does not support required extensions, missing: [VK_KHR_push_descriptor, VK_EXT_vertex_attribute_divisor]\n" +
            "[Render thread/WARN]: Device [Adreno (TM) 540] does not have required feature [timelineSemaphore]"
        val r = GameExitReport.diagnose(1, log = log)
        assertEquals(ExitReason.VulkanUnsupported, r.reason)
        assertEquals(listOf("VK_KHR_push_descriptor", "VK_EXT_vertex_attribute_divisor", "timelineSemaphore"), r.missingVulkanRequirements)
    }
    @Test fun vulkanUnsuitableWinsOverTheOpenGLFallbackCrash() {
        // Emulator, NeoForge 26.2.0.93: the Vulkan check fails, Minecraft falls back to OpenGL, and GL has no context.
        val log = """
            [Render thread/WARN] [mojang/VulkanBackend]: Device [Goldfish GFXStream (llvmpipe (LLVM 17.0.6, 256 bits))] does not support required extensions, missing: [VK_EXT_vertex_attribute_divisor, VK_KHR_push_descriptor]
            [Render thread/ERROR] [minecraft/Minecraft]: Failed to create backend Vulkan
            com.mojang.blaze3d.systems.BackendCreationException: Device missing capabilities
            java.lang.IllegalStateException: There is no OpenGL context current in the current thread.
            	at com.mojang.blaze3d.opengl.GlDevice.<init>(GlDevice.java:120)
        """.trimIndent()
        for (r in listOf(GameExitReport.diagnose(1, log = log), GameExitReport.diagnose(1, crashReport = "java.lang.IllegalStateException: There is no OpenGL context current in the current thread.", log = log))) {
            assertEquals(ExitReason.VulkanUnsupported, r.reason)
            assertEquals(listOf("VK_EXT_vertex_attribute_divisor", "VK_KHR_push_descriptor"), r.missingVulkanRequirements)
            assertTrue(r.clue!!.contains("does not support required extensions"), r.clue)
        }
        assertEquals(ExitReason.VulkanUnsupported, GameExitReport.diagnose(1, log = "[Render thread/ERROR] [minecraft/Minecraft]: Failed to create backend Vulkan\njava.lang.IllegalStateException: There is no OpenGL context current in the current thread.").reason)
        assertEquals(ExitReason.NativeWindowInUse, GameExitReport.diagnose(1, log = "Failed to create backend Vulkan\nVK_ERROR_NATIVE_WINDOW_IN_USE_KHR").reason)
    }
    @Test fun missingModDependenciesAreNamed() {
        val log = """
            [12:00:01] [main/ERROR] [ModSorter/LOADING]: Missing or unsupported mandatory dependencies:
            	Mod ID: 'yet_another_config_lib_v3', Requested by: 'controlify', Expected range: '(,)', Actual version: '[MISSING]'
            [12:00:01] [main/ERROR] [FMLLoader/]: Mod controlify requires yet_another_config_lib_v3 any
            Currently, yet_another_config_lib_v3 is not installed
        """.trimIndent()
        val r = GameExitReport.diagnose(1, log = log)
        assertEquals(ExitReason.MissingDependency, r.reason)
        assertEquals(listOf(MissingDependency("yet_another_config_lib_v3", "controlify", "(,)", "[MISSING]")), r.missingDependencies)
        assertEquals("Controlify needs yet_another_config_lib_v3", r.clue)
    }
    @Test fun onlyAbsentModsAreOfferedForDownload() {
        val log = "Mod ID: '§6terrablender§r', Requested by: 'biomesoplenty', Expected range: '[26.2,)', Actual version: '[MISSING]'\n" +
            "Mod ID: 'glitchcore', Requested by: 'biomesoplenty', Expected range: '[2,)', Actual version: '1.0'\n" +
            "Mod ID: 'minecraft', Requested by: 'x', Expected range: '[26.3,)', Actual version: '[MISSING]'"
        val r = GameExitReport.diagnose(1, log = log)
        assertEquals(ExitReason.MissingDependency, r.reason); assertTrue("glitchcore" in r.clue!!, "a wrong version is still named")
        assertEquals(listOf("terrablender"), r.missingDependencies.map { it.modId }, "present in the wrong version and built-in ids are left out")
        val provider = GameExitReport.diagnose(1, log = "[FATAL] Error during pre-loading phase: Mod File mods/bountiful.jar needs language provider kotlinforforge:4 or above to load").missingDependencies.single()
        assertEquals("kotlinforforge", provider.modId); assertEquals("bountiful.jar", provider.requestedBy)
    }
    @Test fun openGLCrashNamesTheModFromNeoForgeAndMixinFrames() {
        val trace = "java.lang.IllegalStateException: No context is current\n\tat TRANSFORMER/minecraft@26.2/net.minecraft.client.Main.run(Main.java:1)\n\tat TRANSFORMER/better_clouds@1.0/com.example.Clouds.render(Clouds.java:9)"
        val r = GameExitReport.diagnose(1, log = trace); assertEquals(ExitReason.OpenGLMod, r.reason); assertEquals("Better Clouds", r.mod); assertTrue("Better Clouds" in r.explanation && "Better Clouds" in r.nextStep)
        assertEquals("Iris", GameExitReport.diagnose(1, crashReport = "java.lang.RuntimeException: No GLCapabilities instance set\n\tat net.Foo.bar(Foo.java) [mixin from mod iris]").mod)
        val unknown = GameExitReport.diagnose(1, log = "java.lang.IllegalStateException: No context is current\n\tat TRANSFORMER/neoforge@26.2.0.93/net.neoforged.X.y(X.java:1)")
        assertNull(unknown.mod); assertTrue(unknown.explanation.startsWith("A mod")); assertNull(GameExitReport.diagnose(1, log = "java.lang.OutOfMemoryError").mod)
    }
    @Test fun crashReportPrecedesLogAndMemoryPrecedesModsWithinSource() {
        assertEquals(ExitReason.Memory, GameExitReport.diagnose(1, crashReport = "java.lang.OutOfMemoryError: metaspace", log = "[ERROR] VK_ERROR_DEVICE_LOST").reason)
        assertEquals(ExitReason.Memory, GameExitReport.diagnose(1, log = "[ERROR] NoSuchMethodError\n[ERROR] java.lang.OutOfMemoryError").reason)
    }
    @Test fun neoForgeFailureIsNotMisclassifiedAsAShaderCrash() {
        val r = GameExitReport.diagnose(1, crashReport = "Description: Rendering overlay\njava.lang.IllegalStateException: RegisterShadersEvent to a broken mod state", log = "[FATAL] Error during pre-loading phase: Mod File bountiful.jar needs language provider kotlinforforge:4 or above")
        assertEquals(ExitReason.MissingDependency, r.reason); assertTrue(r.clue!!.contains("kotlinforforge"))
    }
    @Test fun systemDetailsAndInfoLinesCannotOverrideTheCause() {
        val r = GameExitReport.diagnose(1, crashReport = "Description: Ticking entity\njava.lang.IllegalStateException: boom\n-- System Details --\nOutOfMemoryError in a mod name", log = "[INFO] Loaded shader pack")
        assertEquals(ExitReason.Java, r.reason); assertEquals("java.lang.IllegalStateException: boom", r.clue)
    }
    @Test fun codeCacheFullAndNativeVulkanDriverCrashesAreNamed() {
        val r = GameExitReport.diagnose(134, log = "CodeCache is full. Compiler has been disabled.\nSIGILL"); assertEquals(ExitReason.Java, r.reason); assertTrue(r.explanation.contains("compiled code"))
        assertEquals(ExitReason.Renderer, GameExitReport.diagnose(134, log = "# SIGSEGV\n# libvulkan.so+123").reason)
        assertNotEquals(ExitReason.Renderer, GameExitReport.diagnose(1, log = "libvulkan.so loaded").reason)
        val hsErr = "#  SIGSEGV (0xb) at pc=0x7a1b2c, pid=4242, tid=4300\n# Problematic frame:\n# C  [libvulkan.so+0x1234]\nInternal exceptions (2 events):\n" +
            "Event: 1.2 Thread 0x1 Exception <a 'java/lang/NoSuchMethodError'{0x2}: 'void a.b()'>\nEvent: 1.3 Thread 0x1 Exception <a 'java/lang/ClassNotFoundException'{0x3}: x.Y>"
        assertEquals(ExitReason.Renderer, GameExitReport.diagnose(134, crashReport = hsErr).reason)
    }
    @Test fun aSigkillWithoutACrashReportIsTheSystemNotTheLastWarning() {
        val log = "[12:00] [main/WARN] [mixin/]: Error loading class: net.x.Y (java.lang.ClassNotFoundException: net.x.Y)\n[12:01] [Render thread/INFO]: Loaded 4 shaders"
        for (code in listOf(-9, 137)) assertEquals(ExitReason.SystemKilled, GameExitReport.diagnose(code, log = log).reason)
        assertEquals(ExitReason.MissingLibrary, GameExitReport.diagnose(1, log = log).reason)
    }
    @Test fun normalShutdownAndUnknownExitAreDistinguished() {
        assertEquals(ExitReason.Quit, GameExitReport.diagnose(0).reason); assertEquals(ExitReason.Unknown, GameExitReport.diagnose(1).reason)
        assertEquals(ExitReason.SystemKilled, GameExitReport.diagnose(137).reason); assertEquals(ExitReason.Quit, GameExitReport.diagnose(1, log = "earlier Exception\n[INFO]: Stopping!").reason)
        assertNotEquals(ExitReason.Quit, GameExitReport.diagnose(1, log = "[INFO]: Stopping!\njava.lang.IllegalStateException").reason)
    }
    @Test fun cluesAreCleanAndLimitedAndTheTailIsKept() {
        val r = GameExitReport.diagnose(1, log = "[12:00] [main/ERROR]: java.lang.UnsatisfiedLinkError: " + "x".repeat(400)); assertEquals(200, r.clue?.length); assertTrue(r.clue!!.startsWith("java.lang.")); assertTrue(r.clue.endsWith('…'))
        val d = GameExitReport.diagnose(1, log = (1..100).joinToString("\n") { "line $it" }, instance = "Main")
        assertEquals(60, d.logTail.size); assertEquals("line 100", d.logTail.last()); assertEquals("Main", d.instance)
    }
}
