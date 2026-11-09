package com.ignacio.cofre.kit.compat

import com.ignacio.cofre.kit.common.Zip
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

data class GlUsage(val className: String, val references: Set<String>)

/** How likely a jar's OpenGL references are to run under Vulkan. */
enum class GlRisk { None, May, Likely }

/** [backendAware]: the jar also uses Mojang's backend-neutral GPU API, so its GL code is probably one backend of several. */
data class GlScan(val usages: List<GlUsage>, val backendAware: Boolean = false) {
    val risk: GlRisk get() = when { usages.isEmpty() -> GlRisk.None; backendAware -> GlRisk.May; else -> GlRisk.Likely }
}

/**
 * Static references, not proof of execution. Unknown/malformed classes fail the scan rather than pass silently.
 * Known Vulkan-ready mods are vouched for by id and version in known-mods.json, not here.
 */
object GlUsageScanner {
    /** 26.2's backend-neutral GPU API (`RenderSystem.getDevice()` returns a GpuDevice) and the Vulkan backend itself. */
    private val neutral = listOf("com/mojang/blaze3d/systems/GpuDevice", "com/mojang/blaze3d/systems/GpuBackend", "com/mojang/blaze3d/systems/CommandEncoder", "com/mojang/blaze3d/systems/RenderPass", "com/mojang/blaze3d/vulkan/", "org/lwjgl/vulkan/")
    /** Also scans jars nested in `META-INF/jarjar/` (NeoForge) and `META-INF/jars/`; their classes read `inner.jar!/pkg/Cls`. */
    fun scan(jar: File): GlScan {
        val usages = mutableListOf<GlUsage>(); var aware = false
        scan(jar, "", 0) { name, refs ->
            refs.filter(::isGL).toSet().takeIf { it.isNotEmpty() }?.let { usages += GlUsage(name, it) }
            if (!aware) aware = refs.any { r -> neutral.any(r::startsWith) }
        }
        return GlScan(usages.sortedBy { it.className }, aware)
    }
    private fun scan(jar: File, prefix: String, depth: Int, found: (String, Set<String>) -> Unit) {
        val all = Zip.entries(jar).filter { !it.directory }
        val entries = all.filter { it.path.endsWith(".class") }
        require(entries.sumOf { it.size } <= 256L shl 20) { "Class scan size limit" }
        for (nested in all.filter { it.path.endsWith(".jar") && (it.path.startsWith("META-INF/jarjar/") || it.path.startsWith("META-INF/jars/")) }) {
            require(depth < 3) { "Nested jar depth limit: ${nested.path}" }
            val temp = File.createTempFile("cofre-nested-", ".jar")
            try {
                temp.writeBytes(Zip.read(jar, nested.path, 64L shl 20))
                scan(temp, prefix + nested.path.substringAfterLast('/') + "!/", depth + 1, found)
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
                found(prefix + e.path.removeSuffix(".class"), classReferences(bytes))
            }
        }
    }
    /** The OpenGL classes one class file references. */
    fun references(bytes: ByteArray): Set<String> = classReferences(bytes).filterTo(linkedSetOf(), ::isGL)
    /** Every class a class file references: class constants plus types in field, method and call descriptors. */
    private fun classReferences(bytes: ByteArray): Set<String> {
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
        for (index in classNames) result += requireNotNull(utf8[index]) { "Invalid class name index" }.trimStart('[').removePrefix("L").removeSuffix(";")
        for (index in descriptors) descriptor.findAll(requireNotNull(utf8[index]) { "Invalid descriptor index" }).forEach { result += it.groupValues[1] }
        return result
    }
    private val descriptor = Regex("L([^;]+);")
    private fun isGL(value: String): Boolean = value.startsWith("org/lwjgl/opengl/") || value.startsWith("com/mojang/blaze3d/opengl/")
}
