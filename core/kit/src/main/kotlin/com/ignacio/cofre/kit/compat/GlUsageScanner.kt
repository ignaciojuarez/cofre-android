package com.ignacio.cofre.kit.compat

import com.ignacio.cofre.kit.common.Hashes
import com.ignacio.cofre.kit.modpacks.Zip
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

data class GlUsage(val className: String, val references: Set<String>)
data class GlScan(val usages: List<GlUsage>, val excluded: List<GlUsage>)

/** Static references, not proof of execution. Unknown/malformed classes fail the scan rather than pass silently. */
object GlUsageScanner {
    private const val SODIUM_SHA512 = "15fb941bd58abe3e57b53eb91f070f00e9a5178c718d50cbae588986e4cd8349c5a2ef4f6c84cc4887320d85ec561aa2094214723f502fddd3aeb493c80b5c39"
    private val sodiumGLClasses = setOf("net/caffeinemc/mods/sodium/client/compatibility/environment/GlContextInfo", "net/caffeinemc/mods/sodium/client/compatibility/workarounds/nvidia/NvidiaWorkarounds")
    internal fun excluded(sha512: String, className: String): Boolean = sha512 == SODIUM_SHA512 && className in sodiumGLClasses
    /** Also scans jars nested in `META-INF/jarjar/` (NeoForge) and `META-INF/jars/`; their classes read `inner.jar!/pkg/Cls`. */
    fun scan(jar: File): GlScan {
        val usages = mutableListOf<GlUsage>(); val exclusions = mutableListOf<GlUsage>()
        scan(jar, "", 0, usages, exclusions)
        return GlScan(usages.sortedBy { it.className }, exclusions.sortedBy { it.className })
    }
    private fun scan(jar: File, prefix: String, depth: Int, usages: MutableList<GlUsage>, exclusions: MutableList<GlUsage>) {
        val all = Zip.entries(jar).filter { !it.directory }
        val entries = all.filter { it.path.endsWith(".class") }
        require(entries.sumOf { it.size } <= 256L shl 20) { "Class scan size limit" }
        val digest = Hashes.sha512(jar)
        for (nested in all.filter { it.path.endsWith(".jar") && (it.path.startsWith("META-INF/jarjar/") || it.path.startsWith("META-INF/jars/")) }) {
            require(depth < 3) { "Nested jar depth limit: ${nested.path}" }
            val temp = File.createTempFile("cofre-nested-", ".jar")
            try {
                temp.writeBytes(Zip.read(jar, nested.path, 64L shl 20))
                scan(temp, prefix + nested.path.substringAfterLast('/') + "!/", depth + 1, usages, exclusions)
            } finally { temp.delete() }
        }
        ZipFile(jar).use { zip ->
            for (e in entries) {
                require(e.size <= 8L shl 20) { "Class size limit: ${e.path}" }
                val bytes = zip.getInputStream(zip.getEntry(e.path)).use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n == -1) break; require(out.size() + n <= e.size); out.write(buffer, 0, n) }
                    require(out.size().toLong() == e.size); out.toByteArray()
                }
                val refs = references(bytes)
                if (refs.isNotEmpty()) {
                    val className = e.path.removeSuffix(".class")
                    val usage = GlUsage(prefix + className, refs)
                    if (excluded(digest, className)) exclusions += usage else usages += usage
                }
            }
        }
    }
    fun references(bytes: ByteArray): Set<String> {
        require(bytes.size <= 8 shl 20)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid class magic" }
        input.readUnsignedShort(); input.readUnsignedShort()
        val count = input.readUnsignedShort(); require(count > 0)
        val utf8 = mutableMapOf<Int, String>(); val classNames = mutableListOf<Int>(); val descriptors = mutableListOf<Int>()
        var i = 1
        fun skip(n: Int) { require(n >= 0 && n <= input.available()); input.skipBytes(n) }
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                7 -> classNames += input.readUnsignedShort()
                12 -> { input.readUnsignedShort(); descriptors += input.readUnsignedShort() }
                16 -> descriptors += input.readUnsignedShort()
                3, 4, 9, 10, 11, 17, 18 -> skip(4)
                5, 6 -> { require(i + 1 < count); skip(8); i++ }
                8, 19, 20 -> skip(2)
                15 -> skip(3)
                else -> throw IllegalArgumentException("Unknown class constant: $tag")
            }
            i++
        }
        fun attributes() { repeat(input.readUnsignedShort()) { input.readUnsignedShort(); skip(input.readInt()) } }
        skip(6); repeat(input.readUnsignedShort()) { skip(2) }
        repeat(2) {
            repeat(input.readUnsignedShort()) { skip(4); descriptors += input.readUnsignedShort(); attributes() }
        }
        attributes(); require(input.available() == 0)
        val result = linkedSetOf<String>()
        for (index in classNames) {
            val name = requireNotNull(utf8[index]) { "Invalid class name index" }.trimStart('[').removePrefix("L").removeSuffix(";")
            if (isGL(name)) result += name
        }
        val pattern = Regex("L((?:org/lwjgl/opengl/|com/mojang/blaze3d/opengl/)[^;]+);")
        for (index in descriptors) pattern.findAll(requireNotNull(utf8[index]) { "Invalid descriptor index" }).forEach { result += it.groupValues[1] }
        return result
    }
    private fun isGL(value: String): Boolean = value.startsWith("org/lwjgl/opengl/") || value.startsWith("com/mojang/blaze3d/opengl/")
}
