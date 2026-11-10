package com.ignacio.cofre.ui.folders

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ignacio.cofre.R
import com.ignacio.cofre.design.AdaptiveListDetail
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.EmptyState
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PaneScroll
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.Segmented
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Stat
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.modfolders.ConvertTarget
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.modfolders.ModFolderStore
import com.ignacio.cofre.ui.common.Fmt

/** What the overview's detail does. */
class FolderDetailActions(
    val onOpen: (String) -> Unit = {},
    val onOpenInstance: (String) -> Unit = {},
    val onLink: () -> Unit = {},
    val onAutoUpdate: (com.ignacio.cofre.kit.modfolders.AutoUpdate) -> Unit = {},
    val onIncludeBeta: (Boolean) -> Unit = {},
    val onRestore: (com.ignacio.cofre.kit.mods.Snapshots.Snapshot) -> Unit = {},
    val onDuplicate: () -> Unit = {},
    val onConvert: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/**
 * Mod folders: every folder (unused and can't-play-yet ones too) on the left, the picked one's detail on the right.
 * X makes a new folder. Rename, Delete, Link and New folder open as sheets; each sends focus back where it was.
 */
@Composable
fun FoldersScreen(
    model: FoldersModel,
    onOpen: (String) -> Unit,
    onConvert: (String, ConvertTarget?) -> Unit,
    onOpenInstance: (String) -> Unit,
    onPickPack: () -> Unit,
    instances: List<Instance>,
    builds: List<String>,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val versions by model.versions.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var linking by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-modrinth-modpack+zip")) { uri ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) model.export(id, uri)
    }
    LaunchedEffect(Unit) { model.reload() }

    FoldersContent(
        state,
        onSelect = model::select,
        onNew = { creating = true },
        onRestoreDeleted = model::restoreDeleted,
        onDismissMessage = model::dismissMessage,
        actions = { card ->
            FolderDetailActions(
                onOpen = onOpen,
                onOpenInstance = onOpenInstance,
                onLink = { linking = card.id },
                onAutoUpdate = { model.autoUpdate(card.id, it) },
                onIncludeBeta = { model.includeBeta(card.id, it) },
                onRestore = { model.restoreSnapshot(card.id, it) },
                onDuplicate = { model.duplicate(card.id) },
                onConvert = { onConvert(card.id, null) },
                onExport = { exporting = card.id; export.launch("${card.folder.name}.mrpack") },
                onRename = { renaming = card.id },
                onDelete = { deleting = card.id },
            )
        },
        modifier = modifier,
    )

    val cards = state.cards
    if (creating) {
        LaunchedEffect(Unit) { model.loadVersions() }
        NewFolderSheet(
            instances = instances,
            folders = cards.map { it.folder },
            versions = versions,
            builds = builds,
            suggestName = { base -> uniqueName(base, cards.map { it.folder.name }) },
            onCreate = { request -> creating = false; model.create(request) },
            onConvert = { source, target -> creating = false; onConvert(source, target) },
            onPickPack = { creating = false; onPickPack() },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { id ->
        val card = cards.firstOrNull { it.id == id }
        if (card == null) renaming = null
        else RenameSheet(card.folder.name, onSave = { name -> renaming = null; model.rename(id, name) }, onDismiss = { renaming = null })
    }
    deleting?.let { id ->
        val card = cards.firstOrNull { it.id == id }
        if (card == null) deleting = null
        else DeleteSheet(
            card,
            targets = cards.map { it.folder }.filter { it.id != id && it.playable && it.gameVersion == card.folder.gameVersion && it.loader == card.folder.loader },
            onDelete = { moveTo -> deleting = null; model.delete(id, moveTo) },
            onDismiss = { deleting = null },
        )
    }
    linking?.let { id ->
        val card = cards.firstOrNull { it.id == id }
        if (card == null) linking = null
        else LinkSheet(
            card,
            candidates = instances.filter { card.folder.fits(it) && it.modFolder != id },
            onLink = { instance -> linking = null; model.link(id, instance) },
            onDismiss = { linking = null },
        )
    }
}

/** The overview's two panes, drawn from [state] (screenshots use this directly). */
@Composable
fun FoldersContent(
    state: FoldersState,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRestoreDeleted: (ModFolderStore.TrashToken) -> Unit,
    onDismissMessage: () -> Unit,
    actions: (FolderCard) -> FolderDetailActions,
    modifier: Modifier = Modifier,
) {
    AdaptiveListDetail(
        selection = state.selected,
        modifier = modifier.controllerActions(ControllerAction(Pad.X, stringResource(R.string.folders_new_short), onNew)),
        list = { FoldersList(state, onSelect, onNew, onRestoreDeleted, onDismissMessage) },
        detail = {
            val card = state.current
            if (card != null) FolderDetail(card, state.snapshots, state.busy, actions(card))
        },
        placeholder = {
            if (state.loading) BlockRow(stringResource(R.string.folders_loading), Modifier.padding(20.dp), leading = { Spinner() })
            else EmptyState(stringResource(R.string.folders_none), stringResource(R.string.folders_none_message), PixelGlyph.Chest) {
                BlockButton(stringResource(R.string.folders_new), onNew, role = ButtonRole.Primary)
            }
        },
    )
}

@Composable
private fun FoldersList(
    state: FoldersState,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRestoreDeleted: (ModFolderStore.TrashToken) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val context = LocalContext.current
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        ColumnHeader(stringResource(R.string.folders_root_title)) {
            BlockButton(stringResource(R.string.folders_new), onNew, size = ButtonSize.Small, role = ButtonRole.Primary)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().pageScroll(list),
            state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.cards.isNotEmpty()) {
                item(key = "total") {
                    CofreText(stringResource(R.string.folders_total, state.cards.size, Fmt.bytes(context, state.bytes)), CofreTheme.type.label, color = CofreTheme.colors.muted, maxLines = 1)
                }
            }
            state.undo?.let { undo ->
                item(key = "undo") {
                    Banner(stringResource(R.string.folders_deleted, undo.folder.name), PixelGlyph.Check, tone = Tone.Success) {
                        BlockButton(stringResource(R.string.folders_undo), { onRestoreDeleted(undo.token) }, size = ButtonSize.Small)
                    }
                }
            }
            state.message?.let { message ->
                item(key = "message") {
                    Banner(message, PixelGlyph.Paper, tone = Tone.Accent) {
                        BlockButton(stringResource(R.string.folders_ok), onDismissMessage, size = ButtonSize.Small)
                    }
                }
            }
            items(state.cards, key = { it.id }) { card ->
                FolderCardRow(card, selected = card.id == state.selected, onClick = { onSelect(card.id) })
            }
            val bin = state.deleted.filter { it.token != state.undo?.token }
            if (bin.isNotEmpty()) {
                item(key = "bin") { SectionLabel(stringResource(R.string.folders_recently_deleted), Modifier.padding(top = 8.dp)) }
                items(bin, key = { "bin:${it.token.folder}" }) { deleted ->
                    BlockRow(
                        deleted.folder.name,
                        dimmed = true,
                        subtitle = deleted.folder.label,
                        leading = { FolderIcon(deleted.folder, 32.dp) },
                        trailing = { BlockButton(stringResource(R.string.folders_restore), { onRestoreDeleted(deleted.token) }, size = ButtonSize.Small) },
                    )
                }
            }
        }
    }
}

