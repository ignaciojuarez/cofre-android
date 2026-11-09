package com.ignacio.cofre.ui.downloads

import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.kit.downloads.DownloadItem
import com.ignacio.cofre.kit.downloads.DownloadQueue
import com.ignacio.cofre.kit.downloads.DownloadSummary
import com.ignacio.cofre.work
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The download queue as screen state: the Play strip and the Downloads sheet. Running happens in the app scope;
 * pausing cancels that run (the queue keeps partial files and resumes them).
 */
class Downloads(private val graph: AppGraph) {
    private val queue: DownloadQueue get() = graph.downloads

    var summary by mutableStateOf(DownloadSummary(emptyList()))
        private set
    var bytesPerSecond by mutableStateOf(0L)
        private set
    var running by mutableStateOf(false)
        private set
    /** Big downloads waiting on mobile data that the user hasn't answered yet. */
    var asking by mutableStateOf<List<DownloadItem>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    init {
        graph.scope.launch {
            queue.progress.collect { progress ->
                summary = DownloadSummary(progress.items)
                bytesPerSecond = progress.bytesPerSecond
            }
        }
    }

    fun metered(): Boolean =
        !graph.settings.value.allowMetered &&
            graph.context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

    fun start() {
        if (job?.isActive == true) return
        // A paused run may still be unwinding and holding the queue; the next run waits for it.
        val paused = job
        running = true
        error = null
        job = graph.work({ error = it; running = false }) {
            paused?.join()
            val result = withContext(graph.io) { queue.run(metered()) }
            if (result is DownloadQueue.Result.NeedsMeteredApproval) {
                asking = summary.items.filter { it.id in result.ids }
            }
            running = false
        }
    }

    fun pause() {
        job?.cancel()
        running = false
    }

    fun retry(id: String) {
        queue.retry(id)
        start()
    }

    fun cancel(id: String) = queue.cancel(id)

    /** "Download now" on mobile data. */
    fun allowMetered() {
        val ids = asking.map { it.id }
        asking = emptyList()
        pause()
        ids.forEach { queue.retry(it, allowMetered = true) }
        start()
    }

    /** "Wait for Wi-Fi": the ask doesn't come back for these. */
    fun waitForWifi() {
        asking = emptyList()
    }
}
