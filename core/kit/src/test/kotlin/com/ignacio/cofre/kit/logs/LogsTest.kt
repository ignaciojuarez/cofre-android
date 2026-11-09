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
    @Test fun sourcesNewestCrashAndTailAreReadFromGameFiles() {
        write("launcher.log", "launch\n"); write("game/logs/latest.log", "one\ntwo\nthree\n")
        write("game/crash-reports/old.txt", "old").setLastModified(1000); val newest = write("game/crash-reports/new.txt", "new").apply { setLastModified(2000) }
        val s = store(); assertEquals("launch", s.read(LogFile.Launcher).single().text); assertEquals(listOf("two", "three"), s.tail(LogFile.Game, 2).map { it.text }); assertEquals(newest, s.newestCrashReport()); assertNull(s.newestCrashReport(Instant.ofEpochMilli(3000)))
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
    @Test fun redactionCoversIPsHostsUUIDsAndUserPaths() {
        val input = "192.168.1.2:25565 [2001:db8::1]:25565 ::1\n/Users/alice/My Games/cofre/log.txt\n/data/user/0/com.ignacio.cofre/files/game\n/storage/emulated/0/Documents/game\nC:\\Users\\Alice\\Game\\latest.log\nplay.example.com 123e4567-e89b-12d3-a456-426614174000"
        val text = ReportBundle.redact(input)
        for (secret in listOf("192.168.1.2", "2001:db8", "::1", "alice", "Alice", "com.ignacio", "emulated/0", "play.example.com", "123e4567")) assertFalse(secret in text, secret)
    }
    @Test fun exportedLogIsRedactedWithoutChangingSource() {
        val raw = "token=SECRET me@example.com"; val source = write("game/logs/latest.log", raw)
        val out = store().export(LogFile.Game, File(root, "clean.log")); assertFalse("SECRET" in out.readText()); assertEquals(raw, source.readText())
    }
    @Test fun reportRedactsContextAndEveryArchiveMember() {
        write("game/logs/latest.log", "token=SECRET"); write("game/crash-reports/a.txt", "user@example.com /home/alice/world")
        val context = ReportContext(device = "me@example.com", instance = ReportInstance(mods = mapOf("user@example.com" to "token=SECRET")), clue = "username=Alex")
        val bundle = ReportBundle(store(), context); val zip = bundle.make(File(root, "report.zip")); assertEquals(3, bundle.preview().files.size)
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
    }
    @Test fun normalShutdownAndUnknownExitAreDistinguished() {
        assertEquals(ExitReason.Quit, GameExitReport.diagnose(0).reason); assertEquals(ExitReason.Unknown, GameExitReport.diagnose(1).reason)
        assertEquals(ExitReason.SystemKilled, GameExitReport.diagnose(137).reason); assertEquals(ExitReason.Quit, GameExitReport.diagnose(1, log = "earlier Exception\n[INFO]: Stopping!").reason)
        assertNotEquals(ExitReason.Quit, GameExitReport.diagnose(1, log = "[INFO]: Stopping!\njava.lang.IllegalStateException").reason)
    }
    @Test fun cluesAreCleanAndLimitedAndDetailsUseLastTwentyLines() {
        val r = GameExitReport.diagnose(1, log = "[12:00] [main/ERROR]: java.lang.UnsatisfiedLinkError: " + "x".repeat(400)); assertEquals(200, r.clue?.length); assertTrue(r.clue!!.startsWith("java.lang.")); assertTrue(r.clue.endsWith('…'))
        val d = GameExitReport.diagnose(1, log = (1..100).joinToString("\n") { "line $it" }, instance = "Main")
        assertEquals(60, d.logTail.size); assertTrue("Instance: Main\nExit code: 1" in d.detailsText); assertFalse("line 80\n" in d.detailsText); assertTrue(d.detailsText.endsWith("line 100"))
    }
    @Test fun launchFailuresOfferTheOwningRecoveryAndLoaderDetection() {
        assertEquals(LaunchRecovery.RepairInstance, LaunchFailure(LaunchFailure.Reason.LoaderMissing).recovery); assertEquals(LaunchRecovery.Retry, LaunchFailure(LaunchFailure.Reason.DownloadFailed).recovery)
        assertEquals(LaunchRecovery.Log, LaunchFailure(LaunchFailure.Reason.JVMExit).recovery)
        for (reason in LaunchFailure.Reason.entries) assertTrue(LaunchFailure(reason).message.isNotBlank())
        assertTrue(LaunchFailure.modLoaderFailed("Failed to start FML: ModLoadingException")); assertFalse(LaunchFailure.modLoaderFailed("Loaded successfully"))
    }
}
