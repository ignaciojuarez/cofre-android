package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Immutable
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.compat.CompatibilityIssue
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.mods.ModCandidate
import com.ignacio.cofre.kit.mods.ModInstallFailure
import com.ignacio.cofre.kit.mods.ModInstallPlan
import com.ignacio.cofre.kit.mods.PlanBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Where a Get is. The sheet draws one phase at a time. */
sealed interface InstallPhase {
    data object Planning : InstallPhase

    /** What Get adds. [offered] keeps the first plan's optional mods so their toggles stay put when picked. */
    @Immutable
    data class Confirm(
        val plan: ModInstallPlan,
        val offered: List<ModCandidate>,
        val picked: Set<String> = emptySet(),
        val replanning: Boolean = false,
    ) : InstallPhase {
        /** Required extras, without the optional ones the user turned on (those keep their toggle). */
        val required: List<ModCandidate> get() = plan.required.filter { it.projectId !in picked }
        val bytes: Long get() = plan.downloadBytes
        val files: Int get() = plan.downloads.size
    }

    /** It can't be installed as asked; [block] says why. A disabled CurseForge download carries the website. */
    data class Blocked(val block: PlanBlock?) : InstallPhase

    data class Installing(val files: List<String>) : InstallPhase

    data class Done(val files: List<String>) : InstallPhase

    data class Failed(val reason: String) : InstallPhase
}

/**
 * One Get, from plan to installed. A plan that adds nothing and warns about nothing installs straight away;
 * anything else waits for [confirm]. Optional mods re-plan when toggled so their own dependencies come too.
 */
class InstallModel(
    private val scope: CoroutineScope,
    val project: ProjectSummary,
    /** A Vulkan or loader warning that must be read before Get. */
    val issue: CompatibilityIssue?,
    private val plan: suspend (optional: Set<String>) -> ModInstallPlan,
    private val install: suspend (ModInstallPlan) -> Unit,
) {
    private val mutable = MutableStateFlow<InstallPhase>(InstallPhase.Planning)
    val phase: StateFlow<InstallPhase> = mutable.asStateFlow()
    private var job: Job? = null

    init {
        start()
    }

    private fun start() {
        mutable.value = InstallPhase.Planning
        job = scope.launch {
            val first = attempt { plan(emptySet()) } ?: return@launch
            if (first.blocked != null || first.target == null) {
                mutable.value = InstallPhase.Blocked(first.blocked)
                return@launch
            }
            val confirm = InstallPhase.Confirm(first, first.optional)
            if (!first.needsConfirmation && issue == null) run(confirm.plan) else mutable.value = confirm
        }
    }

    fun toggle(projectId: String) {
        val now = mutable.value as? InstallPhase.Confirm ?: return
        val picked = if (projectId in now.picked) now.picked - projectId else now.picked + projectId
        mutable.value = now.copy(picked = picked, replanning = true)
        job?.cancel()
        job = scope.launch {
            val next = attempt { plan(picked) } ?: return@launch
            mutable.update { s ->
                when {
                    s !is InstallPhase.Confirm -> s
                    next.blocked != null -> InstallPhase.Blocked(next.blocked)
                    else -> s.copy(plan = next, replanning = false)
                }
            }
        }
    }

    fun confirm() {
        val now = mutable.value as? InstallPhase.Confirm ?: return
        if (now.replanning) return
        job = scope.launch { run(now.plan) }
    }

    fun retry() {
        if (mutable.value is InstallPhase.Failed) start()
    }

    private suspend fun run(plan: ModInstallPlan) {
        val files = plan.downloads.map { it.title }
        mutable.value = InstallPhase.Installing(files)
        attempt { install(plan) } ?: return
        mutable.value = InstallPhase.Done(files)
    }

    /** Runs [block]; a failure becomes [InstallPhase.Failed] (or Blocked) and returns null. */
    private suspend fun <T : Any> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (blocked: ModInstallFailure.Blocked) {
        mutable.value = InstallPhase.Blocked(blocked.reason)
        null
    } catch (e: Exception) {
        mutable.value = InstallPhase.Failed(reason(e))
        null
    }

    companion object {
        fun reason(e: Exception): String = when (e) {
            is ModInstallFailure.Download -> "A download failed. Check your connection and try again."
            is ModInstallFailure.MissingRequirement -> "It needs ${e.requirement.name}, and no NeoForge 26.2 build of it was found."
            is ModInstallFailure.Rollback -> "Couldn't put the old mods back. A backup is in ${e.recoveryDirectory}."
            is ModInstallFailure.MeteredApproval -> "These files are big. Connect to Wi-Fi to get them."
            is java.io.IOException -> e.message ?: "Couldn't reach the server."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

/** Why a plan is blocked, in words. */
fun PlanBlock?.describe(title: String): String = when (this) {
    is PlanBlock.MissingBuild -> "$reason."
    is PlanBlock.Incompatible -> "$first doesn't work with $second."
    is PlanBlock.VersionConflict -> "Two mods need different versions of $projectId."
    is PlanBlock.DownloadDisabled -> "The author turned off downloads outside CurseForge. Get $title on the website."
    null -> "No NeoForge 26.2 build of $title yet."
}

/**
 * Gets one resource pack, shader pack or data pack into [folder]: download and verify in a stage beside it, then
 * move it in. Jars go through ModInstaller instead.
 */
suspend fun installFile(candidate: ModCandidate, folder: File, staging: File) = withContext(Dispatchers.IO) {
    val name = requireNotNull(candidate.filename) { "No file to download" }
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..") { "Unsafe file name" }
    val url = requireNotNull(candidate.url) { "No download link" }
    val stage = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
    try {
        val download = File(stage, name)
        val queue = DownloadQueue(File(stage, "queue.json"))
        queue.enqueue(DownloadItem(url, download.path, candidate.title, candidate.size, candidate.sha1, candidate.sha512))
        when (val result = queue.run()) {
            DownloadQueue.Result.Completed -> Unit
            is DownloadQueue.Result.Failed -> throw ModInstallFailure.Download(result.ids)
            is DownloadQueue.Result.NeedsMeteredApproval -> throw ModInstallFailure.MeteredApproval(result.ids)
        }
        folder.mkdirs()
        Files.move(download.toPath(), File(folder, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        stage.deleteRecursively()
    }
}

/** The content folder a Get lands in, for kinds that aren't jars. */
fun contentFolder(instance: File, kind: ContentKind, world: String?): File = when (kind) {
    ContentKind.DataPacks -> {
        require(!world.isNullOrBlank() && '/' !in world && !world.startsWith('.')) { "Pick a world" }
        File(instance, "saves/$world/datapacks")
    }
    else -> File(instance, requireNotNull(kind.folderName))
}
