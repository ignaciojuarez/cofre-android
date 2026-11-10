package com.ignacio.cofre.ui.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.Radio
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.modfolders.ConvertTarget
import com.ignacio.cofre.ui.common.Note

/** Every mod folder with its users; reloads when any instance changes (a link, a rename). */
@Composable
internal fun rememberFolders(graph: AppGraph): List<Library.FolderInfo>? {
    val library = graph.library
    val folders by produceState<List<Library.FolderInfo>?>(null, library.instances) {
        value = runCatching { library.folders() }.getOrDefault(emptyList())
    }
    return folders
}

/** "42 mods · also used by Creative test": [instance]'s view of [info]. */
@Composable
internal fun usage(info: Library.FolderInfo, instance: Instance?): String {
    val others = info.users.filter { it.id != instance?.id }.joinToString(", ") { it.name }
    val linked = instance != null && info.users.any { it.id == instance.id }
    return listOfNotNull(
        pluralStringResource(R.plurals.folders_mods, info.mods, info.mods),
        when {
            linked && others.isNotEmpty() -> stringResource(R.string.link_shared_with, others)
            linked -> stringResource(R.string.link_current)
            others.isNotEmpty() -> stringResource(R.string.link_used_by, others)
            else -> stringResource(R.string.link_not_used)
        },
    ).joinToString(" · ")
}

/** Overview's "Mod folder" block: the linked folder, Change, Open, and a private copy when it's shared. */
@Composable
internal fun FolderBlock(
    graph: AppGraph,
    instance: Instance,
    folders: List<Library.FolderInfo>?,
    onChange: () -> Unit,
) {
    val running = remember(instance) { graph.launcher.gameRunning }
    val info = folders?.firstOrNull { it.folder.id == instance.modFolder }
    val loader = "${instance.loader.displayName} ${instance.gameVersion}"
    val change = @Composable {
        BlockButton(stringResource(R.string.link_change), onChange, Modifier.testTag("instance.folder.change"), size = ButtonSize.Small, enabled = !running)
    }
    when {
        instance.modFolder == null -> BlockRow(
            stringResource(R.string.link_no_mods),
            subtitle = stringResource(R.string.link_no_mods_note, loader),
            leading = { PixelIcon(PixelGlyph.Stone, size = 32.dp) },
            trailing = change,
        )
        folders == null -> BlockRow(stringResource(R.string.link_loading), leading = { Spinner() })
        info == null -> BlockRow(
            stringResource(R.string.link_folder_gone),
            subtitle = stringResource(R.string.link_folder_gone_note),
            leading = { PixelIcon(PixelGlyph.Cross, size = 32.dp) },
            trailing = change,
        )
        else -> {
            val others = info.users.filter { it.id != instance.id }
            val subtitle = listOfNotNull(
                "${info.folder.loader.displayName} ${info.folder.gameVersion}",
                pluralStringResource(R.plurals.folders_mods, info.mods, info.mods),
                others.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.link_also_used_by, it.joinToString(", ") { u -> u.name }) },
            ).joinToString(" · ")
            BlockRow(
                info.folder.name,
                Modifier.testTag("instance.folder"),
                subtitle = subtitle,
                big = true,
                leading = { PixelIcon(PixelGlyph.Chest, size = 36.dp) },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        change()
                        BlockButton(stringResource(R.string.link_open), { graph.nav.openFolder(info.folder.id) }, size = ButtonSize.Small)
                    }
                },
            )
            if (others.isNotEmpty()) {
                BlockButton(
                    stringResource(R.string.link_copy_for, instance.name),
                    { graph.library.copyFolderFor(instance.id) },
                    Modifier.testTag("instance.folder.copy"),
                    icon = PixelGlyph.Chest,
                    enabled = !running && !graph.library.busy,
                )
            }
        }
    }
}

/** Sentinel for "No mods" in the picker. */
private const val NO_MODS = ""

/**
 * Picks [instance]'s mod folder: fitting, playable folders (and "No mods") can be chosen; the rest show why not and
 * offer Convert a copy. Disabled while the game runs.
 */