/** One folder: icon, name, badges and "42 mods · 412 MB · Survival+, Creative test". */
@Composable
fun FolderCardRow(card: FolderCard, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val line = buildList {
        add(context.resources.getQuantityString(R.plurals.folders_mods, card.mods, card.mods))
        add(Fmt.bytes(context, card.bytes))
        if (card.usedBy.isNotEmpty()) add(card.usedBy.joinToString { it.name })
    }.joinToString(" · ")
    BlockRow(
        card.folder.name,
        modifier,
        selected = selected,
        dimmed = card.usedBy.isEmpty() && card.folder.playable,
        onClick = onClick,
        leading = { FolderIcon(card.folder, 40.dp) },
        subtitleContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FolderBadges(card.folder, card.usedBy.size, autoUpdate = true)
                CofreText(line, CofreTheme.type.caption, color = CofreTheme.colors.muted, maxLines = 1)
            }
        },
    )
}

/** Mods, Packs, Size; Used by (+ Link); Updates; Snapshots; then what you can do with the folder. */
@Composable
fun FolderDetail(card: FolderCard, snapshots: List<SnapshotRow>, busy: Boolean, actions: FolderDetailActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val folder = card.folder
    Column(modifier.fillMaxSize()) {
        ColumnHeader(folder.name) { FolderBadges(folder, card.usedBy.size) }
        PaneScroll {
            made(folder)?.let { CofreText(it, CofreTheme.type.caption, color = CofreTheme.colors.muted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${card.mods}", stringResource(R.string.folders_stat_mods), Modifier.weight(1f))
                Stat("${card.packs}", stringResource(R.string.folders_stat_packs), Modifier.weight(1f))
                Stat(Fmt.bytes(context, card.bytes), stringResource(R.string.folders_stat_size), Modifier.weight(1f))
            }
            BlockButton(stringResource(R.string.folders_open_in_mods), { actions.onOpen(folder.id) }, role = ButtonRole.Primary, icon = PixelGlyph.Book)
            if (!folder.playable) Banner(stringResource(R.string.folders_cant_play_detail, folder.label), PixelGlyph.Bolt, tone = Tone.Warn)

            SectionLabel(stringResource(R.string.folders_used_by))
            if (card.usedBy.isEmpty()) CofreText(stringResource(R.string.folders_used_by_none), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
            card.usedBy.forEach { user ->
                BlockRow(
                    user.name,
                    subtitle = Fmt.ago(user.lastPlayedAt)?.let { stringResource(R.string.folders_played, it) } ?: stringResource(R.string.folders_never_played),
                    leading = { com.ignacio.cofre.design.PixelIcon(PixelGlyph.Grass, size = 24.dp) },
                    trailing = { BlockButton(stringResource(R.string.folders_open), { actions.onOpenInstance(user.id) }, size = ButtonSize.Small) },
                )
            }
            if (folder.playable) BlockButton(stringResource(R.string.folders_link), actions.onLink)

            SectionLabel(stringResource(R.string.folders_updates))
            Segmented(folder.autoUpdate, actions.onAutoUpdate, autoUpdateOptions())
            CofreText(stringResource(R.string.folders_updates_note), CofreTheme.type.caption, color = CofreTheme.colors.muted)
            ToggleRow(stringResource(R.string.folders_include_beta), folder.includeBeta, actions.onIncludeBeta)

            SectionLabel(stringResource(R.string.folders_snapshots))
            if (snapshots.isEmpty()) CofreText(stringResource(R.string.folders_snapshots_none), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
            snapshots.forEach { row -> SnapshotItem(row, { actions.onRestore(row.snapshot) }) }

            SectionLabel(stringResource(R.string.folders_folder))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockButton(stringResource(R.string.folders_duplicate), actions.onDuplicate, Modifier.weight(1f), icon = PixelGlyph.Chest, loading = busy)
                BlockButton(stringResource(R.string.folders_convert), actions.onConvert, Modifier.weight(1f), icon = PixelGlyph.Bolt)
            }
            BlockButton(stringResource(R.string.folders_export), actions.onExport, icon = PixelGlyph.Paper, loading = busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockButton(stringResource(R.string.folders_rename), actions.onRename, Modifier.weight(1f))
                BlockButton(stringResource(R.string.folders_delete), actions.onDelete, Modifier.weight(1f), role = ButtonRole.Danger)
            }
        }
    }
}

/** "Made from Survival+ · Nov 2", when Cofre knows. */
@Composable
private fun made(folder: ModFolder): String? {
    val context = LocalContext.current
    val date = folder.createdAt?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) }
    return date?.let { stringResource(R.string.folders_made, it) }
}

