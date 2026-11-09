package com.ignacio.cofre.kit.modrinth

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Kit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.*

class ModrinthTest {
    private fun fixture(name: String): String = javaClass.getResource("/modrinth/modrinth-$name.json")!!.readText()
    @Test fun filtersUseJsonArrays() {
        val url = Modrinth().versionsUrl("a/b", listOf("neoforge"), listOf("26.2"), false)
        assertEquals(listOf("neoforge"), Kit.json.decodeFromString<List<String>>(url.queryParameter("loaders")!!))
        assertEquals(listOf("26.2"), Kit.json.decodeFromString<List<String>>(url.queryParameter("game_versions")!!))
        assertEquals("false", url.queryParameter("include_changelog"))
        assertTrue(url.encodedPath.contains("a%2Fb"))
    }
    @Test fun filesAndDependenciesDecode() {
        val v = Kit.json.decodeFromString<Modrinth.Version>(fixture("version"))
        assertTrue(v.files.isNotEmpty()); assertTrue(v.files.first().hashes.containsKey("sha1"))
        assertEquals(emptyList(), v.dependencies)
        assertNotNull(v.changelog)
    }
    @Test fun matchingVersionsStayFirst() {
        val all = Kit.json.decodeFromString<List<Modrinth.Version>>(fixture("versions"))
        assertEquals((listOf(all.last()) + all.dropLast(1)).map { it.id }, Modrinth.mergeVersions(listOf(all.last()), all).map { it.id })
    }
    @Test fun searchAllowsEmptyAndMissingIcons() {
        val hits = Kit.json.parseToJsonElement(fixture("search"))
        assertNotNull(hits)
        val hit = Kit.json.decodeFromString<Modrinth.SearchHit>("""{"project_id":"a","title":"A","icon_url":"","date_modified":"2026-10-27T17:15:43.902761+00:00"}""")
        assertNull(hit.iconUrl); assertNotNull(hit.dateModified)
        assertNull(hit.pageUrl)
    }
    @Test fun cacheAndChangelog() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = fixture("version")))
            val client = Modrinth(baseUrl = server.url("/v2/"))
            val first = client.version("abc")
            assertEquals(first, client.version("abc"))
            assertEquals(1, server.requestCount)
            assertEquals("/v2/version/abc", server.takeRequest().url.encodedPath)
        }
    }
    @Test fun rateLimitsAreRetriedAndRecorded() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 429, headers = okhttp3.Headers.headersOf("X-Ratelimit-Reset", "0", "Retry-After", "0")))
            server.enqueue(MockResponse(body = "[]", headers = okhttp3.Headers.headersOf("X-Ratelimit-Limit", "300", "X-Ratelimit-Remaining", "299")))
            val client = Modrinth(baseUrl = server.url("/"))
            assertEquals(emptyList(), client.versions("a")); assertEquals(299, client.rateLimit?.remaining)
        }
    }
    @Test fun bulkHashesUseV2Contract() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = "{}"))
            val client = Modrinth(baseUrl = server.url("/v2/"))
            assertEquals(emptyMap(), client.versionFiles(listOf("abc")))
            val bulk = server.takeRequest(); assertEquals("POST", bulk.method); assertEquals("/v2/version_files", bulk.url.encodedPath)
        }
    }
    @Test fun facetsFollowInstanceUnlessTurnedOff() {
        val filters = ProjectFilters(categories = setOf("adventure"))
        assertTrue(filters.facets().contains(listOf("categories:neoforge")))
        assertTrue(filters.facets().contains(listOf("versions:26.2")))
        assertFalse(filters.copy(followGame = false).facets().any { it.any { v -> v.startsWith("versions:") } })
        assertFalse(filters.facets(ContentKind.DataPacks).contains(listOf("categories:neoforge")))
        assertTrue(filters.facets(ContentKind.Shaders).contains(listOf("categories:iris")))
    }
}
