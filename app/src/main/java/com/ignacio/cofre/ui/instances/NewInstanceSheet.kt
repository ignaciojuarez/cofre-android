package com.ignacio.cofre.ui.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.AppGraph
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SelectButton
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.ToggleRow
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.pageScroll
import com.ignacio.cofre.ui.common.Note

/** New instance: a name, a NeoForge 26.2 build, and the handheld preset (on by default). Nothing else to pick. */
@Composable
fun NewInstanceSheet(
    graph: AppGraph,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = graph.library
    val preset = Library.handheld
    val defaultName = stringResource(R.string.new_default_name)
    val name = rememberTextFieldState(defaultName)
    var version by rememberSaveable { mutableStateOf(preset.loaderVersion) }
    var usePreset by rememberSaveable { mutableStateOf(true) }
    /** null: a new empty folder. */
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    val fitting = rememberFolders(graph).orEmpty().filter { it.folder.playable }
    LaunchedEffect(Unit) {
        library.clearError()
        library.loadNeoForgeVersions()
    }
    val scroll = rememberScrollState()
    BlockSheet(onDismiss, modifier.testTag("newInstance")) {
        SheetHeader(stringResource(R.string.new_title), onDismiss)
        Column(
            Modifier.pageScroll(scroll).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field(name, stringResource(R.string.instance_name), Modifier.testTag("newInstance.name"))
            SelectButton(stringResource(R.string.instance_neoforge), version, { version = it }, library.neoForgeVersions.map { it to it })
            ListSection(label = stringResource(R.string.new_folder)) {
                Option(
                    stringResource(R.string.new_folder_empty),
                    stringResource(R.string.new_folder_empty_note, name.text.toString().trim()),
                    PixelGlyph.Chest,
                    folderId == null,
                    Modifier.testTag("newInstance.folder.new"),
                ) { folderId = null }
                if (folderId == null) {
                    ToggleRow(
                        preset.name,
                        usePreset,
                        { usePreset = it },
                        Modifier.testTag("newInstance.preset"),
                        subtitle = stringResource(R.string.new_preset_note, preset.pitch, preset.pinnedMods.size),
                    )
                }
                fitting.forEach { info ->
                    Option(info.folder.name, usage(info, null), PixelGlyph.Chest, folderId == info.folder.id, Modifier.testTag("newInstance.folder.${info.folder.name}")) {
                        folderId = info.folder.id
                    }
                }
            }
            Note(stringResource(R.string.new_only_target))
            library.error?.let { Banner(it, PixelGlyph.Cross, tone = Tone.Danger) }
            BlockButton(
                stringResource(R.string.new_create),
                {
                    library.create(name.text.toString(), version, preset.takeIf { usePreset && folderId == null }, folderId) { made ->
                        graph.nav.instance = made.id
                        library.select(made.id)
                        onDismiss()
                    }
                },
                Modifier.testTag("newInstance.create"),
                role = ButtonRole.Primary,
                enabled = name.text.isNotBlank() && !library.busy,
                loading = library.busy,
            )
        }
    }
}
