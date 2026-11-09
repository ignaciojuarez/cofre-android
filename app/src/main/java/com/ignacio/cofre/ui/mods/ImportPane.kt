package com.ignacio.cofre.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Banner
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.ButtonRole
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.ColumnHeader
import com.ignacio.cofre.design.Field
import com.ignacio.cofre.design.FieldKind
import com.ignacio.cofre.design.ListSection
import com.ignacio.cofre.design.PaneScroll
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.ProgressBar
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Stat
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.ValueRow

/** M03 wired to its model. */
@Composable
internal fun ImportRoute(
    import: ImportModel,
    onPickFile: () -> Unit,
    onOpenInstance: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase by import.phase.collectAsStateWithLifecycle()
    ImportPane(
        phase,
        onPickFile = onPickFile,
        onLink = import::openLink,
        onInstall = import::install,
        onReset = import::reset,
        onOpenInstance = onOpenInstance,
        modifier = modifier,
    )
}

/** M03 Import pack: from a file, a link or another app → what's inside → a new NeoForge 26.2 instance. */
@Composable
internal fun ImportPane(
    phase: ImportPhase,
    onPickFile: () -> Unit,
    onLink: (String) -> Unit,
    onInstall: () -> Unit,
    onReset: () -> Unit,
    onOpenInstance: (String) -> Unit,
    modifier: Modifier = Modifier,
    link: TextFieldState = rememberTextFieldState(),
) {
    Column(modifier.fillMaxSize()) {
        ColumnHeader(stringResource(R.string.mods_import_title))
        PaneScroll(readable = true) {
            when (phase) {
                ImportPhase.Idle -> {
                    ListSection {
                        ValueRow(stringResource(R.string.mods_import_file), ".mrpack · .zip", onPickFile)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field(link, stringResource(R.string.mods_import_link), Modifier.weight(1f), placeholder = stringResource(R.string.mods_import_link_hint), kind = FieldKind.Mono)
                        BlockButton(
                            stringResource(R.string.mods_import),
                            { onLink(link.text.toString()) },
                            role = ButtonRole.Primary,
                            fullWidth = false,
                            enabled = link.text.isNotBlank(),
                        )
                    }
                    CofreText(stringResource(R.string.mods_import_share_hint), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
                }
                is ImportPhase.Working -> BlockRow(phase.title, leading = { Spinner() })
                is ImportPhase.Ready -> Ready(phase, onInstall, onReset)
                is ImportPhase.Installing -> {
                    BlockRow(phase.name, leading = { PixelIcon(PixelGlyph.Chest, size = 22.dp) })
                    ProgressBar(phase.fraction, label = stringResource(R.string.mods_import_preparing))
                    CofreText(stringResource(R.string.mods_import_files, phase.files, phase.totalFiles), CofreTheme.type.label, color = CofreTheme.colors.muted)
                }
                is ImportPhase.Done -> {
                    Banner(stringResource(R.string.mods_import_done, phase.instance.name), PixelGlyph.Check, tone = Tone.Success)
                    CofreText(stringResource(R.string.mods_import_done_detail), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
                    BlockButton(stringResource(R.string.mods_open_instance), { onOpenInstance(phase.instance.id) }, role = ButtonRole.Primary)
                    BlockButton(stringResource(R.string.mods_import_another), onReset)
                }
                is ImportPhase.Failed -> {
                    Banner(stringResource(R.string.mods_import_failed), PixelGlyph.Cross, tone = Tone.Danger)
                    CofreText(phase.reason, CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
                    BlockButton(stringResource(R.string.mods_import_another), onReset, role = ButtonRole.Primary)
                }
            }
        }
    }
}

@Composable
private fun Ready(phase: ImportPhase.Ready, onInstall: () -> Unit, onReset: () -> Unit) {
    val pack = phase.pack
    BlockRow(
        pack.name ?: phase.archive.name,
        subtitle = listOfNotNull(pack.gameVersion?.let { "Minecraft $it" }, "${pack.loader.displayName} ${pack.loaderVersion.orEmpty()}".trim()).joinToString(" · "),
        big = true,
        leading = { PixelIcon(PixelGlyph.Chest, size = 28.dp) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("${phase.mods}", stringResource(R.string.mods_kind_mods), Modifier.weight(1f))
        Stat("${phase.other}", stringResource(R.string.mods_import_other_files), Modifier.weight(1f))
        Stat("${pack.overrides.size}", stringResource(R.string.mods_import_overrides), Modifier.weight(1f))
        Stat(size(pack.unpackedBytes), stringResource(R.string.mods_import_size), Modifier.weight(1f))
    }
    if (phase.rejected != null) {
        Banner(stringResource(R.string.mods_import_cant_run), PixelGlyph.Cross, tone = Tone.Danger)
        CofreText(phase.rejected, CofreTheme.type.body, color = CofreTheme.colors.inkSoft)
        BlockButton(stringResource(R.string.mods_import_another), onReset, role = ButtonRole.Primary)
    } else {
        CofreText(stringResource(R.string.mods_import_new_instance), CofreTheme.type.secondary, color = CofreTheme.colors.muted)
        BlockButton(stringResource(R.string.mods_import_install), onInstall, role = ButtonRole.Primary, icon = PixelGlyph.Chest)
        BlockButton(stringResource(R.string.mods_import_another), onReset)
    }
}
