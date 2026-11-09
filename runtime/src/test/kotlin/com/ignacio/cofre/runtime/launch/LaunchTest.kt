package com.ignacio.cofre.runtime.launch

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.logs.ExitReason
import com.ignacio.cofre.kit.logs.GameExitReport
import com.ignacio.cofre.kit.versions.InstallProfile
import com.ignacio.cofre.kit.versions.VersionJson
import com.ignacio.cofre.runtime.LaunchSpec
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Golden tests against the real 26.2 and NeoForge 26.2.0.93 JSONs kit captured. */
class LaunchTest {
    private val fixtures = File("../core/kit/src/test/resources/versions")
    private fun fixture(name: String) = File(fixtures, name).readText()
    private val vanilla = VersionJson.decode(fixture("26.2.json"))
    private val neoforge = VersionJson.decode(fixture("neoforge-version.json")).merge(vanilla)
    private val profile = Kit.json.decodeFromString(InstallProfile.serializer(), fixture("neoforge-install_profile.json"))
    private val files = GameFiles(File("/m"))
    private val account = GameAccount("Dev", "0123456789abcdef0123456789abcdef")
    private val exit = File("/r/g.exit.json")

    @Test fun substitutesKnownKeysAndKeepsUnknownOnes() {
        assertEquals("-Djna.tmpdir=/n/jna \${nope}", LaunchArgs.substitute("-Djna.tmpdir=\${natives_directory}/jna \${nope}", mapOf("natives_directory" to "/n")))
    }

    @Test fun vanillaJvmArgsForLinuxArm64() {
        val spec = LaunchArgs.spec(vanilla, files, File("/g"), account, "/jre", 2048, exit)
        val jvm = spec.jvmArgs
        assertEquals(listOf("-Xms256m", "-Xmx2048m"), jvm.take(2))
        assertTrue("-Djna.tmpdir=/m/natives/26.2/jna" in jvm)
        assertTrue("-Dorg.lwjgl.system.SharedLibraryExtractPath=/m/natives/26.2/lwjgl" in jvm)
        assertTrue("-Dminecraft.launcher.brand=cofre" in jvm)
        assertTrue("--enable-native-access=ALL-UNNAMED" in jvm)
        // macOS, Windows and x86 rules, the classpath pair and the library path drop out.
        listOf("-XstartOnFirstThread", "-Xss1M", "-cp").forEach { assertFalse(it in jvm, it) }
        assertTrue(jvm.none { it.startsWith("-Djava.library.path") || "\${" in it || it.startsWith("-XX:HeapDumpPath") })
        assertEquals("net.minecraft.client.main.Main", spec.mainClass)
        assertEquals("/m/versions/26.2/26.2.jar", spec.classpath.last())
    }

    @Test fun vanillaGameArgs() {
        val args = LaunchArgs.spec(vanilla, files, File("/g"), account, "/jre", 2048, exit).gameArgs
        fun value(name: String) = args[args.indexOf(name) + 1]
        assertEquals("Dev", value("--username"))
        assertEquals("26.2", value("--version"))
        assertEquals("/g", value("--gameDir"))
        assertEquals("/m/assets", value("--assetsDir"))
        assertEquals("32", value("--assetIndex"))
        assertEquals(account.uuid, value("--uuid"))
        assertEquals(LaunchSpec.TOKEN, value("--accessToken")) // the spec on disk never holds the token
        assertEquals("tok", LaunchArgs.spec(vanilla, files, File("/g"), account, "/jre", 2048, exit).withToken("tok").gameArgs.let { it[it.indexOf("--accessToken") + 1] })
        assertEquals("msa", value("--userType"))
        assertEquals("release", value("--versionType"))
        assertEquals("vulkan", value("--graphicsBackend"))
        listOf("--demo", "--width", "--quickPlayPath").forEach { assertFalse(it in args, it) }
        assertTrue(args.none { "\${" in it && it != LaunchSpec.TOKEN })
    }

