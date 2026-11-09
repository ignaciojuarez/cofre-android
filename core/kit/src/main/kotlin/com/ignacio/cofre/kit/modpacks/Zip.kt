package com.ignacio.cofre.kit.modpacks

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared file boundary for the file-based domains. Never traverse links inside a supplied root. */
object FileOps {
    /** The segments of a relative [path], checked as a string: no absolute path, `..`, `.` or empty segments, `\\`, `:` or NUL. */
    fun segments(path: String): List<String> {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path) { "Unsafe path: $path" }
        val parts = path.removeSuffix("/").split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe path: $path" }
        return parts
    }

    fun child(root: File, path: String): File {
        val parts = segments(path)
        var target = root.absoluteFile
        require(!Files.isSymbolicLink(target.toPath())) { "Symbolic link: $target" }
        for (part in parts) {
            target = File(target, part)
            require(!Files.isSymbolicLink(target.toPath())) { "Symbolic link: $target" }
        }
        return target
    }

    /** Writes [bytes] to [file] through a synced temp file and a rename: readers see the old file or the new one. */
    fun atomic(file: File, bytes: ByteArray) {
        require(!Files.isSymbolicLink(file.toPath()))
        Files.createDirectories(file.absoluteFile.parentFile.toPath())
        val temp = File(file.absoluteFile.parentFile, ".cofre-write-${UUID.randomUUID()}")
        try {
            java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            replace(temp, file)
        } finally { temp.delete() }
    }

    /** Renames [source] over [target], atomically where the file system can. */
    fun replace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun move(source: File, target: File) {
        require(!Files.exists(target.toPath(), NOFOLLOW_LINKS)) { "Already exists: ${target.name}" }
        Files.createDirectories(target.absoluteFile.parentFile.toPath())
        Files.move(source.toPath(), target.toPath())
    }

    /** Deletes a file or folder without following links: a link inside is removed, never its target. */
    fun delete(root: File) {
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return
        Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult { Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE }
        })
    }

    fun tree(root: File): List<File> {
        if (!root.exists()) return emptyList()
        require(!Files.isSymbolicLink(root.toPath()))
        val result = mutableListOf<File>()
        fun visit(dir: File) {
            for (file in dir.listFiles().orEmpty().sortedBy { it.name }) {
                require(!Files.isSymbolicLink(file.toPath())) { "Symbolic link: $file" }
                result += file
                if (file.isDirectory) visit(file)
            }
        }
        visit(root)
        return result
    }

    fun copy(source: File, target: File) {
        require(!target.exists())
        if (source.isFile) {
            require(!Files.isSymbolicLink(source.toPath()))
            Files.createDirectories(target.absoluteFile.parentFile.toPath())
            source.copyTo(target)
        } else {
            Files.createDirectories(target.toPath())
            for (file in tree(source)) {
                val dest = child(target, file.relativeTo(source).invariantSeparatorsPath)
                if (file.isDirectory) Files.createDirectories(dest.toPath()) else {
                    Files.createDirectories(dest.parentFile.toPath()); file.copyTo(dest)
                }
            }
        }
    }
}

/** Streaming ZIP I/O with bounds checked before allocation and again while inflating. */
object Zip {
    const val MAX_ENTRY_BYTES: Long = 1L shl 30
    const val MAX_TOTAL_BYTES: Long = 8L shl 30
    const val MAX_ARCHIVE_BYTES: Long = 2L shl 30
    const val MAX_ENTRIES: Int = 100_000
    data class Entry(val path: String, val size: Long, val directory: Boolean)

