package com.ignacio.cofre.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.Chip
import com.ignacio.cofre.design.ChipGroup
import com.ignacio.cofre.design.CofreBlock
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.FieldKind
import com.ignacio.cofre.design.Meta
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.RemoteImage
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.WidthClass
import com.ignacio.cofre.design.outline
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.modrinth.ProjectFilters
import com.ignacio.cofre.kit.pages.ProjectPage
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

/** Kinds in the order the chips show them. Shaders stay listed; their rows say they can't run on Vulkan. */
private val Kinds = listOf(
    ContentKind.Mods to R.string.mods_kind_mods,
    ContentKind.ResourcePacks to R.string.mods_kind_resource,
    ContentKind.DataPacks to R.string.mods_kind_data,
    ContentKind.Modpacks to R.string.mods_kind_packs,
    ContentKind.Shaders to R.string.mods_kind_shaders,
)

private val Sorts = listOf(
    "relevance" to R.string.mods_sort_relevance,
    "downloads" to R.string.mods_sort_downloads,
    "follows" to R.string.mods_sort_follows,
    "newest" to R.string.mods_sort_newest,
    "updated" to R.string.mods_sort_updated,
)

/** M01 browse: search, kind, source, filters and an endless list of results. */
@Composable
internal fun BrowsePane(
    state: BrowseState,
    selected: String?,
    updates: Int,
    hasCurseForge: Boolean,
    onQueryChange: ((BrowseQuery) -> BrowseQuery) -> Unit,
    onOpen: (ProjectSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onFilters: () -> Unit,
    onInstalled: () -> Unit,
    onImport: () -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
    searchFocus: FocusRequester = remember { FocusRequester() },
    instance: Instance? = null,
    instances: List<Instance> = emptyList(),
    onInstance: (String) -> Unit = {},
) {
    val query = state.query
    val field = rememberTextFieldState(query.text)
    val change by rememberUpdatedState(onQueryChange)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.distinctUntilChanged().collect { text -> change { it.copy(text = text) } }
    }
    val margin = if (CofreTheme.widthClass == WidthClass.Compact) 16.dp else 20.dp
    Column(modifier.fillMaxSize()) {
        ColumnHeader(stringResource(R.string.mods_get_mods)) {
            BlockButton(stringResource(R.string.mods_import), onImport, size = ButtonSize.Small)
            BlockButton(
                if (updates > 0) stringResource(R.string.mods_installed_updates, updates) else stringResource(R.string.mods_installed),
                onInstalled,
                size = ButtonSize.Small,
            )
        }
        Column(Modifier.padding(horizontal = margin).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (instance != null && instances.size > 1) {
                SelectButton(
                    stringResource(R.string.mods_for_instance),
                    selection = instance.id,
                    onSelect = onInstance,
                    options = instances.map { it.id to stringResource(R.string.mods_for_instance_named, it.name) },
                    compact = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(
                    field,
                    label = stringResource(R.string.mods_search_label, kindName(query.kind)),
                    modifier = Modifier.weight(1f).focusRequester(searchFocus),
                    kind = FieldKind.Search,
                )
                if (hasCurseForge) {
                    val other = if (query.source == ProjectPage.Source.Modrinth) ProjectPage.Source.CurseForge else ProjectPage.Source.Modrinth
                    BlockButton(sourceName(query.source) + " ⇄", { onQueryChange { it.copy(source = other) } }, size = ButtonSize.Small)
                }
                BlockButton(
                    if (query.activeFilters > 0) stringResource(R.string.mods_filters_count, query.activeFilters) else stringResource(R.string.mods_filters),
                    onFilters,
                    size = ButtonSize.Small,
                )
            }
            ChipGroup(
                selection = query.kind,
                onSelect = { kind -> onQueryChange { it.copy(kind = kind, filters = it.filters.copy(categories = emptySet())) } },
                options = Kinds.map { (kind, label) -> kind to stringResource(label) },
            )
            CofreText(filterSummary(query), CofreTheme.type.label, color = CofreTheme.colors.muted, maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.sourceReady -> EmptyState(
                    stringResource(R.string.mods_needs_key),
                    stringResource(R.string.mods_needs_key_message),
                    PixelGlyph.Key,
                ) { BlockButton(stringResource(R.string.mods_add_key), onAddKey, role = ButtonRole.Primary) }
                state.error != null && state.results.isEmpty() && !state.searching -> EmptyState(
                    stringResource(R.string.mods_offline, sourceName(query.source)),
                    stringResource(R.string.mods_offline_message),
                    PixelGlyph.Cross,
                ) { BlockButton(stringResource(R.string.mods_try_again), onRetry, role = ButtonRole.Primary) }
                state.results.isEmpty() && state.searching -> BlockRow(
                    stringResource(R.string.mods_searching),
                    Modifier.padding(horizontal = margin),
                    leading = { Spinner() },
                )
                state.results.isEmpty() -> EmptyState(stringResource(R.string.mods_no_results), stringResource(R.string.mods_no_results_message), PixelGlyph.Book) {
                    if (query.activeFilters > 0) {
                        BlockButton(stringResource(R.string.mods_clear_filters), { onQueryChange { it.copy(filters = ProjectFilters()) } }, role = ButtonRole.Primary)
                    }
                }
                else -> ResultList(state, selected, margin, onOpen, onLoadMore)
            }
        }
    }
}

@Composable
private fun ResultList(
    state: BrowseState,
    selected: String?,
    margin: Dp,
    onOpen: (ProjectSummary) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val more by rememberUpdatedState(onLoadMore)
    val count by rememberUpdatedState(state.results.size)
    // Ten rows before the end, fetch the next page so scrolling doesn't stop.
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= count - 10) more() }
    }
    LazyColumn(
        modifier.fillMaxSize().pageScroll(list),
        state = list,
        contentPadding = PaddingValues(start = margin, end = margin, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(state.results, key = { it.source.name + it.id }) { project ->
            ProjectRow(project, selected = project.route == selected, onClick = { onOpen(project) })
        }
        if (state.loadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Spinner() }
            }
        }
    }
}

