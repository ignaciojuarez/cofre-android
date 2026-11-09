package com.ignacio.cofre.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class UntarTest {
    private class Tar {
        val bytes = ByteArrayOutputStream()

        fun entry(name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "", mode: Int = 420) {
            val header = ByteArray(512)
            fun put(offset: Int, value: String) = value.toByteArray().copyInto(header, offset)
            put(0, name)
            put(100, "%07o".format(mode))
            put(124, "%011o".format(data.size))
            header[156] = type.code.toByte()
            put(157, link)
            put(257, "ustar")
            bytes.write(header)
            bytes.write(data)
            bytes.write(ByteArray(((512 - data.size % 512) % 512)))
        }

        fun xz(): ByteArray {
            bytes.write(ByteArray(1024))
            return ByteArrayOutputStream().also { out -> XZOutputStream(out, LZMA2Options()).use { it.write(bytes.toByteArray()) } }.toByteArray()
        }
    }

    @Test
    fun unpacksFilesFoldersLongNamesAndSymlinks() {
        val longName = "lib/" + "x".repeat(120) + ".dat"
        val tar = Tar().apply {
            entry("./", '5')
            entry("./lib/", '5')
            entry("./lib/server/libjvm.so", '0', "elf".toByteArray(), mode = 493)
            entry("././@LongLink", 'L', longName.toByteArray())
            entry("ignored", '0', ByteArray(700) { 7 })
            entry("./legal/LICENSE", '2', link = "../lib/server/libjvm.so")
        }
        val dest = Files.createTempDirectory("untar").toFile()
        untarXz(ByteArrayInputStream(tar.xz()), dest)

        assertEquals("elf", dest.resolve("lib/server/libjvm.so").readText())
        assertTrue(dest.resolve("lib/server/libjvm.so").canExecute())
        assertEquals(700, dest.resolve(longName).length())
        assertEquals("../lib/server/libjvm.so", Files.readSymbolicLink(dest.resolve("legal/LICENSE").toPath()).toString())
    }

    @Test
    fun refusesEntriesOutsideTheDestination() {
        val tar = Tar().apply { entry("../evil", '0', "x".toByteArray()) }
        assertFailsWith<IOException> { untarXz(ByteArrayInputStream(tar.xz()), Files.createTempDirectory("untar").toFile()) }
    }
}
