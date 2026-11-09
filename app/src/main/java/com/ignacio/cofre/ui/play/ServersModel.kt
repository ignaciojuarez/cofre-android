package com.ignacio.cofre.ui.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.servers.MinecraftServerAddress
import com.ignacio.cofre.kit.servers.MinecraftServerStatus
import com.ignacio.cofre.kit.servers.ServerRecord
import com.ignacio.cofre.kit.servers.ServersStore
import com.ignacio.cofre.onIo
import com.ignacio.cofre.work
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The instance's saved servers (its servers.dat), pinged in parallel. */
class ServersModel(private val graph: AppGraph, val instance: Instance) {
    private val store = ServersStore(graph.instances.directory(instance)) { graph.launcher.gameRunning }

    data class Server(val id: String, val record: ServerRecord, val address: MinecraftServerAddress?)

    sealed interface Ping {
        data object Pinging : Ping
        data class Online(val status: MinecraftServerStatus) : Ping
        data object Offline : Ping
    }

    var servers by mutableStateOf<List<Server>>(emptyList())
        private set
    val pings = mutableStateMapOf<String, Ping>()
    var error by mutableStateOf<String?>(null)

    fun refresh() = graph.work({ error = it }) {
        val list = graph.onIo { store.list() }.withIndex()
            .filter { !it.value.hidden }
            .map { (i, r) -> Server(ServersStore.id(i, r), r, MinecraftServerAddress.parse(r.ip)) }
        servers = list
        coroutineScope {
            list.map { server ->
                async {
                    pings[server.id] = Ping.Pinging
                    val address = server.address
                    pings[server.id] = if (address == null) Ping.Offline else {
                        runCatching { graph.onIo { graph.ping(address) } }.map { Ping.Online(it) }.getOrDefault(Ping.Offline)
                    }
                }
            }.awaitAll()
        }
    }

    /** Returns false (and sets [error]) for an address Minecraft couldn't join. */
    fun add(name: String, address: String): Boolean {
        val parsed = MinecraftServerAddress.parse(address)
        if (parsed == null) {
            error = graph.context.getString(R.string.servers_bad_address)
            return false
        }
        graph.work({ error = it }) {
            graph.onIo { store.add(ServerRecord(name.trim().ifEmpty { parsed.display }, parsed.display)) }
            refresh()
        }
        return true
    }

    fun remove(server: Server) = graph.work({ error = it }) {
        graph.onIo { store.remove(server.id) }
        refresh()
    }
}
