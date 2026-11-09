package com.ignacio.cofre.kit.servers

import com.ignacio.cofre.kit.worlds.NBTTag
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.*

class ServersTest {
    @TempDir lateinit var root: File
    @Test fun serversRoundTripKeepsUnknownRootAndRecordTags() {
        val record = ServerRecord("Name", "play.x", byteArrayOf(1, 2, 3), 1, true, mapOf("future" to NBTTag.IntArrayTag(listOf(1, -2))))
        val list = ServerList(listOf(record), mapOf("custom" to NBTTag.StringTag("yes")), "root")
        val decoded = ServersDatCodec.decode(ServersDatCodec.encode(list))
        assertEquals(list.unknown, decoded.unknown); assertEquals("root", decoded.rootName); assertEquals(record.unknown, decoded.records.single().unknown)
        assertContentEquals(record.iconPNG, decoded.records.single().iconPNG); assertEquals(record.acceptTextures, decoded.records.single().acceptTextures); assertTrue(decoded.records.single().hidden)
    }
    @Test fun storeAddsRemovesCopiesAndKeepsUnknownTags() {
        val s = ServersStore(root); val unknown = mapOf("custom" to NBTTag.LongTag(42)); s.file.writeBytes(ServersDatCodec.encode(ServerList(emptyList(), unknown)))
        assertEquals(0, s.add(ServerRecord("One", "one.test"))); val r = s.list().single(); val id = ServersStore.id(0, r)
        val destination = ServersStore(File(root, "other")); s.copy(id, destination); assertEquals("one.test", destination.list().single().ip)
        s.remove(id); assertTrue(s.list().isEmpty()); assertEquals(unknown, ServersDatCodec.decode(s.file.readBytes()).unknown)
    }
    @Test fun corruptionAndStaleIDsCannotBeOverwritten() {
        val s = ServersStore(root); s.file.writeText("broken"); assertFails { s.add(ServerRecord("a", "a.test")) }; assertEquals("broken", s.file.readText())
        s.file.delete(); s.add(ServerRecord("a", "a.test")); val id = ServersStore.id(0, s.list().single()); s.remove(id); assertFails { s.remove(id) }
    }
    @Test fun storeRefusesWritesWhileGameRuns() { assertFails { ServersStore(root) { true }.add(ServerRecord("a", "a.test")) } }
    @Test fun defaultPortsCaseIDNAndIPv6Normalize() {
        assertEquals(MinecraftServerAddress.key("Play.X"), MinecraftServerAddress.key("play.x:25565")); assertEquals(MinecraftServerAddress.key("bücher.example."), MinecraftServerAddress.key("xn--bcher-kva.example:25565"))
        assertEquals(MinecraftServerAddress.key("[::1]"), MinecraftServerAddress.key("[0:0:0:0:0:0:0:1]:25565"))
        val s = ServersStore(root); s.add(ServerRecord("a", "Play.X")); assertEquals(0, s.add(ServerRecord("b", "play.x:25565"))); assertEquals(1, s.list().size)
    }
    @Test fun invalidAddressesAreRejected() { for (v in listOf("", " ", "host:0", "host:65536", "host:no", "https://host", "user@host", "[no]", "[::1]junk", "host/escape", "a..b")) assertNull(MinecraftServerAddress.parse(v), v) }
    @Test fun quickPlayPreservesSRVNameIPv6AndWorldSpaces() {
        assertEquals(listOf("--quickPlayMultiplayer", "play.x"), MinecraftLaunchArguments.server(MinecraftServerAddress("play.x")))
        assertEquals(listOf("--quickPlayMultiplayer", "[::1]:25566"), MinecraftLaunchArguments.server(MinecraftServerAddress("::1", 25566)))
        assertEquals(listOf("--quickPlaySingleplayer", "My Base"), MinecraftLaunchArguments.singleplayer("My Base")); assertFails { MinecraftLaunchArguments.singleplayer("../base") }
    }
    @Test fun motdFlattensStringsArraysAndNestedExtras() {
        val value = com.ignacio.cofre.kit.common.Kit.json.parseToJsonElement("""[{"text":"hello","extra":[{"text":" world"}]},"!"]""")
        assertEquals("hello world!", MinecraftMOTD.flatten(value)); assertEquals("hello", MinecraftMOTD.flatten(JsonPrimitive("hello")))
    }
    @Test fun srvParsesWireRecordsAndRejectsInvalidLabels() {
        val bytes = byteArrayOf(0, 1, 0, 2, 0x63, 0xdd.toByte(), 4) + "play".toByteArray() + byteArrayOf(4) + "test".toByteArray() + byteArrayOf(0)
        val record = MinecraftSRV.parse(bytes); assertEquals(MinecraftSRV.Record(1, 2, 25565, "play.test"), record)
        assertNull(MinecraftSRV.parse(bytes.copyOf(bytes.size - 1))); assertNull(MinecraftSRV.parse(byteArrayOf(0, 0, 0, 0, 0, 0, 0))); assertNull(MinecraftSRV.parse(bytes + byteArrayOf(1)))
    }
    @Test fun srvSelectsLowestPriorityAndWeightedTarget() {
        val a = MinecraftSRV.Record(0, 0, 25565, "zero.test"); val b = MinecraftSRV.Record(0, 10, 25566, "weighted.test"); val c = MinecraftSRV.Record(1, 100, 25565, "later.test")
        repeat(10) { assertEquals(b, MinecraftSRV.select(listOf(a, b, c), Random(it))) }
        assertNull(MinecraftSRV.select(emptyList())); val explicit = MinecraftServerAddress("example.test", 25565); assertEquals(explicit, MinecraftSRV.resolve(explicit))
    }
    @Test fun statusPacketChecksJSONStringLength() {
        val json = "{\"description\":\"ok\"}".toByteArray(); val good = byteArrayOf(0) + MinecraftServerPing.varint(json.size) + json
        assertEquals("ok", MinecraftServerPing.statusObject(good)["description"]?.toString()?.trim('"'))
        assertFails { MinecraftServerPing.statusObject(byteArrayOf(0) + json) }
    }
    @Test fun varintsRejectOversizeAndTruncation() {
        for (n in listOf(0, 127, 128, 776, Int.MAX_VALUE)) assertEquals(n, MinecraftServerPing.readVarint(ByteArrayInputStream(MinecraftServerPing.varint(n))))
        assertFails { MinecraftServerPing.readVarint(ByteArrayInputStream(byteArrayOf(0x80.toByte()))) }
        assertFails { MinecraftServerPing.readVarint(ByteArrayInputStream(ByteArray(5) { 0xff.toByte() })) }
    }
    @Test fun realSocketHandshakeStatusAndPingPong() {
        ServerSocket(0).use { server ->
            val failure = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                try { server.accept().use { socket ->
                    socket.soTimeout = 2000; val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                    fun packet(): ByteArray = ByteArray(MinecraftServerPing.readVarint(input)).also { input.readFully(it) }
                    val h = DataInputStream(ByteArrayInputStream(packet())); assertEquals(0, MinecraftServerPing.readVarint(h)); assertEquals(776, MinecraftServerPing.readVarint(h))
                    val host = ByteArray(MinecraftServerPing.readVarint(h)); h.readFully(host); assertEquals("127.0.0.1", host.decodeToString()); assertEquals(server.localPort, h.readUnsignedShort()); assertEquals(1, MinecraftServerPing.readVarint(h))
                    assertContentEquals(byteArrayOf(0), packet())
                    val json = """{"description":{"text":"Hello","extra":[{"text":" player"}]},"version":{"name":"26.2","protocol":776},"players":{"online":3,"max":20},"favicon":"data:image/png;base64,AQI="}""".toByteArray()
                    val body = byteArrayOf(0) + MinecraftServerPing.varint(json.size) + json; output.write(MinecraftServerPing.varint(body.size) + body); output.flush()
                    val ping = packet(); assertEquals(1, ping[0].toInt()); output.write(MinecraftServerPing.varint(ping.size) + ping); output.flush()
                } } catch (t: Throwable) { failure.set(t) }
            }
            val status = MinecraftServerPing.ping(MinecraftServerAddress("127.0.0.1", server.localPort), 2000)
            worker.join(3000); failure.get()?.let { throw it }; assertEquals("Hello player", status.motd); assertEquals(3, status.online); assertEquals(776, status.protocolVersion); assertContentEquals(byteArrayOf(1, 2), status.faviconPNG)
        }
    }
    @Test fun socketTimeoutDoesNotWaitForeverForPartialPacket() {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) { runCatching { server.accept().use { socket -> socket.getOutputStream().write(byteArrayOf(0x80.toByte())); Thread.sleep(500) } } }
            assertFailsWith<SocketTimeoutException> { MinecraftServerPing.ping(MinecraftServerAddress("127.0.0.1", server.localPort), 100) }; worker.join(1000)
        }
    }
    @Test fun recommendedServerRequestAndResponseFilterTargetAndSaved() {
        val url = ServerDirectory.requestURL(); assertEquals("/v3/search", url.encodedPath); assertTrue(url.queryParameter("facets")!!.contains("26.2"))
        fun hit(id: String, address: String, loader: String = "neoforge", version: String = "26.2"): String = """{"project_id":"$id","name":"$id","summary":"test","game_versions":["$version"],"mrpack_loaders":["$loader"],"minecraft_java_server":{"address":"$address","ping":{"data":{"players_online":2,"players_max":10}}}}"""
        val text = "{\"hits\":[${listOf(hit("a", "Play.X"), hit("b", "play.x:25565"), hit("c", "fabric.test", "fabric"), hit("d", "old.test", version = "1.21.1")).joinToString(",")}]}"
        assertEquals("a", ServerDirectory.decode(text).single().id); assertTrue(ServerDirectory.decode(text, setOf("play.x:25565")).isEmpty())
    }
    @Test fun protocolMapHasCurrentAndLegacyVersionsAndParsesNames() {
        assertEquals(776, ProtocolVersionMap.protocol("26.2")); assertTrue("26.2" in ProtocolVersionMap.versions(776)); assertTrue(ProtocolVersionMap.versions(-999).isEmpty())
        assertEquals(mapOf(1 to listOf("a", "b")), ProtocolVersionMap.parse("""[{"version":1,"name":"a"},{"version":1,"minecraftVersion":"b"}]"""))
    }
}
