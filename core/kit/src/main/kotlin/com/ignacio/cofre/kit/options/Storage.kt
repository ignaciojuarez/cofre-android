package com.ignacio.cofre.kit.options

import java.io.File
import java.nio.file.Files

object Storage {
    data class Usage(val totalBytes: Long, val categories: Map<String, Long>)
    fun bytes(root: File): Long {
        if (Files.isSymbolicLink(root.toPath())) return 0
        return if (root.isFile) root.length() else root.listFiles().orEmpty().sumOf(::bytes)
    }
    fun usage(gameDirectory: File): Usage {
        val categories = gameDirectory.listFiles().orEmpty().associate { it.name to bytes(it) }
        return Usage(categories.values.sum(), categories)
    }
}

/** Only old logs, crash reports and day-old temporary downloads are disposable. */
class StorageCleanup(private val home: File, private val gameDirectories: List<File>, private val temporaryDirectory: File) {
    private fun files(): List<File> {
        val result = mutableSetOf<File>()
        fun collect(dir: File, old: Boolean = false) {
            if (Files.isSymbolicLink(dir.toPath())) return
            if (dir.isFile) {
                if (dir.name !in setOf("latest.log", "launcher.log") && (!old || dir.lastModified() < System.currentTimeMillis() - 86_400_000)) result += dir
            } else dir.listFiles().orEmpty().forEach { collect(it, old) }
        }
        for (root in (listOf(home) + gameDirectories).distinct()) {
            if (Files.isSymbolicLink(root.toPath())) continue
            listOf("logs", "crash-reports").forEach { collect(File(root, it)) }
        }
        collect(temporaryDirectory, true)
        return result.toList()
    }
    val reclaimableBytes: Long get() = files().sumOf { it.length() }
    fun clear(): Long = files().sumOf { file -> val size = file.length(); Files.delete(file.toPath()); size }
}
