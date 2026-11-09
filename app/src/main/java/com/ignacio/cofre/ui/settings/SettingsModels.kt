package com.ignacio.cofre.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.BuildConfig
import com.ignacio.cofre.design.LogLevel
import com.ignacio.cofre.design.LogLine
import com.ignacio.cofre.kit.logs.LogFile
import com.ignacio.cofre.kit.logs.LogStore
import com.ignacio.cofre.kit.logs.ReportBundle
import com.ignacio.cofre.kit.logs.ReportContext
import com.ignacio.cofre.kit.options.Storage
import com.ignacio.cofre.kit.options.StorageCleanup
import com.ignacio.cofre.onIo
import com.ignacio.cofre.scratch
import com.ignacio.cofre.share
import com.ignacio.cofre.work
import java.io.File
import com.ignacio.cofre.kit.logs.LogLevel as KitLevel

/** Space by area, and the logs/crash reports/temporary files that can go. */
class StorageModel(private val graph: AppGraph) {
    data class Area(val name: String, val bytes: Long)

    var areas by mutableStateOf<List<Area>>(emptyList())
        private set
    var reclaimable by mutableStateOf(0L)
        private set
    var free by mutableStateOf(0L)
        private set
    var clearing by mutableStateOf(false)
        private set
    var freed by mutableStateOf<Long?>(null)
        private set

    private fun cleanup() = StorageCleanup(
        graph.home,
        graph.instances.list().map(graph.instances::directory),
        File(graph.context.cacheDir, "scratch"),
    )

    fun refresh() = graph.work({}) {
        val (list, bytes) = graph.onIo {
            val folders = graph.home.listFiles().orEmpty().filter { it.isDirectory }
            folders.map { Area(it.name, Storage.bytes(it)) }.filter { it.bytes > 0 }.sortedByDescending { it.bytes } to cleanup().reclaimableBytes
        }
        areas = list
        reclaimable = bytes
        free = graph.home.usableSpace
    }

    fun clear() {
        clearing = true
        graph.work({ clearing = false }) {
            freed = graph.onIo { cleanup().clear() }
            clearing = false
            refresh()
        }
    }
}

/** A live log (launcher, game or newest crash report) for the active instance, with search and a redacted share. */
class LogsModel(private val graph: AppGraph) {
    var source by mutableStateOf(LogFile.Launcher)
    var lines by mutableStateOf<List<LogLine>>(emptyList())
        private set

    private fun store(): LogStore {
        val instance = graph.library.active
        return LogStore(instance?.let(graph.instances::directory) ?: graph.home, graph.launcherLog)
    }

    /** Re-reads the log; the screen calls it about once a second while it's visible. */
    suspend fun load(query: String) {
        val source = source
        lines = graph.onIo {
            store().search(query, sources = setOf(source)).takeLast(MAX_LINES).map { entry ->
                val level = when (entry.level) {
                    KitLevel.Error -> LogLevel.Error
                    KitLevel.Warning -> LogLevel.Warning
                    KitLevel.Success -> LogLevel.Success
                    KitLevel.Info -> LogLevel.Info
                }
                LogLine("", source.name, ReportBundle.redact(entry.text), level)
            }
        }
    }

    fun share() = graph.work({}) {
        val file = graph.onIo {
            ReportBundle(store(), ReportContext(appVersion = BuildConfig.VERSION_NAME, device = android.os.Build.MODEL, android = android.os.Build.VERSION.RELEASE))
                .make(graph.scratch("cofre-logs.zip"))
        }
        graph.share(file, "application/zip")
    }

    private companion object {
        const val MAX_LINES = 2000
    }
}