    fun entries(archive: File): List<Entry> {
        require(archive.length() <= MAX_ARCHIVE_BYTES) { "Archive is too big" }
        return ZipFile(archive).use { zip ->
            val result = mutableListOf<Entry>()
            var total = 0L
            val paths = hashSetOf<String>()
            val source = zip.entries()
            while (source.hasMoreElements()) {
                val e = source.nextElement()
                FileOps.segments(e.name)
                require(paths.add(e.name.removeSuffix("/"))) { "Duplicate ZIP path: ${e.name}" }
                require(result.size < MAX_ENTRIES && e.size in 0..MAX_ENTRY_BYTES) { "ZIP size limit" }
                total += e.size
                require(total <= MAX_TOTAL_BYTES) { "ZIP size limit" }
                result += Entry(e.name, e.size, e.isDirectory)
            }
            result
        }
    }

    fun read(archive: File, path: String, maxBytes: Long = 4L shl 20): ByteArray {
        entries(archive)
        return ZipFile(archive).use { zip ->
            val entry = requireNotNull(zip.getEntry(path)) { "Missing ZIP entry: $path" }
            require(entry.size <= maxBytes)
            val out = java.io.ByteArrayOutputStream()
            zip.getInputStream(entry).use { transfer(it, out, entry, maxBytes) }
            out.toByteArray()
        }
    }

    /**
     * Streams each `entry → target` out of [archive], opened once: each through a temp file next to its target, then
     * a rename, so a target is never half-written. Targets must not exist yet.
     */
    fun copyOut(archive: File, targets: List<Pair<String, File>>) {
        entries(archive)
        ZipFile(archive).use { zip ->
            for ((path, target) in targets) {
                val entry = requireNotNull(zip.getEntry(path)) { "Missing ZIP entry: $path" }
                val dir = target.absoluteFile.parentFile
                Files.createDirectories(dir.toPath())
                val temp = File(dir, ".cofre-write-${UUID.randomUUID()}")
                try {
                    zip.getInputStream(entry).use { input -> temp.outputStream().buffered().use { transfer(input, it, entry, MAX_ENTRY_BYTES) } }
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally { temp.delete() }
            }
        }
    }

    fun extract(archive: File, directory: File) {
        val entries = entries(archive)
        ZipFile(archive).use { zip ->
            for (entry in entries) {
                val target = FileOps.child(directory, entry.path)
                if (entry.directory) Files.createDirectories(target.toPath()) else {
                    require(!target.exists()) { "ZIP would overwrite: ${entry.path}" }
                    Files.createDirectories(target.parentFile.toPath())
                    val e = zip.getEntry(entry.path)
                    try { zip.getInputStream(e).use { input -> target.outputStream().use { transfer(input, it, e, MAX_ENTRY_BYTES) } } }
                    catch (e: Exception) { target.delete(); throw e }
                }
            }
        }
    }

    fun write(directory: File, destination: File, include: (String) -> Boolean = { true }) {
        val files = FileOps.tree(directory).filter { include(it.relativeTo(directory).invariantSeparatorsPath) }
        require(destination.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()).not()) { "ZIP destination is inside source" }
        destination.absoluteFile.parentFile.mkdirs()
        val temp = File(destination.absoluteFile.parentFile, ".cofre-zip-${UUID.randomUUID()}")
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                var total = 0L
                require(files.size <= MAX_ENTRIES)
                for (file in files) {
                    val path = file.relativeTo(directory).invariantSeparatorsPath + if (file.isDirectory) "/" else ""
                    require(file.length() <= MAX_ENTRY_BYTES)
                    if (file.isFile) total += file.length()
                    require(total <= MAX_TOTAL_BYTES)
                    out.putNextEntry(ZipEntry(path))
                    if (file.isFile) file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    private fun transfer(input: InputStream, output: OutputStream, entry: ZipEntry, limit: Long) {
        var count = 0L
        val crc = CRC32()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            count += n
            require(count <= limit && count <= entry.size) { "ZIP size limit" }
            crc.update(buffer, 0, n); output.write(buffer, 0, n)
        }
        require(count == entry.size && crc.value == entry.crc) { "Corrupt ZIP entry: ${entry.name}" }
    }
}