@Composable
private fun RenameSheet(name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val field = rememberTextFieldState(name)
    val first = rememberFirstFocus()
    BlockSheet(onDismiss, hugsContent = true) {
        SheetHeader(stringResource(R.string.folders_rename_title), onDismiss)
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(field, stringResource(R.string.folders_name), Modifier.focusRequester(first))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockButton(stringResource(R.string.folders_cancel), onDismiss, Modifier.weight(1f))
                BlockButton(stringResource(R.string.folders_save), { onSave(field.text.toString()) }, Modifier.weight(1f), role = ButtonRole.Primary, enabled = field.text.isNotBlank())
            }
        }
    }
}

/** "Delete Survival mods?": who uses it, where they move (or play without mods), and that it goes to Recently deleted. */
@Composable
fun DeleteSheet(card: FolderCard, targets: List<ModFolder>, onDelete: (String?) -> Unit, onDismiss: () -> Unit) {
    BlockSheet(onDismiss, hugsContent = true) { DeleteContent(card, targets, onDelete, onDismiss) }
}

@Composable
fun DeleteContent(card: FolderCard, targets: List<ModFolder>, onDelete: (String?) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var moveTo by rememberSaveable { mutableStateOf("") }
    val first = rememberFirstFocus()
    val users = card.usedBy.size
    Column(modifier) {
        SheetHeader(stringResource(R.string.folders_delete_title, card.folder.name), onDismiss)
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (users > 0) {
                CofreText(LocalContext.current.resources.getQuantityString(R.plurals.folders_delete_used, users, users), CofreTheme.type.body)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CofreText(stringResource(R.string.folders_move_to), CofreTheme.type.label, color = CofreTheme.colors.muted)
                    SelectButton(
                        stringResource(R.string.folders_move_to),
                        moveTo,
                        { moveTo = it },
                        listOf("" to stringResource(R.string.folders_no_mods)) + targets.map { it.id to it.name },
                        Modifier.weight(1f),
                    )
                }
            }
            CofreText(stringResource(R.string.folders_delete_note), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockButton(stringResource(R.string.folders_cancel), onDismiss, Modifier.weight(1f).focusRequester(first))
                BlockButton(stringResource(R.string.folders_delete_folder), { onDelete(moveTo.ifEmpty { null }) }, Modifier.weight(1f), role = ButtonRole.Danger)
            }
        }
    }
}

