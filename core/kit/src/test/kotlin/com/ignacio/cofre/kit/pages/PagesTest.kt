package com.ignacio.cofre.kit.pages

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.curseforge.CurseForge
import com.ignacio.cofre.kit.modrinth.Modrinth
import kotlin.test.*

class PagesTest {
    private fun page(slug: String): ProjectPage = ProjectPage.from(Kit.json.decodeFromString<Modrinth.Project>(javaClass.getResource("/pages/modrinth-project-$slug.json")!!.readText()))
    @Test fun sodiumPageKeepsBodyLinksGalleryAndLicense() {
        val page = page("sodium")
        assertEquals("AANobbMI", page.id); assertEquals("Sodium", page.title); assertNotNull(page.iconUrl)
        assertIs<ProjectPage.Body.Markdown>(page.body); assertEquals(6, page.gallery.size)
        assertEquals("Underwater Lighting Improvements", page.gallery.first().title)
        assertEquals("Sodium 0.4.1", page.gallery.last().title)
        assertTrue(page.gallery.first().thumb.endsWith("_350.webp")); assertFalse(page.gallery.first().full.endsWith("_350.webp"))
        assertEquals(listOf("source", "issues", "wiki", "discord", "donate:Ko-fi"), page.links.map { it.kind })
        assertEquals("Polyform Shield 1.0.0", page.license)
    }
    @Test fun createAndIrisKeepProviderOrder() {
        val create = page("create"); assertTrue(create.gallery.isEmpty()); assertEquals("Create Mod License", create.license)
        assertEquals(listOf("donate:Patreon", "donate:Paypal"), create.links.takeLast(2).map { it.kind })
        val iris = page("iris"); assertTrue(iris.gallery.first().featured)
        assertEquals(listOf("Grand Teton National Park", "A nice sunset", "Wide Mountain", "Crying Obsidian Sky"), iris.gallery.map { it.title })
        assertFalse(iris.links.any { it.kind == "wiki" })
    }
    @Test fun missingAndUnsafeLinksAreDropped() {
        val page = ProjectPage.from(Kit.json.decodeFromString<Modrinth.Project>("""{"id":"x","title":"X","body":"","icon_url":"","license":{"id":"MIT","name":""},"wiki_url":"javascript:alert(1)","gallery":[{"url":"https://cdn/a","title":""},{"url":"javascript:x"}]}"""))
        assertNull(page.body); assertNull(page.iconUrl); assertTrue(page.links.isEmpty()); assertEquals("MIT", page.license)
        assertNull(page.gallery.single().title); assertEquals(page.gallery.single().thumb, page.gallery.single().full)
    }
    @Test fun linksOnlyOpenWebOutside() {
        assertEquals(PageLinkPolicy.Decision.Allow, PageLinkPolicy.decide("about:blank", true))
        for (url in listOf(null, "about:blank", "file:///x", "javascript:alert(1)", "data:text/html,x", "intent://x", "mailto:x@y")) assertEquals(PageLinkPolicy.Decision.Cancel, PageLinkPolicy.decide(url))
        assertIs<PageLinkPolicy.Decision.OpenOutside>(PageLinkPolicy.decide("https://example.com"))
        assertIs<PageLinkPolicy.Decision.OpenOutside>(PageLinkPolicy.decide("http://example.com", true))
    }
    @Test fun videosFromEmbedWatchShortAndNoCookieDeduplicate() {
        val body = """https://youtube.com/embed/rR8W-f9YhYA https://youtu.be/rR8W-f9YhYA https://youtube-nocookie.com/embed/abcdefghijk https://www.youtube.com/watch?v=12345678901 https://youtube.com/shorts/zyxwvutsrqp"""
        val page = ProjectPage("x", ProjectPage.Source.Modrinth, "X", body = ProjectPage.Body.Markdown(body))
        assertEquals(listOf("rR8W-f9YhYA", "abcdefghijk", "12345678901", "zyxwvutsrqp"), page.videos.map { it.id })
        assertEquals(body, (page.body as ProjectPage.Body.Markdown).text)
        assertFailsWith<IllegalArgumentException> { ProjectPage.Video("a/../b?x=1") }
        assertEquals("https://www.youtube.com/watch?v=rR8W-f9YhYA", page.videos.first().watch)
    }
    @Test fun galleryVideosFirstAndDuplicateImagesShowOnce() {
        val image = ProjectPage.Image("a", "https://cdn/a", title = "Title", caption = "Caption")
        val g = Gallery(listOf(image, image, image.copy(id = "b", title = null, caption = null)), listOf(ProjectPage.Video("abcdefg"), ProjectPage.Video("abcdefg")))
        assertEquals(3, g.count); assertIs<Gallery.Item.Video>(g.items.first())
        assertEquals("2 / 2", g.counter(99)); assertEquals("1 / 2", g.counter(-1))
        assertEquals("Title, Caption, image 1 of 2", g.spokenLabel(0)); assertEquals("image 2 of 2", g.spokenLabel(1))
        assertTrue(Gallery(emptyList()).isEmpty)
    }
    @Test fun onlyNearbyImagesLoadFull() {
        assertTrue(Gallery.loadsFull(1, 2)); assertTrue(Gallery.loadsFull(2, 2)); assertTrue(Gallery.loadsFull(3, 2)); assertFalse(Gallery.loadsFull(4, 2))
    }
    @Test fun curseForgeHtmlAndVersionsStayIntact() {
        val html = javaClass.getResource("/pages/page-curseforge.html")!!.readText()
        val page = ProjectPage.from(CurseForge.Mod(1, "A", screenshots = listOf(CurseForge.Image(1, "https://cdn/a"))), html, listOf(CurseForge.File(2, 1, fileName = "a.jar", gameVersions = listOf("26.2", "NeoForge"), releaseType = 2)))
        assertEquals(html, (page.body as ProjectPage.Body.Html).text)
        assertEquals("beta", page.versions.single().channel); assertEquals(listOf("neoforge"), page.versions.single().loaders)
    }
}
