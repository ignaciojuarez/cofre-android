package com.ignacio.cofre.kit.servers

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Target
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import kotlin.math.max

object MinecraftMOTD {
    fun flatten(value: JsonElement): String = when (value) { is JsonPrimitive -> value.contentOrNull ?: ""; is JsonArray -> value.joinToString("") { flatten(it) }; is JsonObject -> (value["text"]?.let(::flatten) ?: "") + (value["extra"]?.let(::flatten) ?: "") }
}
data class MinecraftServerStatus(val motd: String, val versionName: String?, val protocolVersion: Int?, val online: Int?, val max: Int?, val faviconPNG: ByteArray?, val latencyMillis: Long)

object MinecraftServerPing {
    /** Blocking status handshake and nonce-checked ping/pong. Call from an IO dispatcher. */
    fun ping(address: MinecraftServerAddress, timeoutMillis: Int = 5000): MinecraftServerStatus {
        require(timeoutMillis in 1..60_000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val resolved = MinecraftSRV.resolve(address)
        fun remaining(): Int {
            val nanos = deadline - System.nanoTime(); if (nanos <= 0) throw SocketTimeoutException("Server status timed out")
            return max(1, (nanos / 1_000_000).toInt())
        }
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(resolved.host, resolved.effectivePort), remaining())
            val output = DataOutputStream(socket.getOutputStream())
            val input = object : InputStream() {
                override fun read(): Int { socket.soTimeout = remaining(); return socket.getInputStream().read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int { socket.soTimeout = remaining(); return socket.getInputStream().read(b, off, len) }
            }
            fun send(payload: ByteArray) { output.write(varint(payload.size)); output.write(payload); output.flush() }
            val host = address.host.toByteArray(Charsets.UTF_8)
            val handshake = ByteArrayOutputStream(); DataOutputStream(handshake).use { h -> h.write(varint(0)); h.write(varint(Target.PROTOCOL_VERSION)); h.write(varint(host.size)); h.write(host); h.writeShort(address.effectivePort); h.write(varint(1)) }
            send(handshake.toByteArray()); send(byteArrayOf(0))
            val status = statusObject(packet(input))
            val nonce = System.nanoTime(); val started = System.nanoTime()
            val payload = ByteArrayOutputStream(); DataOutputStream(payload).use { p -> p.writeByte(1); p.writeLong(nonce) }; send(payload.toByteArray())
            val pong = DataInputStream(ByteArrayInputStream(packet(input)))
            require(readVarint(pong) == 1 && pong.available() == 8 && pong.readLong() == nonce) { "Malformed status pong" }
            val version = status["version"]?.jsonObject; val players = status["players"]?.jsonObject
            val icon = status["favicon"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("data:image/png;base64,") }?.let { runCatching { Base64.getDecoder().decode(it.substringAfter(',')) }.getOrNull() }
            MinecraftServerStatus(status["description"]?.let(MinecraftMOTD::flatten) ?: "", version?.get("name")?.jsonPrimitive?.contentOrNull, version?.get("protocol")?.jsonPrimitive?.intOrNull, players?.get("online")?.jsonPrimitive?.intOrNull, players?.get("max")?.jsonPrimitive?.intOrNull, icon, (System.nanoTime() - started) / 1_000_000)
        }
    }
    fun statusObject(packet: ByteArray): JsonObject {
        val input = ByteArrayInputStream(packet)
        require(readVarint(input) == 0)
        val length = readVarint(input); require(length >= 0 && length == input.available()) { "Invalid JSON string length" }
        return Kit.json.parseToJsonElement(input.readBytes().toString(Charsets.UTF_8)).jsonObject
    }
    internal fun varint(value: Int): ByteArray {
        var n = value; val out = ByteArrayOutputStream()
        do { var b = n and 127; n = n ushr 7; if (n != 0) b = b or 128; out.write(b) } while (n != 0)
        return out.toByteArray()
    }
    internal fun readVarint(input: InputStream): Int {
        var result = 0
        for (shift in 0..28 step 7) {
            val b = input.read(); require(b >= 0 && (shift < 28 || b and 0xf0 == 0)) { "Malformed VarInt" }
            result = result or ((b and 127) shl shift); if (b and 128 == 0) return result
        }
        error("Malformed VarInt")
    }
    private fun packet(input: InputStream): ByteArray {
        val length = readVarint(input); require(length in 1..4_000_000) { "Packet size limit" }
        return ByteArray(length).also { DataInputStream(input).readFully(it) }
    }
}
