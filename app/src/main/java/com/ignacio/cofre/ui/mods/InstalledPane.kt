package com.ignacio.cofre.ui.mods

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.BlockSwitch
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.ChipGroup
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.IconButton
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.Segmented
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.design.cycle
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.mods.VersionLabel
import com.ignacio.cofre.ui.folders.FolderBadges
import com.ignacio.cofre.ui.folders.rememberFirstFocus
import com.ignacio.cofre.ui.folders.autoUpdateOptions
import com.ignacio.cofre.ui.folders.label

/** What the folder pane does; the section wires each to the ViewModel (shared folders ask first there). */
internal class FolderActions(
    val onTab: (InstalledTab) -> Unit = {},
    val onFilter: (InstalledFilter) -> Unit = {},
    val onToggle: (InstalledRow) -> Unit = {},
    val onRemove: (InstalledRow) -> Unit = {},
    val onUpdate: (InstalledRow) -> Unit = {},
    val onPin: (InstalledRow) -> Unit = {},
    val onUpdateAll: () -> Unit = {},
    val onConvert: () -> Unit = {},
    val onSnapshots: () -> Unit = {},
    val onAutoUpdate: (AutoUpdate) -> Unit = {},
    val onOpenInstance: (String) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** The folder pane wired to its model. */
@Composable
internal fun FolderRoute(
    installed: InstalledModel,
    info: FolderInfo,
    actions: FolderActions,
    modifier: Modifier = Modifier,
) {
    val state by installed.state.collectAsStateWithLifecycle()
    FolderPane(state, info, actions, modifier)
}

/**
 * The mod folder Mods works on: name, loader and version, who shares it; Update all, Convert / upgrade, Snapshots;
 * Auto-update; Mods · Packs · Data tabs (data packs are in the active instance's worlds when it uses the folder); filter chips; and the rows. One
 * scrolling column, so a short screen scrolls instead of clipping. L1/R1 switch tabs.
 */
@Composable
internal fun FolderPane(
    state: InstalledState,
    info: FolderInfo,
    actions: FolderActions,
    modifier: Modifier = Modifier,
) {
    val margin = 20.dp
    val folder = info.folder
    // Data packs live in worlds: only an instance that uses the folder has some.
    val world = info.worlds
    val tabs = if (world == null) listOf(InstalledTab.Mods, InstalledTab.Packs) else InstalledTab.entries
    val list = rememberLazyListState()
    Column(
        modifier
            .fillMaxSize()
            .controllerActions(
                ControllerAction(Pad.L1, stringResource(R.string.mods_tab_previous)) { actions.onTab(tabs.cycle(state.tab, -1)) },
                ControllerAction(Pad.R1, stringResource(R.string.mods_tab_next)) { actions.onTab(tabs.cycle(state.tab, 1)) },
            ),
    ) {
        ColumnHeader(folder.name) { FolderBadges(folder, info.usedBy.size) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().pageScroll(list),
            state = list,
            contentPadding = PaddingValues(start = margin, end = margin, top = 2.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.updates > 0 && state.tab == InstalledTab.Mods) {
                item(key = "update") {
                    BlockButton(
                        stringResource(R.string.mods_update_all, state.updates), actions.onUpdateAll,
                        role = ButtonRole.Primary, loading = state.busy.isNotEmpty(),
                    )
                }
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockButton(stringResource(R.string.mods_convert), actions.onConvert, Modifier.weight(1f), icon = PixelGlyph.Bolt)
                    BlockButton(stringResource(R.string.mods_snapshots), actions.onSnapshots, Modifier.weight(1f), icon = PixelGlyph.Book)
                }
            }
            item(key = "used") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CofreText(stringResource(R.string.mods_used_by), CofreTheme.type.label, color = CofreTheme.colors.muted)
                        if (info.usedBy.isEmpty()) CofreText(stringResource(R.string.mods_used_by_none), CofreTheme.type.caption, color = CofreTheme.colors.muted)
                        info.usedBy.forEach { user ->
                            BlockButton(user.name, { actions.onOpenInstance(user.id) }, size = ButtonSize.Small, icon = PixelGlyph.Grass)
                        }
                    }
                    CofreText(stringResource(R.string.mods_auto_update), CofreTheme.type.label, color = CofreTheme.colors.muted)
                    SelectButton(stringResource(R.string.mods_auto_update), folder.autoUpdate, actions.onAutoUpdate, autoUpdateOptions(), compact = true)
                }
            }
            if (!folder.playable) {
                item(key = "unplayable") { Banner(stringResource(R.string.mods_folder_unplayable, folder.label), PixelGlyph.Bolt, tone = Tone.Warn) }
            }
            item(key = "tabs") {
                Segmented(
                    selection = state.tab,
                    onSelect = actions.onTab,
                    options = tabs.map { tab ->
                        tab to when (tab) {
                            InstalledTab.Mods -> stringResource(R.string.mods_tab_mods_count, info.mods)
                            InstalledTab.Packs -> stringResource(R.string.mods_tab_packs)
                            InstalledTab.Data -> stringResource(R.string.mods_kind_data)
                        }
                    },
                )
            }
            if (state.tab == InstalledTab.Mods && state.rows.isNotEmpty()) {
                item(key = "filters") {
                    ChipGroup(
                        selection = state.filter,
                        onSelect = actions.onFilter,
                        options = InstalledFilter.entries.map { filter -> filter to filterLabel(filter, state.count(filter)) },
                    )
                }
            }
            if (state.tab == InstalledTab.Data && world != null) {
                item(key = "data") { SectionLabel(stringResource(R.string.mods_data_scope, world.name)) }
            }
            state.undo?.let { undo ->
                item(key = "undo") {
                    Banner(undo.text, PixelGlyph.Check, tone = Tone.Success) {
                        BlockButton(stringResource(R.string.mods_undo), actions.onUndo, size = ButtonSize.Small)
                    }
                }
            }
            state.message?.let { message ->
                item(key = "message") {
                    Banner(message, PixelGlyph.Cross, tone = Tone.Danger) {
                        BlockButton(stringResource(R.string.mods_ok), actions.onDismiss, size = ButtonSize.Small)
                    }
                }
            }
            when {
                state.loading -> item(key = "loading") { BlockRow(stringResource(R.string.mods_loading_page), leading = { Spinner() }) }
                state.rows.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        stringResource(if (state.tab == InstalledTab.Mods) R.string.mods_none_yet else R.string.mods_nothing_yet),
                        stringResource(if (state.tab == InstalledTab.Data) R.string.mods_data_hint else R.string.mods_get_hint),
                        PixelGlyph.Chest,
                    )
                }
                else -> {
                    // Data packs live in each world: one section per world.
                    state.shown.groupBy { it.item.world }.forEach { (world, rows) ->
                        if (world != null) item(key = "world:$world") { SectionLabel(world) }
                        items(rows, key = { it.key }) { row ->
                            InstalledItemRow(
                                row, busy = row.key in state.busy,
                                onToggle = { actions.onToggle(row) }, onRemove = { actions.onRemove(row) },
                                onUpdate = { actions.onUpdate(row) }, onPin = { actions.onPin(row) },
                            )
                        }
                    }
                    item(key = "footer") {
                        CofreText(
                            stringResource(R.string.mods_on_off, state.on, state.rows.size - state.on),
                            CofreTheme.type.label,
                            Modifier.padding(vertical = 8.dp),
                            color = CofreTheme.colors.muted,
                        )
                    }
                }
            }
        }
    }
}

