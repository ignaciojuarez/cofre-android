package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Immutable
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.instances.InstanceStore
import com.ignacio.cofre.kit.modpacks.FileOps
import com.ignacio.cofre.kit.modpacks.Modpack
import com.ignacio.cofre.kit.modpacks.ModpackInstallPlan
import com.ignacio.cofre.kit.modpacks.Modpacks
import com.ignacio.cofre.kit.modpacks.PackFile
import com.ignacio.cofre.kit.modpacks.PackRejected
import com.ignacio.cofre.kit.mods.safeFilename
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID

/** Where an import is. */
sealed interface ImportPhase {
    data object Idle : ImportPhase

    data class Working(val title: String) : ImportPhase

    /** Inspected. [plan] is null when the pack can't run here, and [rejected] says why. */
    @Immutable
    data class Ready(val archive: File, val pack: Modpack, val plan: ModpackInstallPlan?, val rejected: String?) : ImportPhase {
        val mods: Int get() = pack.files.count { it.path.startsWith("mods/") }
        val other: Int get() = pack.files.size - mods
    }

    data class Installing(val name: String, val files: Int, val totalFiles: Int, val fraction: Float?) : ImportPhase

    data class Done(val instance: Instance) : ImportPhase

    data class Failed(val reason: String) : ImportPhase
}

/**
 * Import pack: a file, a link or a share from another app → inspect → a new instance. Everything lands in a
 * staged folder under the instances root first; a failure rolls the stage back, so no half instance is left.
 */
