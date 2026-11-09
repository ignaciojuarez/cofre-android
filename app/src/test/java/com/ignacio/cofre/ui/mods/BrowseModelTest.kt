package com.ignacio.cofre.ui.mods

import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.pages.ProjectPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseModelTest {
    private fun hits(prefix: String, from: Int, n: Int) =
        (from until from + n).map { ProjectSummary("$prefix$it", ProjectPage.Source.Modrinth, ContentKind.Mods, "$prefix $it", "") }

    private fun TestScope.model(
        pageSize: Int = 3,
        sourceReady: (BrowseQuery) -> Boolean = { true },
        search: suspend (BrowseQuery, Int, Int) -> List<ProjectSummary>,
    ) = BrowseModel(CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), search, sourceReady, pageSize, debounceMs = 300)

    @Test fun typingDebouncesToOneSearchOfTheLastText() = runTest {
        val searched = mutableListOf<String>()
        val browse = model { q, _, _ -> searched += q.text; hits(q.text, 0, 3) }
        advanceUntilIdle()
        searched.clear()
        for (text in listOf("s", "so", "sod", "sodi")) {
            browse.setQuery { it.copy(text = text) }
            advanceTimeBy(100)
        }
        assertEquals(emptyList(), searched, "nothing searched while typing")
        advanceTimeBy(301)
        assertEquals(listOf("sodi"), searched)
        assertEquals("sodi 0", browse.state.value.results.first().title)
    }

    @Test fun aNewQueryCancelsTheSearchInFlight() = runTest {
        val slow = CompletableDeferred<List<ProjectSummary>>()
        var cancelled = false
        val browse = model { q, _, _ ->
            if (q.text == "old") {
                try { slow.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            } else {
                hits(q.text, 0, 3)
            }
        }
        browse.setQuery { it.copy(text = "old") }
        advanceTimeBy(301)
        runCurrent()
        assertTrue(browse.state.value.searching)
        browse.setQuery { it.copy(text = "new") }
        runCurrent()
        assertTrue(cancelled, "the old search was cancelled")
        slow.complete(hits("old", 0, 3)) // too late: it must never land
        advanceUntilIdle()
        assertEquals(listOf("new 0", "new 1", "new 2"), browse.state.value.results.map { it.title })
        assertFalse(browse.state.value.searching)
    }

    @Test fun pagesUntilAShortPage() = runTest {
        val browse = model { q, offset, limit -> if (offset >= 6) hits(q.text, offset, 1) else hits(q.text, offset, limit) }
        advanceUntilIdle()
        browse.loadMore(); advanceUntilIdle()
        browse.loadMore(); advanceUntilIdle()
        assertEquals(7, browse.state.value.results.size)
        assertTrue(browse.state.value.endReached)
        browse.loadMore(); advanceUntilIdle()
        assertEquals(7, browse.state.value.results.size, "no page after the end")
    }

    @Test fun loadMoreWaitsForTheNewQueryToSettle() = runTest {
        val offsets = mutableListOf<Pair<String, Int>>()
        val browse = model { q, offset, limit -> offsets += q.text to offset; hits(q.text, offset, limit) }
        advanceUntilIdle()
        browse.setQuery { it.copy(text = "x") }
        browse.loadMore() // old results, new query: must not page the new query from offset 3
        advanceUntilIdle()
        assertEquals(listOf("" to 0, "x" to 0), offsets)
    }

    @Test fun failuresShowAndRetrySearchesAgain() = runTest {
        var fail = true
        val browse = model { q, _, _ -> if (fail) throw java.io.IOException("offline") else hits(q.text, 0, 3) }
        advanceUntilIdle()
        assertEquals("offline", browse.state.value.error)
        fail = false
        browse.retry(); advanceUntilIdle()
        assertNull(browse.state.value.error)
        assertEquals(3, browse.state.value.results.size)
    }

    @Test fun curseForgeWithoutAKeyNeverSearches() = runTest {
        var calls = 0
        val browse = model(sourceReady = { it.source == ProjectPage.Source.Modrinth }) { q, _, _ -> calls++; hits(q.text, 0, 3) }
        advanceUntilIdle()
        browse.setQuery { it.copy(source = ProjectPage.Source.CurseForge) }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertFalse(browse.state.value.sourceReady)
        assertTrue(browse.state.value.results.isEmpty())
    }
}
