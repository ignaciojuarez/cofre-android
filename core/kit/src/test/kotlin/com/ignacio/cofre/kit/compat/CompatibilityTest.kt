package com.ignacio.cofre.kit.compat

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Hashes
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
    private val scans by lazy { GlScanCache(File(root, "gl-risk.json")) }
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
        assertTrue(KnownModsCatalog.match("sodium", "Sodium", version = "0.9.2")!!.works); assertNull(KnownModsCatalog.match("sodium", "Sodium"))
        assertNull(KnownModsCatalog.match("evil", "Sodium", version = "0.9.2"))
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
        val r = GlUsageScanner.scan(j); assertEquals("example/A", r.usages.single().className); assertEquals(GlRisk.Likely, r.risk)
    }
    @Test fun scannerFindsOpenGLInNeoForgeJarInJar() {
        val inner = jar("inner.jar", mapOf("lib/Gl.class" to clazz("org/lwjgl/opengl/GL11C"))).readBytes()
        val outer = jar("outer.jar", mapOf("META-INF/jarjar/inner.jar" to inner, "example/B.class" to clazz("java/lang/Object"), "assets/other.jar" to inner))
        assertEquals(listOf("inner.jar!/lib/Gl"), GlUsageScanner.scan(outer).usages.map { it.className })
    }
    @Test fun malformedClassesFailInsteadOfLookingCompatible() {
        val c = clazz("java/lang/Object"); for (n in listOf(0, 9, c.size - 1)) assertFails { GlUsageScanner.references(c.copyOf(n)) }
        val bad = c.copyOf(); bad[10] = 99; assertFails { GlUsageScanner.references(bad) }
    }
    private fun toml(id: String, version: String) = "[[mods]]\nmodId = \"$id\"\nversion = \"$version\"\n".toByteArray()
    private val gl = "org/lwjgl/opengl/GL33"; private val device = "com/mojang/blaze3d/systems/GpuDevice"
    // Class names below are from the real NeoForge 26.2 jars (see VULKAN-MODS.md); each is a one-reference stub.
    private fun sodium(version: String) = jar("mods/sodium-neoforge-$version.jar", mapOf(
        "META-INF/neoforge.mods.toml" to toml("sodium", version),
        "net/caffeinemc/mods/sodium/client/compatibility/environment/GlContextInfo.class" to clazz("org/lwjgl/opengl/GL11C"),
        "META-INF/jarjar/net.caffeinemc.sodium-neoforge-mod.jar" to jar("sodium-inner.jar", mapOf(
            "net/caffeinemc/mods/sodium/mixin/core/GlDeviceMixin.class" to clazz(gl),
            "net/caffeinemc/mods/sodium/client/gpu/device/context/GLDrawContext.class" to clazz("com/mojang/blaze3d/opengl/GlStateManager"),
            "net/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend.class" to clazz("com/mojang/blaze3d/vulkan/VulkanDevice"),
        )).readBytes(),
    ))
    @Test fun vulkanReadyModsWithGlBackendCodeAreNotFlagged() {
        sodium("0.9.2+mc26.2")
        jar("mods/ImmediatelyFast-NeoForge-1.16.5+26.2.jar", mapOf("META-INF/neoforge.mods.toml" to toml("immediatelyfast", "1.16.5+26.2"),
            "net/raphimc/immediatelyfast/injection/mixins/avoid_redundant_framebuffer_switching/MixinGlCommandEncoder.class" to clazz("com/mojang/blaze3d/opengl/GlStateManager"),
            "net/raphimc/immediatelyfast/injection/mixins/avoid_redundant_framebuffer_switching/MixinGlSurface.class" to clazz("com/mojang/blaze3d/opengl/GlConst")))
        jar("mods/modernfix-neoforge-5.27.21+mc26.2.jar", mapOf("META-INF/neoforge.mods.toml" to toml("modernfix", "5.27.21+mc26.2"),
            "org/embeddedt/modernfix/neoforge/util/AsyncLoadingScreen.class" to clazz("org/lwjgl/opengl/GLCapabilities")))
        assertEquals(GlRisk.May, GlUsageScanner.scan(File(root, "mods/sodium-neoforge-0.9.2+mc26.2.jar")).risk) // What the scan alone would say.
        assertEquals(emptyList(), ContentCompatibility.playIssues(root, scans))
    }
    @Test fun vouchingNeedsTheModIdAndMinimumVersion() {
        sodium("0.8.0+mc26.2")
        assertEquals("may", ContentCompatibility.playIssues(root, scans).single().severity)
        assertTrue(ContentCompatibility.vouched("sodium", "Sodium", "0.9.0")); assertFalse(ContentCompatibility.vouched("sodium", "Sodium", null))
        assertFalse(ContentCompatibility.vouched("not_sodium", "Sodium", "0.9.2")); assertTrue(ContentCompatibility.vouched("ferritecore", "FerriteCore", null))
        assertFalse(ContentCompatibility.vouched("modernfix", "ModernFix", "5.27.17"))
        assertNull(ContentCompatibility.issue("sodium", "Sodium", ContentKind.Mods, "0.9.2"))
    }
    @Test fun versionsCompareByLeadingNumbers() {
        assertTrue(KnownMod.atLeast("0.9.2+mc26.2", "0.9.0")); assertTrue(KnownMod.atLeast("1.16", "1.16.0")); assertTrue(KnownMod.atLeast("v3.10.0", "3.9.9"))
        assertFalse(KnownMod.atLeast("0.10", "1.0")); assertFalse(KnownMod.atLeast("mc26.2-0.9.2", "0.9.0")); assertFalse(KnownMod.atLeast(null, "1"))
    }
    @Test fun irisIsAHardErrorEvenThoughItUsesTheNeutralApi() {
        jar("mods/iris-neoforge-1.11.4+mc26.2.jar", mapOf("META-INF/neoforge.mods.toml" to toml("iris", "1.11.4+mc26.2"),
            "net/irisshaders/iris/Iris.class" to clazz("com/mojang/blaze3d/opengl/GlDevice"), "net/irisshaders/iris/gl/IrisRenderSystem.class" to clazz(device)))
        val issue = ContentCompatibility.playIssues(root, scans).single(); assertEquals("broken", issue.severity); assertEquals("Iris", issue.name)
    }
    @Test fun unknownGlOnlyJarIsLikelyAndMixedJarMay() {
        jar("mods/glonly.jar", mapOf("META-INF/neoforge.mods.toml" to toml("glonly", "1.0"), "a/Draw.class" to clazz(gl)))
        jar("mods/mixed.jar", mapOf("META-INF/neoforge.mods.toml" to toml("mixed", "1.0"), "b/GlPath.class" to clazz(gl), "b/Device.class" to clazz(device)))
        val issues = ContentCompatibility.playIssues(root, scans).associateBy { it.id }
        assertEquals("likely", issues.getValue("glonly").severity); assertTrue(issues.getValue("glonly").reason.contains("likely to crash"))
        assertEquals("may", issues.getValue("mixed").severity); assertTrue(issues.getValue("mixed").reason.contains("may not work"))
        assertTrue(issues.values.none { it.reason.contains("will crash") })
    }
    @Test fun playChecksOnlyEnabledModsAndCanDisableIssues() {
        jar("mods/iris-1.jar", mapOf("x.class" to clazz("org/lwjgl/opengl/GL11C")))
        jar("mods/unknown.jar", mapOf("unsafe/Caller.class" to clazz("com/mojang/blaze3d/opengl/GlTexture")))
        jar("mods/off.jar.disabled", mapOf("x.class" to clazz("org/lwjgl/opengl/GL11C")))
        val issues = ContentCompatibility.playIssues(root, scans); assertEquals(2, issues.size); assertEquals("likely", issues.single { it.file == "unknown.jar" }.severity)
        ContentCompatibility.turnOff(issues, root); assertTrue(ContentCompatibility.playIssues(root, scans).isEmpty())
    }
    @Test fun playIssuesUseTheSharedScanCacheAndSkipFolders() {
        val clean = jar("mods/clean.jar", mapOf("META-INF/neoforge.mods.toml" to toml("clean", "1.0"), "a/A.class" to clazz("java/lang/Object")))
        File(root, "gl-risk.json").writeText("""{"${Hashes.sha1(clean)}":"Likely"}""")
        assertEquals("likely", ContentCompatibility.playIssues(root, scans).single().severity, "the cached finding, not a rescan")
        val glOnly = jar("mods/glonly.jar", mapOf("META-INF/neoforge.mods.toml" to toml("glonly", "1.0"), "a/Draw.class" to clazz(gl)))
        File(root, "mods/folder-mod").mkdirs()
        assertEquals(2, ContentCompatibility.playIssues(root, scans).size)
        assertEquals(GlRisk.Likely, GlScanCache(File(root, "gl-risk.json")).cached(glOnly), "saved for the Installed tab and the next start")
        assertNull(scans.cached(File(root, "mods/folder-mod")))
    }
    @Test fun activeShaderIsReportedOnceWhenItsModIsAlreadyBlocked() {
        jar("mods/iris.jar", emptyMap()); File(root, "shaderpacks").mkdir(); File(root, "shaderpacks/Pack.zip").writeText("")
        File(root, "config").mkdir(); File(root, "config/iris.properties").writeText("enableShaders=true\nshaderPack=Pack.zip")
        assertEquals(1, ContentCompatibility.playIssues(root, scans).size)
    }
}
