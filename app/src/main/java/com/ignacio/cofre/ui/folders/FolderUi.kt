package com.ignacio.cofre.ui.folders

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ignacio.cofre.R
import com.ignacio.cofre.design.Badge
import com.ignacio.cofre.design.BlockButton
import com.ignacio.cofre.design.BlockRow
import com.ignacio.cofre.design.BlockSheet
import com.ignacio.cofre.design.ButtonSize
import com.ignacio.cofre.design.CofreText
import com.ignacio.cofre.design.CofreTheme
import com.ignacio.cofre.design.PixelGlyph
import com.ignacio.cofre.design.PixelIcon
import com.ignacio.cofre.design.SheetHeader
import com.ignacio.cofre.design.Spinner
import com.ignacio.cofre.design.Tone
import com.ignacio.cofre.design.outline
import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.modfolders.AutoUpdate
import com.ignacio.cofre.kit.modfolders.ModFolder
import com.ignacio.cofre.kit.mods.Snapshots

/**
 * Focus for a sheet's main action. The sheet claims focus for its top-left item when it opens; this moves it on a
 * couple of frames later, so A does the expected thing and B still closes.
 */
@Composable
fun rememberFirstFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        withFrameNanos {}
        runCatching { requester.requestFocus() }
    }
    return requester
}

/** "NeoForge 26.2". */
val ModFolder.label: String get() = "${loader.displayName} $gameVersion"

val Loader.tone: Tone
    get() = when (this) {
        Loader.NeoForge -> Tone.NeoForge
        Loader.Fabric -> Tone.Fabric
        Loader.Quilt -> Tone.Quilt
        Loader.Forge -> Tone.Forge
        else -> Tone.Neutral
    }

val Loader.glyph: PixelGlyph
    get() = when (this) {
        Loader.NeoForge -> PixelGlyph.NeoForge
        Loader.Fabric -> PixelGlyph.Fabric
        Loader.Quilt -> PixelGlyph.Quilt
        Loader.Forge -> PixelGlyph.Forge
        else -> PixelGlyph.Chest
    }

/** The loaders the new folder sheet offers. */
val FolderLoaders: List<Loader> = listOf(Loader.NeoForge, Loader.Fabric, Loader.Quilt, Loader.Forge)

/** A folder's icon: its loader's block. */
@Composable
fun FolderIcon(folder: ModFolder, size: Dp, modifier: Modifier = Modifier) {
    PixelIcon(folder.loader.glyph, modifier.outline().padding(2.dp), size = size - 4.dp)
}

/** Loader and version, then "Can't play yet", "Shared ×N" or "Not used" (users null: not known here). */
@Composable
fun FolderBadges(folder: ModFolder, users: Int?, modifier: Modifier = Modifier, autoUpdate: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(folder.label, tone = folder.loader.tone)
        when {
            !folder.playable -> Badge(stringResource(R.string.folders_cant_play), tone = Tone.Warn)
            users != null && users > 1 -> Badge(stringResource(R.string.folders_shared, users), tone = Tone.Accent)
            users == 0 -> Badge(stringResource(R.string.folders_not_used))
        }
        if (autoUpdate && folder.autoUpdate == AutoUpdate.Auto) Badge(stringResource(R.string.folders_auto), tone = Tone.Success)
    }
}

@Composable
fun autoUpdateOptions(): List<Pair<AutoUpdate, String>> = listOf(
    AutoUpdate.Off to stringResource(R.string.folders_update_off),
    AutoUpdate.Notify to stringResource(R.string.folders_update_notify),
    AutoUpdate.Auto to stringResource(R.string.folders_update_auto),
)

/** "Before update · Nov 8, 21:02" and how much changed since. */
@Composable
fun SnapshotItem(row: SnapshotRow, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reason = stringResource(
        when (row.snapshot.reason) {
            Snapshots.Reason.BeforeUpdate -> R.string.folders_snapshot_before_update
            Snapshots.Reason.LastGood -> R.string.folders_snapshot_last_good
            Snapshots.Reason.Manual -> R.string.folders_snapshot_manual
        },
    )
    val time = DateUtils.formatDateTime(context, row.snapshot.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    BlockRow(
        "$reason · $time",
        modifier,
        subtitle = if (row.changed == 0) stringResource(R.string.folders_snapshot_same) else pluralStringResource(R.plurals.folders_snapshot_changed, row.changed, row.changed),
        leading = { PixelIcon(if (row.snapshot.reason == Snapshots.Reason.LastGood) PixelGlyph.Check else PixelGlyph.Book, size = 22.dp) },
        trailing = { BlockButton(stringResource(R.string.folders_restore), onRestore, size = ButtonSize.Small, enabled = row.changed > 0) },
    )
}

/** Snapshots of one folder, Restore on each; null rows means still loading. */
@Composable
fun SnapshotsSheet(rows: List<SnapshotRow>?, onRestore: (Snapshots.Snapshot) -> Unit, onDismiss: () -> Unit) {
    BlockSheet(onDismiss, hugsContent = false) {
        SheetHeader(stringResource(R.string.folders_snapshots), onDismiss)
        SnapshotsContent(rows, onRestore)
    }
}

@Composable
fun SnapshotsContent(rows: List<SnapshotRow>?, onRestore: (Snapshots.Snapshot) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            rows == null -> BlockRow(stringResource(R.string.folders_loading), leading = { Spinner() })
            rows.isEmpty() -> CofreText(stringResource(R.string.folders_snapshots_none), CofreTheme.type.body, color = CofreTheme.colors.muted)
            else -> rows.forEach { row -> SnapshotItem(row, { onRestore(row.snapshot) }) }
        }
        CofreText(stringResource(R.string.folders_snapshots_note), CofreTheme.type.caption, color = CofreTheme.colors.muted)
    }
}
