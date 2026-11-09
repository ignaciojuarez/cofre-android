package com.ignacio.cofre.ui.instances

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.design.AdaptiveListDetail
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.Chevron
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.FieldKind
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PaneScroll
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.ui.common.Fmt
import com.ignacio.cofre.ui.common.loaderLabel

/** Instances: the library beside the open instance (pushed on phones). */
@Composable
fun InstancesSection(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val nav = graph.nav
    val library = graph.library
    LaunchedEffect(Unit) { library.refresh() }
    AdaptiveListDetail(
        selection = nav.instance?.takeIf { id -> library.instances.any { it.id == id } },
        onSelectionChange = { nav.instance = it },
        list = { InstanceList(graph) },
        detail = { id -> library.instances.firstOrNull { it.id == id }?.let { InstanceDetail(graph, it) } },
        modifier = modifier,
        placeholder = {
            EmptyState(stringResource(R.string.instances_pick_title), stringResource(R.string.instances_pick_body), PixelGlyph.Grass)
        },
    )
}

@Composable
private fun InstanceList(graph: AppGraph) {
    val library = graph.library
    val nav = graph.nav
    val query = rememberTextFieldState()
    val search = remember { FocusRequester() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { library.import(it) { made -> nav.instance = made.id } }
    }
    val text = query.text.toString().trim()
    val shown = library.instances.filter { text.isEmpty() || it.name.contains(text, ignoreCase = true) }
    Column(Modifier.controllerActions(ControllerAction(Pad.Y, stringResource(R.string.search)) { search.requestFocus() })) {
        ColumnHeader(stringResource(R.string.section_instances)) {
            BlockButton(stringResource(R.string.instances_new), { nav.newInstance = true }, Modifier.testTag("instances.new"), role = ButtonRole.Primary, size = ButtonSize.Small)
        }
        PaneScroll(spacing = 8.dp) {
            Field(query, stringResource(R.string.search), Modifier.focusRequester(search), kind = FieldKind.Search)
            library.error?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) { BlockButton(stringResource(R.string.dismiss), library::clearError, size = ButtonSize.Small) } }
            library.undo?.let { undo ->
                Banner(stringResource(R.string.instances_deleted, undo.name), PixelGlyph.Check, tone = Tone.Success) {
                    BlockButton(stringResource(R.string.undo), library::undoTrash, size = ButtonSize.Small)
                }
            }
            when {
                !library.loaded -> BlockRow(stringResource(R.string.instances_loading), leading = { Spinner() })
                library.instances.isEmpty() -> EmptyState(stringResource(R.string.instances_empty_title), stringResource(R.string.instances_empty_body), PixelGlyph.Grass) {
                    BlockButton(stringResource(R.string.instances_new_long), { nav.newInstance = true }, role = ButtonRole.Primary)
                }
                shown.isEmpty() -> EmptyState(stringResource(R.string.instances_no_match), stringResource(R.string.instances_no_match_body), PixelGlyph.Book)
                else -> Groups(shown, selected = nav.instance, onOpen = { nav.instance = it })
            }
            if (library.deleted.isNotEmpty()) {
                ListSection(label = stringResource(R.string.instances_recently_deleted)) {
                    library.deleted.forEach { deleted ->
                        BlockRow(
                            deleted.instance.name,
                            subtitle = deleted.instance.loaderLabel,
                            dimmed = true,
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    BlockButton(stringResource(R.string.restore), { library.restore(deleted.token) }, size = ButtonSize.Small)
                                    BlockButton(stringResource(R.string.delete_forever), { library.purge(deleted.token) }, role = ButtonRole.GhostDanger, size = ButtonSize.Small)
                                }
                            },
                        )
                    }
                }
            }
            BlockButton(stringResource(R.string.instances_import), { importer.launch(arrayOf("application/zip", "application/octet-stream")) }, icon = PixelGlyph.Chest)
        }
    }
}

/** Favourites first, then each group (alphabetical), then the rest. */
@Composable
private fun Groups(
    shown: List<Instance>,
    selected: String?,
    onOpen: (String) -> Unit,
) {
    val favorites = shown.filter { it.favorite }
    val grouped = shown.filter { !it.favorite }.groupBy { it.group }
    val other = stringResource(R.string.instances_other)
    val labelled = favorites.isNotEmpty() || grouped.keys.any { it != null }
    if (favorites.isNotEmpty()) {
        ListSection(label = stringResource(R.string.instances_favorites)) { favorites.forEach { InstanceRow(it, it.id == selected, onOpen) } }
    }
    grouped.keys.sortedWith(compareBy(nullsLast()) { it?.lowercase() }).forEach { key ->
        ListSection(label = if (labelled) key ?: other else null) {
            grouped.getValue(key).forEach { InstanceRow(it, it.id == selected, onOpen) }
        }
    }
}

@Composable
private fun InstanceRow(
    instance: Instance,
    selected: Boolean,
    onOpen: (String) -> Unit,
) {
    BlockRow(
        instance.name,
        Modifier.testTag("instance.${instance.name}"),
        subtitle = listOfNotNull(instance.loaderLabel, Fmt.ago(instance.lastPlayedAt)).joinToString(" · "),
        selected = selected,
        big = true,
        onClick = { onOpen(instance.id) },
        leading = { PixelIcon(if (instance.favorite) PixelGlyph.Ore else PixelGlyph.Grass, size = 28.dp) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp)) {
                if (instance.favorite) Badge("★", tone = Tone.Warn)
                Chevron()
            }
        },
    )
}
