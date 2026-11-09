package com.ignacio.cofre.ui.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.compat.CompatibilityIssue
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.downloads.DownloadProgress
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModInstallFailure
import com.ignacio.cofre.kit.mods.ModInstallPlan
import com.ignacio.cofre.kit.mods.PlanBlock
import com.ignacio.cofre.kit.pages.ProjectPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InstallModelTest {
    private val project = ProjectSummary("AANobbMI", ProjectPage.Source.Modrinth, ContentKind.Mods, "Sodium", "Fast")

    private fun mod(id: String, size: Long = 1000) =
        ModCandidate(id, "$id-v", id, "1.0", listOf("26.2"), listOf(Loader.NeoForge), filename = "$id.jar", url = "https://cdn/$id.jar", size = size)

    private fun TestScope.model(
        issue: CompatibilityIssue? = null,
        plan: suspend (Set<String>) -> ModInstallPlan,
        install: suspend (ModInstallPlan, (DownloadProgress) -> Unit) -> Unit = { _, _ -> },
    ) = InstallModel(CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), project, issue, plan, install)

    @Test fun aLoneModInstallsWithoutAsking() = runTest {
        val installed = mutableListOf<ModInstallPlan>()
        val get = model(plan = { ModInstallPlan(mod("sodium")) }, install = { p, _ -> installed += p })
        advanceUntilIdle()
        assertEquals(InstallPhase.Done(listOf("sodium")), get.phase.value)
        assertEquals(1, installed.size)
    }

    @Test fun extrasWaitForConfirm() = runTest {
        val get = model(plan = { ModInstallPlan(mod("create"), required = listOf(mod("flywheel"))) })
        advanceUntilIdle()
        val confirm = assertIs<InstallPhase.Confirm>(get.phase.value)
        assertEquals(listOf("flywheel"), confirm.required.map { it.projectId })
        assertEquals(2000, confirm.bytes)
        get.confirm()
        advanceUntilIdle()
        assertEquals(InstallPhase.Done(listOf("create", "flywheel")), get.phase.value)
    }

    @Test fun aVulkanWarningAlwaysAsksFirst() = runTest {
        val issue = CompatibilityIssue("iris", "Iris", ContentKind.Mods, "Iris uses OpenGL.")
        val get = model(issue, plan = { ModInstallPlan(mod("iris")) })
        advanceUntilIdle()
        assertIs<InstallPhase.Confirm>(get.phase.value)
    }

    @Test fun pickingAnOptionalModReplansWithItAndKeepsItsToggle() = runTest {
        val asked = mutableListOf<Set<String>>()
        val get = model(plan = { picked ->
            asked += picked
            if ("jei" in picked) ModInstallPlan(mod("create"), required = listOf(mod("jei"), mod("jei-lib")))
            else ModInstallPlan(mod("create"), optional = listOf(mod("jei")))
        })
        advanceUntilIdle()
        get.toggle("jei")
        assertTrue(assertIs<InstallPhase.Confirm>(get.phase.value).replanning)
        advanceUntilIdle()
        val confirm = assertIs<InstallPhase.Confirm>(get.phase.value)
        assertEquals(listOf(emptySet(), setOf("jei")), asked)
        assertEquals(listOf("jei"), confirm.offered.map { it.projectId }, "the toggle stays")
        assertEquals(listOf("jei-lib"), confirm.required.map { it.projectId }, "jei shows as its toggle, its library as required")
        assertEquals(3, confirm.files)
    }

    @Test fun disabledCurseForgeDownloadsPointToTheWebsite() = runTest {
        val get = model(plan = { ModInstallPlan(mod("jei"), blocked = PlanBlock.DownloadDisabled("jei", "https://www.curseforge.com/minecraft/mc-mods/jei")) })
        advanceUntilIdle()
        val blocked = assertIs<InstallPhase.Blocked>(get.phase.value)
        assertEquals("https://www.curseforge.com/minecraft/mc-mods/jei", (blocked.block as PlanBlock.DownloadDisabled).websiteUrl)
        assertTrue(blocked.block.describe("JEI").contains("website"))
    }

    @Test fun aFailedInstallSaysWhyAndCanRetry() = runTest {
        var attempts = 0
        val get = model(plan = { ModInstallPlan(mod("sodium")) }, install = { _, _ ->
            attempts++
            if (attempts == 1) throw ModInstallFailure.Download(listOf("sodium"))
        })
        advanceUntilIdle()
        val failed = assertIs<InstallPhase.Failed>(get.phase.value)
        assertTrue(failed.reason.contains("download", ignoreCase = true))
        get.retry()
        advanceUntilIdle()
        assertIs<InstallPhase.Done>(get.phase.value)
        assertEquals(2, attempts)
    }

    @Test fun installingShowsBytesAndFiles() = runTest {
        val gate = CompletableDeferred<Unit>()
        val get = model(plan = { ModInstallPlan(mod("sodium")) }, install = { _, report ->
            fun item(done: Long, state: DownloadItem.State) = DownloadItem("u", "d", size = 1000, completedBytes = done, state = state)
            report(DownloadProgress(listOf(item(1000, DownloadItem.State.Finished), item(500, DownloadItem.State.Running))))
            gate.await()
        })
        advanceUntilIdle()
        assertEquals(InstallPhase.Installing(listOf("sodium"), done = 1, total = 2, fraction = 0.75f), get.phase.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertIs<InstallPhase.Done>(get.phase.value)
    }

    @Test fun blockedDuringInstallBecomesBlocked() = runTest {
        val get = model(plan = { ModInstallPlan(mod("a")) }, install = { _, _ -> throw ModInstallFailure.Blocked(PlanBlock.Incompatible("A", "B")) })
        advanceUntilIdle()
        assertEquals("A doesn't work with B.", assertIs<InstallPhase.Blocked>(get.phase.value).block.describe("A"))
    }
}
