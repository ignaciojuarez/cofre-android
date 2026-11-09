package com.ignacio.cofre.kit.servers

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.Target
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Request/response model only; fetching belongs to callers. */
object ServerDirectory {
    data class Entry(val id: String, val name: String, val address: String, val summary: String, val versions: List<String>, val online: Int?, val max: Int?)
    fun requestURL(): HttpUrl = "https://api.modrinth.com/v3/search".toHttpUrl().newBuilder().addQueryParameter("facets", Kit.json.encodeToString(listOf(listOf("project_types:minecraft_java_server"), listOf("game_versions:${Target.GAME_VERSION}")))).addQueryParameter("index", "follows").addQueryParameter("limit", "100").build()
    fun decode(text: String, saved: Set<String> = emptySet()): List<Entry> {
        val seen = saved.map(MinecraftServerAddress::key).toMutableSet()
        return Kit.json.parseToJsonElement(text).jsonObject.getValue("hits").jsonArray.mapNotNull { hit ->
            val h = hit.jsonObject; val versions = h["game_versions"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            val server = h["minecraft_java_server"] as? JsonObject ?: return@mapNotNull null
            val address = server["address"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val loaders = (h["mrpack_loaders"] ?: (h["project_loader_fields"] as? JsonObject)?.get("mrpack_loaders")) as? JsonArray
            if (Target.GAME_VERSION !in versions || MinecraftServerAddress.parse(address) == null || (!loaders.isNullOrEmpty() && loaders.none { it.jsonPrimitive.content == "neoforge" }) || !seen.add(MinecraftServerAddress.key(address))) return@mapNotNull null
            val players = ((server["ping"] as? JsonObject)?.get("data") as? JsonObject)
            Entry(h.getValue("project_id").jsonPrimitive.content, h.getValue("name").jsonPrimitive.content, address, h["summary"]?.jsonPrimitive?.content ?: "", versions, players?.get("players_online")?.jsonPrimitive?.intOrNull, players?.get("players_max")?.jsonPrimitive?.intOrNull)
        }.take(20)
    }
}