/** No folder to show: the instance's is gone, or there's no instance at all. */
@Composable
internal fun NoFolderPane(onManage: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        stringResource(R.string.mods_no_folder),
        stringResource(R.string.mods_no_folder_message),
        PixelGlyph.Chest,
        modifier,
    ) { BlockButton(stringResource(R.string.mods_manage_folders), onManage, role = ButtonRole.Primary) }
}

@Composable
private fun filterLabel(filter: InstalledFilter, count: Int): String = when (filter) {
    InstalledFilter.All -> stringResource(R.string.mods_filter_all)
    InstalledFilter.Updates -> stringResource(R.string.mods_filter_updates, count)
    InstalledFilter.Off -> stringResource(R.string.mods_filter_off, count)
    InstalledFilter.Issues -> stringResource(R.string.mods_filter_issues, count)
    InstalledFilter.Pinned -> stringResource(R.string.mods_filter_pinned, count)
}

/** One file. Focus lands on its switch (A turns it on or off); X removes; right reaches Update, the pin and ✕. */
@Composable
private fun InstalledItemRow(
    row: InstalledRow,
    busy: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onUpdate: () -> Unit,
    onPin: () -> Unit,
) {
    val item = row.item
    val glyph = when (item.kind) {
        ContentKind.Mods -> PixelGlyph.Ore
        ContentKind.DataPacks -> PixelGlyph.Paper
        else -> PixelGlyph.Book
    }
    BlockRow(
        item.name,
        Modifier.controllerActions(ControllerAction(Pad.X, stringResource(R.string.mods_remove), onRemove)),
        dimmed = !item.enabled,
        leading = { ModIcon(null, 32.dp, glyph = glyph) },
        subtitleContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val version = row.mod?.version ?: item.version
                    if (version.isNotBlank()) CofreText(VersionLabel.clean(version), CofreTheme.type.caption, color = CofreTheme.colors.muted, maxLines = 1)
                    if (!item.enabled) Badge(stringResource(R.string.mods_off))
                    if (row.pinned) Badge(stringResource(R.string.mods_pinned))
                    if (item.inUse) Badge(stringResource(R.string.mods_in_use), tone = Tone.Accent)
                    row.issue?.let { Badge(it.badge, tone = it.tone) }
                    row.update?.let { Badge(stringResource(R.string.mods_update_badge, VersionLabel.clean(it.version)), tone = Tone.Warn) }
                }
                row.issue?.let { CofreText(it.reason, CofreTheme.type.caption, color = CofreTheme.colors.tone(it.tone), maxLines = 2) }
            }
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.update != null) BlockButton(stringResource(R.string.mods_update), onUpdate, size = ButtonSize.Small, loading = busy)
                if (row.pinnable) {
                    IconButton(
                        PixelGlyph.Key,
                        stringResource(if (row.pinned) R.string.mods_unpin_named else R.string.mods_pin_named, item.name),
                        onPin,
                    )
                }
                BlockSwitch(item.enabled, { onToggle() })
                IconButton(PixelGlyph.Cross, stringResource(R.string.mods_remove_named, item.name), onRemove)
            }
        },
    )
}

