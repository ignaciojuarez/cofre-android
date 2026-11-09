package com.ignacio.cofre.kit.curseforge

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.*

class CurseForgeTest {
    @Test fun searchUsesCallerKeyAndNeoForgeFilters() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = """{"data":[{"id":1,"name":"Test"}]}"""))
            val cf = CurseForge("runtime-only", baseUrl = server.url("/v1/"))
            assertEquals("Test", cf.search("test", "26.2").single().name)
            val request = server.takeRequest()
            assertEquals("runtime-only", request.headers["x-api-key"])
            assertEquals("6", request.url.queryParameter("modLoaderType"))
            assertEquals("26.2", request.url.queryParameter("gameVersion"))
        }
    }
    @Test fun disabledDownloadsCarryWebsite() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse(body = """{"data":null}"""))
            val cf = CurseForge("key", baseUrl = server.url("/"))
            assertEquals(CurseForge.Download.Disabled("https://curseforge.com/a"), cf.downloadUrl(1, 2, "https://curseforge.com/a"))
            assertIs<CurseForge.Download.Disabled>(cf.download(CurseForge.File(2, 1, fileName = "a.jar"), "https://curseforge.com/a"))
        }
    }
    @Test fun modFilesDescriptionAndChangelog() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            listOf("""{"data":{"id":1,"name":"A","links":{"websiteUrl":"https://curseforge.com/a"}}}""", """{"data":[{"id":2,"modId":1,"fileName":"a.jar","downloadUrl":"https://cdn/a.jar"}]}""", """{"data":"<p>About</p>"}""", """{"data":"<p>Fixed</p>"}""").forEach { server.enqueue(MockResponse(body = it)) }
            val cf = CurseForge("key", baseUrl = server.url("/"))
            assertEquals(1, cf.mod(1).id)
            assertIs<CurseForge.Download.Available>(cf.download(cf.files(1).single(), "https://curseforge.com/a"))
            assertEquals("<p>About</p>", cf.description(1)); assertEquals("<p>Fixed</p>", cf.changelog(1, 2))
        }
    }
    @Test fun emptyKeyFailsBeforeNetwork() = runBlocking {
        assertFailsWith<IllegalArgumentException> { CurseForge("").mod(1) }
    }
}
