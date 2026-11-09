package com.ignacio.cofre.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.BlockSwitch
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
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
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.WidthClass
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.design.cycle
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.kit.common.ContentKind
import com.ignacio.cofre.kit.mods.VersionLabel

/** M04 wired to its model. */
@Composable
internal fun InstalledRoute(
    installed: InstalledModel,
    instanceName: String,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by installed.state.collectAsStateWithLifecycle()
    InstalledPane(
        state, instanceName,
        onTab = installed::select,
        onToggle = installed::toggle,
        onRemove = installed::remove,
        onUpdate = installed::update,
        onUpdateAll = installed::updateAll,
        onUndo = installed::undo,
        onDismiss = installed::dismissMessage,
        onBrowse = onBrowse,
        modifier = modifier,
    )
}

/** M04 Installed: Mods · Packs · Data tabs, on/off, update one or all, remove with undo. L1/R1 switch tabs. */
@Composable
internal fun InstalledPane(
    state: InstalledState,
    instanceName: String,
    onTab: (InstalledTab) -> Unit,
    onToggle: (InstalledRow) -> Unit,
    onRemove: (InstalledRow) -> Unit,
    onUpdate: (InstalledRow) -> Unit,
    onUpdateAll: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = CofreTheme.widthClass == WidthClass.Compact
    val margin = if (compact) 16.dp else 20.dp
    val tabs = InstalledTab.entries
    Column(
        modifier
            .fillMaxSize()
            .controllerActions(
                ControllerAction(Pad.L1, stringResource(R.string.mods_tab_previous)) { onTab(tabs.cycle(state.tab, -1)) },
                ControllerAction(Pad.R1, stringResource(R.string.mods_tab_next)) { onTab(tabs.cycle(state.tab, 1)) },
            ),
    ) {
        ColumnHeader(stringResource(R.string.mods_installed)) { Badge(instanceName, tone = Tone.NeoForge) }
        Column(Modifier.padding(horizontal = margin).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(
                selection = state.tab,
                onSelect = onTab,
                options = listOf(
                    InstalledTab.Mods to stringResource(R.string.mods_kind_mods),
                    InstalledTab.Packs to stringResource(R.string.mods_tab_packs),
                    InstalledTab.Data to stringResource(R.string.mods_kind_data),
                ),
            )
            if (state.updates > 0) {
                BlockButton(stringResource(R.string.mods_update_all, state.updates), onUpdateAll, role = ButtonRole.Primary, loading = state.busy.isNotEmpty())
            }
            state.undo?.let { undo ->
                Banner(undo.text, PixelGlyph.Check, tone = Tone.Success) {
                    BlockButton(stringResource(R.string.mods_undo), onUndo, size = ButtonSize.Small)
                }
            }
            state.message?.let { message ->
                Banner(message, PixelGlyph.Cross, tone = Tone.Danger) {
                    BlockButton(stringResource(R.string.mods_ok), onDismiss, size = ButtonSize.Small)
                }
            }
        }
        when {
            state.loading -> BlockRow(stringResource(R.string.mods_loading_page), Modifier.padding(horizontal = margin), leading = { Spinner() })
            state.rows.isEmpty() -> EmptyState(
                stringResource(if (state.tab == InstalledTab.Mods) R.string.mods_none_yet else R.string.mods_nothing_yet),
                stringResource(if (state.tab == InstalledTab.Data) R.string.mods_data_hint else R.string.mods_get_hint),
                PixelGlyph.Chest,
            ) { if (compact) BlockButton(stringResource(R.string.mods_get_mods), onBrowse, role = ButtonRole.Primary) }
            else -> InstalledList(state, margin, onToggle, onRemove, onUpdate, Modifier.weight(1f))
        }
    }
}

@Composable
private fun InstalledList(
    state: InstalledState,
    margin: androidx.compose.ui.unit.Dp,
    onToggle: (InstalledRow) -> Unit,
    onRemove: (InstalledRow) -> Unit,
    onUpdate: (InstalledRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    // Data packs live in each world: one section per world.
    val groups = state.rows.groupBy { it.item.world }
    LazyColumn(
        modifier.fillMaxSize().pageScroll(list),
        state = list,
        contentPadding = PaddingValues(start = margin, end = margin, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        groups.forEach { (world, rows) ->
            if (world != null) item(key = "world:$world") { SectionLabel(world) }
            items(rows, key = { it.key }) { row ->
                InstalledItemRow(row, busy = row.key in state.busy, onToggle = { onToggle(row) }, onRemove = { onRemove(row) }, onUpdate = { onUpdate(row) })
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

/** One installed file. Focus lands on its switch (A turns it on or off); X removes; right reaches Update and ✕. */
@Composable
private fun InstalledItemRow(
    row: InstalledRow,
    busy: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onUpdate: () -> Unit,
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
                    if (item.inUse) Badge(stringResource(R.string.mods_in_use), tone = Tone.Accent)
                    row.issue?.let { Badge(it.badge, tone = it.tone) }
                    row.update?.let { Badge(stringResource(R.string.mods_update_badge, VersionLabel.clean(it.version)), tone = Tone.Warn) }
                }
                row.issue?.let { CofreText(it.reason, CofreTheme.type.caption, color = CofreTheme.colors.tone(it.tone), maxLines = 2) }
            }
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BlockSwitch(item.enabled, { onToggle() })
                if (row.update != null) BlockButton(stringResource(R.string.mods_update), onUpdate, size = ButtonSize.Small, loading = busy)
                IconButton(PixelGlyph.Cross, stringResource(R.string.mods_remove_named, item.name), onRemove)
            }
        },
    )
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
