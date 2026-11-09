package com.ignacio.cofre.kit.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ResolverTest {
    private fun candidate(id: String, game: String = "26.2", loaders: List<Loader> = listOf(Loader.NeoForge), channel: VersionChannel = VersionChannel.Release, version: String = "1", deps: List<ModCandidate.Dependency> = emptyList(), downloadable: Boolean = true): ModCandidate =
        ModCandidate(id, "$id-$version", id, version, listOf(game), loaders, channel, deps, downloadable = downloadable)
    private fun dep(id: String, relation: Relation = Relation.Required, versionId: String? = null) = ModCandidate.Dependency(id, versionId, relation)
    private fun resolver(catalog: Map<String, List<ModCandidate>>, installed: List<InstalledMod> = emptyList(), kind: ContentKind = ContentKind.Mods): ModResolver = ModResolver(installed, kind = kind) { catalog[it] ?: error("No project $it") }
    @Test fun cyclesResolveOnce() = runBlocking {
        val plan = resolver(mapOf("a" to listOf(candidate("a", deps = listOf(dep("b")))), "b" to listOf(candidate("b", deps = listOf(dep("a")))))).plan("a")
        assertNull(plan.blocked); assertEquals(listOf("b"), plan.required.map { it.projectId })
    }
    @Test fun wrongLoaderDependencyBlocks() = runBlocking {
        val plan = resolver(mapOf("a" to listOf(candidate("a", deps = listOf(dep("lib")))), "lib" to listOf(candidate("lib", loaders = listOf(Loader.Forge))))).plan("a")
        assertIs<PlanBlock.MissingBuild>(plan.blocked)
    }
    @Test fun multiLoaderSkipsAnotherLoadersDependency() = runBlocking {
        val plan = resolver(mapOf("a" to listOf(candidate("a", loaders = listOf(Loader.Fabric, Loader.NeoForge), deps = listOf(dep("fabric-api")))), "fabric-api" to listOf(candidate("fabric-api", loaders = listOf(Loader.Fabric))))).plan("a")
        assertNull(plan.blocked); assertTrue(plan.required.isEmpty())
    }
    @Test fun wrongGameDependencyBlocks() = runBlocking {
        val plan = resolver(mapOf("a" to listOf(candidate("a", deps = listOf(dep("lib")))), "lib" to listOf(candidate("lib", game = "1.20.1")))).plan("a")
        assertIs<PlanBlock.MissingBuild>(plan.blocked)
    }
    @Test fun disabledDependencyTurnsOnWithoutDownloading() = runBlocking {
        val plan = resolver(mapOf("a" to listOf(candidate("a", deps = listOf(dep("lib"))))), listOf(InstalledMod("lib.jar.disabled", "Lib", "lib", enabled = false))).plan("a")
        assertEquals(listOf("lib.jar.disabled"), plan.turnsOn.map { it.file }); assertTrue(plan.required.isEmpty()); assertTrue(plan.needsConfirmation)
    }
    @Test fun installedEnabledDependencyIsPresent() = runBlocking {
        val plan = resolver(mapOf("a" to listOf(candidate("a", deps = listOf(dep("lib"))))), listOf(InstalledMod("lib.jar", "Lib", "lib"))).plan("a")
        assertEquals(listOf("Lib"), plan.present); assertFalse(plan.needsConfirmation)
    }
    @Test fun unindexedJarMatchesByTitle() = runBlocking {
        val plan = resolver(mapOf("a" to listOf(candidate("a", deps = listOf(dep("lib")))), "lib" to listOf(candidate("lib").copy(title = "Library"))), listOf(InstalledMod("random.jar", "Library"))).plan("a")
        assertTrue(plan.required.isEmpty()); assertEquals(listOf("Library"), plan.present)
    }
    @Test fun incompatibleInstalledOrPlannedModBlocksInEitherOrder() = runBlocking {
        val a = candidate("a", deps = listOf(dep("lib"), dep("x", Relation.Incompatible), dep("x")))
        val catalog = mapOf("a" to listOf(a), "lib" to listOf(candidate("lib")), "x" to listOf(candidate("x")))
        assertIs<PlanBlock.Incompatible>(resolver(catalog).plan("a").blocked)
        val installed = listOf(InstalledMod("x.jar", "X", "x"))
        val incompatibleOnly = mapOf("a" to listOf(a.copy(dependencies = listOf(dep("x", Relation.Incompatible)))))
        assertIs<PlanBlock.Incompatible>(resolver(incompatibleOnly, installed).plan("a").blocked)
    }
    @Test fun optionalOnlyOfferedForRoot() = runBlocking {
        val catalog = mapOf("a" to listOf(candidate("a", deps = listOf(dep("opt", Relation.Optional), dep("lib")))), "lib" to listOf(candidate("lib", deps = listOf(dep("deep", Relation.Optional)))), "opt" to listOf(candidate("opt")))
        val plan = resolver(catalog).plan("a")
        assertEquals(listOf("opt"), plan.optional.map { it.projectId }); assertEquals(listOf("a", "lib"), plan.downloads.map { it.projectId })
    }
    @Test fun selectedOptionalResolvesItsOwnRequirements() = runBlocking {
        val catalog = mapOf("a" to listOf(candidate("a", deps = listOf(dep("opt", Relation.Optional)))), "opt" to listOf(candidate("opt", deps = listOf(dep("deep")))), "deep" to listOf(candidate("deep")))
        val plan = resolver(catalog).plan("a", selectedOptional = setOf("opt"))
        assertEquals(listOf("opt", "deep"), plan.required.map { it.projectId }); assertTrue(plan.optional.isEmpty())
    }
    @Test fun optionalThatBecomesRequiredIsInstalled() = runBlocking {
        val catalog = mapOf("a" to listOf(candidate("a", deps = listOf(dep("opt", Relation.Optional), dep("lib")))), "lib" to listOf(candidate("lib", deps = listOf(dep("opt")))), "opt" to listOf(candidate("opt")))
        val plan = resolver(catalog).plan("a"); assertEquals(listOf("lib", "opt"), plan.required.map { it.projectId }); assertTrue(plan.optional.isEmpty())
    }
    @Test fun stableWinsAndInvalidRootPinFallsBack() = runBlocking {
        val builds = listOf(candidate("a", channel = VersionChannel.Beta, version = "3"), candidate("a", game = "1.20.1", version = "2"), candidate("a"))
        assertEquals("a-1", resolver(mapOf("a" to builds)).plan("a", "a-2").target?.versionId)
        assertEquals("a-3", resolver(mapOf("a" to builds)).plan("a", "a-3").target?.versionId)
    }
    @Test fun loaderBuildPreferredOverPackAndPinnedWrongLoaderFallsBack() = runBlocking {
        val builds = listOf(candidate("a", loaders = emptyList(), version = "zip"), candidate("a", loaders = listOf(Loader.Forge), version = "forge"), candidate("a", version = "neo"))
        assertEquals("a-neo", resolver(mapOf("a" to builds)).plan("a", "a-forge").target?.versionId)
    }
    @Test fun requiredPinnedVersionMustExistAndConflictingPinsBlock() = runBlocking {
        val builds = listOf(candidate("lib", version = "1"), candidate("lib", version = "2"))
        val a = candidate("a", deps = listOf(dep("lib", versionId = "lib-1"), dep("lib", versionId = "lib-2")))
        assertIs<PlanBlock.VersionConflict>(resolver(mapOf("a" to listOf(a), "lib" to builds)).plan("a").blocked)
        assertIs<PlanBlock.MissingBuild>(resolver(mapOf("a" to listOf(a.copy(dependencies = listOf(dep("lib", versionId = "lib-3")))), "lib" to builds)).plan("a").blocked)
    }
    @Test fun dependenciesWithOnlyVersionIdResolveThroughLookup() = runBlocking {
        val a = candidate("a", deps = listOf(ModCandidate.Dependency(versionId = "lib-1")))
        val plan = ModResolver(versionById = { candidate("lib") }) { listOf(a) }.plan("a")
        assertEquals(listOf("lib"), plan.required.map { it.projectId })
    }
    @Test fun disabledThirdPartyDownloadsBlockRootAndDependency() = runBlocking {
        val root = candidate("a", downloadable = false).copy(websiteUrl = "https://curseforge.com/a")
        val block = assertIs<PlanBlock.DownloadDisabled>(resolver(mapOf("a" to listOf(root))).plan("a").blocked)
        assertEquals("https://curseforge.com/a", block.websiteUrl)
        assertIs<PlanBlock.DownloadDisabled>(resolver(mapOf("a" to listOf(candidate("a", deps = listOf(dep("lib")))), "lib" to listOf(candidate("lib", downloadable = false)))).plan("a").blocked)
    }
    @Test fun packsIgnoreLoadersAndDependencies() = runBlocking {
        val plan = resolver(mapOf("a" to listOf(candidate("a", loaders = listOf(Loader.Unknown), deps = listOf(dep("ignored"))))), kind = ContentKind.ResourcePacks).plan("a")
        assertNull(plan.blocked); assertTrue(plan.required.isEmpty())
    }
    @Test fun newestStableOtherwiseBetaOtherwiseAlpha() = runBlocking {
        for (channel in VersionChannel.entries) {
            val plan = resolver(mapOf("a" to listOf(candidate("a", channel = channel)))).plan("a")
            assertEquals(channel, plan.target?.channel); assertEquals(channel != VersionChannel.Release, plan.needsConfirmation)
        }
        val dep = resolver(mapOf("a" to listOf(candidate("a", deps = listOf(dep("lib")))), "lib" to listOf(candidate("lib", channel = VersionChannel.Beta, version = "9"), candidate("lib", version = "8")))).plan("a")
        assertEquals("lib-8", dep.required.single().versionId)
    }
    @Test fun updatesNeverDowngradeStabilityOrVersion() {
        val old = candidate("a", version = "1")
        val beta = candidate("a", channel = VersionChannel.Beta, version = "3")
        val release = candidate("a", version = "2")
        assertEquals(release, ModUpdates.candidate(old, listOf(beta, release, old)))
        assertNull(ModUpdates.candidate(old, listOf(beta, old)))
        assertNull(ModUpdates.candidate(release, listOf(release, old)))
        assertEquals(release, ModUpdates.candidate(old.copy(channel = VersionChannel.Alpha), listOf(beta.copy(gameVersions = listOf("other")), release, old)))
        assertEquals(listOf(VersionChannel.Release), VersionChannel.Release.updateChannels)
        assertTrue(VersionChannel.Beta.allowsUpdate(VersionChannel.Release)); assertFalse(VersionChannel.Beta.allowsUpdate(VersionChannel.Alpha))
        assertEquals(VersionChannel.Beta, VersionChannel.from("BETA")); assertEquals(VersionChannel.Release, VersionChannel.from(null))
    }
    @Test fun versionLabelsPortIosCases() {
        mapOf("mc1.21.1-0.6.0-fabric" to "0.6.0", "0.9.2+mc26.3" to "0.9.2", "1.11.6+mc26.3" to "1.11.6", "0.160.5+26.3" to "0.160.5", "mc26.3-0.9.3-beta.1-fabric" to "0.9.3 beta 1", "mc26.3-0.9.0-alpha.3-neoforge" to "0.9.0 alpha 3", "0.5.8+1.21.1-fabric" to "0.5.8", "fabric-0.5.3" to "0.5.3", "v2.1.0" to "2.1.0", "sodium-fabric-0.6.0+mc1.21.1.jar (1.21.1)" to "0.6.0", "6.0.4" to "6.0.4").forEach { (raw, expected) -> assertEquals(expected, VersionLabel.clean(raw), raw) }
    }
}
