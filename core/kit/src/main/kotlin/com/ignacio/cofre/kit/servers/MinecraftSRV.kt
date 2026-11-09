package com.ignacio.cofre.kit.servers

import org.minidns.hla.ResolverApi
import org.minidns.record.SRV
import kotlin.random.Random

object MinecraftSRV {
    data class Record(val priority: Int, val weight: Int, val port: Int, val host: String)
    /** Blocking MiniDNS query. Explicit ports and IP literals never trigger SRV. */
    fun resolve(address: MinecraftServerAddress): MinecraftServerAddress {
        if (address.port != null || ':' in address.host || address.host.all { it.isDigit() || it == '.' }) return address
        val records = try {
            ResolverApi.INSTANCE.resolve("_minecraft._tcp.${address.host}", SRV::class.java).answersOrEmptySet.filter { it.isServiceAvailable }.map { Record(it.priority, it.weight, it.port, it.target.ace) }
        } catch (_: java.io.IOException) { emptyList() }
        return select(records)?.let { MinecraftServerAddress(it.host, it.port) } ?: address
    }
    fun select(records: List<Record>, random: Random = Random.Default): Record? {
        val valid = records.filter { it.priority in 0..65535 && it.weight in 0..65535 && it.port in 1..65535 && MinecraftServerAddress.parse(it.host) != null }
        val priority = valid.minOfOrNull { it.priority } ?: return null
        val candidates = valid.filter { it.priority == priority }; val total = candidates.sumOf { it.weight }
        if (total == 0) return candidates.random(random)
        var ticket = random.nextInt(total)
        for (candidate in candidates) { ticket -= candidate.weight; if (ticket < 0) return candidate }
        return candidates.last()
    }
    /** Uncompressed SRV RDATA, also useful for deterministic fixtures. */
    fun parse(bytes: ByteArray): Record? = runCatching {
        require(bytes.size >= 7)
        fun word(i: Int): Int = ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255)
        var i = 6; val labels = mutableListOf<String>()
        while (true) {
            require(i < bytes.size); val n = bytes[i++].toInt() and 255
            if (n == 0) break
            require(n in 1..63 && i + n <= bytes.size)
            val label = bytes.copyOfRange(i, i + n).toString(Charsets.US_ASCII); require(label.all { it.isLetterOrDigit() || it == '-' })
            labels += label; i += n
        }
        require(i == bytes.size && labels.isNotEmpty() && word(4) > 0)
        Record(word(0), word(2), word(4), labels.joinToString("."))
    }.getOrNull()
}