/** "Update FerriteCore?": the folder is shared, so change it for everyone or copy it for this instance first. */
@Composable
internal fun SharedSheet(prompt: SharedPrompt, onBoth: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    BlockSheet(onDismiss, hugsContent = true) { SharedContent(prompt, onBoth, onCopy, onDismiss) }
}

@Composable
internal fun SharedContent(prompt: SharedPrompt, onBoth: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val verb = stringResource(
        when (prompt.kind) {
            SharedChange.Update, SharedChange.UpdateAll -> R.string.mods_shared_update
            SharedChange.Remove -> R.string.mods_shared_remove
            SharedChange.TurnOff -> R.string.mods_shared_turn_off
            SharedChange.TurnOn -> R.string.mods_shared_turn_on
        },
    )
    val title = if (prompt.kind == SharedChange.UpdateAll) stringResource(R.string.mods_shared_update_all_title, prompt.item)
    else stringResource(R.string.mods_shared_title, verb, prompt.item)
    val first = rememberFirstFocus()
    Column(modifier) {
        SheetHeader(title, onDismiss)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CofreText(stringResource(R.string.mods_shared_body, prompt.folder, prompt.others.joinToString()), CofreTheme.type.body)
            BlockButton(
                if (prompt.others.size == 1) stringResource(R.string.mods_shared_both, verb) else stringResource(R.string.mods_shared_all, verb, prompt.others.size + 1),
                onBoth,
                Modifier.focusRequester(first),
                role = ButtonRole.Primary,
            )
            BlockButton(stringResource(R.string.mods_shared_copy, prompt.instance.name), onCopy)
        }
    }
}

/** Remove something others need: who breaks, and which dependencies only it used (on by default). */
@Composable
internal fun RemovalSheet(
    prompt: RemovalPrompt,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BlockSheet(onDismiss, modifier, hugsContent = true) {
        RemovalContent(prompt, onConfirm, onDismiss)
    }
}

@Composable
internal fun RemovalContent(
    prompt: RemovalPrompt,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember(prompt) { mutableStateOf(prompt.extras.map { it.key }.toSet()) }
    Column(modifier) {
        SheetHeader(stringResource(R.string.mods_remove_question, prompt.row.item.name), onDismiss)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (prompt.dependents.isNotEmpty()) {
                Banner(stringResource(R.string.mods_dependents, prompt.dependents.joinToString()), PixelGlyph.Ore, tone = Tone.Warn)
            }
            if (prompt.extras.isNotEmpty()) {
                SectionLabel(stringResource(R.string.mods_also_remove, prompt.row.item.name))
                prompt.extras.forEach { extra ->
                    ToggleRow(extra.item.name, extra.key in picked, { on -> picked = if (on) picked + extra.key else picked - extra.key })
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockButton(stringResource(R.string.mods_keep), onDismiss, Modifier.weight(1f))
                BlockButton(stringResource(R.string.mods_remove), { onConfirm(picked) }, Modifier.weight(1f), role = ButtonRole.Danger)
            }
        }
    }
}
