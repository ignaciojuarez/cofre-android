package com.ignacio.cofre.kit.compat

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class CompatibilityTest {
    @TempDir lateinit var root: File
    private fun clazz(reference: String, classReference: Boolean = true): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { o ->
        o.writeInt(0xCAFEBABE.toInt()); o.writeShort(0); o.writeShort(69); o.writeShort(7)
        o.writeByte(1); o.writeUTF("example/Caller"); o.writeByte(7); o.writeShort(1)
        o.writeByte(1); o.writeUTF(reference); o.writeByte(if (classReference) 7 else 8); o.writeShort(3)
        o.writeByte(5); o.writeLong(123) // Two constant-pool slots.
        o.writeShort(0x21); o.writeShort(2); o.writeShort(0); repeat(4) { o.writeShort(0) }
    } }.toByteArray()
    private fun jar(path: String, classes: Map<String, ByteArray>): File = File(root, path).also { f -> f.parentFile.mkdirs(); ZipOutputStream(f.outputStream()).use { z -> classes.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } } }
    @Test fun matchesIDsSlugsNamesAndLongestPrefix() {
        for (id in listOf("iris", "iris-1.11.4.jar", "IRIS")) assertEquals("Iris", KnownModsCatalog.match(id, "")?.name)
        assertEquals("Embeddium", KnownModsCatalog.name("embeddium-1.jar"))
        assertNull(KnownModsCatalog.match("sodium", "Sodium")); assertNull(KnownModsCatalog.match("distanthorizons", "Distant Horizons"))
    }
    @Test fun matcherHonorsRendererLoaderAndVersionConditions() {
        val rule = KnownModsCatalog.entries.first().copy(loaders = setOf(Loader.NeoForge), gameVersions = setOf("26.x"))
        assertTrue(rule.matches("iris", "", gameVersion = "26.2")); assertFalse(rule.matches("iris", "", gameVersion = "1.21.1")); assertFalse(rule.matches("iris", "", renderer = "opengl")); assertFalse(rule.matches("iris", "", loader = Loader.Fabric))
        assertEquals("Vulkan 26.2", rule.copy(reason = "{renderer} {gameVersion}").renderedReason("Vulkan", "26.2"))
    }
    @Test fun bundledRulesValidateAndRejectInvalidCatalogs() {
        assertTrue(KnownModsCatalog.entries.size >= 5); KnownModsCatalog.validate(KnownModsCatalog.Manifest(1, KnownModsCatalog.entries))
        val m = KnownModsCatalog.entries.first(); assertFails { KnownModsCatalog.validate(KnownModsCatalog.Manifest(2, listOf(m))) }
        assertFails { KnownModsCatalog.validate(KnownModsCatalog.Manifest(1, listOf(m, m))) }; assertFails { KnownModsCatalog.validate(KnownModsCatalog.Manifest(1, listOf(m.copy(reason = "")))) }
        assertFails { KnownModsCatalog.validate(KnownModsCatalog.Manifest(1, listOf(m.copy(evidence = "device", evidenceRef = null)))) }
    }
    @Test fun shadersIrisOculusOptifineAndEmbeddiumAreBlocked() {
        for (name in listOf("Iris", "Oculus", "OptiFine", "Embeddium", "Nvidium")) assertNotNull(ContentCompatibility.issue(name.lowercase(), name, ContentKind.Mods))
        assertNotNull(ContentCompatibility.issue("p", "Pack", ContentKind.Shaders)); assertNull(ContentCompatibility.issue("p", "Pack", ContentKind.ResourcePacks))
    }
    @Test fun scannerFindsBothOpenGLNamespacesAndIgnoresVulkan() {
        val a = "org/lwjgl/opengl/GL11C"; val b = "com/mojang/blaze3d/opengl/GlTexture"
        assertEquals(setOf(a), GlUsageScanner.references(clazz(a))); assertEquals(setOf(b), GlUsageScanner.references(clazz(b)))
        assertTrue(GlUsageScanner.references(clazz("org/lwjgl/vulkan/VK10")).isEmpty())
    }
    @Test fun scannerDoesNotFlagStringLiterals() { assertTrue(GlUsageScanner.references(clazz("org/lwjgl/opengl/GL11C", false)).isEmpty()) }
    @Test fun scannerFindsArrayClassReferences() { assertEquals(setOf("org/lwjgl/opengl/GLCapabilities"), GlUsageScanner.references(clazz("[Lorg/lwjgl/opengl/GLCapabilities;"))) }
    @Test fun scannerReportsClassPathsFromJar() {
        val j = jar("a.jar", mapOf("example/A.class" to clazz("org/lwjgl/opengl/GL11C"), "example/B.class" to clazz("java/lang/Object")))
        val r = GlUsageScanner.scan(j); assertEquals("example/A", r.usages.single().className); assertTrue(r.excluded.isEmpty())
    }
    @Test fun malformedClassesFailInsteadOfLookingCompatible() {
        val c = clazz("java/lang/Object"); for (n in listOf(0, 9, c.size - 1)) assertFails { GlUsageScanner.references(c.copyOf(n)) }
        val bad = c.copyOf(); bad[10] = 99; assertFails { GlUsageScanner.references(bad) }
    }
    @Test fun sodiumExclusionsRequireExactVerifiedHashAndClass() {
        val hash = "15fb941bd58abe3e57b53eb91f070f00e9a5178c718d50cbae588986e4cd8349c5a2ef4f6c84cc4887320d85ec561aa2094214723f502fddd3aeb493c80b5c39"
        val name = "net/caffeinemc/mods/sodium/client/compatibility/environment/GlContextInfo"
        assertTrue(GlUsageScanner.excluded(hash, name)); assertFalse(GlUsageScanner.excluded("fake", name)); assertFalse(GlUsageScanner.excluded(hash, "example/Unsafe"))
        assertEquals(1, GlUsageScanner.scan(jar("sodium.jar", mapOf("$name.class" to clazz("org/lwjgl/opengl/GL11C")))).usages.size)
    }
    @Test fun playChecksOnlyEnabledModsAndCanDisableIssues() {
        jar("mods/iris-1.jar", mapOf("x.class" to clazz("org/lwjgl/opengl/GL11C")))
        jar("mods/unknown.jar", mapOf("unsafe/Caller.class" to clazz("com/mojang/blaze3d/opengl/GlTexture")))
        jar("mods/off.jar.disabled", mapOf("x.class" to clazz("org/lwjgl/opengl/GL11C")))
        val issues = ContentCompatibility.playIssues(root); assertEquals(2, issues.size); assertTrue(issues.any { it.reason.contains("unsafe/Caller") })
        ContentCompatibility.turnOff(issues, root); assertTrue(ContentCompatibility.playIssues(root).isEmpty())
    }
    @Test fun activeShaderIsReportedOnceWhenItsModIsAlreadyBlocked() {
        jar("mods/iris.jar", emptyMap()); File(root, "shaderpacks").mkdir(); File(root, "shaderpacks/Pack.zip").writeText("")
        File(root, "config").mkdir(); File(root, "config/iris.properties").writeText("enableShaders=true\nshaderPack=Pack.zip")
        assertEquals(1, ContentCompatibility.playIssues(root).size)
    }
}
