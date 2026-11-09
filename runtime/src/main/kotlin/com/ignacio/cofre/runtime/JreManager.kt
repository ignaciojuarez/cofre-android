package com.ignacio.cofre.runtime

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import org.tukaani.xz.XZInputStream

/**
 * Installs the JRE bundled in the APK's assets (runtime/jre-25.tar.xz, stamped by
 * runtime/jre-25.version) into filesDir/runtimes/jre-25.
 *
 * Installs are atomic: the archive is unpacked into a staging folder, stamped last, then renamed
 * into place. A folder without the bundled stamp (half-extracted, or an older JRE) is replaced.
 */
class JreManager(private val context: Context) {
    val home: File get() = File(context.filesDir, "runtimes/$NAME")

    /** The bundled JRE's version stamp, or null when this build carries no JRE. */
    fun bundledVersion(): String? = try {
        context.assets.open("runtime/$NAME.version").bufferedReader().use { it.readText().trim() }
    } catch (_: FileNotFoundException) {
        null
    }

    fun installedVersion(): String? = File(home, STAMP).takeIf { it.isFile }?.readText()?.trim()

    fun isInstalled(): Boolean = bundledVersion()?.let { it == installedVersion() } ?: false

    /** Installs the bundled JRE unless it's already there; returns [home]. Blocking: takes seconds. */
    fun install(): File {
        val version = checkNotNull(bundledVersion()) { "This build has no bundled JRE" }
        if (installedVersion() == version) return home
        val parent = checkNotNull(home.parentFile).apply { mkdirs() }
        parent.listFiles { f -> f.name.startsWith("$NAME.") }?.forEach { it.deleteRecursively() }
        val staging = File(parent, "$NAME.partial")
        context.assets.open("runtime/$NAME.tar.xz").use { untarXz(it, staging) }
        File(staging, STAMP).writeText(version)
        val old = File(parent, "$NAME.old")
        if (home.exists() && !home.renameTo(old)) throw IOException("can't move $home aside")
        if (!staging.renameTo(home)) throw IOException("can't move $staging into place")
        old.deleteRecursively()
        return home
    }

    private companion object {
        const val NAME = "jre-25"
        const val STAMP = ".cofre-version"
    }
}

/** Unpacks a .tar.xz (ustar, GNU long names, pax paths; files, folders, symlinks) into [dest]. */
internal fun untarXz(input: InputStream, dest: File) {
    val tar = DataInputStream(BufferedInputStream(XZInputStream(BufferedInputStream(input, 1 shl 16)), 1 shl 16))
    val root = dest.canonicalFile.apply { mkdirs() }
    val header = ByteArray(512)
    val buffer = ByteArray(1 shl 16)
    var nextName: String? = null
    while (true) {
        tar.readFully(header)
        if (header.all { it == 0.toByte() }) break
        val size = octal(header, 124, 12)
        val type = header[156].toInt().toChar()
        if (type == 'L' || type == 'x' || type == 'g') {
            val data = ByteArray(size.toInt()).also { tar.readFully(it) }
            skipPadding(tar, size)
            if (type == 'L') nextName = String(data).trimEnd('\u0000')
            if (type == 'x') nextName = paxPath(String(data)) ?: nextName
            continue
        }
        val name = (nextName ?: ustarName(header)).removePrefix("./")
        nextName = null
        val target = root.toPath().resolve(name).normalize().toFile()
        if (name.isEmpty() || target == root) {
            skip(tar, size + padding(size))
            continue
        }
        if (!target.toPath().startsWith(root.toPath())) throw IOException("tar entry escapes $dest: $name")
        when (type) {
            '5' -> target.mkdirs()
            '2' -> {
                target.parentFile?.mkdirs()
                target.delete()
                Files.createSymbolicLink(Paths.get(target.path), Paths.get(field(header, 157, 100)))
            }
            '0', '\u0000', '7' -> {
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    var left = size
                    while (left > 0) {
                        val n = tar.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        if (n < 0) throw IOException("truncated tar entry $name")
                        out.write(buffer, 0, n)
                        left -= n
                    }
                }
                if (octal(header, 100, 8) and 0b001_000_000 != 0L) target.setExecutable(true, true)
            }
            else -> skip(tar, size) // hard links, devices: none in a JRE
        }
        if (type != '5' && type != '2') skipPadding(tar, size) else skip(tar, size + padding(size))
    }
}

private fun field(header: ByteArray, offset: Int, length: Int): String {
    var end = offset
    while (end < offset + length && header[end] != 0.toByte()) end++
    return String(header, offset, end - offset, Charsets.UTF_8)
}

private fun ustarName(header: ByteArray): String {
    val name = field(header, 0, 100)
    val prefix = if (field(header, 257, 5) == "ustar") field(header, 345, 155) else ""
    return if (prefix.isEmpty()) name else "$prefix/$name"
}

private fun octal(header: ByteArray, offset: Int, length: Int): Long =
    field(header, offset, length).trim().ifEmpty { "0" }.toLong(8)

private fun paxPath(records: String): String? =
    records.lineSequence().map { it.substringAfter(' ') }.firstOrNull { it.startsWith("path=") }?.removePrefix("path=")

private fun padding(size: Long) = (512 - size % 512) % 512

private fun skipPadding(tar: DataInputStream, size: Long) = skip(tar, padding(size))

private fun skip(tar: DataInputStream, count: Long) {
    var left = count
    while (left > 0) {
        val n = tar.skip(left)
        if (n <= 0) {
            if (tar.read() < 0) throw IOException("truncated tar")
            left--
        } else {
            left -= n
        }
    }
}
