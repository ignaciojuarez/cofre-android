package com.ignacio.cofre.kit.downloads

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.modpacks.FileOps
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Serializable data class DownloadItem(
    val url: String, val destination: String, val title: String = File(destination).name,
    val size: Long? = null, val sha1: String? = null, val sha512: String? = null,
    val id: String = UUID.randomUUID().toString(), val completedBytes: Long = 0,
    val state: State = State.Queued, val error: String? = null, val allowMetered: Boolean = false,
    val etag: String? = null,
) {
    @Serializable enum class State { Queued, Running, WaitingForWifi, Paused, Failed, Finished, Cancelled }
    val part: File get() = File("$destination.part")
}

data class DownloadProgress(val items: List<DownloadItem> = emptyList(), val bytesPerSecond: Long = 0) {
    val bytes: Long get() = items.sumOf { it.completedBytes }
    val totalBytes: Long get() = items.sumOf { it.size ?: 0 }
    val files: Int get() = items.count { it.state == DownloadItem.State.Finished }
    val totalFiles: Int get() = items.size
}

sealed class DownloadFailure(message: String) : IOException(message) {
    data class NoDiskSpace(val required: Long, val free: Long) : DownloadFailure("Not enough disk space")
    data object HashMismatch : DownloadFailure("Downloaded hash does not match")
    data object SizeMismatch : DownloadFailure("Downloaded size does not match")
    data class Http(val status: Int) : DownloadFailure("HTTP $status")
    data object InvalidRange : DownloadFailure("Invalid HTTP content range")
}

data class DiskPreflight(val downloadBytes: Long, val freeBytes: Long) {
    val needBytes: Long get() = if (downloadBytes <= 0) 0 else if (downloadBytes > Long.MAX_VALUE - MARGIN) Long.MAX_VALUE else downloadBytes + MARGIN
    val hasRoom: Boolean get() = freeBytes >= needBytes
    companion object {
        const val MARGIN: Long = 128L * 1024 * 1024
        fun check(bytes: Long, at: File): DiskPreflight {
            var directory = at.absoluteFile
            while (!directory.exists() && directory.parentFile != null) directory = directory.parentFile
            return DiskPreflight(bytes, directory.usableSpace)
        }
    }
}

