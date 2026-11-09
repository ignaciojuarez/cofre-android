package com.ignacio.cofre.kit.worlds

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** All twelve standard NBT types. Unknown named tags survive edits and round trips. */
sealed interface NBTTag {
    val type: Int
    data class ByteTag(val value: Byte) : NBTTag { override val type: Int = 1 }
    data class ShortTag(val value: Short) : NBTTag { override val type: Int = 2 }
    data class IntTag(val value: Int) : NBTTag { override val type: Int = 3 }
    data class LongTag(val value: Long) : NBTTag { override val type: Int = 4 }
    data class FloatTag(val value: Float) : NBTTag { override val type: Int = 5 }
    data class DoubleTag(val value: Double) : NBTTag { override val type: Int = 6 }
    data class ByteArrayTag(val value: List<Byte>) : NBTTag { override val type: Int = 7 }
    data class StringTag(val value: String) : NBTTag { override val type: Int = 8 }
    data class ListTag(val elementType: Int, val value: List<NBTTag>) : NBTTag { override val type: Int = 9 }
    data class Compound(val value: Map<String, NBTTag>) : NBTTag { override val type: Int = 10; operator fun get(key: String): NBTTag? = value[key] }
    data class IntArrayTag(val value: List<Int>) : NBTTag { override val type: Int = 11 }
    data class LongArrayTag(val value: List<Long>) : NBTTag { override val type: Int = 12 }
    val string: String? get() = (this as? StringTag)?.value
    val integer: Long? get() = when (this) { is ByteTag -> value.toLong(); is ShortTag -> value.toLong(); is IntTag -> value.toLong(); is LongTag -> value; else -> null }
}

data class NBTDocument(val root: NBTTag.Compound, val name: String = "")

object NBT {
    const val MAX_BYTES: Int = 64 shl 20
    private const val MAX_ELEMENTS = 1_000_000
    fun read(bytes: ByteArray): NBTDocument {
        require(bytes.size <= MAX_BYTES)
        val raw = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count == -1) break; require(out.size() + count <= MAX_BYTES); out.write(buffer, 0, count) }
            out.toByteArray()
        } else bytes
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readUnsignedByte() == 10) { "NBT root must be a compound" }
            val name = input.readUTF()
            val root = readTag(input, 10, 0) as NBTTag.Compound
            require(input.available() == 0) { "Trailing NBT bytes" }
            NBTDocument(root, name)
        }
    }
    fun write(document: NBTDocument, gzip: Boolean = false): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out -> out.writeByte(10); out.writeUTF(document.name); writeTag(out, document.root, 0) }
        require(raw.size() <= MAX_BYTES)
        if (!gzip) return raw.toByteArray()
        return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(raw.toByteArray()) } }.toByteArray()
    }
    private fun count(input: DataInputStream, width: Int = 1): Int = input.readInt().also { require(it in 0..MAX_ELEMENTS && it <= input.available() / width) { "Invalid NBT count" } }
    private fun readTag(input: DataInputStream, type: Int, depth: Int): NBTTag {
        require(depth < 64) { "NBT nesting limit" }
        return when (type) {
            1 -> NBTTag.ByteTag(input.readByte())
            2 -> NBTTag.ShortTag(input.readShort())
            3 -> NBTTag.IntTag(input.readInt())
            4 -> NBTTag.LongTag(input.readLong())
            5 -> NBTTag.FloatTag(input.readFloat())
            6 -> NBTTag.DoubleTag(input.readDouble())
            7 -> NBTTag.ByteArrayTag(List(count(input)) { input.readByte() })
            8 -> NBTTag.StringTag(input.readUTF())
            9 -> {
                val element = input.readUnsignedByte(); val size = count(input)
                require(element in 0..12 && (element != 0 || size == 0))
                NBTTag.ListTag(element, List(size) { readTag(input, element, depth + 1) })
            }
            10 -> {
                val tags = linkedMapOf<String, NBTTag>()
                while (true) {
                    val t = input.readUnsignedByte(); if (t == 0) break
                    require(tags.size < MAX_ELEMENTS)
                    val key = input.readUTF(); require(key !in tags) { "Duplicate NBT name" }
                    tags[key] = readTag(input, t, depth + 1)
                }
                NBTTag.Compound(tags)
            }
            11 -> NBTTag.IntArrayTag(List(count(input, 4)) { input.readInt() })
            12 -> NBTTag.LongArrayTag(List(count(input, 8)) { input.readLong() })
            else -> throw IllegalArgumentException("Unknown NBT type: $type")
        }
    }
    private fun writeTag(out: DataOutputStream, tag: NBTTag, depth: Int) {
        require(depth < 64)
        when (tag) {
            is NBTTag.ByteTag -> out.writeByte(tag.value.toInt())
            is NBTTag.ShortTag -> out.writeShort(tag.value.toInt())
            is NBTTag.IntTag -> out.writeInt(tag.value)
            is NBTTag.LongTag -> out.writeLong(tag.value)
            is NBTTag.FloatTag -> out.writeFloat(tag.value)
            is NBTTag.DoubleTag -> out.writeDouble(tag.value)
            is NBTTag.ByteArrayTag -> { require(tag.value.size <= MAX_ELEMENTS); out.writeInt(tag.value.size); tag.value.forEach { out.writeByte(it.toInt()) } }
            is NBTTag.StringTag -> out.writeUTF(tag.value)
            is NBTTag.ListTag -> {
                require(tag.elementType in 0..12 && tag.value.size <= MAX_ELEMENTS && tag.value.all { it.type == tag.elementType })
                out.writeByte(tag.elementType); out.writeInt(tag.value.size); tag.value.forEach { writeTag(out, it, depth + 1) }
            }
            is NBTTag.Compound -> {
                require(tag.value.size <= MAX_ELEMENTS)
                tag.value.forEach { (name, value) -> out.writeByte(value.type); out.writeUTF(name); writeTag(out, value, depth + 1) }; out.writeByte(0)
            }
            is NBTTag.IntArrayTag -> { require(tag.value.size <= MAX_ELEMENTS); out.writeInt(tag.value.size); tag.value.forEach(out::writeInt) }
            is NBTTag.LongArrayTag -> { require(tag.value.size <= MAX_ELEMENTS); out.writeInt(tag.value.size); tag.value.forEach(out::writeLong) }
        }
    }
}
