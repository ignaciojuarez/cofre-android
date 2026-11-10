package com.ignacio.cofre.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.R
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.ui.folders.FolderBadges
import com.ignacio.cofre.ui.folders.rememberFirstFocus
import com.ignacio.cofre.ui.folders.FolderIcon

/** "Adding to": every folder (Get and the folder pane follow the pick), then Manage folders. Focus starts on the current one. */
@Composable
internal fun FolderPickerSheet(
    folders: List<ModFolder>,
    current: String?,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    BlockSheet(onDismiss, hugsContent = true) {
        SheetHeader(stringResource(R.string.mods_adding_to_title), onDismiss)
        FolderPickerContent(folders, current, onPick, onManage)
    }
}

@Composable
internal fun FolderPickerContent(
    folders: List<ModFolder>,
    current: String?,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = rememberFirstFocus()
    val start = folders.indexOfFirst { it.id == current }.coerceAtLeast(0)
    Column(
        modifier.padding(horizontal = 16.dp).padding(top = 10.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        folders.forEachIndexed { i, folder ->
            BlockRow(
                folder.name,
                if (i == start) Modifier.focusRequester(first) else Modifier,
                selected = folder.id == current,
                onClick = { onPick(folder.id) },
                leading = { FolderIcon(folder, 30.dp) },
                subtitleContent = { FolderBadges(folder, users = null) },
            )
        }
        BlockButton(stringResource(R.string.mods_manage_folders), onManage, Modifier.padding(top = 4.dp), icon = PixelGlyph.Chest)
    }
}
