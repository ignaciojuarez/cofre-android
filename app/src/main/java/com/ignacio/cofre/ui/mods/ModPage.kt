package com.ignacio.cofre.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.GalleryGrid
import com.ignacio.cofre.design.GalleryItem
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.Meta
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PaneScroll
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.Segmented
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.ValueRow
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.design.cycle
import com.ignacio.cofre.kit.pages.Gallery
import com.ignacio.cofre.kit.pages.ProjectPage

/** M02 wired to its model: tab saved per page, changelog loaded on demand. */
@Composable
internal fun ProjectRoute(
    project: ProjectModel,
    onGet: (versionId: String?) -> Unit,
    onRemove: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onGallery: (List<GalleryItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by project.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(PageTab.About) }
    ModPage(
        state, tab,
        onTab = { tab = it },
        onAction = {
            when (state.action) {
                PageAction.Get -> onGet(null)
                PageAction.Update -> onGet(state.update?.versionId)
                PageAction.Remove -> onRemove()
                PageAction.Unavailable -> Unit
            }
        },
        onGetVersion = onGet,
        onChangelog = project::changelog,
        onOpenUrl = onOpenUrl,
        onGallery = onGallery,
        onRetry = project::load,
        modifier = modifier,
    )
}

/** M02 Mod page: header, the Get/Update/Remove action, then About · Gallery · Versions · Changelog · Links. L1/R1 switch tabs. */
@Composable
internal fun ModPage(
    state: ProjectState,
    tab: PageTab,
    onTab: (PageTab) -> Unit,
    onAction: () -> Unit,
    onGetVersion: (String) -> Unit,
    onChangelog: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onGallery: (List<GalleryItem>, Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = state.summary
    val tabs = state.tabs
    val current = if (tab in tabs) tab else PageTab.About
    val previous = stringResource(R.string.mods_tab_previous)
    val next = stringResource(R.string.mods_tab_next)
    Column(
        modifier
            .fillMaxSize()
            .controllerActions(
                ControllerAction(Pad.L1, previous) { onTab(tabs.cycle(current, -1)) },
                ControllerAction(Pad.R1, next) { onTab(tabs.cycle(current, 1)) },
            ),
    ) {
        ColumnHeader(p.title)
        PaneScroll(readable = true) {
            PageHeader(state)
            p.issue?.let { issue ->
                Banner(issue.badge + " · " + issue.reason, PixelGlyph.Ore, tone = issue.tone)
            }
            ActionButton(state, onAction)
            Segmented(
                selection = current,
                onSelect = onTab,
                options = tabs.map { it to tabTitle(it, state) },
                small = true,
            )
            when {
                state.loading && state.page == null -> BlockRow(stringResource(R.string.mods_loading_page), leading = { Spinner() })
                state.page == null -> EmptyState(
                    stringResource(R.string.mods_offline, sourceName(p.source)),
                    state.error ?: stringResource(R.string.mods_offline_message),
                    PixelGlyph.Cross,
                    Modifier.padding(top = 20.dp),
                ) { BlockButton(stringResource(R.string.mods_try_again), onRetry, role = ButtonRole.Primary) }
                else -> when (current) {
                    PageTab.About -> AboutTab(state.page)
                    PageTab.Gallery -> GalleryTab(state.page, onGallery, onOpenUrl)
                    PageTab.Versions -> VersionsTab(state, onGetVersion)
                    PageTab.Changelog -> ChangelogTab(state, onChangelog)
                    PageTab.Links -> LinksTab(state, onOpenUrl)
                }
            }
        }
    }
}

@Composable
private fun PageHeader(state: ProjectState) {
    val p = state.summary
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ModIcon(state.page?.iconUrl ?: p.iconUrl, 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CofreText(p.summary.ifEmpty { state.page?.summary.orEmpty() }, CofreTheme.type.secondary, color = CofreTheme.colors.inkSoft, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(sourceName(p.source))
                p.downloads?.let { Meta("↓ " + count(it)) }
                state.page?.license?.let { Meta(it) }
                state.installed?.let { Badge(stringResource(if (it.item.enabled) R.string.mods_installed_badge else R.string.mods_off), tone = Tone.Success) }
            }
        }
    }
}

@Composable
private fun ActionButton(state: ProjectState, onAction: () -> Unit) {
    when (state.action) {
        PageAction.Get -> BlockButton(
            stringResource(if (state.summary.issue != null) R.string.mods_get_anyway else R.string.mods_get),
            onAction,
            role = if (state.summary.issue != null) ButtonRole.Secondary else ButtonRole.Primary,
            icon = PixelGlyph.Chest,
        )
        PageAction.Update -> BlockButton(
            stringResource(R.string.mods_update_to, state.update?.version.orEmpty()),
            onAction,
            role = ButtonRole.Primary,
        )
        PageAction.Remove -> BlockButton(stringResource(R.string.mods_remove), onAction, role = ButtonRole.Danger)
        PageAction.Unavailable -> BlockButton(stringResource(R.string.mods_no_build), onAction, enabled = false)
    }
}

