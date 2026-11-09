package com.ignacio.cofre.kit.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KitTest {
    @Test
    fun userAgentNamesTheProject() {
        assertTrue(Kit.USER_AGENT.contains("cofre-android"))
    }

    @Test
    fun sha1MatchesKnownDigest() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hashes.sha1("abc".toByteArray()))
    }

    @Test
    fun loaderReadsModrinthIds() {
        assertEquals(Loader.NeoForge, Loader.from("NeoForge"))
        assertEquals(Loader.Unknown, Loader.from("liteloader"))
    }

    @Test
    fun loaderRoundTripsThroughJson() {
        val text = Kit.json.encodeToString(Loader.serializer(), Loader.NeoForge)
        assertEquals("\"neoforge\"", text)
        assertEquals(Loader.NeoForge, Kit.json.decodeFromString(Loader.serializer(), text))
    }
}