@Composable
internal fun FolderPickerSheet(
    graph: AppGraph,
    instance: Instance,
    onDismiss: () -> Unit,
) {
    val library = graph.library
    val folders = rememberFolders(graph)
    val running = remember { graph.launcher.gameRunning }
    var choice by rememberSaveable(instance.id) { mutableStateOf(instance.modFolder ?: NO_MODS) }
    val (fit, misfit) = folders.orEmpty().partition { it.folder.fits(instance) && it.folder.playable }
    val chosenName = if (choice == NO_MODS) stringResource(R.string.link_no_mods) else fit.firstOrNull { it.folder.id == choice }?.folder?.name
    val first = remember { FocusRequester() }
    LaunchedEffect(folders != null) {
        library.clearError()
        if (folders == null) return@LaunchedEffect
        repeat(2) { withFrameNanos {} }
        runCatching { first.requestFocus() }
    }
    val scroll = rememberScrollState()
    BlockSheet(onDismiss, Modifier.testTag("folderPicker")) {
        SheetHeader(stringResource(R.string.link_sheet_title, instance.name), onDismiss)
        Column(
            Modifier.weight(1f, fill = false).pageScroll(scroll).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (running) Banner(stringResource(R.string.link_running), PixelGlyph.Cross, tone = Tone.Warn)
            library.error?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) }
            if (folders == null) {
                BlockRow(stringResource(R.string.link_loading), leading = { Spinner() })
                return@Column
            }
            SectionLabel(stringResource(R.string.link_fits, instance.loader.displayName, instance.gameVersion))
            val focusId = choice.takeIf { c -> c == NO_MODS || fit.any { it.folder.id == c } } ?: NO_MODS
            fit.forEach { info ->
                Option(
                    info.folder.name, usage(info, instance), PixelGlyph.Chest, choice == info.folder.id,
                    Modifier.testTag("folderPicker.${info.folder.name}").then(if (info.folder.id == focusId) Modifier.focusRequester(first) else Modifier),
                ) { choice = info.folder.id }
            }
            Option(
                stringResource(R.string.link_no_mods),
                stringResource(R.string.link_no_mods_note, instance.loader.displayName),
                PixelGlyph.Stone,
                choice == NO_MODS,
                if (focusId == NO_MODS) Modifier.focusRequester(first) else Modifier,
            ) { choice = NO_MODS }
            if (misfit.isNotEmpty()) {
                SectionLabel(stringResource(R.string.link_doesnt_fit), Modifier.padding(top = 6.dp))
                misfit.forEach { info ->
                    val f = info.folder
                    val reason = when {
                        f.loader != instance.loader -> stringResource(R.string.link_other_loader, f.loader.displayName, f.gameVersion, instance.loader.displayName)
                        f.gameVersion != instance.gameVersion -> stringResource(R.string.link_other_version, f.gameVersion)
                        else -> stringResource(R.string.link_cant_play, f.loader.displayName, f.gameVersion)
                    }
                    BlockRow(
                        f.name,
                        subtitle = reason,
                        leading = { PixelIcon(PixelGlyph.Chest, size = 30.dp) },
                        trailing = {
                            BlockButton(stringResource(R.string.link_convert), {
                                graph.nav.convert(f.id, ConvertTarget(instance.gameVersion, instance.loader))
                                onDismiss()
                            }, size = ButtonSize.Small)
                        },
                    )
                }
            }
            BlockButton(
                "+ " + stringResource(R.string.link_new_folder),
                { library.newFolderFor(instance.id) { onDismiss() } },
                Modifier.testTag("folderPicker.new"),
                enabled = !running && !library.busy,
            )
            Note(stringResource(R.string.link_note, instance.name))
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlockButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
            BlockButton(
                stringResource(R.string.link_use, chosenName ?: stringResource(R.string.link_no_mods)),
                { library.linkFolder(instance.id, choice.takeIf { it != NO_MODS }) { onDismiss() } },
                Modifier.weight(1f).testTag("folderPicker.use"),
                role = ButtonRole.Primary,
                enabled = !running && !library.busy && chosenName != null && choice != (instance.modFolder ?: NO_MODS),
                loading = library.busy,
            )
        }
    }
}

/** A radio row: picks without closing. */
@Composable
internal fun Option(
    title: String,
    subtitle: String,
    icon: PixelGlyph,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    BlockRow(
        title,
        modifier,
        subtitle = subtitle,
        selected = selected,
        onClick = onClick,
        leading = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Radio(selected)
                PixelIcon(icon, size = 30.dp)
            }
        },
    )
}

/**
 * "Delete Survival+?": the instance goes to Recently deleted; its folder stays. When nothing else uses the folder,
 * an optional switch deletes it too.
 */
@Composable
internal fun DeleteInstanceSheet(
    graph: AppGraph,
    instance: Instance,
    onDismiss: () -> Unit,
) {
    val folders = rememberFolders(graph)
    val info = folders?.firstOrNull { it.folder.id == instance.modFolder }
    val others = info?.users.orEmpty().filter { it.id != instance.id }
    var alsoFolder by rememberSaveable(instance.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    BlockSheet(onDismiss, Modifier.testTag("deleteInstance"), hugsContent = true) {
        SheetHeader(stringResource(R.string.delete_title, instance.name), onDismiss)
        Column(
            Modifier.weight(1f, fill = false).pageScroll(scroll).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CofreText(stringResource(R.string.delete_body), CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
            if (info != null) {
                val name = info.folder.name
                Banner(
                    if (others.isEmpty()) stringResource(R.string.delete_folder_stays_alone, name)
                    else stringResource(R.string.delete_folder_stays, name, others.joinToString(", ") { it.name }),
                    PixelGlyph.Chest,
                    tone = Tone.Accent,
                )
                if (others.isEmpty()) {
                    ToggleRow(
                        stringResource(R.string.delete_also_folder, name),
                        alsoFolder,
                        { alsoFolder = it },
                        Modifier.testTag("deleteInstance.folder"),
                        subtitle = stringResource(R.string.delete_also_folder_note),
                    )
                }
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlockButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
            BlockButton(
                stringResource(R.string.delete_confirm),
                {
                    graph.library.trash(instance.id, alsoFolder = alsoFolder && info != null && others.isEmpty())
                    onDismiss()
                },
                Modifier.weight(1f).testTag("deleteInstance.confirm"),
                role = ButtonRole.Danger,
            )
        }
    }
}