/** "+ Link to an instance": instances on the folder's version and loader that don't use it yet. */
@Composable
private fun LinkSheet(card: FolderCard, candidates: List<Instance>, onLink: (String) -> Unit, onDismiss: () -> Unit) {
    val first = rememberFirstFocus()
    BlockSheet(onDismiss, hugsContent = true) {
        SheetHeader(stringResource(R.string.folders_link_title, card.folder.name), onDismiss)
        Column(
            Modifier.padding(horizontal = 16.dp).padding(top = 10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (candidates.isEmpty()) {
                CofreText(stringResource(R.string.folders_link_none, card.folder.label), CofreTheme.type.body, color = CofreTheme.colors.muted)
                BlockButton(stringResource(R.string.folders_ok), onDismiss, Modifier.focusRequester(first))
            }
            candidates.forEachIndexed { i, instance ->
                BlockRow(
                    instance.name,
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                    subtitle = stringResource(R.string.folders_link_keeps),
                    onClick = { onLink(instance.id) },
                    leading = { com.ignacio.cofre.design.PixelIcon(PixelGlyph.Grass, size = 24.dp) },
                )
            }
        }
    }
}

/** [base], or "[base] 2", "[base] 3"… whichever no folder has. */
fun uniqueName(base: String, taken: List<String>): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