    @Test fun heapInstanceArgsAndQuickPlayGoLast() {
        val spec = LaunchArgs.spec(vanilla, files, File("/g"), account, "/jre", 3072, exit, listOf("-XX:+UseZGC"), listOf("--quickPlaySingleplayer", "World"))
        assertEquals(listOf("-Xmx3072m"), spec.jvmArgs.filter { it.startsWith("-Xmx") })
        assertEquals("-XX:+UseZGC", spec.jvmArgs.last())
        assertEquals(listOf("--quickPlaySingleplayer", "World"), spec.gameArgs.takeLast(2))
        assertEquals("vulkan", spec.gameArgs[spec.gameArgs.indexOf("--graphicsBackend") + 1])
    }

    @Test fun neoForgeArgsAppendToVanilla() {
        val spec = LaunchArgs.spec(neoforge, files, File("/g"), account, "/jre", 4096, exit)
        assertEquals("net.neoforged.fml.startup.Client", spec.mainClass)
        assertTrue(LaunchArgs.isNeoForge(neoforge))
        assertFalse(LaunchArgs.isNeoForge(vanilla))
        val jvm = spec.jvmArgs
        assertTrue("-DlibraryDirectory=/m/libraries" in jvm)
        assertEquals("java.base/java.lang.invoke=ALL-UNNAMED", jvm[jvm.indexOf("--add-opens") + 1])
        assertEquals("jdk.naming.dns/com.sun.jndi.dns=java.naming", jvm[jvm.indexOf("--add-exports") + 1])
        val game = spec.gameArgs
        assertEquals("26.2.0.93", game[game.indexOf("--fml.neoForgeVersion") + 1])
        assertEquals("neoforge-26.2.0.93", game[game.indexOf("--version") + 1])
        assertEquals("vulkan", game.last())
        // NeoForge's jars come first; the vanilla client jar stays last.
        assertTrue(spec.classpath.first().endsWith("earlydisplay-11.0.16.jar"))
        assertEquals("/m/versions/26.2/26.2.jar", spec.classpath.last())
    }

    @Test fun librariesSelectedByRulesWithoutDesktopNatives() {
        val names = GameFiles.select(neoforge.libraries).map { it.name }
        assertTrue(names.none { ":natives-" in it }, names.filter { ":natives-" in it }.toString())
        assertTrue("io.netty:netty-transport-native-epoll:4.2.15.Final:linux-aarch_64" in names)
        assertFalse("io.netty:netty-transport-native-epoll:4.2.15.Final:linux-x86_64" in names)
        assertFalse("ca.weblite:java-objc-bridge:1.1" in names)
        assertTrue("org.lwjgl:lwjgl:3.4.1:unsafe" in names)
        assertTrue("org.lwjgl:lwjgl-vulkan:3.4.1" in names)
        assertEquals(81, names.size)
        assertEquals(names.size, names.distinct().size)
        val downloads = files.libraryDownloads(neoforge.libraries)
        assertEquals(names.size, downloads.size)
        assertTrue(downloads.all { it.sha1 != null && it.size != null && it.url.startsWith("https://") })
    }

    @Test fun mavenPaths() {
        assertEquals("net/neoforged/installertools/installertools/4.0.17/installertools-4.0.17-fatjar.jar", GameFiles.mavenPath("net.neoforged.installertools:installertools:4.0.17:fatjar"))
        assertEquals("de/oceanlabs/mcp/mcp_config/1.0/mcp_config-1.0.zip", GameFiles.mavenPath("de.oceanlabs.mcp:mcp_config:1.0@zip"))
        assertFailsWith<IllegalArgumentException> { GameFiles.mavenPath("nope") }
    }