/** One result: icon, title, summary, downloads and a Vulkan badge when the rules know it can't run. */
@Composable
internal fun ProjectRow(
    project: ProjectSummary,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val issue = remember(project) { project.issue }
    BlockRow(
        project.title,
        modifier,
        selected = selected,
        onClick = onClick,
        leading = { ModIcon(project.iconUrl, 40.dp) },
        subtitleContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                CofreText(project.summary, CofreTheme.type.caption, color = CofreTheme.colors.muted, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    project.downloads?.let { Meta("↓ " + count(it)) }
                    issue?.let { Badge(it.badge, tone = it.tone) }
                }
            }
        },
    )
}

/** A project icon, kept sharp when pixel art is scaled up; ore while it loads or when there's none. */
@Composable
internal fun ModIcon(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    glyph: PixelGlyph = PixelGlyph.Ore,
) {
    Box(modifier.size(size).outline()) {
        val inner = Modifier.fillMaxSize().padding(CofreBlock.outline)
        if (url == null) {
            PixelIcon(glyph, inner, size = size - CofreBlock.outline * 2)
        } else {
            RemoteImage(url, inner, ContentScale.Crop, pixelated = true) { PixelIcon(glyph, size = size - CofreBlock.outline * 2) }
        }
    }
}

/** Filters for the current kind. Changes search live (through the debounce), so there's no Apply. */
@Composable
internal fun FiltersSheet(
    query: BrowseQuery,
    onQueryChange: (BrowseQuery) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BlockSheet(onDismiss, modifier, hugsContent = true) {
        SheetHeader(stringResource(R.string.mods_filters_title, kindName(query.kind)), onDismiss)
        FiltersContent(query, onQueryChange, onDismiss)
    }
}

@Composable
internal fun FiltersContent(
    query: BrowseQuery,
    onQueryChange: (BrowseQuery) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = query.filters
    Column(modifier.padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleRow(
            stringResource(R.string.mods_fits_instance),
            checked = query.fitsInstance,
            onCheckedChange = { on -> onQueryChange(query.copy(filters = filters.copy(followGame = on, followLoader = on))) },
            subtitle = stringResource(R.string.mods_fits_instance_detail, Target.GAME_VERSION),
        )
        if (query.source == ProjectPage.Source.Modrinth) {
            SectionLabel(stringResource(R.string.mods_sort))
            ChipGroup(
                selection = filters.sort,
                onSelect = { sort -> onQueryChange(query.copy(filters = filters.copy(sort = sort))) },
                options = Sorts.map { (id, label) -> id to stringResource(label) },
            )
            val categories = ProjectFilters.categoryIds(query.kind)
            if (categories.isNotEmpty()) {
                SectionLabel(stringResource(R.string.mods_categories))
                categories.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { id ->
                            Chip(
                                ProjectFilters.categoryLabel(id),
                                selected = id in filters.categories,
                                onSelectedChange = { on ->
                                    val next = if (on) filters.categories + id else filters.categories - id
                                    onQueryChange(query.copy(filters = filters.copy(categories = next)))
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlockButton(stringResource(R.string.mods_clear), { onQueryChange(query.copy(filters = ProjectFilters())) }, Modifier.weight(1f))
            BlockButton(stringResource(R.string.mods_done), onDone, Modifier.weight(1f), role = ButtonRole.Primary)
        }
    }
}

@Composable
private fun filterSummary(query: BrowseQuery): String {
    val parts = buildList {
        add(if (query.fitsInstance) "${Target.GAME_VERSION} · NeoForge" else stringResource(R.string.mods_any_version))
        Sorts.firstOrNull { it.first == query.filters.sort }?.let { add(stringResource(it.second)) }
        query.filters.categories.sorted().forEach { add(ProjectFilters.categoryLabel(it)) }
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun kindName(kind: ContentKind): String = stringResource(Kinds.first { it.first == kind }.second)

internal fun sourceName(source: ProjectPage.Source): String = when (source) {
    ProjectPage.Source.Modrinth -> "Modrinth"
    ProjectPage.Source.CurseForge -> "CurseForge"
}

/** 1234 → "1.2K", 3_400_000 → "3.4M". */
internal fun count(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.ROOT, "%.1fK", n / 1e3)
    else -> "$n"
}.replace(".0", "")

/** Bytes as "4.2 MB". */
internal fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
