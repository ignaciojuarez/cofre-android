package com.ignacio.cofre.kit.versions

import com.ignacio.cofre.kit.common.Kit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class VersionsTest {
    @TempDir lateinit var root: File
    private fun fixture(name: String): String = javaClass.getResource("/versions/$name")!!.readText()
    private fun vanilla(): VersionJson = VersionJson.decode(fixture("26.2.json"))
    // Source URLs: /versions/SOURCES.md. Golden fixtures are captured once, never fetched by tests.
    @Test fun real262JsonLosesNoFields() {
        val text = fixture("26.2.json"); val model = VersionJson.decode(text)
        assertEquals("26.2", model.id); assertEquals(25, model.javaVersion?.majorVersion)
        assertEquals(39193383, model.downloads["client"]?.size)
        assertNotNull(model.assetIndex); assertNotNull(model.logging["client"])
        assertTrue(model.arguments!!.defaultUserJvm.isNotEmpty())
        assertSubset(Kit.json.parseToJsonElement(text), Kit.json.encodeToJsonElement(model))
    }
    @Test fun realNeoForgeVersionAndInstallerLoseNoFields() {
        val version = VersionJson.decode(fixture("neoforge-version.json"))
        val profile = Kit.json.decodeFromString<InstallProfile>(fixture("neoforge-install_profile.json"))
        assertEquals("26.2", version.inheritsFrom); assertEquals("neoforge-26.2.0.93", profile.version)
        assertEquals("/data/client.lzma", profile.data["BINPATCH"]?.client)
        assertFalse(profile.processors.first().runsOn("client")); assertTrue(profile.processors.last().runsOn("client"))
        assertSubset(Kit.json.parseToJsonElement(fixture("neoforge-version.json")), Kit.json.encodeToJsonElement(version))
        assertSubset(Kit.json.parseToJsonElement(fixture("neoforge-install_profile.json")), Kit.json.encodeToJsonElement(profile))
    }
    private fun assertSubset(expected: JsonElement, actual: JsonElement) {
        when (expected) {
            is JsonObject -> expected.forEach { (key, value) -> assertTrue(key in actual.jsonObject, key); assertSubset(value, actual.jsonObject.getValue(key)) }
            is JsonArray -> { assertEquals(expected.size, actual.jsonArray.size); expected.zip(actual.jsonArray).forEach { (e, a) -> assertSubset(e, a) } }
            else -> assertEquals(expected, actual)
        }
    }
    @Test fun mergeInheritsAssetsDownloadsJavaAndAppendsArgs() {
        val parent = vanilla(); val child = VersionJson.decode(fixture("neoforge-version.json"))
        val merged = child.merge(parent)
        assertEquals(child.mainClass, merged.mainClass); assertEquals(parent.assetIndex, merged.assetIndex)
        assertEquals(parent.javaVersion, merged.javaVersion); assertEquals(parent.downloads, merged.downloads)
        assertEquals(parent.arguments!!.game + child.arguments!!.game, merged.arguments!!.game)
        assertNull(merged.inheritsFrom); assertTrue(merged.libraries.size > parent.libraries.size)
    }
    @Test fun childLibrariesOverrideVersionsWithoutDroppingNativeClassifiers() {
        val parent = VersionJson("26.2", libraries = listOf(Library("a:b:1"), Library("a:b:1:natives-linux")))
        val child = VersionJson("neo", inheritsFrom = "26.2", libraries = listOf(Library("a:b:2")))
        assertEquals(listOf("a:b:2", "a:b:1:natives-linux"), child.merge(parent).libraries.map { it.name })
    }
    @Test fun rulesUseLinuxArm64FeaturesAndLastMatch() {
        val linux = RuleContext()
        assertTrue(linux.allows(emptyList()))
        assertFalse(linux.allows(listOf(Rule("allow", Rule.Os(name = "windows")))))
        assertTrue(linux.allows(listOf(Rule("allow", Rule.Os(name = "linux", arch = "aarch64")))))
        assertFalse(linux.allows(listOf(Rule("allow"), Rule("disallow", Rule.Os(name = "linux")))))
        assertFalse(linux.allows(listOf(Rule("allow", features = mapOf("is_demo_user" to true)))))
        assertTrue(linux.copy(features = mapOf("is_demo_user" to true)).allows(listOf(Rule("allow", features = mapOf("is_demo_user" to true)))))
    }
    @Test fun argumentsSupportStringArrayAndLegacy() {
        val version = vanilla()
        assertFalse("--demo" in version.arguments!!.game())
        assertTrue("--demo" in version.arguments.game(RuleContext(features = mapOf("is_demo_user" to true))))
        assertFalse("-XstartOnFirstThread" in version.arguments.jvm())
        val legacy = VersionJson.decode("""{"id":"legacy","minecraftArguments":"--username demo","libraries":[{"name":"a:b:1","natives":{"linux":"natives-linux-${'$'}{arch}"},"downloads":{"classifiers":{"natives-linux-64":{"url":"https://cdn/n","size":10}}},"extract":{"exclude":["META-INF/"]}}]}""")
        assertEquals("--username demo", legacy.minecraftArguments); assertEquals(10, legacy.libraries.single().downloads!!.classifiers.values.single().size)
    }
    @Test fun manifestFindsEntriesById() {
        val manifest = Kit.json.decodeFromString<VersionManifest>("""{"versions":[{"id":"26.2","type":"release"},{"id":"snap","type":"snapshot"}]}""")
        assertEquals("release", manifest.entry("26.2")?.type); assertNull(manifest.entry("none"))
    }
    @Test fun assetsUseHashPrefix() {
        val index = Kit.json.decodeFromString<AssetIndex>("""{"objects":{"a":{"hash":"abcdef","size":12}},"virtual":true}""")
        assertEquals("ab/abcdef", index.objects.getValue("a").path); assertTrue(index.virtual)
    }
    @Test fun neoForgeMetadataOnlyReturns262() {
        val versions = NeoForgeVersion.parseMetadata("<versions><version>21.1.1</version><version>26.2.0.9-beta</version><version>26.2.0.93</version><version>26.3.0.1</version></versions>")
        assertEquals(listOf("26.2.0.93", "26.2.0.9-beta"), versions.map { it.version })
        assertTrue(versions.first().installerUrl.endsWith("/26.2.0.93/neoforge-26.2.0.93-installer.jar"))
    }
    @Test fun inheritanceCycleIsRejected() = runBlocking {
        assertFailsWith<IllegalArgumentException> { Versions().resolve(VersionJson("a", inheritsFrom = "b")) { VersionJson("b", inheritsFrom = "a") } }
    }
}
