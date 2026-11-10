package com.ignacio.cofre.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ControllerAction
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.Pad
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SectionLabel
import com.ignacio.cofre.design.Segmented
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.controllerActions
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.common.Target
import com.ignacio.cofre.kit.instances.Instance
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.modfolders.ConvertTarget
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.options.PresetCatalog

/**
 * New mod folder: a name; start from Empty, an instance, a folder or a .mrpack; the game version and loader (or
 * "Match an instance"); the loader build; options. Copying from another version or loader turns Create into "Review
 * conversion ›", which opens Convert on the source's folder. Start (≡) creates. Focus starts on the name.
 */
@Composable
fun NewFolderSheet(
    instances: List<Instance>,
    folders: List<ModFolder>,
    versions: List<String>,
    builds: List<String>,
    suggestName: (String) -> String,
    onCreate: (NewFolderRequest) -> Unit,
    onConvert: (String, ConvertTarget) -> Unit,
    onPickPack: () -> Unit,
    onDismiss: () -> Unit,
) {
    BlockSheet(onDismiss) {
        NewFolderContent(instances, folders, versions, builds, suggestName, onCreate, onConvert, onPickPack, onDismiss)
    }
}

@Composable
fun NewFolderContent(
    instances: List<Instance>,
    folders: List<ModFolder>,
    versions: List<String>,
    builds: List<String>,
    suggestName: (String) -> String,
    onCreate: (NewFolderRequest) -> Unit,
    onConvert: (String, ConvertTarget) -> Unit,
    onPickPack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialStart: FolderStart = if (instances.isNotEmpty()) FolderStart.Instance else FolderStart.Empty,
) {
    var start by rememberSaveable { mutableStateOf(initialStart) }
    var instanceId by rememberSaveable { mutableStateOf(instances.firstOrNull()?.id) }
    var folderId by rememberSaveable { mutableStateOf(folders.firstOrNull()?.id) }
    val first = instances.firstOrNull { it.id == instanceId }
    var game by rememberSaveable { mutableStateOf(if (start == FolderStart.Instance && first != null) first.gameVersion else Target.GAME_VERSION) }
    var loader by rememberSaveable { mutableStateOf(if (start == FolderStart.Instance && first != null) first.loader else Target.LOADER) }
    var build by rememberSaveable { mutableStateOf<String?>(if (start == FolderStart.Instance) first?.loaderVersion else null) }
    var autoUpdate by rememberSaveable { mutableStateOf(AutoUpdate.Notify) }
    var essentials by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf(true) }
    /** The instance "Match an instance" last picked, which "Link to … now" offers too. */
    var matched by rememberSaveable { mutableStateOf<String?>(null) }
    val name = rememberTextFieldState(suggestName(first?.takeIf { start == FolderStart.Instance }?.let { "${it.name} mods" } ?: "Mods"))
    val nameFocus = rememberFirstFocus()

    val sourceInstance = instances.firstOrNull { it.id == instanceId }?.takeIf { start == FolderStart.Instance }
    val sourceFolder = folders.firstOrNull { it.id == folderId }?.takeIf { start == FolderStart.Folder }
    fun matchTo(version: String, to: Loader, loaderVersion: String?) {
        game = version
        loader = to
        build = loaderVersion
    }
    val playable = game == Target.GAME_VERSION && loader == Target.LOADER
    // What a copy is converted from: the instance's own folder, or the picked one.
    val from: Pair<String, Pair<String, Loader>>? = when {
        sourceInstance != null -> sourceInstance.modFolder?.let { it to (sourceInstance.gameVersion to sourceInstance.loader) }
        sourceFolder != null -> sourceFolder.id to (sourceFolder.gameVersion to sourceFolder.loader)
        else -> null
    }
    val converts = from != null && from.second != (game to loader)
    val linkTarget = (sourceInstance ?: instances.firstOrNull { it.id == matched })
        ?.takeIf { playable && it.gameVersion == game && it.loader == loader }
    val canCreate = name.text.isNotBlank() && start != FolderStart.Mrpack &&
        (start != FolderStart.Instance || sourceInstance != null) && (start != FolderStart.Folder || sourceFolder != null)

    fun submit() {
        if (!canCreate) return
        if (converts) return onConvert(from.first, ConvertTarget(game, loader))
        onCreate(
            NewFolderRequest(
                name = name.text.toString().trim(),
                start = start,
                source = sourceInstance?.id ?: sourceFolder?.id,
                gameVersion = game,
                loader = loader,
                loaderVersion = build?.takeIf { playable },
                autoUpdate = autoUpdate,
                essentials = essentials && playable,
                linkTo = linkTarget?.id?.takeIf { link },
            ),
        )
    }

    Column(modifier.controllerActions(ControllerAction(Pad.Start, stringResource(R.string.folders_create), ::submit))) {
        SheetHeader(stringResource(R.string.folders_new_title), onDismiss)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Field(name, stringResource(R.string.folders_name), Modifier.focusRequester(nameFocus))

            SectionLabel(stringResource(R.string.folders_start_from))
            Segmented(
                start,
                { picked ->
                    start = picked
                    when (picked) {
                        FolderStart.Instance -> instances.firstOrNull { it.id == instanceId }?.let { matchTo(it.gameVersion, it.loader, it.loaderVersion) }
                        FolderStart.Folder -> folders.firstOrNull { it.id == folderId }?.let { matchTo(it.gameVersion, it.loader, it.loaderVersion) }
                        else -> Unit
                    }
                },
                listOf(
                    FolderStart.Empty to stringResource(R.string.folders_start_empty),
                    FolderStart.Instance to stringResource(R.string.folders_start_instance),
                    FolderStart.Folder to stringResource(R.string.folders_start_folder),
                    FolderStart.Mrpack to stringResource(R.string.folders_start_mrpack),
                ),
            )
            when (start) {
                FolderStart.Empty -> Note(stringResource(R.string.folders_start_empty_note))
                FolderStart.Instance -> {
                    if (instances.isEmpty()) Note(stringResource(R.string.folders_no_instances))
                    else {
                        SelectButton(
                            stringResource(R.string.folders_start_instance),
                            instanceId ?: "",
                            { id ->
                                instanceId = id
                                instances.firstOrNull { it.id == id }?.let { matchTo(it.gameVersion, it.loader, it.loaderVersion) }
                            },
                            instances.map { it.id to it.name },
                        )
                        Note(stringResource(R.string.folders_start_instance_note))
                    }
                }
                FolderStart.Folder -> {
                    if (folders.isEmpty()) Note(stringResource(R.string.folders_none))
                    else {
                        SelectButton(
                            stringResource(R.string.folders_start_folder),
                            folderId ?: "",
                            { id ->
                                folderId = id
                                folders.firstOrNull { it.id == id }?.let { matchTo(it.gameVersion, it.loader, it.loaderVersion); autoUpdate = it.autoUpdate }
                            },
                            folders.map { it.id to it.name },
                        )
                        Note(stringResource(R.string.folders_start_folder_note))
                    }
                }
                FolderStart.Mrpack -> {
                    BlockButton(stringResource(R.string.folders_choose_mrpack), onPickPack, icon = PixelGlyph.Paper)
                    Note(stringResource(R.string.folders_start_mrpack_note))
                }
            }

            if (start != FolderStart.Mrpack) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.folders_game_and_loader), Modifier.weight(1f))
                    if (instances.isNotEmpty()) {
                        SelectButton(
                            stringResource(R.string.folders_match_instance),
                            matched ?: "",
                            { id ->
                                matched = id
                                instances.firstOrNull { it.id == id }?.let { matchTo(it.gameVersion, it.loader, it.loaderVersion) }
                            },
                            listOf("" to stringResource(R.string.folders_match_instance)) + instances.map { it.id to it.name },
                            compact = true,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectButton(
                        stringResource(R.string.folders_game_version),
                        game,
                        { game = it; if (it != Target.GAME_VERSION) build = null },
                        (versions + game).distinct().map { it to it },
                        Modifier.width(150.dp),
                    )
                    Segmented(loader, { loader = it; if (it != Target.LOADER) build = null }, FolderLoaders.map { it to it.displayName }, Modifier.weight(1f))
                }
                if (playable) {
                    SelectButton(
                        stringResource(R.string.folders_loader_build),
                        build ?: "",
                        { build = it.ifEmpty { null } },
                        listOf("" to stringResource(R.string.folders_any_build, game, builds.firstOrNull() ?: "")) +
                            builds.map { it to stringResource(R.string.folders_build, loader.displayName, it) },
                    )
                } else {
                    Banner(
                        if (converts) stringResource(R.string.folders_not_playable_convert, loader.displayName, game)
                        else stringResource(R.string.folders_not_playable, loader.displayName, game),
                        PixelGlyph.Bolt,
                        tone = Tone.Warn,
                    )
                }
                if (converts && playable) Banner(stringResource(R.string.folders_converts), PixelGlyph.Bolt, tone = Tone.Accent)

                SectionLabel(stringResource(R.string.folders_options))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CofreText(stringResource(R.string.folders_auto_update), CofreTheme.type.rowTitle, Modifier.weight(1f), maxLines = 1)
                    Segmented(autoUpdate, { autoUpdate = it }, autoUpdateOptions(), Modifier.width(260.dp), small = true)
                }
                if (playable && !converts) {
                    val preset = remember { PresetCatalog.presets.first() }
                    ToggleRow(
                        stringResource(R.string.folders_essentials),
                        essentials,
                        { essentials = it },
                        subtitle = preset.pinnedMods.take(3).joinToString { it.slug } +
                            if (preset.pinnedMods.size > 3) " +${preset.pinnedMods.size - 3}" else "",
                    )
                }
                if (linkTarget != null && !converts) {
                    ToggleRow(stringResource(R.string.folders_link_now, linkTarget.name), link, { link = it }, subtitle = stringResource(R.string.folders_link_now_note))
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlockButton(stringResource(R.string.folders_cancel), onDismiss, Modifier.weight(1f))
            BlockButton(
                stringResource(if (converts) R.string.folders_review_conversion else R.string.folders_create),
                ::submit,
                Modifier.weight(1f),
                role = ButtonRole.Primary,
                enabled = canCreate,
            )
        }
    }
}

@Composable
private fun Note(text: String) = CofreText(text, CofreTheme.type.caption, color = CofreTheme.colors.muted)