    @Test fun processorDataAndArgumentsForTheClient() {
        val extracted = mutableListOf<String>()
        val base = mapOf("SIDE" to "client", "MINECRAFT_JAR" to "/m/versions/26.2/26.2.jar", "MINECRAFT_VERSION" to "26.2", "ROOT" to "/m", "INSTALLER" to "/i.jar", "LIBRARY_DIR" to "/m/libraries")
        val data = Processors.data(profile, "client", base, File("/m/libraries")) { extracted += it; File("/tmp/x$it") }
        assertEquals(listOf("/data/client.lzma"), extracted)
        assertEquals("/tmp/x/data/client.lzma", data["BINPATCH"])
        assertEquals("/m/libraries/net/neoforged/minecraft-client-patched/26.2.0.93/minecraft-client-patched-26.2.0.93.jar", data["PATCHED"])
        assertEquals("26.2-2", data["MCP_VERSION"])

        val steps = Processors.resolve(profile, "client", data, File("/m/libraries"))
        assertEquals(1, steps.size) // EXTRACT_FILES is server-only
        val step = steps.single()
        assertEquals("/m/libraries/net/neoforged/installertools/installertools/4.0.17/installertools-4.0.17-fatjar.jar", step.jar.path)
        assertEquals(listOf(step.jar), step.classpath)
        assertEquals(
            listOf("--task", "PROCESS_MINECRAFT_JAR", "--no-mod-manifest", "--input", "/m/versions/26.2/26.2.jar", "--output", data["PATCHED"],
                "--extract-libraries-to", "/m/libraries/", "--apply-patches", "/tmp/x/data/client.lzma"),
            step.args,
        )
        assertEquals(emptyMap(), step.outputs)
        assertEquals(1, Processors.resolve(profile, "server", data, File("/m/libraries")).count { "EXTRACT_FILES" in it.args })
    }

    @Test fun processorTokens() {
        val data = mapOf("SIDE" to "client", "ROOT" to "/m")
        assertEquals("client", Processors.tokens("{SIDE}", data))
        assertEquals("/m/run.sh", Processors.tokens("{ROOT}/run.sh", data))
        assertEquals("{SIDE}", Processors.tokens("'{SIDE}'", data))
        assertEquals("a{b", Processors.tokens("a\\{b", data))
        assertEquals("/m/libraries/a/b/1/b-1.jar", Processors.argument("[a:b:1]", data, File("/m/libraries")))
        assertFailsWith<IllegalArgumentException> { Processors.tokens("{MISSING}", data) }
        assertFailsWith<IllegalArgumentException> { Processors.tokens("{SIDE", data) }
    }

    @Test fun prepareGameDirWritesVulkanAndTurnsOffTheEarlyWindow() {
        val dir = Files.createTempDirectory("cofre-game").toFile()
        try {
            File(dir, "options.txt").writeText("version:4000\npreferredGraphicsBackend:opengl\nfov:0.5\n")
            File(dir, "config").mkdirs()
            File(dir, "config/fml.toml").writeText("earlyWindowControl = true # GL window\n[other]\nkey = 1\n")
            GameLaunch.prepareGameDir(dir, neoForge = true)
            assertEquals("version:4000\npreferredGraphicsBackend:vulkan\nfov:0.5\n", File(dir, "options.txt").readText())
            assertEquals("earlyWindowControl = false # GL window\n[other]\nkey = 1\n", File(dir, "config/fml.toml").readText())

            val fresh = File(dir, "vanilla")
            GameLaunch.prepareGameDir(fresh, neoForge = false)
            assertEquals("preferredGraphicsBackend:vulkan", File(fresh, "options.txt").readText())
            assertFalse(File(fresh, "config/fml.toml").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun vulkanDiagnosisNamesWhatIsMissing() {
        val log = "[12:00:00] [Render thread/WARN]: Device [llvmpipe] does not support required extensions, missing: [VK_KHR_push_descriptor, VK_EXT_vertex_attribute_divisor]"
        val report = GameExitReport.diagnose(1, null, log)
        assertEquals(ExitReason.VulkanUnsupported, report.reason)
        assertEquals(listOf("VK_KHR_push_descriptor", "VK_EXT_vertex_attribute_divisor"), report.missingVulkanRequirements)
        assertEquals(
            "Vulkan device not suitable. This device's Vulkan driver is missing: VK_KHR_push_descriptor, VK_EXT_vertex_attribute_divisor",
            GameExit(null, report).summary,
        )
    }
}
