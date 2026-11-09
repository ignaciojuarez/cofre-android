package com.ignacio.cofre.kit.servers

import com.ignacio.cofre.kit.modpacks.FileOps
import com.ignacio.cofre.kit.worlds.NBT
import com.ignacio.cofre.kit.worlds.NBTDocument
import com.ignacio.cofre.kit.worlds.NBTTag
import java.io.File
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64

data class MinecraftServerAddress(val host: String, val port: Int? = null) {
    init { require(host.isNotBlank() && (port == null || port in 1..65535)) }
    val effectivePort: Int get() = port ?: 25565
    val key: String get() = "${canonicalHost(host)}:$effectivePort"
    val display: String get() = (if (':' in host) "[$host]" else host) + (port?.let { ":$it" } ?: "")
    companion object {
        fun parse(value: String): MinecraftServerAddress? = runCatching {
            val s = value.trim(); require(s.isNotEmpty() && s.toByteArray().size <= 255 && s.none { it.isWhitespace() || it in "/\\@?#\u0000" })
            val host: String; val port: Int?
            if (s.startsWith('[')) {
                val close = s.indexOf(']'); require(close > 1)
                host = s.substring(1, close); val tail = s.substring(close + 1)
                require(tail.isEmpty() || tail.startsWith(':'))
                port = if (tail.isEmpty()) null else tail.drop(1).toInt()
                require(InetAddress.getByName(host) is Inet6Address)
            } else if (s.count { it == ':' } == 1) { host = s.substringBefore(':'); port = s.substringAfter(':').toInt() }
            else { host = s; port = null }
            require('[' !in host && ']' !in host)
            if (':' in host) require(InetAddress.getByName(host) is Inet6Address) else {
                val dns = IDN.toASCII(host.trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
                require(dns.isNotBlank() && dns.length <= 253 && dns.split('.').all { it.isNotEmpty() && it.length <= 63 })
            }
            MinecraftServerAddress(host, port)
        }.getOrNull()
        fun key(value: String): String = parse(value)?.key ?: value.lowercase()
        private fun canonicalHost(host: String): String = if (':' in host) InetAddress.getByName(host).hostAddress!!.lowercase() else IDN.toASCII(host.trimEnd('.')).lowercase()
    }
}

object MinecraftLaunchArguments {
    fun server(address: MinecraftServerAddress): List<String> = listOf("--quickPlayMultiplayer", address.display)
    fun singleplayer(folder: String): List<String> { require(folder.isNotBlank() && folder !in setOf(".", "..") && folder.none { it in "/\\\u0000" }); return listOf("--quickPlaySingleplayer", folder) }
}

data class ServerRecord(val name: String, val ip: String, val iconPNG: ByteArray? = null, val acceptTextures: Byte? = null, val hidden: Boolean = false, val unknown: Map<String, NBTTag> = emptyMap())
data class ServerList(val records: List<ServerRecord>, val unknown: Map<String, NBTTag> = emptyMap(), val rootName: String = "")

object ServersDatCodec {
    private val keys = setOf("name", "ip", "icon", "acceptTextures", "hidden")
    fun decode(bytes: ByteArray): ServerList {
        val doc = NBT.read(bytes)
        val list = doc.root["servers"] as? NBTTag.ListTag ?: error("Missing servers list")
        require(list.elementType == 10 || (list.elementType == 0 && list.value.isEmpty()))
        val records = list.value.map { v ->
            val c = v as? NBTTag.Compound ?: error("Invalid server record")
            val name = requireNotNull(c["name"]?.string); val ip = requireNotNull(c["ip"]?.string)
            val icon = c["icon"]?.let { require(it is NBTTag.StringTag); Base64.getDecoder().decode(it.value.substringAfter(',')) }
            val accept = c["acceptTextures"]?.let { require(it is NBTTag.ByteTag); it.value }
            val hidden = c["hidden"]?.let { require(it is NBTTag.ByteTag); it.value != 0.toByte() } ?: false
            ServerRecord(name, ip, icon, accept, hidden, c.value.filterKeys { it !in keys })
        }
        return ServerList(records, doc.root.value.filterKeys { it != "servers" }, doc.name)
    }
    fun encode(list: ServerList): ByteArray {
        val values = list.records.map { s ->
            require(s.unknown.keys.none { it in keys })
            val tags = linkedMapOf<String, NBTTag>("name" to NBTTag.StringTag(s.name), "ip" to NBTTag.StringTag(s.ip), "hidden" to NBTTag.ByteTag(if (s.hidden) 1 else 0))
            s.iconPNG?.let { tags["icon"] = NBTTag.StringTag("data:image/png;base64," + Base64.getEncoder().encodeToString(it)) }
            s.acceptTextures?.let { tags["acceptTextures"] = NBTTag.ByteTag(it) }
            NBTTag.Compound(tags + s.unknown)
        }
        require("servers" !in list.unknown)
        return NBT.write(NBTDocument(NBTTag.Compound(list.unknown + ("servers" to NBTTag.ListTag(10, values))), list.rootName))
    }
}

class ServersStore(gameDirectory: File, private val isGameRunning: () -> Boolean = { false }) {
    val file: File = FileOps.child(gameDirectory, "servers.dat")
    fun list(): List<ServerRecord> = document().records
    fun record(id: String): ServerRecord = list().withIndex().firstOrNull { id(it.index, it.value) == id }?.value ?: error("Server list changed; refresh first")
    fun add(server: ServerRecord): Int {
        writable(); requireNotNull(MinecraftServerAddress.parse(server.ip)) { "Invalid server address" }
        val doc = document(); val found = doc.records.indexOfFirst { MinecraftServerAddress.key(it.ip) == MinecraftServerAddress.key(server.ip) }
        if (found >= 0) return found
        save(doc.copy(records = doc.records + server)); return doc.records.size
    }
    fun remove(id: String) {
        writable(); val doc = document(); val index = doc.records.indices.firstOrNull { id(it, doc.records[it]) == id } ?: error("Server list changed; refresh first")
        save(doc.copy(records = doc.records.filterIndexed { i, _ -> i != index }))
    }
    fun copy(id: String, destination: ServersStore): Int = destination.add(record(id))
    private fun document(): ServerList = if (file.exists()) { require(file.length() <= NBT.MAX_BYTES); ServersDatCodec.decode(file.readBytes()) } else ServerList(emptyList())
    private fun writable() { check(!isGameRunning()) { "Close the game first" } }
    private fun save(list: ServerList) { writable(); FileOps.atomic(file, ServersDatCodec.encode(list)) }
    companion object { fun id(index: Int, record: ServerRecord): String = "$index:${record.ip}" }
}
