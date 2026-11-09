package com.ignacio.cofre.ui.mods

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the browse pane draws. */
@Immutable
data class BrowseState(
    val query: BrowseQuery = BrowseQuery(),
    val results: List<ProjectSummary> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    /** False while CurseForge is picked without a key. */
    val sourceReady: Boolean = true,
)

/**
 * The search box behind the browse pane. Every query change waits [debounceMs]; a newer one cancels both the wait
 * and any search or page load in flight, so old text is never searched and stale pages never land.
 */
class BrowseModel(
    private val scope: CoroutineScope,
    private val search: suspend (query: BrowseQuery, offset: Int, limit: Int) -> List<ProjectSummary>,
    private val sourceReady: (BrowseQuery) -> Boolean = { true },
    private val pageSize: Int = 30,
    private val debounceMs: Long = 300,
) {
    private val mutable = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = mutable.asStateFlow()

    private val queries = MutableStateFlow(BrowseQuery())
    private var searched: BrowseQuery? = null
    private var more: Job? = null

    init {
        scope.launch {
            queries.collectLatest { query ->
                if (query == searched) return@collectLatest
                more?.cancel()
                delay(debounceMs)
                run(query)
            }
        }
    }

    fun setQuery(transform: (BrowseQuery) -> BrowseQuery) {
        val next = transform(queries.value)
        queries.value = next
        mutable.update { it.copy(query = next, sourceReady = sourceReady(next)) }
    }

    /** Searches the current query again now (Try again, a new CurseForge key). */
    fun retry() {
        searched = null
        val query = queries.value
        more?.cancel()
        more = scope.launch { run(query) }
    }

    /** The list neared its end: fetch the next page unless one is loading or there are no more. */
    fun loadMore() {
        val now = mutable.value
        val settled = now.query == searched
        if (!settled || now.loadingMore || now.searching || now.endReached || now.results.isEmpty() || more?.isActive == true) return
        val query = now.query
        more = scope.launch {
            mutable.update { it.copy(loadingMore = true) }
            try {
                val page = search(query, now.results.size, pageSize)
                mutable.update { s ->
                    if (s.query != query) s
                    else s.copy(results = (s.results + page).distinctBy { it.id }, loadingMore = false, endReached = page.size < pageSize)
                }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(loadingMore = false) }
                throw cancelled
            } catch (e: Exception) {
                mutable.update { it.copy(loadingMore = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun run(query: BrowseQuery) {
        if (!sourceReady(query)) {
            mutable.update { it.copy(results = emptyList(), searching = false, error = null, sourceReady = false) }
            return
        }
        mutable.update { it.copy(searching = true, error = null, endReached = false) }
        try {
            val page = search(query, 0, pageSize)
            searched = query
            mutable.update { it.copy(results = page, searching = false, endReached = page.size < pageSize) }
        } catch (cancelled: CancellationException) {
            mutable.update { it.copy(searching = false) }
            throw cancelled
        } catch (e: Exception) {
            mutable.update { it.copy(results = emptyList(), searching = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }
}
