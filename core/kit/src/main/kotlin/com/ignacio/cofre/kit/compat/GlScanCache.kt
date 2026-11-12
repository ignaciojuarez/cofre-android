package com.ignacio.cofre.kit.compat

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.common.Kit
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The OpenGL risk per jar, cached on disk by SHA-1 so a jar is scanned once, whether the Installed tab or Play asks
 * first. Findings of another [GlUsageScanner.VERSION] are dropped. The scan reads every class and is slow on big jars:
 * call [scan] off the main thread.
 */
class GlScanCache(private val file: File) {
    @Serializable private data class Saved(val scanner: Int, val risks: Map<String, GlRisk>)
    private val scanning = Any()
    private var entries: MutableMap<String, GlRisk>? = null

    /** Hashing a jar is cheap next to scanning it, but still remembered per path, size and time. */
    private val hashes = mutableMapOf<String, String>()

    /** The cached finding for this jar, or null when it hasn't been scanned or isn't a file (a folder in `mods/`). */
    fun cached(jar: File): GlRisk? {
        if (!jar.isFile) return null
        val hash = hashOf(jar)
        return synchronized(this) { load()[hash] }
    }

    /** One scan at a time, so the same jar is never scanned twice at once. */
    fun scan(jar: File): GlRisk = synchronized(scanning) {
        cached(jar)?.let { return it }
        val found = GlUsageScanner.scan(jar).risk
        val hash = hashOf(jar)
        synchronized(this) {
            load()[hash] = found
            file.parentFile?.mkdirs()
            file.writeText(Kit.json.encodeToString(Saved(GlUsageScanner.VERSION, load().toMap())))
        }
        found
    }

    private fun load(): MutableMap<String, GlRisk> = entries ?: runCatching { Kit.json.decodeFromString<Saved>(file.readText()) }.getOrNull()
        ?.takeIf { it.scanner == GlUsageScanner.VERSION }?.risks.orEmpty().toMutableMap().also { entries = it }

    private fun hashOf(jar: File): String {
        val key = "${jar.path}|${jar.length()}|${jar.lastModified()}"
        return synchronized(hashes) { hashes.getOrPut(key) { Hashes.sha1(jar) } }
    }
}