@Composable
private fun tabTitle(tab: PageTab, state: ProjectState): String = when (tab) {
    PageTab.About -> stringResource(R.string.mods_tab_about)
    PageTab.Gallery -> stringResource(R.string.mods_tab_gallery, state.page?.let { Gallery(it).count } ?: 0)
    PageTab.Versions -> stringResource(R.string.mods_tab_versions)
    PageTab.Changelog -> stringResource(R.string.mods_tab_changelog)
    PageTab.Links -> stringResource(R.string.mods_tab_links)
}

@Composable
private fun AboutTab(page: ProjectPage) {
    val body = page.body
    if (body == null) {
        CofreText(page.summary, CofreTheme.type.body)
    } else {
        ProjectBody(body)
    }
}

@Composable
private fun GalleryTab(page: ProjectPage, onGallery: (List<GalleryItem>, Int) -> Unit, onOpenUrl: (String) -> Unit) {
    val gallery = Gallery(page)
    val videos = gallery.videos.map { GalleryItem(it.thumb, "Video", video = true, full = it.watch) }
    val images = gallery.images.mapIndexed { i, image ->
        GalleryItem(image.thumb, image.title ?: "Image ${i + 1} of ${gallery.images.size}", full = image.full, title = image.title, caption = image.caption)
    }
    val items = videos + images
    GalleryGrid(items, onOpen = { i ->
        val item = items[i]
        if (item.video) item.full?.let(onOpenUrl) else onGallery(images, i - videos.size)
    })
}

@Composable
private fun VersionsTab(state: ProjectState, onGet: (String) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val fitting = state.fitting
    ListSection(label = stringResource(R.string.mods_versions_fit)) {
        if (fitting.isEmpty()) {
            CofreText(stringResource(R.string.mods_versions_none), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
        }
        fitting.forEach { VersionRow(it, fits = true, installed = it.id == state.installed?.mod?.versionId, onGet = onGet) }
    }
    if (state.others.isNotEmpty()) {
        if (all) {
            ListSection(label = stringResource(R.string.mods_versions_other)) {
                state.others.take(50).forEach { VersionRow(it, fits = false, installed = false, onGet = onGet) }
            }
        } else {
            BlockButton(stringResource(R.string.mods_versions_show_all, state.others.size), { all = true }, size = ButtonSize.Small, fullWidth = false)
        }
    }
}

@Composable
private fun VersionRow(version: ProjectPage.Version, fits: Boolean, installed: Boolean, onGet: (String) -> Unit) {
    val detail = (version.gameVersions.take(3) + version.loaders.take(2)).joinToString(" · ")
    BlockRow(
        version.name,
        subtitle = detail,
        dimmed = !fits,
        onClick = if (fits && !installed) ({ onGet(version.id) }) else null,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (version.channel != "release") Badge(version.channel, tone = Tone.Warn)
                when {
                    installed -> Badge(stringResource(R.string.mods_installed_badge), tone = Tone.Success)
                    fits -> Meta(stringResource(R.string.mods_get))
                }
            }
        },
    )
}

@Composable
private fun ChangelogTab(state: ProjectState, onChangelog: (String) -> Unit) {
    val version = state.fitting.firstOrNull() ?: state.page?.versions?.firstOrNull()
    if (version == null) {
        CofreText(stringResource(R.string.mods_versions_none), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
        return
    }
    LaunchedEffect(version.id) { onChangelog(version.id) }
    CofreText(version.name, CofreTheme.type.rowTitle)
    when {
        version.id !in state.changelogs -> BlockRow(stringResource(R.string.mods_loading_page), leading = { Spinner() })
        else -> state.changelogs[version.id]?.let { ProjectBody(it) }
            ?: CofreText(stringResource(R.string.mods_no_changelog), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
    }
}

@Composable
private fun LinksTab(state: ProjectState, onOpenUrl: (String) -> Unit) {
    ListSection {
        state.summary.pageUrl?.let { ValueRow(sourceName(state.summary.source), stringResource(R.string.mods_open), { onOpenUrl(it) }) }
        state.page?.links.orEmpty().forEach { link ->
            ValueRow(linkTitle(link.kind), link.url.removePrefix("https://").substringBefore('/'), { onOpenUrl(link.url) })
        }
    }
}

private fun linkTitle(kind: String): String = when {
    kind.startsWith("donate:") -> "Donate" + kind.removePrefix("donate:").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    else -> kind.replaceFirstChar { it.uppercase() }
}