/** Run in a caller-owned coroutine. Cancelling that coroutine pauses unfinished items on disk. */
class DownloadQueue(
    private val journal: File,
    private val http: OkHttpClient = Kit.http,
    private val concurrency: Int = 4,
    private val attempts: Int = 3,
    private val backoffMillis: Long = 500,
) {
    init { require(concurrency > 0 && attempts > 0 && backoffMillis >= 0) }
    private val lock = Any()
    private val runLock = Mutex()
    private val items = linkedMapOf<String, DownloadItem>()
    private val jobs = mutableMapOf<String, Job>()
    private val mutableProgress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = mutableProgress.asStateFlow()
    private var startedAt = 0L
    private var transferred = 0L

    init {
        if (journal.isFile) {
            // A corrupt journal is an error, not an empty queue that silently loses work.
            Kit.json.decodeFromString<List<DownloadItem>>(journal.readText()).forEach { item ->
                val state = if (item.state == DownloadItem.State.Running) DownloadItem.State.Paused else item.state
                items[item.id] = item.copy(state = state, completedBytes = if (state == DownloadItem.State.Finished) item.completedBytes else item.part.length())
            }
            publish()
        }
    }
    fun enqueue(item: DownloadItem, metered: Boolean = false): String = synchronized(lock) {
        require(item.size == null || item.size >= 0)
        require(items.values.none { it.id != item.id && File(it.destination).canonicalPath == File(item.destination).canonicalPath && it.state !in listOf(DownloadItem.State.Finished, DownloadItem.State.Cancelled) }) { "Destination is already queued" }
        require(item.id !in jobs) { "Download is running" }
        val check = DiskPreflight.check((item.size ?: 0) - item.part.length(), File(item.destination))
        if (!check.hasRoom) throw DownloadFailure.NoDiskSpace(check.needBytes, check.freeBytes)
        items[item.id] = item.copy(completedBytes = item.part.length(), state = if (asksOnMetered(item.size, metered, item.allowMetered)) DownloadItem.State.WaitingForWifi else DownloadItem.State.Queued, error = null)
        save(); item.id
    }
    fun retry(id: String, allowMetered: Boolean = false) = update(id) { it.copy(state = DownloadItem.State.Queued, error = null, allowMetered = allowMetered || it.allowMetered) }
    fun cancel(id: String) {
        synchronized(lock) { jobs[id]?.cancel(); update(id) { it.copy(state = DownloadItem.State.Cancelled) } }
    }
    sealed interface Result {
        data object Completed : Result
        data class NeedsMeteredApproval(val ids: List<String>) : Result
        data class Failed(val ids: List<String>) : Result
    }
    suspend fun run(metered: Boolean = false): Result {
        check(runLock.tryLock()) { "Queue is already running" }
        try {
            synchronized(lock) { startedAt = System.nanoTime(); transferred = 0 }
            val semaphore = Semaphore(concurrency)
            while (true) {
                val pending = synchronized(lock) {
                    items.values.filter { it.state in listOf(DownloadItem.State.Queued, DownloadItem.State.Paused, DownloadItem.State.WaitingForWifi) }.mapNotNull { item ->
                        if (asksOnMetered(item.size, metered, item.allowMetered)) { update(item.id) { it.copy(state = DownloadItem.State.WaitingForWifi) }; null } else item
                    }
                }
                if (pending.isEmpty()) break
                val remaining = pending.sumOf { ((it.size ?: 0) - it.part.length()).coerceAtLeast(0) }
                pending.forEach {
                    val check = DiskPreflight.check(remaining, File(it.destination))
                    if (!check.hasRoom) throw DownloadFailure.NoDiskSpace(check.needBytes, check.freeBytes)
                }
                coroutineScope {
                    pending.forEach { item ->
                        val job = launch(start = CoroutineStart.LAZY) {
                            try {
                                semaphore.withPermit { download(item.id) }
                            } catch (cancelled: CancellationException) {
                                update(item.id) { if (it.state == DownloadItem.State.Cancelled) it else it.copy(state = DownloadItem.State.Paused, completedBytes = it.part.length()) }
                                throw cancelled
                            } finally { synchronized(lock) { jobs.remove(item.id) } }
                        }
                        synchronized(lock) { jobs[item.id] = job }
                        job.start()
                    }
                }
            }
            val snapshot = progress.value.items
            val failed = snapshot.filter { it.state == DownloadItem.State.Failed }.map { it.id }
            if (failed.isNotEmpty()) return Result.Failed(failed)
            val waiting = snapshot.filter { it.state == DownloadItem.State.WaitingForWifi }.map { it.id }
            return if (waiting.isNotEmpty()) Result.NeedsMeteredApproval(waiting) else Result.Completed
        } finally { runLock.unlock() }
    }
    private suspend fun download(id: String) {
        if (item(id).state == DownloadItem.State.Cancelled) return
        update(id) { it.copy(state = DownloadItem.State.Running, error = null) }
        for (attempt in 0 until attempts) {
            currentCoroutineContext().ensureActive()
            try {
                val item = item(id)
                val destination = File(item.destination)
                withContext(Dispatchers.IO) {
                    if (destination.isFile && (item.sha1 != null || item.sha512 != null)) {
                        try { verify(destination, item); update(id) { it.copy(state = DownloadItem.State.Finished, completedBytes = destination.length()) }; return@withContext } catch (_: DownloadFailure) { /* Replace stale destination only after verification. */ }
                    }
                    destination.parentFile?.mkdirs()
                    transfer(item)
                    currentCoroutineContext().ensureActive()
                    verify(item.part, item)
                    synchronized(lock) {
                        if (item(id).state == DownloadItem.State.Cancelled) throw CancellationException()
                        atomicMove(item.part, destination)
                        update(id) { it.copy(state = DownloadItem.State.Finished, completedBytes = destination.length(), error = null) }
                    }
                }
                return
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is DownloadFailure.HashMismatch || failure is DownloadFailure.SizeMismatch || failure is DownloadFailure.InvalidRange) item(id).part.delete()
                if (attempt + 1 == attempts || failure is DownloadFailure.Http && failure.status in 400..499 && failure.status !in listOf(408, 429)) {
                    update(id) { it.copy(state = DownloadItem.State.Failed, completedBytes = it.part.length(), error = failure.message) }; return
                }
                delay((backoffMillis * (1L shl attempt.coerceAtMost(10))).coerceAtMost(30_000))
            }
        }
    }
    private suspend fun transfer(item: DownloadItem): Unit = suspendCancellableCoroutine { continuation ->
        val offset = item.part.length()
        val request = Request.Builder().url(item.url).header("Accept-Encoding", "identity")
        if (offset > 0) {
            request.header("Range", "bytes=$offset-")
            item.etag?.let { request.header("If-Range", it) }
        }
        val call = http.newCall(request.build())
        val responseRef = AtomicReference<Response?>()
        continuation.invokeOnCancellation { call.cancel(); responseRef.get()?.close() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWith(kotlin.Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                responseRef.set(response)
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (it.code == 416 && item.size != null && offset == item.size) {
                            continuation.resumeWith(kotlin.Result.success(Unit)); return
                        }
                        if (!it.isSuccessful) throw DownloadFailure.Http(it.code)
                        val append = it.code == 206
                        if (append) {
                            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it.header("Content-Range").orEmpty()) ?: throw DownloadFailure.InvalidRange
                            val start = range.groupValues[1].toLong(); val end = range.groupValues[2].toLong()
                            if (start != offset || end < start || (item.size != null && range.groupValues[3] != "${item.size}")) throw DownloadFailure.InvalidRange
                        } else if (it.code != 200) throw DownloadFailure.Http(it.code)
                        it.header("ETag")?.let { etag -> update(item.id) { row -> row.copy(etag = etag) } }
                        var done = if (append) offset else 0
                        val expected = it.body.contentLength()
                        var received = 0L
                        FileOutputStream(item.part, append).use { output ->
                            it.body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!continuation.isActive) return
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read); done += read; received += read
                                    synchronized(lock) { transferred += read; update(item.id, persist = false) { row -> row.copy(completedBytes = done) } }
                                }
                            }
                            output.fd.sync()
                        }
                        if (expected >= 0 && received != expected) throw IOException("Interrupted response")
                    }
                    if (!continuation.isCancelled) continuation.resumeWith(kotlin.Result.success(Unit))
                } catch (failure: Exception) {
                    if (!continuation.isCancelled) continuation.resumeWith(kotlin.Result.failure(failure))
                } finally { responseRef.set(null) }
            }
        })
    }
    private fun verify(file: File, item: DownloadItem) {
        if (item.size != null && file.length() != item.size) throw DownloadFailure.SizeMismatch
        if (item.sha1 != null && !Hashes.sha1(file).equals(item.sha1, true)) throw DownloadFailure.HashMismatch
        if (item.sha512 != null && !Hashes.sha512(file).equals(item.sha512, true)) throw DownloadFailure.HashMismatch
    }
    private fun item(id: String): DownloadItem = synchronized(lock) { items.getValue(id) }
    private fun update(id: String, persist: Boolean = true, transform: (DownloadItem) -> DownloadItem) = synchronized(lock) {
        items[id]?.let { items[id] = transform(it) }
        if (persist) save() else publish()
    }
    private fun publish() {
        val elapsed = if (startedAt == 0L) 0 else System.nanoTime() - startedAt
        mutableProgress.value = DownloadProgress(items.values.toList(), if (elapsed <= 0) 0 else (transferred * 1e9 / elapsed).toLong())
    }
    private fun save() {
        FileOps.atomic(journal, Kit.json.encodeToString(items.values.toList()).toByteArray()); publish()
    }
    companion object {
        const val METERED_ASK_BYTES: Long = 200L * 1024 * 1024
        fun asksOnMetered(bytes: Long?, metered: Boolean, allowed: Boolean = false): Boolean = metered && !allowed && (bytes ?: 0) > METERED_ASK_BYTES
        internal fun atomicMove(from: File, to: File) {
            try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}

data class DownloadSummary(val items: List<DownloadItem>) {
    val visible: List<DownloadItem> get() = items.filter { it.state !in listOf(DownloadItem.State.Finished, DownloadItem.State.Cancelled) }.sortedBy { it.title }
    val active: List<DownloadItem> get() = visible.filter { it.state in listOf(DownloadItem.State.Running, DownloadItem.State.Queued) }
    val waiting: List<DownloadItem> get() = visible.filter { it.state == DownloadItem.State.WaitingForWifi }
    val stopped: List<DownloadItem> get() = visible.filter { it.state in listOf(DownloadItem.State.Paused, DownloadItem.State.Failed) }
    val fraction: Double get() { val sized = active.filter { (it.size ?: 0) > 0 }; val total = sized.sumOf { it.size!! }; return if (total == 0L) 0.0 else (sized.sumOf { it.completedBytes }.toDouble() / total).coerceIn(0.0, 1.0) }
    val title: String get() = when {
        active.isNotEmpty() -> "Downloading ${active.size} · ${(fraction * 100).toInt()}%"
        stopped.size == 1 -> "A download stopped"
        stopped.isNotEmpty() -> "${stopped.size} downloads stopped"
        waiting.size == 1 -> "A download waits for Wi-Fi"
        else -> "${waiting.size} downloads wait for Wi-Fi"
    }
}