class ImportModel(
    private val scope: CoroutineScope,
    private val instances: InstanceStore,
    private val modrinth: Modrinth,
    private val curseForge: () -> CurseForge?,
    private val cache: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow<ImportPhase>(ImportPhase.Idle)
    val phase: StateFlow<ImportPhase> = mutable.asStateFlow()
    private var job: Job? = null

    /**
     * A local archive; [load] copies it out of a content URI first when it came from the picker or another app. The
     * file is the model's: it's deleted once the import ends, fails or is dropped.
     */
    fun open(name: String, load: () -> File) {
        discard()
        begin("Reading $name") { inspect(withContext(io) { load() }) }
    }

    /** A pasted link: a Modrinth modpack page, or a direct `.mrpack`/`.zip` link. */
    fun openLink(text: String) {
        discard()
        begin("Fetching the pack") {
            val url = text.trim().let { if ("://" in it) it else "https://$it" }.toHttpUrlOrNull()
            require(url != null && url.isHttps) { "Paste a Modrinth modpack page or an https .mrpack link." }
            val segments = url.pathSegments.filter { it.isNotBlank() }
            val direct = when {
                url.encodedPath.endsWith(".mrpack") || url.encodedPath.endsWith(".zip") -> url.toString()
                url.host.endsWith("modrinth.com") && segments.size >= 2 && segments[0] == "modpack" -> {
                    val versions = modrinth.versions(segments[1], listOf(Target.LOADER.modrinthId), listOf(Target.GAME_VERSION), includeChangelog = false)
                        .ifEmpty { modrinth.versions(segments[1], includeChangelog = false) }
                    requireNotNull(versions.firstOrNull()?.primaryFile?.url) { "That pack has no files." }
                }
                else -> throw IllegalArgumentException("Paste a Modrinth modpack page or an https .mrpack link.")
            }
            val name = direct.substringAfterLast('/').substringBefore('?').takeIf { it.endsWith(".mrpack") || it.endsWith(".zip") } ?: "pack.mrpack"
            inspect(fetch(direct, name))
        }
    }

    fun install() {
        val ready = mutable.value as? ImportPhase.Ready ?: return
        val plan = ready.plan ?: return
        begin("Installing ${plan.name}") {
            try {
                mutable.value = ImportPhase.Done(installPack(ready.archive, plan))
            } finally {
                delete(ready.archive)
            }
        }
    }

    fun reset() {
        job?.cancel()
        discard()
        mutable.value = ImportPhase.Idle
    }

    /** Drops the archive of an inspected pack nobody installed. */
    private fun discard() {
        (mutable.value as? ImportPhase.Ready)?.let { delete(it.archive) }
    }

    /** Deletes an archive, and the folder [fetch] made for it. */
    private fun delete(archive: File) {
        archive.delete()
        archive.parentFile?.takeIf { it.parentFile == File(cache, "imports") }?.let(FileOps::delete)
    }

    private suspend fun inspect(archive: File) {
        val pack = try {
            withContext(io) { Modpacks.inspect(archive) }
        } catch (e: Exception) {
            delete(archive)
            throw e
        }
        val (plan, rejected) = try {
            Modpacks.plan(pack) to null
        } catch (e: PackRejected) {
            null to describe(e.reason)
        }
        mutable.value = ImportPhase.Ready(archive, pack, plan, rejected)
    }

    private suspend fun fetch(url: String, name: String): File = withContext(io) {
        val dir = File(cache, "imports/${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, name)
        try {
            val queue = DownloadQueue(File(dir, "queue.json"))
            queue.enqueue(DownloadItem(url, file.path, name))
            check(queue.run() == DownloadQueue.Result.Completed) { "Couldn't download the pack." }
            File(dir, "queue.json").delete()
            file
        } catch (e: Exception) {
            FileOps.delete(dir)
            throw e
        }
    }

    private suspend fun installPack(archive: File, plan: ModpackInstallPlan): Instance = withContext(io) {
        val stage = plan.stage(instances.root)
        val journal = File(cache, "imports/${UUID.randomUUID()}.json").apply { parentFile?.mkdirs() }
        try {
            val queue = DownloadQueue(journal)
            queue.enqueue(plan.filesToFetch.map { download(it, stage.directory) })
            val progress = launch {
                queue.progress.collectLatest { p ->
                    val fraction = if (p.totalBytes > 0) p.bytes.toFloat() / p.totalBytes else null
                    mutable.value = ImportPhase.Installing(plan.name, p.files, p.totalFiles, fraction)
                }
            }
            val result = try { queue.run() } finally { progress.cancel() }
            check(result == DownloadQueue.Result.Completed) { "Some of the pack's files didn't download. Nothing was installed." }
            plan.copyOverrides(archive, stage)
            val instance = Instance(InstanceStore.newID(plan.name), instances.uniqueName(plan.name), loader = Loader.NeoForge, loaderVersion = plan.loaderVersion)
            instances.validate(instance) // once committed, a bad cofre.json can't be rolled back
            File(stage.directory, "cofre.json").writeText(Kit.json.encodeToString(instance))
            stage.commit(File(instances.root, instance.id))
            instances.get(instance.id)
        } catch (e: Exception) {
            stage.rollback()
            throw e
        } finally {
            journal.delete()
        }
    }

    /**
     * The hash-checked download of one pack file into [stage]. CurseForge files need the key; their name is checked
     * to be a plain file name and their SHA-1 comes from CurseForge's API.
     */
    internal suspend fun download(file: PackFile, stage: File): DownloadItem {
        file.downloads.firstOrNull()?.let { url ->
            return DownloadItem(url, FileOps.child(stage, file.path).path, file.path.substringAfterLast('/'), file.fileSize.takeIf { it > 0 }, file.hashes["sha1"], file.hashes["sha512"])
        }
        val project = requireNotNull(file.projectID) { "A pack file has no download." }
        val client = curseForge() ?: throw IllegalStateException("This is a CurseForge pack. Add a CurseForge key in Settings first.")
        val cf = client.file(project, requireNotNull(file.fileID))
        return when (val download = client.download(cf, "https://www.curseforge.com/projects/$project")) {
            is CurseForge.Download.Available -> {
                safeFilename(cf.fileName)
                val sha1 = cf.hashes.firstOrNull { it.algo == CF_SHA1 }?.value
                require(sha1 == null || sha1.matches(Regex("[0-9a-fA-F]{40}"))) { "CurseForge sent a bad hash for ${cf.fileName}." }
                DownloadItem(download.url, FileOps.child(stage, "mods/${cf.fileName}").path, cf.fileName, cf.fileLength.takeIf { it > 0 }, sha1)
            }
            is CurseForge.Download.Disabled -> throw IllegalStateException("${cf.displayName} can only be downloaded on the CurseForge website.")
        }
    }

    private fun begin(title: String, block: suspend () -> Unit) {
        job?.cancel()
        mutable.value = ImportPhase.Working(title)
        job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                mutable.value = ImportPhase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        /** CurseForge's `HashAlgo.Sha1`. */
        private const val CF_SHA1 = 1

        fun describe(reason: PackRejected.Reason): String = when (reason) {
            is PackRejected.Reason.GameVersion -> "It's for Minecraft ${reason.actual ?: "an unknown version"}. Cofre runs ${Target.GAME_VERSION} only."
            is PackRejected.Reason.LoaderType -> "It's a ${reason.actual.displayName} pack. Cofre runs NeoForge only."
            PackRejected.Reason.LoaderVersionMissing -> "It doesn't say which NeoForge version it needs."
            is PackRejected.Reason.Invalid -> reason.detail
        }
    }
}
