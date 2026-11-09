package com.ignacio.cofre.ui.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.modrinth.Modrinth
import com.ignacio.cofre.kit.modrinth.ProjectFilters
import com.ignacio.cofre.kit.pages.ProjectPage
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Filters → facets, end to end through a fake Modrinth: what the browse pane asks for is what goes on the wire. */
class CatalogTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun catalog(curseForge: CurseForge? = null) = Catalog(Modrinth(http, server.url("/v2/")), curseForge)

    private fun searched(query: BrowseQuery): List<List<String>> = runBlocking {
        server.enqueue(MockResponse(body = """{"hits":[{"project_id":"AANobbMI","slug":"sodium","title":"Sodium","project_type":"mod","downloads":123}]}"""))
        catalog().search(query, offset = 30, limit = 30)
        val request = server.takeRequest()
        assertEquals("30", request.url.queryParameter("offset"))
        kotlinx.serialization.json.Json.decodeFromString<List<List<String>>>(request.url.queryParameter("facets")!!)
    }

    @Test fun modsFitThisInstanceByDefault() {
        val facets = searched(BrowseQuery("sodium"))
        assertEquals(listOf(listOf("project_type:mod"), listOf("versions:26.2"), listOf("categories:neoforge")), facets)
    }

    @Test fun categoriesAndSortGoOnTheWire() = runBlocking {
        val facets = searched(BrowseQuery(filters = ProjectFilters(categories = setOf("optimization", "storage"))))
        assertTrue(listOf("categories:optimization") in facets)
        assertTrue(listOf("categories:storage") in facets)
        server.enqueue(MockResponse(body = """{"hits":[]}"""))
        catalog().search(BrowseQuery(filters = ProjectFilters(sort = "downloads")), 0, 30)
        assertEquals("downloads", server.takeRequest().url.queryParameter("index"))
    }

    @Test fun anyVersionDropsTheGameAndLoaderFacets() {
        val facets = searched(BrowseQuery(filters = ProjectFilters(followGame = false, followLoader = false)))
        assertEquals(listOf(listOf("project_type:mod")), facets)
    }

    @Test fun resourcePacksHaveNoLoaderFacet() {
        val facets = searched(BrowseQuery(kind = ContentKind.ResourcePacks))
        assertEquals(listOf(listOf("project_type:resourcepack"), listOf("versions:26.2")), facets)
    }

    @Test fun hitsMapToSummariesWithVulkanBadges() = runBlocking {
        server.enqueue(MockResponse(body = """{"hits":[{"project_id":"YL57xq9U","slug":"iris","title":"Iris Shaders","project_type":"mod","downloads":9}]}"""))
        val hit = catalog().search(BrowseQuery("iris"), 0, 30).single()
        assertEquals(ProjectPage.Source.Modrinth, hit.source)
        assertEquals("https://modrinth.com/mod/iris", hit.pageUrl)
        assertEquals("Needs OpenGL", hit.issue?.badge)
        val shader = hit.copy(kind = ContentKind.Shaders, slug = "complementary")
        assertEquals("Not on Vulkan", shader.issue?.badge)
    }

    @Test fun curseForgeSearchesWithTheUsersKey() = runBlocking {
        server.enqueue(MockResponse(body = """{"data":[{"id":238222,"name":"JEI","summary":"Items","links":{"websiteUrl":"https://www.curseforge.com/minecraft/mc-mods/jei"}}]}"""))
        val cf = CurseForge("user-key", http, server.url("/v1/"))
        val hit = catalog(cf).search(BrowseQuery("jei", source = ProjectPage.Source.CurseForge), 0, 30).single()
        val request = server.takeRequest()
        assertEquals("user-key", request.headers["x-api-key"])
        assertEquals("26.2", request.url.queryParameter("gameVersion"))
        assertEquals("238222", hit.id)
        assertFalse(catalog().hasCurseForge)
    }
}
