package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.Loader
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

internal fun jarBytes(files: Map<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { out -> files.forEach { (name, body) -> out.putNextEntry(ZipEntry(name)); out.write(body); out.closeEntry() } }
    return bytes.toByteArray()
}
internal fun modJar(id: String, extra: String = ""): ByteArray = jarBytes(mapOf("META-INF/neoforge.mods.toml" to "modLoader=\"javafml\"\n[[mods]]\nmodId=\"$id\"\nversion=\"1\"\n$extra".toByteArray()))

class MetadataTest {
    @TempDir lateinit var root: File
    private fun bountiful(): String = javaClass.getResource("/mods/bountiful.toml")!!.readText()
    private fun jar(files: Map<String, ByteArray>): File = File(root, "${java.util.UUID.randomUUID()}.jar").also { it.writeBytes(jarBytes(files)) }
    @Test fun tomlSkipsDescriptionsPlaceholdersAndServerRequirements() {
        val toml = ModsToml(bountiful())
        assertEquals("kotlinforforge", toml.modLoader); assertEquals(listOf("bountiful"), toml.modIds)
        assertEquals(listOf("neoforge", "architectury"), toml.dependencies.filter { it.required }.map { it.id })
        assertEquals("bountiful", toml.dependencies.first().owner); assertEquals("[26.2,)", toml.dependencies.first().versionRange)
    }
    @Test fun inlineArrayOfTablesLikeControlify() {
        // Controlify 3.5.3+mc26.2's real META-INF/neoforge.mods.toml (Stonecutter writes inline tables).
        val toml = ModsToml("""
            mods = [{modId = 'controlify', version = '3.5.3+mc26.2', displayName = 'Controlify', authors = 'isXander'}]
            modproperties.controlify.rrls.force_load = ['controlify:default_config', 'controlify:controller_type']
            dependencies.controlify = [{modId = 'minecraft', type = 'required', versionRange = '[26.2,26.3)'}, {modId = 'yet_another_config_lib_v3', type = 'required', versionRange = '(,)'}]
        """.trimIndent())
        assertEquals(listOf("controlify"), toml.modIds)
        assertEquals(listOf("minecraft", "yet_another_config_lib_v3"), toml.dependencies.filter { it.required }.map { it.id })
        assertEquals("controlify", toml.dependencies.first().owner)
        assertEquals("[26.2,26.3)", toml.dependencies.first().versionRange)
    }
    @Test fun legacyForgeMandatoryAndQuotedDependencyTable() {
        val toml = ModsToml("""modLoader='javafml'
            [[mods]]
            modId='sample'
            [[dependencies."sample"]]
            modId='cloth_config'
            mandatory=true # trailing comment
            [[dependencies.sample]]
            modId='optional'
            mandatory=false
        """.trimIndent())
        assertEquals(listOf("cloth_config"), toml.dependencies.filter { it.required }.map { it.id })
        assertEquals("sample", toml.dependencies.first().owner)
    }
    @Test fun neoForgeReadsLoaderDependenciesIconAndVersion() {
        val logo = byteArrayOf(0x50, 0x4e, 0x47)
        val jar = jar(mapOf("META-INF/neoforge.mods.toml" to bountiful().toByteArray(), "logo.png" to logo))
        val metadata = assertNotNull(ModJarMetadata.read(jar))
        assertEquals(listOf("bountiful"), metadata.modIds); assertEquals("Bountiful", metadata.name); assertEquals("8.0.0-beta.2", metadata.version)
        assertEquals(listOf("neoforge", "architectury", "kotlinforforge"), metadata.dependencies.filter { it.required }.map { it.id })
        assertContentEquals(logo, metadata.icon); assertSame(metadata, ModJarMetadata.read(jar))
    }
    @Test fun fabricReadsDependenciesEnvironmentAndIconMap() {
        val metadata = assertNotNull(ModJarMetadata.read(jar(mapOf("fabric.mod.json" to """{"id":"sample","name":"Sample","version":"1.2","environment":"client","depends":{"fabric-api":">=0.1"},"breaks":{"bad":"*"},"icon":{"64":"assets/icon.png"}}""".toByteArray(), "assets/icon.png" to byteArrayOf(1, 2))), Loader.Fabric))
        assertEquals(listOf("sample"), metadata.modIds); assertEquals("1.2", metadata.version); assertEquals(ModSide.Client, metadata.side)
        assertEquals("fabric-api", metadata.dependencies.first().id); assertEquals(Relation.Incompatible, metadata.dependencies.last().relation)
        assertContentEquals(byteArrayOf(1, 2), metadata.icon)
    }
    @Test fun quiltReadsDependenciesAndSide() {
        val metadata = assertNotNull(ModJarMetadata.read(jar(mapOf("quilt.mod.json" to """{"quilt_loader":{"id":"sample","version":"2","metadata":{"name":"Sample"},"depends":[{"id":"qsl","versions":">=1"},{"id":"optional","optional":true}]},"minecraft":{"environment":"client"}}""".toByteArray())), Loader.Quilt))
        assertEquals(listOf("sample"), metadata.modIds); assertEquals(ModSide.Client, metadata.side)
        assertEquals(listOf("qsl"), metadata.dependencies.filter { it.required }.map { it.id })
    }
    @Test fun neoForgePreferredAndManifestFillsBuildPlaceholder() {
        val metadata = assertNotNull(ModJarMetadata.read(jar(mapOf("META-INF/neoforge.mods.toml" to "[[mods]]\nmodId='neo'\nversion='\${file.jarVersion}'".toByteArray(), "META-INF/mods.toml" to "[[mods]]\nmodId='forge'".toByteArray(), "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nImplementation-Version: 3.2\r\n\r\n".toByteArray()))))
        assertEquals(listOf("neo"), metadata.modIds); assertEquals("3.2", metadata.version)
    }
    @Test fun nestedJarsAndBundledArtifactsSatisfyRequirements() {
        val inner = jarBytes(mapOf("META-INF/mods.toml" to "[[mods]]\nmodId='architectury'".toByteArray()))
        val metadata = assertNotNull(ModJarMetadata.read(jar(mapOf("META-INF/neoforge.mods.toml" to bountiful().toByteArray(), "META-INF/jarjar/arch.jar" to inner, "META-INF/jarjar/metadata.json" to """{"jars":[{"identifier":{"artifact":"kotlinforforge"}}]}""".toByteArray()))))
        assertTrue("architectury" in metadata.modIds); assertTrue(ModRequirements.missing(mapOf("b.jar" to metadata)).isEmpty())
    }
    @Test fun plainInvalidOrOversizedMetadataIsIgnored() {
        assertNull(ModJarMetadata.read(jar(mapOf("other.txt" to byteArrayOf(1)))))
        assertNull(ModJarMetadata.read(File(root, "bad.jar").also { it.writeText("not a zip") }))
        assertNull(ModJarMetadata.read(jar(mapOf("fabric.mod.json" to ByteArray(1_000_001)))))
    }
    @Test fun tomlQuotesPreserveHashEscapesAndIgnoreSingleLineDescriptions() {
        val toml = ModsToml("modLoader=\"javafml\"\n[[mods]]\nmodId='sample'\ndisplayName=\"A # \\\"quoted\\\" name\" # comment\ndescription=\"\"\"inline description\"\"\"\nversion='1'")
        assertEquals("A # \"quoted\" name", toml.mods.single()["displayName"]); assertEquals("1", toml.mods.single()["version"])
    }
    @Test fun missingRequirementsNameProvidersAliasesAndNeededBy() {
        val metadata = assertNotNull(ModJarMetadata.read(jar(mapOf("META-INF/neoforge.mods.toml" to bountiful().toByteArray()))))
        val missing = ModRequirements.missing(mapOf("bountiful.jar" to metadata))
        assertEquals(listOf("kotlinforforge", "architectury"), missing.map { it.modId })
        assertEquals("ordsPcFz", missing.first().projectId); assertEquals("Kotlin for Forge", missing.first().name)
        assertEquals("modLoader:kotlinforforge", missing.first().reason); assertEquals("bountiful.jar", missing.first().neededBy)
        assertEquals(listOf("architectury-api", "architectury"), missing.last().slugs)
        assertTrue(ModRequirements.missing(mapOf("b.jar" to metadata, "arch.jar" to ModJarMetadata(listOf("architectury"))), listOf("kotlinforforge-5.5.jar")).isEmpty())
    }
    @Test fun neoForge262RequiredFlagFindsBiomeAndCullLibraries() {
        val toml = ModsToml("[[mods]]\nmodId='biomesoplenty'\n[[dependencies.biomesoplenty]]\nmodId='glitchcore'\nrequired=true\n[[dependencies.biomesoplenty]]\nmodId='terrablender'\nrequired=true\nside='CLIENT'")
        val missing = ModRequirements.missing(mapOf("biomes.jar" to ModJarMetadata(toml.modIds, dependencies = toml.dependencies)))
        assertEquals(listOf("GlitchCore", "TerraBlender"), missing.map { it.name })
    }
    @Test fun crashCluesPortAllIosFormats() {
        assertEquals("ordsPcFz", ModRequirements.fromCrash("Mod File mods/bountiful.jar needs language provider kotlinforforge:4 or above to load").single().projectId)
        assertEquals(listOf("sophisticated-core", "sophisticatedcore"), ModRequirements.fromCrash("Mod backpacks requires sophisticatedcore 1.0 or above\nCurrently, sophisticatedcore is not installed").single().slugs)
        assertEquals("bountiful", ModRequirements.fromCrash("Mod ID: 'kotlinforforge', Requested by: 'bountiful', Expected range: '[4,)', Actual version: '[MISSING]'").single().neededBy)
        assertEquals("fabric-language-kotlin", ModRequirements.fromCrash("Mod 'Bountiful' requires any version of fabric-language-kotlin, which is missing!").single().modId)
        assertTrue(ModRequirements.fromCrash("Mod x requires minecraft 1.21\nCurrently, minecraft is not installed").isEmpty())
        assertTrue(ModRequirements.fromCrash("Mod foo requires bar 2.0 or above\nCurrently, bar is 1.0").isEmpty())
    }
    @Test fun coloredNeoForge262CrashDeduplicatesLibraries() {
        val text = "Mod ID: 'terrablender', Requested by: 'biomesoplenty', Expected range: '[26.2,)', Actual version: '[MISSING]'\nMod ID: 'glitchcore', Requested by: 'biomesoplenty', Expected range: '[26.2,)', Actual version: '[MISSING]'\nMod §ebiomesoplenty§r requires §6terrablender§r 1 or above\nCurrently, terrablender is not installed"
        val missing = ModRequirements.fromCrash(text)
        assertEquals(listOf("terrablender", "glitchcore"), missing.map { it.modId }); assertEquals(listOf("TerraBlender", "GlitchCore"), missing.map { it.name })
    }
}
