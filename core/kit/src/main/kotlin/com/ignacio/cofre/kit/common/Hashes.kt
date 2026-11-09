package com.ignacio.cofre.kit.common

import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File
import java.security.MessageDigest

/** Lowercase hex digests, the form Mojang, Modrinth and NeoForge publish. */
object Hashes {
    fun sha1(file: File): String = digest(file) { HashingSink.sha1(it) }
    fun sha256(file: File): String = digest(file) { HashingSink.sha256(it) }
    fun sha512(file: File): String = digest(file) { HashingSink.sha512(it) }

    fun sha1(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-1").digest(bytes))
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun sha512(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-512").digest(bytes))

    private fun digest(file: File, sink: (okio.Sink) -> HashingSink): String {
        val hashing = sink(blackholeSink())
        file.source().use { source -> hashing.buffer().use { it.writeAll(source) } }
        return hashing.hash.hex()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
